package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import com.pulseloop.ui.components.rememberReduceMotion
import com.pulseloop.ui.components.switchRow
import androidx.activity.compose.BackHandler

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.BodyClock
import com.pulseloop.service.Cycle
import com.pulseloop.service.HealthArea
import com.pulseloop.service.HealthInsightsRepository
import com.pulseloop.service.HealthInsightsRepository.TrendMetric
import com.pulseloop.service.MindfulSessions
import com.pulseloop.service.RestMode
import com.pulseloop.service.SleepDebt
import com.pulseloop.service.SleepFormat
import com.pulseloop.service.SymptomRadar
import com.pulseloop.service.Trends
import com.pulseloop.service.VitalSample
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.dither
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * My Health: the longer view (ported in approach from anikbharadwaj129/Colmi, Apache-2.0).
 * Symptom Radar and Rest Mode, long-term health areas, sleep need and debt, body clock, daytime
 * stress and resilience, heart rate today in context, cardio capacity, habits, and trends by
 * day / week / month / year. Every number is an estimate against the user's own normal.
 */

@Composable
internal fun Section(
    title: String,
    /**
     * Set for a section that starts closed: the card shows its title and this one-line summary,
     * and opens in place on tap. Leave null for one that is always open.
     */
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(summary == null) }
    val collapsible = summary != null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            if (collapsible) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button) { open = !open },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        if (!open) Text(summary!!, fontSize = 12.sp, color = PulseColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                    Text("⌄", fontSize = 18.sp, color = PulseColors.textMuted, modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = open,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                }
            } else {
                Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                content()
            }
        },
    )
}

@Composable
internal fun Fact(label: String, value: String, note: String? = null, color: Color = PulseColors.textPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = PulseColors.textSecondary)
            note?.let { Text(it, fontSize = 12.sp, color = PulseColors.textMuted) }
        }
        // Bounded: a translated value ("Moderada · 3h 20m") must not squeeze the label column.
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 160.dp))
    }
}

private fun ratingColor(r: HealthArea.Rating) = when (r) {
    HealthArea.Rating.THRIVING -> PulseColors.success
    HealthArea.Rating.GOOD -> PulseColors.info
    HealthArea.Rating.FAIR -> PulseColors.warning
    HealthArea.Rating.NEEDS_CARE -> PulseColors.danger
}

@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(40.dp)) {
    if (values.size < 2) return
    Canvas(modifier) {
        val min = values.min(); val max = values.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = size.height - ((v - min) / range * size.height * 0.85 + size.height * 0.075).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun MyHealthScreen(onBack: () -> Unit, onBreathe: () -> Unit, onWhatChanged: () -> Unit = {}, onCycle: () -> Unit = {}, onMeds: () -> Unit = {}, onPostpartum: () -> Unit = {}, onLabReports: () -> Unit = {}) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    var refresh by remember { mutableIntStateOf(0) }
    // A failed load used to leave "Reading your history…" up forever.
    var loadFailed by remember { mutableStateOf(false) }
    val snap by produceState<HealthInsightsRepository.Snapshot?>(null, refresh) {
        loadFailed = false
        value = runCatching { HealthInsightsRepository.load(db) }.onFailure { loadFailed = true }.getOrNull()
    }
    var restUntil by remember { mutableStateOf(RestMode.until(context)) }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
            Text(tr("My Health"), modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val shareScope = rememberCoroutineScope()
            IconButton(
                onClick = {
                    shareScope.launch {
                        runCatching {
                            val recap = com.pulseloop.service.WeeklyRecap.load(db)
                            val uri = com.pulseloop.service.WeeklyRecap.share(context, recap)
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, tr("Share my week")))
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) { Icon(androidx.compose.material.icons.Icons.Filled.Share, tr("Share my week"), tint = PulseColors.textSecondary) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = snap
            if (s == null) {
                if (loadFailed) {
                    Text(tr("Couldn't load your health history."), color = PulseColors.warning, fontSize = 13.sp)
                } else {
                    // Only after a moment: on a quick read the line would just flash before the cards.
                    var slow by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { delay(400); slow = true }
                    if (slow) Text(tr("Reading your history…"), color = PulseColors.textMuted, fontSize = 13.sp)
                }
            } else {
                // Overview first: is anything off, then the long view, then the tools to dig in.
                // Programs (cycle, meds, postpartum) live on Today and in "+ Log" now, and the
                // day's sleep, heart and stress detail on their own tabs.
                RadarSection(s.radar, restUntil) { days ->
                    RestMode.set(context, days); restUntil = RestMode.until(context)
                }
                if (s.areas.isNotEmpty()) {
                    Section(tr("Health areas · last 60–90 days")) {
                        s.areas.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                                    Text(a.detail, fontSize = 12.sp, color = PulseColors.textMuted)
                                }
                                Text(a.rating.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ratingColor(a.rating), textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.widthIn(max = 120.dp))
                            }
                            Sparkline(a.trend, ratingColor(a.rating))
                        }
                    }
                }
                TrendsSection(db)
                HabitsSection(s.habits, s.cardio)
                StressSection(s, onBreathe)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
                        .clickable(onClick = onWhatChanged).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("What changed"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        Text(tr("Your nights against your own normal, kept as a log"), fontSize = 12.sp, color = PulseColors.textMuted)
                    }
                    Text("›", fontSize = 22.sp, color = PulseColors.textMuted)
                }
                // Lab reports (LabReportsUi.kt): the PDFs the user added, every marker charted by exam.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
                        .clickable(onClick = onLabReports).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Lab reports"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        Text(tr("Blood, urine and any other lab report, charted exam by exam"), fontSize = 12.sp, color = PulseColors.textMuted)
                    }
                    Text("›", fontSize = 22.sp, color = PulseColors.textMuted)
                }
                Text(
                    tr("Estimates from your ring compared with your own recent normal, not medical measurements."),
                    fontSize = 11.sp, color = PulseColors.textMuted,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RadarSection(r: SymptomRadar.Result?, restUntil: java.time.LocalDate?, onRest: (Int?) -> Unit) {
    // Open only when it has something to say; otherwise one line with Rest Mode's state.
    val flagged = r != null && r.baselineNights >= SymptomRadar.MIN_BASELINE && r.level != SymptomRadar.Level.NONE
    val quiet = when {
        r == null || r.baselineNights < SymptomRadar.MIN_BASELINE -> trf("Learning your normal: {0} of {1} nights.", r?.baselineNights ?: 0, SymptomRadar.MIN_BASELINE)
        else -> r.level.label
    } + if (restUntil != null) " · " + tr("Rest Mode on") else ""
    Section(tr("Symptom Radar"), summary = if (flagged) null else quiet) {
        if (r == null || r.baselineNights < SymptomRadar.MIN_BASELINE) {
            Text(trf("Learning your normal: {0} of {1} nights.", r?.baselineNights ?: 0, SymptomRadar.MIN_BASELINE), fontSize = 13.sp, color = PulseColors.textSecondary)
        } else {
            val color = when (r.level) {
                SymptomRadar.Level.NONE -> PulseColors.success
                SymptomRadar.Level.MINOR -> PulseColors.warning
                SymptomRadar.Level.MAJOR -> PulseColors.danger
            }
            Text(r.level.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(r.level.summary, fontSize = 13.sp, color = PulseColors.textSecondary)
            // When nothing is flagged the headline says so; five "Normal" rows under it were noise.
            if (r.level != SymptomRadar.Level.NONE) r.signals.forEach { sig ->
                Fact(sig.metric.title, if (sig.flagged) tr("Elevated") else tr("Normal"), sig.detail,
                    if (sig.flagged) PulseColors.warning else PulseColors.textSecondary)
            }
            if (r.level != SymptomRadar.Level.NONE) {
                Text(tr("Not a diagnosis. Rest, fluids and an easier day help; see a doctor if you feel unwell."), fontSize = 12.sp, color = PulseColors.textMuted)
            }
        }
        Row(Modifier.switchRow(checked = restUntil != null, onCheckedChange = { on -> onRest(if (on) 3 else null) }), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Rest Mode"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(
                    restUntil?.let { trf("On until {0}/{1}: activity nudges paused, the coach eases off.", it.dayOfMonth, it.monthValue) }
                        ?: tr("Feeling unwell? Pause activity nudges for 3 days."),
                    fontSize = 12.sp, color = PulseColors.textMuted,
                )
            }
            Switch(checked = restUntil != null, onCheckedChange = null)
        }
    }
}

@Composable
internal fun SleepBalanceSection(s: HealthInsightsRepository.SleepBalance) {
    Section(tr("Sleep balance")) {
        Fact(tr("Sleep need"), SleepFormat.duration(s.need.minutes),
            if (s.need.isLearned) tr("Learned from nights you woke recovered") else tr("Your goal, until 5 recovered nights teach the app yours"))
        s.debt?.let { d ->
            val color = when (d.level) {
                SleepDebt.Level.NONE -> PulseColors.success; SleepDebt.Level.LOW -> PulseColors.info
                SleepDebt.Level.MODERATE -> PulseColors.warning; SleepDebt.Level.HIGH -> PulseColors.danger
            }
            Fact(tr("Sleep debt"), "${d.level.label} · ${SleepFormat.duration(d.minutes)}", tr("Last 14 nights, recent nights weigh more"), color)
        } ?: Fact(tr("Sleep debt"), "—", tr("Needs 5 nights in the last 2 weeks"))
        s.bodyClock?.let { c ->
            Fact(tr("Body clock"), c.chronotype.label, trf("Usual sleep midpoint {0} · this week {1}", c.midpointLabel(), c.alignment.label.lowercase()) +
                if (c.alignment != BodyClock.Alignment.ALIGNED) " (${if (c.recentShiftMin > 0) "+" else ""}${c.recentShiftMin} min)" else "")
        }
    }
}

@Composable
private fun StressSection(s: HealthInsightsRepository.Snapshot, onBreathe: () -> Unit) {
    val context = LocalContext.current
    Section(tr("Stress & breathing"), summary = s.resilience?.let { trf("Resilience {0}", it.level.label.lowercase()) } ?: tr("Breathing sessions and how you recover")) {
        s.resilience?.let { Fact(tr("Resilience"), it.level.label, trf("Daytime recovery against stress, and how well nights restore you ({0} days)", it.daysUsed)) }
        val sessions = remember { MindfulSessions.all(context) }
        val week = sessions.filter { it.startAt >= System.currentTimeMillis() - 7 * 86_400_000L }
        Fact(tr("Breathing sessions this week"), "${week.size} · ${week.sumOf { it.minutes }} min")
        val effectDb = remember { PulseLoopDatabase.getInstance(context) }
        val effect by produceState<MindfulSessions.Effect?>(null) { value = runCatching { MindfulSessions.effect(context, effectDb) }.getOrNull() }
        effect?.let { e ->
            Fact(
                tr("After a session"), "HRV ${if (e.hrvChangePct >= 0) "+" else ""}${e.hrvChangePct}%",
                trf("Two hours after against two hours before, over {0} sessions. Readings during a session aren't counted: slow breathing raises HRV while it lasts.", e.sessions),
            )
        }
        OutlinedButton(onClick = onBreathe, modifier = Modifier.fillMaxWidth()) { Text(tr("Start a breathing session")) }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = PulseColors.textMuted)
    }
}

/** Heart rate over the last ~30 hours, each segment coloured by what was happening: sleep, workout, or day. */
@Composable
fun HeartRateContextChart(samples: List<VitalSample>, sleep: List<LongRange>, workouts: List<LongRange>) {
    val sorted = remember(samples) { samples.sortedBy { it.timestampMs } }
    val summary = remember(sorted) {
        trf("Heart rate since last night: latest {0} bpm, range {1}–{2} bpm, {3} readings", sorted.last().value.roundToInt(), sorted.minOf { it.value }.roundToInt(), sorted.maxOf { it.value }.roundToInt(), sorted.size)
    }
    Canvas(Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = summary }) {
        val t0 = sorted.first().timestampMs; val t1 = sorted.last().timestampMs.coerceAtLeast(t0 + 1)
        val lo = sorted.minOf { it.value } - 5; val hi = sorted.maxOf { it.value } + 5
        fun x(t: Long) = (t - t0).toFloat() / (t1 - t0) * size.width
        fun y(v: Double) = size.height - ((v - lo) / (hi - lo) * size.height).toFloat()
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]; val b = sorted[i]
            if (b.timestampMs - a.timestampMs > 45 * 60_000L) continue // a gap, not a line
            val color = when {
                workouts.any { b.timestampMs in it } -> PulseColors.steps
                sleep.any { b.timestampMs in it } -> PulseColors.sleep
                else -> PulseColors.heartRate
            }
            drawLine(color, Offset(x(a.timestampMs), y(a.value)), Offset(x(b.timestampMs), y(b.value)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun HabitsSection(h: HealthInsightsRepository.Habits, cardio: com.pulseloop.service.CardioCapacity?) {
    Section(
        tr("Habits & routines"),
        summary = listOfNotNull(
            h.bedtime?.let { trf("bed {0}", it) },
            h.wake?.let { trf("up {0}", it) },
            h.steps?.let { trf("{0} steps", "%,d".format(it)) },
        ).joinToString(" · ").ifBlank { tr("30-day averages") },
    ) {
        Text(tr("30-day averages"), fontSize = 12.sp, color = PulseColors.textMuted)
        h.bedtime?.let { Fact(tr("Bedtime"), it) }
        h.wake?.let { Fact(tr("Wake-up"), it) }
        h.sleepMin?.let { Fact(tr("Sleep"), SleepFormat.duration(it)) }
        h.steps?.let { Fact(tr("Steps"), "%,d".format(it)) }
        h.activeMin?.let { Fact(tr("Active minutes"), "$it min") }
        h.restingHr?.let { Fact(tr("Resting heart rate"), "${it.roundToInt()} bpm") }
        cardio?.let { c ->
            Fact(tr("Cardio capacity (VO₂max)"), "%.0f · %s".format(c.vo2max, c.level.label),
                if (c.fromRing) tr("Measured by your ring") else tr("Estimated from resting heart rate and age"))
            // No "cardiovascular age": from a ring it would only restate resting heart rate and age
            // as a number of years, which reads as far more than it is (OpenStrap Edge dropped its
            // own for the same reason).
        }
        if (listOf(h.bedtime, h.steps, h.restingHr).all { it == null }) {
            Text(tr("A few more days of data and your routines show up here."), fontSize = 12.sp, color = PulseColors.textMuted)
        }
    }
}

@Composable
private fun TrendsSection(db: PulseLoopDatabase) {
    var metric by remember { mutableStateOf(TrendMetric.SLEEP) }
    var period by remember { mutableStateOf(Trends.Period.WEEK) }
    // Each load remembers what it was for, so switching metric or period keeps the last chart up
    // (drawn as its own metric) until the new one is in, instead of collapsing to "Loading…".
    val load by produceState<TrendLoad?>(null, metric, period) {
        val m = metric
        val p = period
        value = TrendLoad(m, p, runCatching { Trends.aggregate(HealthInsightsRepository.daily(db, m, p.days), p) }.getOrNull())
    }
    Section(tr("Trends"), summary = tr("Sleep, steps, heart and more, from a week to a year")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrendMetric.entries.forEach { m ->
                val on = m == metric
                Text(m.label, fontSize = 12.sp, color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
                    modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(16.dp))
                        .background(if (on) PulseColors.accent else PulseColors.cardSoft)
                        .selectable(selected = on, role = Role.Tab) { metric = m }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Trends.Period.entries.forEach { p ->
                val on = p == period
                Text(p.label, fontSize = 12.sp, color = if (on) PulseColors.textPrimary else PulseColors.textMuted,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize().clip(RoundedCornerShape(4.dp))
                        .then(if (on) Modifier.dither(PulseColors.accent, 0.18f) else Modifier)
                        .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(4.dp))
                        .selectable(selected = on, role = Role.Tab) { period = p }.padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val l = load
        val pts = l?.points
        when {
            l == null -> Text(tr("Loading…"), fontSize = 12.sp, color = PulseColors.textMuted)
            pts == null -> Text(trf("Couldn't load {0} trends.", l.metric.label.lowercase()), fontSize = 12.sp, color = PulseColors.warning)
            pts.size < 2 -> Text(trf("Not enough {0} data for this view yet.", l.metric.label.lowercase()), fontSize = 12.sp, color = PulseColors.textMuted)
            else -> {
                val metric = l.metric
                val period = l.period
                val shown = pts.takeLast(if (period == Trends.Period.DAY) 30 else 26)
                val fmt = if (metric == TrendMetric.TEMPERATURE || metric == TrendMetric.SLEEP || metric == TrendMetric.WEIGHT) "%.1f" else "%.0f"
                val summary = trf("{0} by {1}: latest {2} {3}, range {4}–{5} {6}, {7} points", metric.label, period.label.lowercase(), fmt.format(shown.last().value), metric.unit, fmt.format(shown.minOf { it.value }), fmt.format(shown.maxOf { it.value }), metric.unit, shown.size)
                Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = summary }) {
                    val max = shown.maxOf { it.value }; val min = minOf(0.0, shown.minOf { it.value })
                    val lo = if (metric in setOf(TrendMetric.RESTING_HR, TrendMetric.HRV, TrendMetric.SPO2, TrendMetric.TEMPERATURE, TrendMetric.WEIGHT))
                        shown.minOf { it.value } * 0.95 else min
                    val range = (max - lo).takeIf { it > 0 } ?: 1.0
                    val bw = size.width / shown.size
                    shown.forEachIndexed { i, p ->
                        val h = ((p.value - lo) / range * size.height).toFloat().coerceAtLeast(2f)
                        drawRect(PulseColors.accent, Offset(i * bw + bw * 0.15f, size.height - h), Size(bw * 0.7f, h))
                    }
                }
                val last = shown.last()
                Text(trf("Latest {0}: {1} {2} · average {3} {4}", period.label.lowercase(), fmt.format(last.value), metric.unit, fmt.format(shown.map { it.value }.average()), metric.unit),
                    fontSize = 12.sp, color = PulseColors.textSecondary)
            }
        }
    }
}

/** One trend load and the metric and period it was made for. Null points: the load failed. */
private data class TrendLoad(val metric: TrendMetric, val period: Trends.Period, val points: List<Trends.Point>?)

// ── Breathing session ─────────────────────────────────────────────────────────────────────────

/**
 * An unguided breathing session: pick a length, follow the circle (in for 4 s, out for 6 s), and
 * the heart rate the ring logged during it is saved with the session.
 */
@Composable
fun BreatheScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(5) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var remaining by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<MindfulSessions.Session?>(null) }
    val reduceMotion = rememberReduceMotion()

    fun finish() {
        val s = startedAt ?: return
        startedAt = null
        val end = System.currentTimeMillis()
        // Leaving the screen right after finish() disposes `scope`; the save must outlive it.
        // UNDISPATCHED enters the NonCancellable block before any dispose can cancel the launch.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = withContext(NonCancellable) { MindfulSessions.finish(context, db, s, end) }
            result = saved
        }
    }

    BackHandler(enabled = startedAt != null) { finish(); onBack() }

    LaunchedEffect(startedAt) {
        val s = startedAt ?: return@LaunchedEffect
        while (true) {
            elapsedMs = System.currentTimeMillis() - s
            remaining = (minutes * 60 - (elapsedMs / 1000)).toInt()
            if (remaining <= 0) { finish(); break }
            delay(200)
        }
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = { if (startedAt != null) finish(); onBack() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"))
            }
            Text(tr("Breathe"), modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        val r = result
        when {
            startedAt != null -> {
                // Same 10 s cycle as the circle (in for the first 4 s, out for 6), counted from the
                // session start so the cue and the circle agree.
                val inhaling = elapsedMs % BREATH_CYCLE_MS < INHALE_MS
                val s = if (reduceMotion) 0.8f else {
                    val t = rememberInfiniteTransition(label = "breath")
                    val animated by t.animateFloat(
                        0.55f, 1f,
                        infiniteRepeatable(keyframes { durationMillis = BREATH_CYCLE_MS.toInt(); 1f at INHALE_MS.toInt() using FastOutSlowInEasing; 0.55f at BREATH_CYCLE_MS.toInt() }, RepeatMode.Restart),
                        label = "scale",
                    )
                    animated
                }
                Box(Modifier.size(220.dp).scale(s).clip(CircleShape).dither(PulseColors.accent, 0.35f, CircleShape).border(2.dp, PulseColors.accent, CircleShape))
                Spacer(Modifier.height(24.dp))
                Text(
                    if (inhaling) tr("Breathe in") else tr("Breathe out"), fontSize = 18.sp, color = PulseColors.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text("%d:%02d".format(remaining / 60, remaining % 60), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { finish() }) { Text(tr("End session")) }
            }
            r != null -> {
                Text(tr("Session saved"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("${r.minutes} min" + (r.avgHr?.let { trf(" · average {0} bpm", it) } ?: ""), fontSize = 15.sp, color = PulseColors.textSecondary)
                if (r.startHr != null && r.endHr != null) {
                    Text(trf("Heart rate {0} → {1} bpm", r.startHr, r.endHr), fontSize = 15.sp, color = PulseColors.textSecondary)
                } else {
                    Text(tr("Your ring logs heart rate every few minutes, so short sessions may show none."), fontSize = 12.sp, color = PulseColors.textMuted)
                }
                Text(tr("Tagged as \"Meditation or relaxing\" in today's journal."), fontSize = 12.sp, color = PulseColors.textMuted)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(tr("Done")) }
            }
            else -> {
                Text(tr("Slow breathing: in for 4 seconds, out for 6."), fontSize = 15.sp, color = PulseColors.textSecondary)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { m ->
                        val on = m == minutes
                        Text("$m min", fontSize = 14.sp, color = if (on) PulseColors.onAccent else PulseColors.textPrimary,
                            modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(16.dp))
                                .background(if (on) PulseColors.accent else PulseColors.card)
                                .selectable(selected = on, role = Role.RadioButton) { minutes = m }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { result = null; elapsedMs = 0L; startedAt = System.currentTimeMillis() }) { Text(tr("Start")) }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private const val BREATH_CYCLE_MS = 10_000L
private const val INHALE_MS = 4_000L

/**
 * A tab's shortcut into [MyHealthScreen], worded for the question that tab raises
 * ("See your last 90 days" on Sleep, "Trends & lab reports" on Vitals, "How the month went"
 * on Activity). The header's health line is the main door; this is the in-context one.
 */
@Composable
fun MyHealthEntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = PulseColors.textMuted)
        }
        Text("›", fontSize = 22.sp, color = PulseColors.textMuted)
    }
}


/** Ring steps against the phone's own counter, with the option to show calibrated steps on Today. */
@Composable
internal fun StepCalibrationCard(db: PulseLoopDatabase) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(com.pulseloop.service.StepCalibration.hasPermission(context)) }
    var applied by remember { mutableStateOf(com.pulseloop.service.StepCalibration.isApplied(context)) }
    val scope = rememberCoroutineScope()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) scope.launch { com.pulseloop.service.StepCalibration.sample(context) }
    }
    // "Collecting: needs N days…" is the answer for a null result, so it must wait for the read.
    var resultLoaded by remember { mutableStateOf(false) }
    val result by produceState<com.pulseloop.service.StepCalibration.Result?>(null, granted) {
        value = runCatching {
            val zone = java.time.ZoneId.systemDefault()
            val ring = db.activityDailyDao().recentReal(60).associate { java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() to it.steps }
            com.pulseloop.service.StepCalibration.factor(com.pulseloop.service.StepCalibration.phoneDays(context), ring, java.time.LocalDate.now())
        }.getOrNull()
        resultLoaded = true
    }
    Section(tr("Ring steps vs your phone")) {
        when {
            !granted -> {
                Text(tr("Your phone counts steps too. Comparing the two over a few days shows whether your ring over- or under-counts."), fontSize = 13.sp, color = PulseColors.textSecondary)
                OutlinedButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) launcher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("Compare with my phone")) }
            }
            !resultLoaded -> Spacer(Modifier.height(36.dp))
            result == null -> Text(
                trf("Collecting: needs {0} days with at least {1} steps on both. Carry your phone as usual.", com.pulseloop.service.StepCalibration.MIN_DAYS, com.pulseloop.service.StepCalibration.MIN_STEPS),
                fontSize = 13.sp, color = PulseColors.textMuted,
            )
            else -> {
                val r = result!!
                val pct = ((r.factor - 1) * 100).roundToInt()
                Text(
                    when {
                        kotlin.math.abs(pct) < 5 -> trf("Your ring and phone agree within 5% ({0} days).", r.days)
                        pct > 0 -> trf("Your ring counts about {0}% fewer steps than your phone ({1} days).", pct, r.days)
                        else -> trf("Your ring counts about {0}% more steps than your phone ({1} days).", -pct, r.days)
                    },
                    fontSize = 14.sp, color = PulseColors.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Show phone-calibrated steps on Today"), fontSize = 13.sp, color = PulseColors.textSecondary, modifier = Modifier.weight(1f))
                    Switch(checked = applied, onCheckedChange = { applied = it; com.pulseloop.service.StepCalibration.setApplied(context, it) })
                }
                Text(tr("Goals, scores and Health Connect keep the ring's own count."), fontSize = 11.sp, color = PulseColors.textMuted)
            }
        }
    }
}

/** Sleep need, debt and body clock, under last night on the Sleep tab. Nothing until loaded. */
@Composable
fun SleepBalanceCard() {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val balance by produceState<HealthInsightsRepository.SleepBalance?>(null) {
        value = runCatching { HealthInsightsRepository.sleepBalance(db) }.getOrNull()
    }
    balance?.let { SleepBalanceSection(it) }
}

/** Today's cardio load on the Activity tab; nothing until there are enough days of heart rate. */
@Composable
fun CardioLoadCard(refreshKey: Any?) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val load by produceState<com.pulseloop.service.CardioLoad.Result?>(null, refreshKey) {
        value = runCatching { HealthInsightsRepository.cardioLoadToday(db) }.getOrNull() ?: value
    }
    load?.let { l ->
        Section(tr("Cardio load today")) {
            Fact(
                tr("Cardio load"), "${l.load}" + (l.band?.let { " · ${it.label}" } ?: ""),
                trf("Heart rate above your quiet waking {0} bpm, {1} min well above it", l.quietHr, l.elevatedMinutes) +
                    (l.usual?.let { trf(". Your usual day: {0}", it) } ?: ""),
            )
        }
    }
}
