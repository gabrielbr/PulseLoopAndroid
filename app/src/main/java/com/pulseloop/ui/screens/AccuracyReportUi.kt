package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.pulseloop.health.accuracy.AccuracyCorrections
import com.pulseloop.health.accuracy.RingIdentity
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
    val days by remember(db, check.id) { db.accuracyDao().daysFlow(check.id) }.collectAsState(initial = emptyList())
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
                AccuracyScorer.Result.Unavailable, null -> trf("Couldn't read {0} from Health Connect just now.", check.referenceLabel)
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
                if (check.state == AccuracyCheckEntity.STATE_RUNNING) tr("So far (preliminary)") else tr("Report"),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary, modifier = Modifier.weight(1f),
            )
            if (scoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { rescore(force = true) }) { Text(tr("Recompute")) }
            if (summaries.isNotEmpty()) TextButton(onClick = {
                scope.launch {
                    val ring = db.deviceDao().currentReal()?.takeIf { RingIdentity.keyOf(it) == check.deviceKey }
                    val text = AccuracyReportText.share(
                        ringLabel = check.deviceLabel, model = ring?.wearableModelID, firmware = check.firmware,
                        referenceLabel = check.referenceLabel, span = AccuracyUiText.span(check), summaries = summaries,
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, tr("Share accuracy report")))
                }
            }) { Text(tr("Share")) }
        }
        Text(
            trf("How your ring compares with {0}, the device you chose to trust. This is agreement between two consumer devices, not a lab measurement.", check.referenceLabel),
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
        if (echo?.looksLikeEcho == true) {
            Text(
                trf("{0} of {1} heart-rate readings from {2} match your ring exactly. ", echo.matching, echo.compared, check.referenceLabel) +
                    tr("That looks like PulseLoop's own data coming back through a sync app, so this report would compare the ring " +
                    "with itself. Check the sync direction in that app."),
                fontSize = 12.sp, color = PulseColors.danger,
            )
        }
        if (waitingDays > 0) {
            Text(
                trf("Waiting for {0} to deliver {1} day{2}. Open the app that syncs it to send it sooner; otherwise those days are scored 24 hours after they end.", check.referenceLabel, waitingDays, if (waitingDays == 1) "" else "s"),
                fontSize = 12.sp, color = PulseColors.warning,
            )
        }
        status?.let { Text(it, fontSize = 12.sp, color = PulseColors.warning) }
        if (days.isEmpty() && !scoring) {
            Text(tr("Nothing to compare yet. Check back after the first full day and night."), fontSize = 13.sp, color = PulseColors.textSecondary)
        }
        val worst = AccuracyReportText.worstFirst(summaries)
        if (worst.isNotEmpty()) {
            Text(tr("Where the ring falls short"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            worst.forEach { s ->
                Text("• ${s.metric.label}: ${AccuracyReportText.signed(s.metric, s.bias)} (${AccuracyReportText.verdictLabel(s.verdict).lowercase()})",
                    fontSize = 12.sp, color = PulseColors.textSecondary)
            }
        } else if (summaries.isNotEmpty() && summaries.none { it.verdict == Verdict.NOT_ENOUGH_DATA }) {
            Text(tr("Everything compared agrees within its band."), fontSize = 13.sp, color = PulseColors.success)
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
            Text(tr("Details"), fontSize = 12.sp, color = PulseColors.accent, modifier = Modifier.clickable(onClick = onToggle))
            return@AccuracyCard
        }
        if (m.kind != ComparisonMetric.Kind.STAGES && summary.validDays > 0) {
            val (lo, hi) = summary.limits
            Text(
                trf("Average difference {0}; 95 % of differences between {1} and {2}", AccuracyReportText.signed(m, summary.bias), AccuracyReportText.signed(m, lo), AccuracyReportText.signed(m, hi)) +
                    if (m.kind == ComparisonMetric.Kind.PAIRED) trf(" ({0} matched readings).", summary.pairs) else ".",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
        if (m.kind == ComparisonMetric.Kind.STAGES) StageConfusion(days, check.referenceLabel)
        days.forEach { DayRow(m, it, check.referenceLabel) }
        if (m.kind != ComparisonMetric.Kind.STAGES) {
            Text(trf("Agreement band: ±{0}", AccuracyReportText.magnitude(m, summary.band)), fontSize = 12.sp, color = PulseColors.textPrimary)
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
            trf("{0} · ring {1} · {2} {3}", date, AccuracyReportText.value(m, d.ringValue), ref, AccuracyReportText.value(m, d.refValue)) +
                (diff?.let { " (${AccuracyReportText.signed(m, it)})" } ?: "")
        }
        ComparisonMetric.Kind.PAIRED ->
            trf("{0} · {1} readings · {2} on average", date, d.pairs, if (d.pairs > 0) AccuracyReportText.signed(m, d.sumDiff / d.pairs) else "—")
        ComparisonMetric.Kind.STAGES ->
            "$date · ${if (d.pairs > 0) trf("{0} % of {1} min on the same stage", (d.inBand * 100.0 / d.pairs).roundToInt(), d.pairs) else tr("no overlap")}"
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
    Text(trf("When the ring says… what {0} says (minutes)", ref), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
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

/**
 * Under a metric with a consistent bias: apply the fitted correction, adjust it, or switch it off
 * (plan, "Corrections"). Only for the ring the check was run on, and only while it is paired.
 */
@Composable
internal fun AccuracyCorrectionControl(check: AccuracyCheckEntity, summary: AccuracyComparison.Summary) {
    val metric = summary.metric
    val fitted = summary.suggestedCorrection ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val all by remember(db) { db.accuracyDao().allCorrectionsFlow() }.collectAsState(initial = emptyList())
    val ring by remember(db) { db.deviceDao().currentFlow() }.collectAsState(initial = null)
    val existing = all.firstOrNull { it.deviceKey == check.deviceKey && it.metric == metric.name }
    var editing by remember { mutableStateOf(false) }
    val paired = ring?.let { it.id != RingIdentity.DEMO_DEVICE_ID && RingIdentity.keyOf(it) == check.deviceKey } == true

    if (!paired) {
        Text(trf("This check was run on {0}. Pair that ring to apply a correction from it.", check.deviceLabel), fontSize = 12.sp, color = PulseColors.textMuted)
        return
    }
    when {
        existing != null && existing.enabled -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trf("Correction on: {0}", AccuracyCorrections.formatValue(metric, existing.value)), fontSize = 12.sp,
                color = PulseColors.success, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }) { Text(tr("Change")) }
            TextButton(onClick = { scope.launch { AccuracyCorrections.setEnabled(db, existing, false) } }) { Text(tr("Turn off")) }
        }
        existing != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trf("Correction off ({0})", AccuracyCorrections.formatValue(metric, existing.value)), fontSize = 12.sp,
                color = PulseColors.textMuted, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { AccuracyCorrections.setEnabled(db, existing, true) } }) { Text(tr("Turn on")) }
        }
        else -> Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(trf("Apply correction ({0})", AccuracyCorrections.formatValue(metric, fitted)))
        }
    }
    if (editing) {
        CorrectionDialog(metric, fitted, existing?.value ?: fitted, check.referenceLabel, onDismiss = { editing = false }) { value ->
            editing = false
            scope.launch { AccuracyCorrections.accept(db, check, summary, value) }
        }
    }
}

@Composable
private fun CorrectionDialog(
    metric: ComparisonMetric,
    fitted: Double,
    start: Double,
    ref: String,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    val range = correctionRange(metric, fitted)
    var value by remember { mutableStateOf(start.coerceIn(range.start.toDouble(), range.endInclusive.toDouble())) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(trf("Correct {0}", metric.label.lowercase())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    trf("Fitted from this check so your ring matches {0}: {1}. ", ref, AccuracyCorrections.formatValue(metric, fitted)) +
                        tr("It changes what PulseLoop shows and what it works out from it (zones, resting baseline, calorie estimate). " +
                        "Your ring's stored readings and what goes to Health Connect stay as measured."),
                    fontSize = 12.sp,
                )
                Text(AccuracyCorrections.formatValue(metric, value), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Slider(value = value.toFloat(), onValueChange = { value = it.toDouble() }, valueRange = range)
                TextButton(onClick = { value = fitted }) { Text(tr("Use the fitted value")) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(roundCorrection(metric, value)) }) { Text(tr("Apply")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

private fun correctionRange(m: ComparisonMetric, fitted: Double): ClosedFloatingPointRange<Float> = when (m.correction) {
    com.pulseloop.health.accuracy.CorrectionKind.SCALE ->
        (minOf(fitted, 1.0) * 0.8).coerceAtLeast(0.5).toFloat()..(maxOf(fitted, 1.0) * 1.2).coerceAtMost(2.5).toFloat()
    else -> {
        val span = maxOf(kotlin.math.abs(fitted) * 2, when (m) {
            ComparisonMetric.SLEEP_ASLEEP -> 60.0
            ComparisonMetric.SPO2 -> 4.0
            else -> 10.0
        })
        (-span).toFloat()..span.toFloat()
    }
}

private fun roundCorrection(m: ComparisonMetric, v: Double): Double = when (m.correction) {
    com.pulseloop.health.accuracy.CorrectionKind.SCALE -> (v * 100).roundToInt() / 100.0
    else -> if (m == ComparisonMetric.SLEEP_ASLEEP) v.roundToInt().toDouble() else (v * 2).roundToInt() / 2.0
}

/** The paired ring's corrections, on the accuracy-check screen: switch, stale flag, remove. */
@Composable
internal fun AccuracyCorrectionsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val all by remember(db) { db.accuracyDao().allCorrectionsFlow() }.collectAsState(initial = emptyList())
    val ring by remember(db) { db.deviceDao().currentFlow() }.collectAsState(initial = null)
    val r = ring?.takeIf { it.id != RingIdentity.DEMO_DEVICE_ID } ?: return
    val key = RingIdentity.keyOf(r)
    val mine = all.filter { it.deviceKey == key }.sortedBy { ComparisonMetric.byName(it.metric)?.ordinal ?: 99 }
    AccuracyCard {
        Text(trf("Corrections for {0}", RingIdentity.labelOf(r)), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        if (mine.isEmpty()) {
            Text(
                if (all.isNotEmpty()) tr("No accuracy check for this ring yet. Corrections from another ring don't carry over.")
                else tr("None. A finished check offers one wherever the ring is consistently off."),
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
            return@AccuracyCard
        }
        mine.forEach { c ->
            val m = ComparisonMetric.byName(c.metric) ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${m.label}: ${AccuracyCorrections.formatValue(m, c.value)}", fontSize = 13.sp, color = PulseColors.textPrimary)
                    if (AccuracyCorrections.isStale(c, r)) {
                        Text(trf("Fitted {0}{1}: worth a fresh check.", AccuracyUiText.date(c.createdAt), if (c.firmware != null && c.firmware != r.firmwareVersion) " on other firmware" else ""),
                            fontSize = 11.sp, color = PulseColors.warning)
                    }
                }
                Switch(checked = c.enabled, onCheckedChange = { on -> scope.launch { AccuracyCorrections.setEnabled(db, c, on) } })
                TextButton(onClick = { scope.launch { AccuracyCorrections.remove(db, c) } }) { Text(tr("Remove")) }
            }
        }
        Text(tr("Applied to what PulseLoop shows, never to stored readings or Health Connect."), fontSize = 11.sp, color = PulseColors.textMuted)
    }
}
