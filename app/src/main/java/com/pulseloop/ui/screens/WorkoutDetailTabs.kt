package com.pulseloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.service.HeartRateZones
import com.pulseloop.service.WorkoutHeart
import com.pulseloop.ui.calm.Look
import com.pulseloop.ui.calm.lookCard
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import kotlin.math.roundToInt

/**
 * The finished workout's two tabs: Summary (what it cost and how hard it was) and Heart rate (how
 * the effort went). Styled through [Look], so it follows whichever look is on.
 */

/** Everything both tabs read, worked out once per load. Heart-rate values already carry the
 *  accuracy corrections (display only). */
internal data class WorkoutFigures(
    val totalKcal: Double?,
    val activeKcal: Double?,
    val durationSec: Int?,
    val heart: WorkoutHeart.Summary?,
    val trainingLoad: Double?,
    /** The readings as drawn: minutes from the start, bpm. */
    val curve: List<Pair<Double, Double>>,
    val workoutMinutes: Double,
)

@Composable
internal fun WorkoutTabs(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(tr("Summary"), tr("Heart rate"))
    Row(Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = if (on) Look.text else Look.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(if (on) Look.text else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color = Look.border)
}

@Composable
internal fun WorkoutSummaryTab(f: WorkoutFigures) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The one big number: what the workout burned. Active and resting sit under it, like a watch.
        Column(Modifier.fillMaxWidth().lookCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    f.totalKcal?.roundToInt()?.toString() ?: "—",
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
                    color = Look.text,
                )
                Text(" kcal", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = Look.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(tr("Total calories"), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary)
            val active = f.activeKcal
            val total = f.totalKcal
            if (active != null && total != null) {
                Text(
                    trf("{0} active · {1} resting", active.roundToInt().toString(), (total - active).roundToInt().toString()),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.text,
                )
            }
        }

        Column(Modifier.fillMaxWidth().lookCard()) {
            FactRow(tr("Duration"), f.durationSec?.let { com.pulseloop.ui.components.ActivityMeta.duration(it) } ?: "—")
            HorizontalDivider(color = Look.border)
            FactRow(tr("Average heart rate"), f.heart?.let { trf("{0} bpm", it.average.roundToInt().toString()) } ?: "—")
            HorizontalDivider(color = Look.border)
            FactRow(
                tr("Training load"),
                f.trainingLoad?.roundToInt()?.toString() ?: "—",
                note = if (f.trainingLoad != null) tr("Estimated from your heart rate") else null,
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, note: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = Look.text)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted)
        }
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Look.text, maxLines = 1)
    }
}

@Composable
internal fun WorkoutHeartTab(f: WorkoutFigures, age: Int?) {
    val heart = f.heart
    if (heart == null || f.curve.size < 2) {
        Column(Modifier.fillMaxWidth().lookCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("No heart rate for this workout"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Look.text)
            Text(
                tr("The ring sent no readings while it was recorded. Keep the ring snug and connected during the next one."),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().lookCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HeartFigure(tr("Average"), heart.average)
                HeartFigure(tr("Maximum"), heart.max)
            }
            HeartChart(f.curve, heart.average, f.workoutMinutes)
            Row(Modifier.fillMaxWidth()) {
                Text("0", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = Look.textMuted, modifier = Modifier.weight(1f))
                Text(trf("{0} min", f.workoutMinutes.roundToInt().toString()), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = Look.textMuted)
            }
            // Honest about gaps: a figure from half the session is still a figure, but say so.
            if (f.workoutMinutes > 0 && heart.coveredMinutes < f.workoutMinutes * 0.6) {
                Text(
                    trf("Readings cover {0} of {1} min.", heart.coveredMinutes.roundToInt().toString(), f.workoutMinutes.roundToInt().toString()),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted,
                )
            }
        }

        Text(tr("Time in each zone"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = Look.text, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().lookCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val maxHr = HeartRateZones.maxHR(age ?: WorkoutHeart.DEFAULT_AGE)
            val longest = heart.zoneMinutes.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
            // Hardest first, as a watch lists them.
            HeartRateZones.Zone.entries.reversed().forEach { zone ->
                ZoneRow(zone, heart.zoneMinutes[zone] ?: 0.0, longest, maxHr)
            }
            Text(
                trf("Zones are shares of an estimated maximum of {0} bpm, from your age.", maxHr.toString()),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted,
            )
        }
    }
}

@Composable
private fun HeartFigure(label: String, bpm: Double) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bpm.roundToInt().toString(), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = Look.text)
            Text(" bpm", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary)
    }
}

internal fun zoneColor(zone: HeartRateZones.Zone): Color = when (zone) {
    HeartRateZones.Zone.REST -> PulseColors.textMuted
    HeartRateZones.Zone.FAT_BURN -> PulseColors.zoneMint
    HeartRateZones.Zone.CARDIO -> PulseColors.zoneAmber
    HeartRateZones.Zone.PEAK -> PulseColors.zoneOrange
    HeartRateZones.Zone.MAX -> PulseColors.zoneRed
}

@Composable
private fun ZoneRow(zone: HeartRateZones.Zone, minutes: Double, longest: Double, maxHr: Int) {
    val lo = zone.range.first * maxHr / 100
    val range = if (zone == HeartRateZones.Zone.REST) trf("under {0}", (60 * maxHr / 100).toString())
        else if (zone == HeartRateZones.Zone.MAX) trf("{0}+", lo.toString())
        else "$lo–${(zone.range.last + 1) * maxHr / 100 - 1}"
    val mins = minutes.roundToInt()
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = trf("{0}, {1} bpm: {2} min", zone.label, range, mins.toString())
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(zone.label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = Look.text)
            Text("  $range", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trf("{0} min", mins.toString()), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Look.text)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Look.border)) {
            if (minutes > 0) {
                Box(
                    Modifier.fillMaxWidth((minutes / longest).toFloat().coerceIn(0.02f, 1f)).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(zoneColor(zone)),
                )
            }
        }
    }
}

/** The session's heart rate over time, with its average as a dashed line. A gap over five minutes
 *  breaks the line rather than bridging it with a guess. */
@Composable
private fun HeartChart(curve: List<Pair<Double, Double>>, average: Double, minutes: Double) {
    val line = PulseColors.heartRate
    val grid = Look.border
    val avgColor = Look.textSecondary
    val lo = (curve.minOf { it.second } - 10).coerceAtLeast(30.0)
    val hi = curve.maxOf { it.second } + 10
    val span = minutes.coerceAtLeast(curve.last().first).coerceAtLeast(1.0)
    val chartHeight = 160.dp
    // The dashed line's own label, just above it at the right edge.
    val avgFrac = ((average - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth()) {
    Canvas(
        Modifier.fillMaxWidth().height(chartHeight).semantics {
            contentDescription = trf("Heart rate over the workout, average {0} bpm", average.roundToInt().toString())
        },
    ) {
        fun x(m: Double) = (m / span * size.width).toFloat()
        fun y(b: Double) = (size.height - (b - lo) / (hi - lo) * size.height).toFloat()
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val path = Path()
        curve.forEachIndexed { i, (m, b) ->
            val gap = i > 0 && m - curve[i - 1].first > 5.0
            if (i == 0 || gap) path.moveTo(x(m), y(b)) else path.lineTo(x(m), y(b))
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val ay = y(average)
        drawLine(avgColor, Offset(0f, ay), Offset(size.width, ay), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
    Text(
        trf("avg {0}", average.roundToInt().toString()),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        color = Look.textSecondary,
        modifier = Modifier.align(Alignment.TopEnd)
            .offset(y = (chartHeight * (1f - avgFrac) - 18.dp).coerceAtLeast(0.dp))
            .background(Look.card).padding(horizontal = 4.dp)
            .clearAndSetSemantics {},
    )
    }
}
