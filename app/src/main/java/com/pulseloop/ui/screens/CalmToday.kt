package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulseloop.service.Readiness
import com.pulseloop.service.ReadinessCopy
import com.pulseloop.ui.calm.CalmCard
import com.pulseloop.ui.calm.CalmColors
import com.pulseloop.ui.calm.CalmShape
import com.pulseloop.ui.calm.CalmSparkline
import com.pulseloop.ui.calm.CalmType
import com.pulseloop.ui.components.Chevron
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.components.toColor
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

// Today in the new look (docs/new-look-plan.md, "Today"). Readiness leads as one statement, Sleep
// and Activity follow as the two scores that feed it, and every other card the user kept is one
// row: its name, the value, its verdict and a small trend. Detail is a tap away, as before.

/**
 * Readiness as the screen's statement: the number, its band and the one sentence that explains
 * it. Holds its height while loading, so the rows below never jump when it arrives.
 */
@Composable
internal fun CalmReadinessHero(readiness: Readiness.Result?, loaded: Boolean, onOpen: () -> Unit) {
    val score = readiness as? Readiness.Result.Score
    val (headline, headlineColor) = when (readiness) {
        is Readiness.Result.Score -> readiness.band.label to bandColor(readiness.band)
        is Readiness.Result.Learning -> trf("Learning: {0} of {1} nights", readiness.nightsCollected, readiness.nightsNeeded) to CalmColors.text
        Readiness.Result.NoSleep -> tr("No sleep yet") to CalmColors.text
        is Readiness.Result.Withheld -> tr("Not measured") to CalmColors.text
        null -> (if (loaded) tr("No data") else " ") to CalmColors.text
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CalmShape.radius))
            .clickable(role = Role.Button, onClickLabel = tr("Readiness"), onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("Readiness"), style = CalmType.title, color = CalmColors.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (score != null) Text("${score.value}", style = CalmType.score, color = CalmColors.text)
            Text(
                headline,
                style = if (score != null) CalmType.sectionTitle else CalmType.display,
                color = headlineColor,
                modifier = if (score != null) Modifier.padding(bottom = 4.dp) else Modifier,
            )
        }
        Text(
            readiness?.let { ReadinessCopy.headline(it) } ?: " ",
            style = CalmType.body, color = CalmColors.textSecondary,
        )
    }
}

/** One score that feeds readiness: Sleep or Activity, as a row with its number on the right. */
internal data class CalmScore(
    val name: String,
    val value: Int?,
    val detail: String?,
    val loaded: Boolean,
    val onOpen: () -> Unit,
)

/** Sleep and Activity in one card, divided by a hairline: two answers to "why this readiness?". */
@Composable
internal fun CalmScoresCard(scores: List<CalmScore>) {
    if (scores.isEmpty()) return
    val shape = RoundedCornerShape(CalmShape.radius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(CalmColors.card).border(1.dp, CalmColors.border, shape),
    ) {
        scores.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(CalmColors.border))
            CalmScoreRow(s)
        }
    }
}

@Composable
private fun CalmScoreRow(s: CalmScore) {
    val band = s.value?.let { Readiness.Band.of(it) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = s.onOpen)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = CalmType.title, color = CalmColors.text)
            val detail = when {
                band != null && s.detail != null -> "${band.label} · ${s.detail}"
                band != null -> band.label
                s.detail != null -> s.detail
                s.loaded -> tr("No data")
                else -> " "
            }
            Text(detail, style = CalmType.bodySmall, color = CalmColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            s.value?.toString() ?: if (s.loaded) "—" else "",
            style = CalmType.value,
            color = band?.let { bandColor(it) } ?: CalmColors.textMuted,
        )
        Chevron(tint = CalmColors.textSecondary)
    }
}

/**
 * One card the user kept, as a row: name and verdict on top, the value and its recent trend
 * underneath. A tap opens the same detail screen the tile did.
 */
@Composable
internal fun CalmTodayRow(
    name: String,
    accent: Color,
    value: String?,
    unit: String? = null,
    status: String? = null,
    statusColor: Color = CalmColors.textSecondary,
    note: String? = null,
    noteColor: Color = CalmColors.textMuted,
    trend: List<Double> = emptyList(),
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    CalmCard(modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The metric's own colour, as the old tiles had it, so a card is found by colour too.
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(name, style = CalmType.bodySmall, color = CalmColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (status != null) Text(status, style = CalmType.label, color = statusColor, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                Modifier.weight(1f).semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(value ?: "—", style = CalmType.value, color = if (value != null) CalmColors.text else CalmColors.textMuted, maxLines = 1)
                if (value != null && unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = CalmType.bodySmall, color = CalmColors.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            CalmSparkline(trend, accent)
        }
        if (note != null) Text(note, style = CalmType.bodySmall, color = noteColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}

/** A quiet in-row action ("+ Log meal"): a text PulseButton, so it keeps its 48dp target. */
@Composable
internal fun CalmRowAction(label: String, onClick: () -> Unit) {
    PulseButton(PulseButtonStyle.Text, onClick = onClick, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, style = CalmType.title, color = CalmColors.accent)
    }
}

/** The coach's brief (or the unusual-night alert) as one calm block above the scores. */
@Composable
internal fun CalmGlance(alert: String?, insight: String?, insightPending: Boolean, onAlert: () -> Unit, onInsight: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (alert != null) {
            CalmCard(onClick = onAlert) {
                Text(alert, style = CalmType.body, color = CalmColors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            insight != null -> Text(
                insight, style = CalmType.body, color = CalmColors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(CalmShape.radius))
                    .clickable(role = Role.Button, onClickLabel = tr("Read more"), onClick = onInsight)
                    .semantics { contentDescription = insight },
            )
            // Holds the brief's space while the coach writes it, so nothing below jumps.
            insightPending -> Spacer(Modifier.height(48.dp))
        }
    }
}

/** Which row a Today card becomes in the new look, and where a tap on it goes. */
@Composable
internal fun CalmTodayCardRow(
    card: com.pulseloop.ui.dashboard.DashboardCard,
    cards: Map<com.pulseloop.service.MetricKind, com.pulseloop.ui.viewmodels.VitalCardState>,
    vitals: com.pulseloop.ui.viewmodels.VitalsViewModel.VitalsState,
    sleep: com.pulseloop.ui.viewmodels.SleepViewModel.SleepState,
    state: com.pulseloop.ui.viewmodels.TodayViewModel.TodayState,
    food: FoodTotals?,
    foodLoaded: Boolean,
    drinks: com.pulseloop.service.DrinksRepository.Snapshot?,
    drinksLoaded: Boolean,
    stepFactor: Double?,
    units: com.pulseloop.settings.UnitSystem,
    open: (String) -> Unit,
    onLogMeal: () -> Unit,
    onAddDrink: () -> Unit,
) {
    val m = Modifier.fillMaxWidth()
    @Composable
    fun VitalRow(kind: com.pulseloop.service.MetricKind, route: String) {
        val c = cards.getValue(kind)
        val empty = c.latestValue == null
        CalmTodayRow(
            name = c.title,
            accent = com.pulseloop.ui.components.ZonePalette.accent(c.metric),
            value = if (empty) null else c.valueText,
            unit = c.unitText,
            status = if (empty) null else c.statusText,
            statusColor = c.statusToken.toColor(),
            // Issue #103 / #67: an estimate says so wherever it is shown.
            note = when {
                empty -> c.statusText
                c.isEstimated -> tr("Estimated from heart rate")
                else -> null
            },
            noteColor = if (c.isEstimated && !empty) com.pulseloop.ui.theme.PulseColors.warning else CalmColors.textMuted,
            trend = c.samples.map { it.value },
            onOpen = { open(route) },
            modifier = m,
        )
    }
    when (card) {
        com.pulseloop.ui.dashboard.DashboardCard.SLEEP -> {
            val night = sleep.lastNight
            CalmTodayRow(
                name = tr("Sleep"), accent = com.pulseloop.ui.theme.PulseColors.sleep,
                value = night?.let { com.pulseloop.util.Formats.hoursMinutes(it.totalMinutes) },
                note = if (night == null) tr("No sleep recorded") else null,
                onOpen = { open("sleep") }, modifier = m,
            )
        }
        com.pulseloop.ui.dashboard.DashboardCard.ACTIVITY -> {
            val steps = state.steps?.let { s -> stepFactor?.let { (s * it).toInt() } ?: s }
            CalmTodayRow(
                name = tr("Activity"), accent = com.pulseloop.ui.theme.PulseColors.steps,
                value = steps?.let { com.pulseloop.util.Formats.count(it) }, unit = tr("steps"),
                trend = state.steps7d.map { it.toDouble() },
                onOpen = { open("activity") }, modifier = m,
            )
        }
        com.pulseloop.ui.dashboard.DashboardCard.NUTRITION -> {
            val goal = food?.goalKcal?.takeIf { it > 0 }
            CalmTodayRow(
                name = tr("Nutrition"), accent = com.pulseloop.ui.theme.PulseColors.calories,
                value = if (foodLoaded) com.pulseloop.util.Formats.count((food?.kcal ?: 0.0).toInt()) else null,
                unit = tr("kcal"),
                status = goal?.let { trf("of {0}", com.pulseloop.util.Formats.count(it.toInt())) },
                note = when {
                    !foodLoaded -> null
                    food != null && food.meals > 0 -> trf("P {0} · C {1} · F {2} g", food.proteinG.toInt(), food.carbsG.toInt(), food.fatG.toInt())
                    else -> tr("Nothing logged yet")
                },
                onOpen = { open("nutrition") }, modifier = m,
                // Today's one-tap meal log stays: taps on a common action never rise.
                action = { CalmRowAction(tr("+ Log meal"), onLogMeal) },
            )
        }
        com.pulseloop.ui.dashboard.DashboardCard.DRINKS -> {
            val week = drinks?.week
            val limit = drinks?.weeklyLimit ?: com.pulseloop.service.DrinkPrefs.weeklyLimit
            val total = week?.standardDrinks ?: 0.0
            fun std(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(v)
            CalmTodayRow(
                name = tr("Drinks"), accent = com.pulseloop.ui.theme.PulseColors.drinks,
                value = if (drinksLoaded) std(total) else null,
                status = trf("of {0} this week", std(limit)),
                statusColor = if (limit > 0 && total > limit) com.pulseloop.ui.theme.PulseColors.warning else CalmColors.textSecondary,
                note = trf("{0} of {1} dry days", week?.dryDays ?: 0, drinks?.dryDaysTarget ?: com.pulseloop.service.DrinkPrefs.dryDaysTarget),
                onOpen = { open("drinks") }, modifier = m,
                action = { CalmRowAction("+ ${com.pulseloop.service.DrinkPrefs.lastType.label}", onAddDrink) },
            )
        }
        com.pulseloop.ui.dashboard.DashboardCard.HEART_RATE -> VitalRow(com.pulseloop.service.MetricKind.HEART_RATE, "vitals/hr")
        com.pulseloop.ui.dashboard.DashboardCard.SPO2 -> VitalRow(com.pulseloop.service.MetricKind.SPO2, "vitals/spo2")
        com.pulseloop.ui.dashboard.DashboardCard.HRV -> VitalRow(com.pulseloop.service.MetricKind.HRV, "vitals/hrv")
        com.pulseloop.ui.dashboard.DashboardCard.TEMPERATURE -> VitalRow(com.pulseloop.service.MetricKind.TEMPERATURE, "vitals/temp")
        com.pulseloop.ui.dashboard.DashboardCard.STRESS -> VitalRow(com.pulseloop.service.MetricKind.STRESS, "vitals/stress")
        com.pulseloop.ui.dashboard.DashboardCard.FATIGUE -> VitalRow(com.pulseloop.service.MetricKind.FATIGUE, "vitals/fatigue")
        com.pulseloop.ui.dashboard.DashboardCard.GLUCOSE -> VitalRow(com.pulseloop.service.MetricKind.GLUCOSE, "vitals/glucose")
        com.pulseloop.ui.dashboard.DashboardCard.BLOOD_PRESSURE -> {
            val c = cards.getValue(com.pulseloop.service.MetricKind.BLOOD_PRESSURE)
            val sys = vitals.bpSystolic
            val dia = vitals.bpDiastolic
            CalmTodayRow(
                name = c.title, accent = com.pulseloop.ui.theme.PulseColors.bloodPressure,
                value = if (sys != null && dia != null) "$sys/$dia" else null,
                unit = c.unitText,
                status = if (sys != null && dia != null) c.statusText else null,
                statusColor = c.statusToken.toColor(),
                note = if (sys == null || dia == null) tr("No reading") else null,
                trend = c.samples.map { it.value },
                onOpen = { open("vitals/bp") }, modifier = m,
            )
        }
    }
}
