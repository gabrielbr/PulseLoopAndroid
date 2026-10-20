package com.pulseloop.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.BodyClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * My Health: the longer view (ported in approach from anikbharadwaj129/Colmi, Apache-2.0).
 * Symptom Radar and Rest Mode, long-term health areas, sleep need and debt, body clock, daytime
 * stress and resilience, heart rate today in context, cardio capacity, habits, and trends by
 * day / week / month / year. Every number is an estimate against the user's own normal.
 */

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
            content()
        },
    )
}

@Composable
private fun Fact(label: String, value: String, note: String? = null, color: Color = PulseColors.textPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = PulseColors.textSecondary)
            note?.let { Text(it, fontSize = 12.sp, color = PulseColors.textMuted) }
        }
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
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
fun MyHealthScreen(onBack: () -> Unit, onBreathe: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val snap by produceState<HealthInsightsRepository.Snapshot?>(null, refresh) {
        value = runCatching { HealthInsightsRepository.load(db) }.getOrNull()
    }
    var restUntil by remember { mutableStateOf(RestMode.until(context)) }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("My Health", modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = snap
            if (s == null) {
                Text("Reading your history…", color = PulseColors.textMuted, fontSize = 13.sp)
            } else {
                RadarSection(s.radar, restUntil) { days ->
                    RestMode.set(context, days); restUntil = RestMode.until(context)
                }
                if (s.areas.isNotEmpty()) {
                    Section("Health areas · last 60–90 days") {
                        s.areas.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                                    Text(a.detail, fontSize = 12.sp, color = PulseColors.textMuted)
                                }
                                Text(a.rating.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ratingColor(a.rating))
                            }
                            Sparkline(a.trend, ratingColor(a.rating))
                        }
                    }
                }
                SleepSection(s)
                StressSection(s, onBreathe)
                HeartSection(s)
                HabitsSection(s.habits)
                TrendsSection(db)
                Text(
                    "Estimates from your ring compared with your own recent normal, not medical measurements.",
                    fontSize = 11.sp, color = PulseColors.textMuted,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RadarSection(r: SymptomRadar.Result?, restUntil: java.time.LocalDate?, onRest: (Int?) -> Unit) {
    Section("Symptom Radar") {
        if (r == null || r.baselineNights < SymptomRadar.MIN_BASELINE) {
            Text("Learning your normal: ${r?.baselineNights ?: 0} of ${SymptomRadar.MIN_BASELINE} nights.", fontSize = 13.sp, color = PulseColors.textSecondary)
        } else {
            val color = when (r.level) {
                SymptomRadar.Level.NONE -> PulseColors.success
                SymptomRadar.Level.MINOR -> PulseColors.warning
                SymptomRadar.Level.MAJOR -> PulseColors.danger
            }
            Text(r.level.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(r.level.summary, fontSize = 13.sp, color = PulseColors.textSecondary)
            r.signals.forEach { sig ->
                Fact(sig.metric.title, if (sig.flagged) "Elevated" else "Normal", sig.detail,
                    if (sig.flagged) PulseColors.warning else PulseColors.textSecondary)
            }
            if (r.level != SymptomRadar.Level.NONE) {
                Text("Not a diagnosis. Rest, fluids and an easier day help; see a doctor if you feel unwell.", fontSize = 12.sp, color = PulseColors.textMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Rest Mode", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(
                    restUntil?.let { "On until ${it.dayOfMonth}/${it.monthValue}: activity nudges paused, the coach eases off." }
                        ?: "Feeling unwell? Pause activity nudges for 3 days.",
                    fontSize = 12.sp, color = PulseColors.textMuted,
                )
            }
            Switch(checked = restUntil != null, onCheckedChange = { on -> onRest(if (on) 3 else null) })
        }
    }
}

@Composable
private fun SleepSection(s: HealthInsightsRepository.Snapshot) {
    Section("Sleep") {
        Fact("Sleep need", SleepFormat.duration(s.need.minutes),
            if (s.need.isLearned) "Learned from nights you woke recovered" else "Your goal, until 5 recovered nights teach the app yours")
        s.debt?.let { d ->
            val color = when (d.level) {
                SleepDebt.Level.NONE -> PulseColors.success; SleepDebt.Level.LOW -> PulseColors.info
                SleepDebt.Level.MODERATE -> PulseColors.warning; SleepDebt.Level.HIGH -> PulseColors.danger
            }
            Fact("Sleep debt", "${d.level.label} · ${SleepFormat.duration(d.minutes)}", "Last 14 nights, recent nights weigh more", color)
        } ?: Fact("Sleep debt", "—", "Needs 5 nights in the last 2 weeks")
        s.bodyClock?.let { c ->
            Fact("Body clock", c.chronotype.label, "Usual sleep midpoint ${c.midpointLabel()} · this week ${c.alignment.label.lowercase()}" +
                if (c.alignment != BodyClock.Alignment.ALIGNED) " (${if (c.recentShiftMin > 0) "+" else ""}${c.recentShiftMin} min)" else "")
        }
    }
}

@Composable
private fun StressSection(s: HealthInsightsRepository.Snapshot, onBreathe: () -> Unit) {
    val context = LocalContext.current
    Section("Stress") {
        val z = s.todayStress
        if (z == null) {
            Text("No daytime stress readings today yet.", fontSize = 13.sp, color = PulseColors.textMuted)
        } else {
            Text("Today: ${z.summary.label}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            val total = z.totalMin.coerceAtLeast(1).toFloat()
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(2.dp))) {
                listOf(z.restoredMin to PulseColors.success, z.relaxedMin to PulseColors.info,
                    z.engagedMin to PulseColors.warning, z.stressedMin to PulseColors.danger).forEach { (m, c) ->
                    if (m > 0) Box(Modifier.weight(m / total).fillMaxHeight().background(c))
                }
            }
            Text("Restored ${SleepFormat.duration(z.restoredMin)} · relaxed ${SleepFormat.duration(z.relaxedMin)} · engaged ${SleepFormat.duration(z.engagedMin)} · stressed ${SleepFormat.duration(z.stressedMin)}",
                fontSize = 12.sp, color = PulseColors.textMuted)
        }
        s.resilience?.let { Fact("Resilience", it.level.label, "Daytime recovery against stress, and how well nights restore you (${it.daysUsed} days)") }
        val sessions = remember { MindfulSessions.all(context) }
        val week = sessions.filter { it.startAt >= System.currentTimeMillis() - 7 * 86_400_000L }
        Fact("Breathing sessions this week", "${week.size} · ${week.sumOf { it.minutes }} min")
        OutlinedButton(onClick = onBreathe, modifier = Modifier.fillMaxWidth()) { Text("Start a breathing session") }
    }
}

@Composable
private fun HeartSection(s: HealthInsightsRepository.Snapshot) {
    Section("Heart") {
        if (s.hrToday.size >= 2) {
            Text("Heart rate since last night", fontSize = 13.sp, color = PulseColors.textSecondary)
            HeartRateContextChart(s.hrToday, s.sleepWindows, s.workoutWindows)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(PulseColors.sleep, "Sleep"); Legend(PulseColors.steps, "Workout"); Legend(PulseColors.heartRate, "Day")
            }
        }
        s.cardio?.let { c ->
            Fact("Cardio capacity (VO₂max)", "%.0f · %s".format(c.vo2max, c.level.label),
                if (c.fromRing) "Measured by your ring" else "Estimated from resting heart rate and age")
            c.cardioAge?.let { Fact("Cardiovascular age", "about $it", "Estimate from VO₂max and HRV against age norms") }
        } ?: Text("Add your age in Settings → User Profile for cardio capacity.", fontSize = 12.sp, color = PulseColors.textMuted)
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
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
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
private fun HabitsSection(h: HealthInsightsRepository.Habits) {
    Section("Habits & routines · 30-day averages") {
        h.bedtime?.let { Fact("Bedtime", it) }
        h.wake?.let { Fact("Wake-up", it) }
        h.sleepMin?.let { Fact("Sleep", SleepFormat.duration(it)) }
        h.steps?.let { Fact("Steps", "%,d".format(it)) }
        h.activeMin?.let { Fact("Active minutes", "$it min") }
        h.restingHr?.let { Fact("Resting heart rate", "${it.roundToInt()} bpm") }
        if (listOf(h.bedtime, h.steps, h.restingHr).all { it == null }) {
            Text("A few more days of data and your routines show up here.", fontSize = 12.sp, color = PulseColors.textMuted)
        }
    }
}

@Composable
private fun TrendsSection(db: PulseLoopDatabase) {
    var metric by remember { mutableStateOf(TrendMetric.SLEEP) }
    var period by remember { mutableStateOf(Trends.Period.WEEK) }
    val points by produceState<List<Trends.Point>?>(null, metric, period) {
        value = null
        value = runCatching { Trends.aggregate(HealthInsightsRepository.daily(db, metric, period.days), period) }.getOrNull()
    }
    Section("Trends") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TrendMetric.entries.forEach { m ->
                val on = m == metric
                Text(m.label, fontSize = 12.sp, color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (on) PulseColors.accent else PulseColors.cardSoft)
                        .clickable { metric = m }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Trends.Period.entries.forEach { p ->
                val on = p == period
                Text(p.label, fontSize = 12.sp, color = if (on) PulseColors.textPrimary else PulseColors.textMuted,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                        .then(if (on) Modifier.dither(PulseColors.accent, 0.18f) else Modifier)
                        .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(4.dp))
                        .clickable { period = p }.padding(vertical = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        val pts = points
        when {
            pts == null -> Text("Loading…", fontSize = 12.sp, color = PulseColors.textMuted)
            pts.size < 2 -> Text("Not enough ${metric.label.lowercase()} data for this view yet.", fontSize = 12.sp, color = PulseColors.textMuted)
            else -> {
                val shown = pts.takeLast(if (period == Trends.Period.DAY) 30 else 26)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
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
                val fmt = if (metric == TrendMetric.TEMPERATURE || metric == TrendMetric.SLEEP || metric == TrendMetric.WEIGHT) "%.1f" else "%.0f"
                Text("Latest ${period.label.lowercase()}: ${fmt.format(last.value)} ${metric.unit} · average ${fmt.format(shown.map { it.value }.average())} ${metric.unit}",
                    fontSize = 12.sp, color = PulseColors.textSecondary)
            }
        }
    }
}

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
    var result by remember { mutableStateOf<MindfulSessions.Session?>(null) }

    fun finish() {
        val s = startedAt ?: return
        startedAt = null
        scope.launch { result = MindfulSessions.finish(context, db, s, System.currentTimeMillis()) }
    }

    LaunchedEffect(startedAt) {
        val s = startedAt ?: return@LaunchedEffect
        while (true) {
            remaining = (minutes * 60 - ((System.currentTimeMillis() - s) / 1000)).toInt()
            if (remaining <= 0) { finish(); break }
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = { if (startedAt != null) finish(); onBack() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Breathe", modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        val r = result
        when {
            startedAt != null -> {
                val t = rememberInfiniteTransition(label = "breath")
                val s by t.animateFloat(
                    0.55f, 1f,
                    infiniteRepeatable(keyframes { durationMillis = 10_000; 1f at 4_000 using FastOutSlowInEasing; 0.55f at 10_000 }, RepeatMode.Restart),
                    label = "scale",
                )
                Box(Modifier.size(220.dp).scale(s).clip(CircleShape).dither(PulseColors.accent, 0.35f, CircleShape).border(2.dp, PulseColors.accent, CircleShape))
                Spacer(Modifier.height(24.dp))
                Text(if (s > 0.98f || remaining % 10 < 4) "Breathe in" else "Breathe out", fontSize = 18.sp, color = PulseColors.textPrimary)
                Text("%d:%02d".format(remaining / 60, remaining % 60), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { finish() }) { Text("End session") }
            }
            r != null -> {
                Text("Session saved", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("${r.minutes} min" + (r.avgHr?.let { " · average $it bpm" } ?: ""), fontSize = 15.sp, color = PulseColors.textSecondary)
                if (r.startHr != null && r.endHr != null) {
                    Text("Heart rate ${r.startHr} → ${r.endHr} bpm", fontSize = 15.sp, color = PulseColors.textSecondary)
                } else {
                    Text("Your ring logs heart rate every few minutes, so short sessions may show none.", fontSize = 12.sp, color = PulseColors.textMuted)
                }
                Text("Tagged as \"Meditation or relaxing\" in today's journal.", fontSize = 12.sp, color = PulseColors.textMuted)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Done") }
            }
            else -> {
                Text("Slow breathing: in for 4 seconds, out for 6.", fontSize = 15.sp, color = PulseColors.textSecondary)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { m ->
                        val on = m == minutes
                        Text("$m min", fontSize = 14.sp, color = if (on) PulseColors.onAccent else PulseColors.textPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                                .background(if (on) PulseColors.accent else PulseColors.card)
                                .clickable { minutes = m }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { result = null; startedAt = System.currentTimeMillis() }) { Text("Start") }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The way into [MyHealthScreen] from Vitals: one row, with Rest Mode shown while it is on. */
@Composable
fun MyHealthEntryCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val restUntil = remember { RestMode.until(context) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("My Health & Trends", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(
                if (restUntil != null) "Rest Mode on until ${restUntil.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}"
                else "Symptom radar, sleep debt, stress, body clock, cardio capacity",
                fontSize = 12.sp, color = if (restUntil != null) PulseColors.accent else PulseColors.textMuted,
            )
        }
        Text("›", fontSize = 22.sp, color = PulseColors.textMuted)
    }
}
