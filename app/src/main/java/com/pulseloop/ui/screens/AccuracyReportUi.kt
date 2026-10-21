package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AccuracyCheckDayEntity
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.health.accuracy.AccuracyComparison
import com.pulseloop.health.accuracy.AccuracyComparison.Verdict
import com.pulseloop.health.accuracy.AccuracyReportText
import com.pulseloop.health.accuracy.AccuracyScorer
import com.pulseloop.health.accuracy.ComparisonMetric
import com.pulseloop.health.accuracy.EchoCheck
import com.pulseloop.health.accuracy.Stage
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * A check's report (docs/accuracy-check-plan.md, "The report"): one card per metric with a
 * verdict, the metrics outside their band listed worst first, and per-day detail behind a tap.
 * Bands are editable and the verdicts recompute from the stored day rows, with no new read.
 * [correctionFor] lets the corrections UI hang a control under a metric with a consistent bias.
 */
@Composable
internal fun AccuracyReportSection(
    check: AccuracyCheckEntity,
    correctionFor: (@Composable (AccuracyComparison.Summary) -> Unit)? = { AccuracyCorrectionControl(check, it) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val days by db.accuracyDao().daysFlow(check.id).collectAsState(initial = emptyList())
    var scoring by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<ComparisonMetric?>(null) }
    val bands = AccuracyComparison.parseBands(check.bandsRaw)

    fun rescore(force: Boolean) {
        scoring = true
        scope.launch {
            val r = runCatching {
                if (force) AccuracyScorer.score(context, check) else AccuracyScorer.scoreIfStale(context, check)
            }.getOrNull()
            status = when (r) {
                AccuracyScorer.Result.Unavailable, null -> "Couldn't read ${check.referenceLabel} from Health Connect just now."
                else -> null
            }
            scoring = false
        }
    }
    // An open check refreshes on view. A finished one keeps its stored result, unless days were
    // still waiting for the reference to deliver when it was last scored.
    LaunchedEffect(check.id) {
        val waiting = db.accuracyDao().days(check.id).any { it.note == AccuracyScorer.NOTE_WAITING }
        if (check.isOpen || check.scoredAt == null || waiting) rescore(force = false)
    }

    val metrics = ComparisonMetric.entries.filter { m -> days.any { it.metric == m.name } }
    val summaries = metrics.map { AccuracyComparison.summarize(it, days, bands[it] ?: it.band) }
    val echoRow = days.firstOrNull { it.metric == AccuracyScorer.ECHO_ROW }
    val echo = echoRow?.let { EchoCheck(it.pairs, it.inBand) }
    val waitingDays = days.filter { it.note == AccuracyScorer.NOTE_WAITING }.map { it.day }.distinct().size

    AccuracyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (check.state == AccuracyCheckEntity.STATE_RUNNING) "So far (preliminary)" else "Report",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary, modifier = Modifier.weight(1f),
            )
            if (scoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { rescore(force = true) }) { Text("Recompute") }
        }
        Text(
            "How your ring compares with ${check.referenceLabel}, the device you chose to trust. This is agreement between two " +
                "consumer devices, not a lab measurement.",
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
        if (echo?.looksLikeEcho == true) {
            Text(
                "${echo.matching} of ${echo.compared} heart-rate readings from ${check.referenceLabel} match your ring exactly. " +
                    "That looks like PulseLoop's own data coming back through a sync app, so this report would compare the ring " +
                    "with itself. Check the sync direction in that app.",
                fontSize = 12.sp, color = PulseColors.danger,
            )
        }
        if (waitingDays > 0) {
            Text(
                "Waiting for ${check.referenceLabel} to deliver $waitingDays day${if (waitingDays == 1) "" else "s"}. Open the app " +
                    "that syncs it to send it sooner; otherwise those days are scored 24 hours after they end.",
                fontSize = 12.sp, color = PulseColors.warning,
            )
        }
        status?.let { Text(it, fontSize = 12.sp, color = PulseColors.warning) }
        if (days.isEmpty() && !scoring) {
            Text("Nothing to compare yet. Check back after the first full day and night.", fontSize = 13.sp, color = PulseColors.textSecondary)
        }
        val worst = AccuracyReportText.worstFirst(summaries)
        if (worst.isNotEmpty()) {
            Text("Where the ring falls short", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            worst.forEach { s ->
                Text("• ${s.metric.label}: ${AccuracyReportText.signed(s.metric, s.bias)} (${AccuracyReportText.verdictLabel(s.verdict).lowercase()})",
                    fontSize = 12.sp, color = PulseColors.textSecondary)
            }
        } else if (summaries.isNotEmpty() && summaries.none { it.verdict == Verdict.NOT_ENOUGH_DATA }) {
            Text("Everything compared agrees within its band.", fontSize = 13.sp, color = PulseColors.success)
        }
    }

    summaries.forEach { s ->
        MetricCard(
            check = check,
            summary = s,
            days = days.filter { it.metric == s.metric.name }.sortedBy { it.day },
            expanded = expanded == s.metric,
            onToggle = { expanded = if (expanded == s.metric) null else s.metric },
            onBand = { v ->
                val next = bands.toMutableMap().apply { put(s.metric, v) }
                scope.launch { db.accuracyDao().upsert(check.copy(bandsRaw = AccuracyComparison.encodeBands(next))) }
            },
            correction = correctionFor,
        )
    }
}

private fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.AGREES -> PulseColors.success
    Verdict.CONSISTENTLY_HIGH, Verdict.CONSISTENTLY_LOW -> PulseColors.warning
    Verdict.INCONSISTENT -> PulseColors.danger
    Verdict.NOT_ENOUGH_DATA -> PulseColors.textMuted
}

@Composable
private fun MetricCard(
    check: AccuracyCheckEntity,
    summary: AccuracyComparison.Summary,
    days: List<AccuracyCheckDayEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBand: (Double) -> Unit,
    correction: (@Composable (AccuracyComparison.Summary) -> Unit)?,
) {
    val m = summary.metric
    AccuracyCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                val headline = when {
                    summary.verdict == Verdict.NOT_ENOUGH_DATA -> null
                    m.kind == ComparisonMetric.Kind.STAGES -> summary.stageAgreement?.let { "${(it * 100).roundToInt()} % same stage" }
                    else -> AccuracyReportText.signed(m, summary.bias) + " vs ${check.referenceLabel}"
                }
                headline?.let { Text(it, fontSize = 13.sp, color = PulseColors.textSecondary) }
            }
            Text(
                AccuracyReportText.verdictLabel(summary.verdict),
                fontSize = 12.sp, color = verdictColor(summary.verdict),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(PulseColors.cardSoft).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(AccuracyReportText.reading(summary, check.referenceLabel), fontSize = 12.sp, color = PulseColors.textSecondary)
        if (summary.verdict == Verdict.CONSISTENTLY_HIGH || summary.verdict == Verdict.CONSISTENTLY_LOW) {
            correction?.invoke(summary)
        }
        if (!expanded) {
            Text("Details", fontSize = 12.sp, color = PulseColors.accent, modifier = Modifier.clickable(onClick = onToggle))
            return@AccuracyCard
        }
        if (m.kind != ComparisonMetric.Kind.STAGES && summary.validDays > 0) {
            val (lo, hi) = summary.limits
            Text(
                "Average difference ${AccuracyReportText.signed(m, summary.bias)}; 95 % of differences between " +
                    "${AccuracyReportText.signed(m, lo)} and ${AccuracyReportText.signed(m, hi)}" +
                    if (m.kind == ComparisonMetric.Kind.PAIRED) " (${summary.pairs} matched readings)." else ".",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
        if (m.kind == ComparisonMetric.Kind.STAGES) StageConfusion(days, check.referenceLabel)
        days.forEach { DayRow(m, it, check.referenceLabel) }
        if (m.kind != ComparisonMetric.Kind.STAGES) {
            Text("Agreement band: ±${AccuracyReportText.magnitude(m, summary.band)}", fontSize = 12.sp, color = PulseColors.textPrimary)
            var draft by remember(summary.band) { mutableStateOf(summary.band.toFloat()) }
            val range = bandRange(m)
            Slider(
                value = draft.coerceIn(range.start, range.endInclusive), onValueChange = { draft = it },
                onValueChangeFinished = { onBand(roundBand(m, draft.toDouble())) }, valueRange = range,
            )
        }
    }
}

private fun bandRange(m: ComparisonMetric): ClosedFloatingPointRange<Float> = when {
    m.percentBand -> 2f..40f
    m == ComparisonMetric.SLEEP_ASLEEP -> 5f..90f
    m == ComparisonMetric.SLEEP_ONSET || m == ComparisonMetric.SLEEP_WAKE -> 5f..60f
    m == ComparisonMetric.SPO2 -> 0.5f..5f
    m == ComparisonMetric.HRV -> 2f..30f
    else -> 1f..15f
}

private fun roundBand(m: ComparisonMetric, v: Double): Double =
    if (m == ComparisonMetric.SPO2) (v * 2).roundToInt() / 2.0 else v.roundToInt().toDouble().coerceAtLeast(1.0)

@Composable
private fun DayRow(m: ComparisonMetric, d: AccuracyCheckDayEntity, ref: String) {
    val date = LocalDate.ofEpochDay(d.day).format(DateTimeFormatter.ofPattern("EEE d MMM"))
    val note = if (d.valid) null else AccuracyReportText.dayNote(d.note, ref) ?: "not counted"
    val text = when (m.kind) {
        ComparisonMetric.Kind.DAILY -> {
            val diff = AccuracyComparison.diff(m, d.ringValue, d.refValue)
            "$date · ring ${AccuracyReportText.value(m, d.ringValue)} · $ref ${AccuracyReportText.value(m, d.refValue)}" +
                (diff?.let { " (${AccuracyReportText.signed(m, it)})" } ?: "")
        }
        ComparisonMetric.Kind.PAIRED ->
            "$date · ${d.pairs} readings · ${if (d.pairs > 0) AccuracyReportText.signed(m, d.sumDiff / d.pairs) else "—"} on average"
        ComparisonMetric.Kind.STAGES ->
            "$date · ${if (d.pairs > 0) "${(d.inBand * 100.0 / d.pairs).roundToInt()} % of ${d.pairs} min on the same stage" else "no overlap"}"
    }
    Text(text + (note?.let { " — $it" } ?: ""), fontSize = 12.sp, color = if (d.valid) PulseColors.textSecondary else PulseColors.textMuted)
}

@Composable
private fun StageConfusion(days: List<AccuracyCheckDayEntity>, ref: String) {
    val totals = mutableMapOf<Pair<Stage, Stage>, Int>()
    days.filter { it.valid }.forEach { d ->
        AccuracyComparison.StageAgreement.decode(d.note).forEach { (k, v) -> totals.merge(k, v, Int::plus) }
    }
    if (totals.isEmpty()) return
    val stages = listOf(Stage.AWAKE, Stage.LIGHT, Stage.DEEP, Stage.REM)
    Text("When the ring says… what $ref says (minutes)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
    stages.forEach { ringStage ->
        val row = stages.map { totals[ringStage to it] ?: 0 }
        if (row.sum() == 0) return@forEach
        Text(
            "${ringStage.name.lowercase()}: " + stages.zip(row).filter { it.second > 0 }
                .joinToString(", ") { "${it.first.name.lowercase()} ${it.second}" },
            fontSize = 12.sp, color = PulseColors.textSecondary,
        )
    }
}

/** Filled in by the corrections phase; shows nothing until then. */
@Composable
internal fun AccuracyCorrectionControl(check: AccuracyCheckEntity, summary: AccuracyComparison.Summary) {
}
