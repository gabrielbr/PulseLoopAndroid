package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.pulseloop.ui.theme.dither
import androidx.compose.foundation.layout.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.service.MetricKind
import com.pulseloop.service.MetricZone
import com.pulseloop.service.VitalsThresholdEngine
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.ui.components.*
import com.pulseloop.ui.dashboard.CustomizeCardsButton
import com.pulseloop.ui.dashboard.DashboardCard
import com.pulseloop.ui.dashboard.EditableCard
import com.pulseloop.ui.dashboard.HiddenMetricsTray
import com.pulseloop.ui.dashboard.MetricPrefsStore
import com.pulseloop.ui.dashboard.MetricScope
import com.pulseloop.ui.dashboard.ReorderDoneBar
import com.pulseloop.ui.dashboard.rememberDashboardEditState
import com.pulseloop.util.Formats
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.TodayViewModel
import com.pulseloop.ui.viewmodels.VitalCardState
import com.pulseloop.ui.viewmodels.VitalsCardFactory
import com.pulseloop.ui.viewmodels.VitalsViewModel
import com.pulseloop.ui.viewmodels.SleepViewModel
import com.pulseloop.ui.viewmodels.toCardInputs
import kotlinx.coroutines.launch

/**
 * Today dashboard — ported from TodayView.swift + TodayTiles.swift (iOS #35 tile re-layout).
 * Hero insight card, then a 2-column grid of half-width metric tiles (activity loop, sleep bar,
 * zone charts, gauges, dual BP gauge), then the coach message card. Tiles are capability-gated
 * like the Vitals screen; card interpretation math is factory-built off the composition path.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TodayScreen(
    navController: androidx.navigation.NavController? = null,
    viewModel: TodayViewModel? = null,
    coordinator: com.pulseloop.service.RingSyncCoordinator? = null,
    vitalsViewModel: VitalsViewModel? = null,
    sleepViewModel: SleepViewModel? = null,
    // Heights of the glass top/bottom bars this screen scrolls under (0 when standalone).
    topBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomBarPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val state by (viewModel?.state?.collectAsState() ?: remember { mutableStateOf(TodayViewModel.TodayState()) })
    val vitals by (vitalsViewModel?.state?.collectAsState() ?: remember { mutableStateOf(VitalsViewModel.VitalsState()) })
    val sleep by (sleepViewModel?.state?.collectAsState() ?: remember { mutableStateOf(SleepViewModel.SleepState()) })
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // remember{}: the ApiKeyStore constructor does Keystore + encrypted-prefs I/O — far
    // too expensive to repeat on every recomposition of this state-collecting screen.
    val keyStore = remember { ApiKeyStore(context) }
    val units = keyStore.resolvedUnitSystem
    val coachEnabled = keyStore.coachEnabled
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            scope.launch {
                coordinator?.pullToRefresh()
                kotlinx.coroutines.delay(1500)
                isRefreshing = false
            }
        },
    )

    // Same factory the Vitals screen and the widget publisher use (iOS TodayStore reuses
    // VitalsCardFactory) — one shared Inputs construction via toCardInputs.
    val cards = remember(vitals, units) {
        val inputs = vitals.toCardInputs(units)
        MetricKind.entries.associateWith { VitalsCardFactory.card(it, inputs, vitals.profile) }
    }
    val hero = remember(state, sleep) { deriveHero(state, sleep) }
    // Readiness recomputes whenever Today's data or last night's session changes.
    val db = remember { com.pulseloop.data.PulseLoopDatabase.getInstance(context) }
    val readiness = rememberReadiness(db, listOf(state.lastUpdated, sleep.lastNight?.id, sleep.lastNight?.updatedAt))
    val activityScore = rememberActivityScore(db, listOf(state.lastUpdated, state.steps))
    val guidance = rememberGuidance(db, listOf(state.lastUpdated, sleep.lastNight?.id, sleep.lastNight?.updatedAt))
    var mealSheet by remember { mutableStateOf(false) }
    var briefSheet by remember { mutableStateOf(false) }
    var tagSheet by remember { mutableStateOf(false) }
    // Today's food log, for the Nutrition tile and the midday food moment.
    val food by produceState<FoodTotals?>(null, state.lastUpdated, mealSheet) {
        value = runCatching {
            val meals = db.mealEntryDao().byDay(java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            val goal = db.userGoalDao().get()
            FoodTotals(
                meals = meals.size,
                kcal = meals.sumOf { it.calories },
                proteinG = meals.sumOf { it.proteinG },
                carbsG = meals.sumOf { it.carbsG },
                fatG = meals.sumOf { it.fatG },
                goalKcal = goal?.intakeCalories,
            )
        }.getOrNull()
    }
    val mealsToday = food?.meals
    // Ring steps calibrated against the phone, only when the user applied it in My Health.
    val stepFactor by produceState<Double?>(null, state.lastUpdated) {
        // An accuracy-check steps correction supersedes the phone factor: applying both would
        // correct the same undercount twice.
        val accuracySteps = com.pulseloop.health.accuracy.AccuracyCorrections.load(db).has(com.pulseloop.health.accuracy.ComparisonMetric.STEPS)
        value = if (accuracySteps || !com.pulseloop.service.StepCalibration.isApplied(context)) null else runCatching {
            val zone = java.time.ZoneId.systemDefault()
            val ring = db.activityDailyDao().recentReal(60).associate { java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() to it.steps }
            com.pulseloop.service.StepCalibration.factor(com.pulseloop.service.StepCalibration.phoneDays(context), ring, java.time.LocalDate.now())?.factor
        }.getOrNull()
    }
    // Drinks, when the user tracks them: the tile and the morning-after line on Last night.
    var drinkTick by remember { mutableIntStateOf(0) }
    val accuracyOpen by db.accuracyDao().openFlow().collectAsState(initial = null)
    val drinks by produceState<com.pulseloop.service.DrinksRepository.Snapshot?>(null, state.lastUpdated, sleep.lastNight?.id, drinkTick) {
        value = if (com.pulseloop.service.DrinkPrefs.enabled) runCatching { com.pulseloop.service.DrinksRepository.load(db) }.getOrNull() else null
    }

    // Per-metric visibility + user order (iOS #64), read reactively so a hide/show/reorder
    // recomposes immediately (this is also the iOS #70 reactivity).
    val prefsStore = remember { MetricPrefsStore.get(context) }
    val prefs by prefsStore.prefs.collectAsState()
    val editState = rememberDashboardEditState(MetricScope.TODAY, prefsStore)
    // System Back finishes editing, the same as Done, instead of leaving the tab mid-edit.
    BackHandler(enabled = editState.editing) { editState.exit() }

    val supported: (DashboardCard) -> Boolean = { card ->
        when (card) {
            DashboardCard.DRINKS -> com.pulseloop.service.DrinkPrefs.enabled
            DashboardCard.ACTIVITY, DashboardCard.SLEEP, DashboardCard.NUTRITION,
            DashboardCard.HEART_RATE, DashboardCard.SPO2 -> true
            DashboardCard.HRV -> vitals.supportsHrv
            DashboardCard.TEMPERATURE -> vitals.supportsTemp
            DashboardCard.STRESS -> vitals.supportsStress
            DashboardCard.FATIGUE -> vitals.supportsFatigue
            DashboardCard.GLUCOSE -> vitals.supportsGlucose
            DashboardCard.BLOOD_PRESSURE -> vitals.supportsBP
        }
    }
    val allSupported = DashboardCard.todayDefault.filter(supported)
    SideEffect { editState.configure(allSupported, DashboardCard.todayDefault) }

    val visibleKeys = allSupported.filter { !prefs.isHidden(it, MetricScope.TODAY) }.map { it.key }.toSet()
    val visibleOrdered: List<DashboardCard> = run {
        val base = if (editState.editing) editState.liveOrder
            else prefsStore.resolvedOrder(visibleKeys, DashboardCard.todayDefault.map { it.key }, MetricScope.TODAY)
                .mapNotNull { DashboardCard.fromKey(it) }
        base.filter { it.key in visibleKeys }
    }
    val hiddenCards = allSupported.filter { prefs.isHidden(it, MetricScope.TODAY) }

    // Dispatch a card id to its tile composable (order/visibility decided above).
    val todayCardFor: @Composable (DashboardCard, Modifier) -> Unit = { card, m ->
        when (card) {
            DashboardCard.ACTIVITY -> ActivityTileFor(state, units, m, onTap = { navController?.navigate("activity") }, stepFactor = stepFactor)
            DashboardCard.SLEEP -> SleepTileFor(sleep, m) { navController?.navigate("sleep") }
            DashboardCard.NUTRITION -> NutritionTile(food, m, onLog = { mealSheet = true }) { navController?.navigate("nutrition") }
            DashboardCard.DRINKS -> TodayTile(label = "Drinks", color = PulseColors.drinks, modifier = m, onTap = { navController?.navigate("drinks") }) {
                DrinksTileContent(
                    week = drinks?.week,
                    limit = drinks?.weeklyLimit ?: com.pulseloop.service.DrinkPrefs.weeklyLimit,
                    dryTarget = drinks?.dryDaysTarget ?: com.pulseloop.service.DrinkPrefs.dryDaysTarget,
                    onAdd = {
                        scope.launch {
                            com.pulseloop.service.DrinksRepository.log(db, com.pulseloop.service.DrinkPrefs.lastType)
                            drinkTick++
                        }
                    },
                )
            }
            DashboardCard.HEART_RATE -> ChartTile(cards.getValue(MetricKind.HEART_RATE), vitals.hrSamples, m) { navController?.navigate("vitals/hr") }
            DashboardCard.SPO2 -> ChartTile(cards.getValue(MetricKind.SPO2), vitals.spo2Samples, m) { navController?.navigate("vitals/spo2") }
            DashboardCard.HRV -> ChartTile(cards.getValue(MetricKind.HRV), vitals.hrvSamples, m) { navController?.navigate("vitals/hrv") }
            DashboardCard.TEMPERATURE -> ChartTile(cards.getValue(MetricKind.TEMPERATURE), vitals.tempSamples, m) { navController?.navigate("vitals/temp") }
            DashboardCard.STRESS -> GaugeTile(cards.getValue(MetricKind.STRESS), m) { navController?.navigate("vitals/stress") }
            DashboardCard.FATIGUE -> GaugeTile(cards.getValue(MetricKind.FATIGUE), m) { navController?.navigate("vitals/fatigue") }
            DashboardCard.GLUCOSE -> GaugeTile(cards.getValue(MetricKind.GLUCOSE), m) { navController?.navigate("vitals/glucose") }
            DashboardCard.BLOOD_PRESSURE -> BloodPressureTile(cards.getValue(MetricKind.BLOOD_PRESSURE), vitals, m) { navController?.navigate("vitals/bp") }
        }
    }

    Box(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 16.dp + topBarPadding, bottom = 16.dp + bottomBarPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Oura-style score header (docs/oura-ux-analysis.md, phase 2).
            item {
                ScoreHeader(
                    readiness = readiness,
                    sleepScore = sleep.score?.score ?: sleep.lastNight?.score,
                    activity = activityScore,
                    onReadiness = { navController?.navigate("readiness") },
                    onSleep = { navController?.navigate("sleep") },
                    onActivity = { navController?.navigate("activity_score") },
                )
            }

            // Accuracy check in progress (docs/accuracy-check-plan.md): say so, since it pauses export.
            accuracyOpen?.let { c -> item { AccuracyCheckBanner(c) { navController?.navigate("settings/accuracy-check") } } }
            state.adjustedNote?.let { note -> item { AdjustedCaption(note) } }

            // Glance: one line of what today means, and an alert line only when something is off.
            item {
                val alert = guidance?.offSignals?.takeIf { com.pulseloop.service.VitalsCheck.shouldAlert(it) }
                val summary = state.coachSummary
                val insight = when {
                    coachEnabled && summary != null -> listOf(summary.title, summary.body).filter { it.isNotBlank() }.joinToString(". ")
                    readiness != null -> com.pulseloop.service.ReadinessCopy.headline(readiness)
                    else -> hero.summary
                }
                TodayGlance(
                    alert = alert,
                    insight = insight,
                    onAlert = { navController?.navigate("readiness") },
                    // The line is cut at three lines; tapping opens the whole brief.
                    onInsight = { briefSheet = true },
                )
            }

            // Act: one tap to log, then at most two cards chosen by the time of day.
            item {
                QuickLogRow(
                    onMeal = { mealSheet = true },
                    onTag = { tagSheet = true },
                    onMeasure = { navController?.navigate("vitals") },
                    onWorkout = {
                        com.pulseloop.ui.LaunchRequests.openWorkoutPicker.value = true
                        navController?.navigate("activity")
                    },
                )
            }
            val moments = TodayMoments.at(
                hour = java.time.LocalTime.now().hour,
                hasSleep = sleep.lastNight != null,
                // The tile already invites a meal when it is on the grid; the card is for when it isn't.
                mealsToday = if (DashboardCard.NUTRITION in visibleOrdered) -1 else mealsToday ?: -1,
                hasBedtime = guidance?.bedtime != null,
            )
            moments.forEach { moment ->
                when (moment) {
                    TodayMoments.Moment.LAST_NIGHT -> sleep.lastNight?.let { night ->
                        item {
                            // Follow the phone's 12/24-hour setting rather than forcing 24-hour.
                            val fmt = java.time.format.DateTimeFormatter.ofPattern(
                                if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
                            )
                            val zone = java.time.ZoneId.systemDefault()
                            fun t(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(fmt)
                            LastNightCard(
                                durationText = Formats.hoursMinutes(night.totalMinutes),
                                score = sleep.score?.score ?: night.score,
                                window = "${t(night.startAt)} – ${t(night.endAt)}",
                                onOpen = { navController?.navigate("sleep") },
                                drinksLine = drinks?.morningAfter?.let { com.pulseloop.service.DrinksRepository.morningAfterLine(it) },
                                onDrinks = { navController?.navigate("drinks") },
                            )
                        }
                    }
                    TodayMoments.Moment.FOOD -> item {
                        FoodCard(
                            db = db,
                            burned = state.calories,
                            onOpen = { navController?.navigate("nutrition") },
                            onLog = { start -> navController?.navigate("nutrition/$start") },
                        )
                    }
                    TodayMoments.Moment.TONIGHT -> guidance?.let { g ->
                        g.bedtime?.let { b -> item { TonightCard(b, g.sleepGoalMinutes) } }
                    }
                    TodayMoments.Moment.TAG_DAY -> item {
                        JournalTagsCard(
                            db, java.time.LocalDate.now(), "Tag your day",
                            tags = com.pulseloop.service.JournalInsights.Tag.entries.take(6),
                            allowYesterday = true,
                        ) {
                            Text(
                                "See what affects you",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.readiness,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) { navController?.navigate("journal") }
                                    .wrapContentHeight(Alignment.CenterVertically),
                            )
                        }
                    }
                }
            }

            // Browse: the metric tiles.
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (editState.editing) ReorderDoneBar { editState.exit() }
                    else CustomizeCardsButton { editState.enterEditing() }
                }
            }

            // Three across from 600dp (tablets, unfolded foldables) instead of two stretched tiles.
            val columns = if (screenWidthDp >= 600) 3 else 2
            val rows = visibleOrdered.chunked(columns)
            // Keyed by the cards in the row so a reorder in edit mode moves rows rather than
            // recomposing every row in place.
            items(rows.size, key = { rows[it].joinToString("|") { card -> card.name } }) { rowIndex ->
                val row = rows[rowIndex]
                // IntrinsicSize.Min + fillMaxHeight is what keeps the grid uniform now that tiles
                // size to their content: the row takes the height its tallest tile actually needs,
                // and the other tile stretches to match. Without it a tall Sleep tile would sit
                // next to a short Activity tile; with a fixed height instead, the tall one clips.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { card ->
                        EditableCard(editState, card, Modifier.weight(1f).fillMaxHeight()) {
                            todayCardFor(card, Modifier.fillMaxWidth().fillMaxHeight())
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (editState.editing) {
                item { HiddenMetricsTray(hiddenCards) { editState.setHidden(it, false) } }
            }

            item { Spacer(Modifier.height(64.dp)) }
        }

        if (mealSheet) {
            MealSheet(onDismiss = { mealSheet = false }) { start ->
                mealSheet = false
                navController?.navigate("nutrition/$start")
            }
        }
        if (briefSheet) {
            val summary = state.coachSummary
            val fromCoach = coachEnabled && summary != null
            CoachBriefSheet(
                title = if (fromCoach) summary!!.title else (readiness?.let { "Today" } ?: hero.title),
                body = if (fromCoach) summary!!.body
                    else listOfNotNull(readiness?.let { com.pulseloop.service.ReadinessCopy.headline(it) }, hero.summary).joinToString("\n\n"),
                chips = if (fromCoach) parseChips(summary!!.chipsJSON) else hero.chips.map { it.label },
                askCoach = coachEnabled,
                onDismiss = { briefSheet = false },
                onAsk = {
                    briefSheet = false
                    navController?.navigate("coach")
                },
            )
        }
        if (tagSheet) {
            TagDaySheet(db, onDismiss = { tagSheet = false }) {
                tagSheet = false
                navController?.navigate("journal")
            }
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

// ─────────────────────────── Tiles ───────────────────────────

@Composable
private fun ActivityTileFor(
    state: TodayViewModel.TodayState,
    units: com.pulseloop.settings.UnitSystem,
    modifier: Modifier,
    onTap: () -> Unit,
    /** Phone-calibrated step factor, when the user applied one (service/StepCalibration.kt). */
    stepFactor: Double? = null,
) {
    val steps = state.steps?.let { s -> stepFactor?.let { (s * it).toInt() } ?: s }
    val distText = state.distanceMeters?.let { Formats.distance(UnitConverter.distance(it, units)) }
    ActivityTile(
        rings = listOf(
            ActivityRing(steps?.toDouble(), state.stepGoal.toDouble(), PulseColors.steps),
            ActivityRing(state.distanceMeters, state.distanceGoalMeters, PulseColors.distance),
            ActivityRing(state.calories, state.caloriesGoal.toDouble(), PulseColors.calories),
        ),
        values = buildList {
            steps?.let { add(ActivityTileValue(if (stepFactor != null) "≈ STEPS" else "STEPS", Formats.count(it), PulseColors.steps)) }
            distText?.let { add(ActivityTileValue(UnitConverter.distanceUnit(units), it, PulseColors.distance)) }
            state.calories?.let { add(ActivityTileValue("CAL", Formats.count(it.toInt()), PulseColors.calories)) }
        },
        modifier = modifier,
        onTap = onTap,
    )
}

@Composable
private fun SleepTileFor(sleep: SleepViewModel.SleepState, modifier: Modifier, onTap: () -> Unit) {
    val session = sleep.lastNight
    val segments = remember(sleep) { buildSleepStageSegments(sleep.lastNightBlocks) }
    SleepTile(
        durationText = session?.let { Formats.hoursMinutes(it.totalMinutes) },
        segments = segments,
        score = sleep.score?.score ?: session?.score,
        modifier = modifier,
        onTap = onTap,
    )
}

/** Half-width chart tile: latest value + compact zone-colored line (TodayChartTile in Swift). */
@Composable
private fun ChartTile(card: VitalCardState, values: List<Double>, modifier: Modifier, onTap: () -> Unit) {
    TodayTile(label = card.title, color = ZonePalette.accent(card.metric), modifier = modifier, onTap = onTap) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                card.valueText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulseColors.textPrimary,
                maxLines = 1,
            )
            card.unitText?.let {
                Text(
                    it, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = PulseColors.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (card.isEmpty) {
            Text(card.statusText, fontSize = 12.sp, color = PulseColors.textMuted)
        } else {
            ZoneLineChart(
                samples = card.samples,
                zones = card.zones,
                yDomain = card.yDomain,
                accent = ZonePalette.accent(card.metric),
                label = card.title,
                referenceBands = card.referenceBands,
                dashedRules = card.dashedRules,
                showPoints = card.metric == MetricKind.SPO2,
                showAxes = false,
                height = 40.dp,
            )
        }
    }
}

/** Half-width gauge tile: the Vitals ring gauge scaled down (TodayGaugeTile in Swift). */
@Composable
private fun GaugeTile(card: VitalCardState, modifier: Modifier, onTap: () -> Unit) {
    TodayTile(label = card.title, color = ZonePalette.accent(card.metric), modifier = modifier, onTap = onTap) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // A gauge needs only ONE reading — card.isEmpty is chart logic (<2 samples) and
            // would blank the glucose gauge (one fasting reading per day).
            if (card.latestValue == null) {
                Text(card.statusText, fontSize = 12.sp, color = PulseColors.textMuted)
            } else {
                // 108dp is the design size at font scale 1.0 (118 on iOS whose padding differs);
                // the gauge draws its inscribed square either way. Scaled with the font scale
                // because the centre value is sized off this dp value (`size.value * 0.30f`.sp)
                // inside a Box pinned to it — a fixed ring with growing text overflows its own
                // ring. The tile is no longer fixed-height, so a taller ring just makes it taller.
                val gaugeScale = TodayTileMetrics.fontScale
                VitalRingGauge(
                    value = card.latestValue,
                    domain = card.yDomain,
                    zones = card.zones,
                    valueColor = card.statusToken.toColor(),
                    centerValue = card.valueText,
                    centerStatus = card.statusText,
                    size = 88.dp * gaugeScale,
                    lineWidth = 9.dp * gaugeScale,
                )
            }
        }
    }
}

/** Systolic + diastolic as two small gauges side by side (TodayBloodPressureTile in Swift). */
@Composable
private fun BloodPressureTile(
    card: VitalCardState,
    vitals: VitalsViewModel.VitalsState,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    TodayTile(label = card.title, color = PulseColors.bloodPressure, modifier = modifier, onTap = onTap) {
        val sys = vitals.bpSystolic?.toDouble()
        val dia = vitals.bpDiastolic?.toDouble()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (sys == null || dia == null) {
                Text("No reading", fontSize = 12.sp, color = PulseColors.textMuted)
            } else {
                val sysZones = VitalsThresholdEngine.zones(MetricKind.BLOOD_PRESSURE, vitals.profile)
                val diaZones = VitalsThresholdEngine.diastolicReferenceZones()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BpRingColumn("SYS", sys, 80.0..190.0, sysZones, PulseColors.bloodPressure, Modifier.weight(1f))
                    BpRingColumn("DIA", dia, 50.0..130.0, diaZones, PulseColors.bloodPressure.copy(alpha = 0.7f), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BpRingColumn(
    title: String,
    value: Double,
    domain: ClosedFloatingPointRange<Double>,
    zones: List<MetricZone>,
    fallback: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val valueColor = zones.firstOrNull {
        (it.lower ?: Double.NEGATIVE_INFINITY) <= value && value < (it.upper ?: Double.POSITIVE_INFINITY)
    }?.colorToken?.toColor() ?: fallback
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        VitalRingGauge(
            value = value,
            domain = domain,
            zones = zones,
            valueColor = valueColor,
            centerValue = "${value.toInt()}",
            // Scaled for the same reason as the GaugeTile ring above.
            size = 56.dp * TodayTileMetrics.fontScale,
            lineWidth = 6.dp * TodayTileMetrics.fontScale,
        )
        Text(
            title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp, color = PulseColors.textMuted,
        )
    }
}

// ─────────────────────────── Hero derivation (TodayInsights) ───────────────────────────

private data class Hero(val title: String, val summary: String, val chips: List<ToneChip>)

/** Ported from TodayInsights.deriveHero (TodayView.swift), minus the iOS calibration branch. */
private fun deriveHero(state: TodayViewModel.TodayState, sleep: SleepViewModel.SleepState): Hero {
    val steps = state.steps ?: return Hero(
        title = "Waiting for first sync",
        summary = if (com.pulseloop.health.source.HealthConnectSource.enabledNow)
            "Importing from Health Connect. Your steps, sleep and heart rate appear once your device's app has synced."
        else "Sync your ring to start collecting movement, heart rate, blood oxygen, and recovery context.",
        chips = listOf(
            ToneChip("Baseline pending", ChipTone.WARN),
            ToneChip(if (state.heartRate == null) "HR pending" else "HR collected", ChipTone.NEUTRAL),
            ToneChip("Sleep pending", ChipTone.WARN),
        ),
    )

    val series = state.steps7d.map { it.toDouble() }
    val prior = series.dropLast(1)
    val base = if (prior.isNotEmpty() && prior.average() > 0) prior.average()
        else if (series.isNotEmpty()) series.average() else 0.0
    val stepsDelta = if (base == 0.0) 0 else (((steps - base) / base) * 100).toInt()

    val title = when {
        stepsDelta >= 20 -> "Building momentum"
        stepsDelta <= -20 -> "Take it easy"
        else -> "Steady build"
    }

    val hrStr = state.heartRate?.let { "$it bpm" } ?: "—"
    val night = sleep.lastNight
    val summary = if (night == null) {
        "You're at %,d steps. Sync after waking to add recovery context.".format(steps)
    } else {
        "You're at %,d steps, %dh %02dm of sleep, and your latest reading is %s.".format(
            steps, night.totalMinutes / 60, night.totalMinutes % 60, hrStr,
        )
    }

    val stepsTone = if (stepsDelta > 5) ChipTone.UP else if (stepsDelta < -5) ChipTone.DOWN else ChipTone.NEUTRAL
    val hrTone = when {
        state.heartRate == null -> ChipTone.WARN
        state.heartRate < 100 -> ChipTone.NEUTRAL
        else -> ChipTone.WARN
    }
    return Hero(
        title = title,
        summary = summary,
        chips = listOf(
            ToneChip(
                if (series.size > 1) "Steps ${if (stepsDelta >= 0) "+" else ""}$stepsDelta%" else "Steps collected",
                stepsTone,
            ),
            ToneChip(if (state.heartRate == null) "HR pending" else "HR collected", hrTone),
            ToneChip(if (night != null) "Sleep synced" else "Sleep pending", if (night != null) ChipTone.NEUTRAL else ChipTone.WARN),
        ),
    )
}

/** Coach summary chips arrive as a JSON string array. */
private fun parseChips(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as? kotlinx.serialization.json.JsonArray ?: return emptyList() }
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Today's food totals for the Nutrition tile. */
internal data class FoodTotals(
    val meals: Int,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val goalKcal: Double?,
)

/** Half-width Nutrition tile: calories eaten against the goal, macros, and a one-tap log. */
@Composable
private fun NutritionTile(food: FoodTotals?, modifier: Modifier, onLog: () -> Unit, onTap: () -> Unit) {
    TodayTile(label = "Nutrition", color = PulseColors.calories, modifier = modifier, onTap = onTap) {
        val kcal = food?.kcal ?: 0.0
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Formats.count(kcal.toInt()), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary, maxLines = 1)
            Text("kcal", fontSize = 11.sp, color = PulseColors.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        val goal = food?.goalKcal?.takeIf { it > 0 }
        if (goal != null) {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(shape).dither(PulseColors.calories, 0.25f)) {
                Box(Modifier.fillMaxWidth((kcal / goal).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(PulseColors.calories))
            }
            Text("of ${Formats.count(goal.toInt())}", fontSize = 11.sp, color = PulseColors.textMuted)
        }
        Spacer(Modifier.weight(1f))
        if (food != null && food.meals > 0) {
            Text(
                "P ${food.proteinG.toInt()} · C ${food.carbsG.toInt()} · F ${food.fatG.toInt()} g",
                fontSize = 11.sp, color = PulseColors.textSecondary, maxLines = 1,
            )
        } else {
            Text("Nothing logged yet", fontSize = 11.sp, color = PulseColors.textMuted, maxLines = 1)
        }
        Text(
            "+ Log meal",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent,
            // No min height here: it would stretch the tile and its row. Compose already widens a
            // small clickable's hit area towards 48dp.
            modifier = Modifier.clickable(role = Role.Button, onClick = onLog).padding(vertical = 2.dp),
        )
    }
}
