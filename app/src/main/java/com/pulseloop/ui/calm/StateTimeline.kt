package com.pulseloop.ui.calm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pulseloop.ui.i18n.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The run-and-step layout behind [StateTimeline], kept pure so it can be tested: consecutive days
 * at the same level join into one run, a day with no value breaks a run, and two runs that touch
 * are joined by a step.
 */
object StateTimelineModel {
    /** Days [start]..[end] (inclusive indices) all at [level]. */
    data class Run(val level: Int, val start: Int, val end: Int)

    fun runs(levels: List<Int?>): List<Run> {
        val out = mutableListOf<Run>()
        var i = 0
        while (i < levels.size) {
            val l = levels[i]
            if (l == null) { i++; continue }
            var j = i
            while (j + 1 < levels.size && levels[j + 1] == l) j++
            out += Run(l, i, j)
            i = j + 1
        }
        return out
    }

    /** Pairs of runs that touch (no empty day between), where the line steps up or down. */
    fun steps(runs: List<Run>): List<Pair<Run, Run>> =
        runs.zipWithNext().filter { (a, b) -> b.start == a.end + 1 && a.level != b.level }

    /** The last day with a value: where the selection starts. */
    fun lastIndex(levels: List<Int?>): Int = levels.indexOfLast { it != null }
}

/**
 * A state over time, one lane per level, like a symptom or readiness history: each day sits on
 * its level, runs of the same level join into a bar, a change of level is drawn as a step, and a
 * tapped day shows its date and level above the chart.
 *
 * [days] and [levels] line up, oldest first; a null level is a day with nothing to show.
 * [levelNames] and [levelColors] are indexed by level, lowest first (drawn at the bottom).
 */
@Composable
fun StateTimeline(
    days: List<LocalDate>,
    levels: List<Int?>,
    levelNames: List<String>,
    levelColors: List<Color>,
    selectedLabel: (LocalDate, String) -> String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var selected by remember(levels) { mutableIntStateOf(StateTimelineModel.lastIndex(levels)) }
    val runs = remember(levels) { StateTimelineModel.runs(levels) }
    val steps = remember(runs) { StateTimelineModel.steps(runs) }
    val tickFmt = remember { DateTimeFormatter.ofPattern("d MMM") }
    val labelStyle = CalmType.label.copy(color = CalmColors.textSecondary)
    val tooltipStyle = CalmType.label.copy(color = CalmColors.page)
    val tickStyle = CalmType.label.copy(color = CalmColors.textMuted)
    val summary = levelNames.indices.joinToString(", ") { l -> "${levels.count { it == l }} ${levelNames[l]}" }
    val description = if (selected in days.indices && levels.getOrNull(selected) != null)
        summary + ". " + selectedLabel(days[selected], levelNames[levels[selected]!!]) else summary
    // The label column fits the longest level name at the user's font size, so a longer
    // translation or a larger font never runs off the edge.
    val density = LocalDensity.current
    val labelColPx = remember(levelNames, density) {
        levelNames.maxOfOrNull { measurer.measure(it, labelStyle).size.width }?.toFloat()?.plus(with(density) { 12.dp.toPx() }) ?: 0f
    }
    val withValue = remember(levels) { levels.indices.filter { levels[it] != null } }
    /** The day with a value nearest to [x] across the chart. */
    fun dayAt(x: Float, width: Float): Int? {
        val step = (width - labelColPx) / days.size.coerceAtLeast(1)
        val i = (x / step).toInt().coerceIn(0, (days.size - 1).coerceAtLeast(0))
        return withValue.minByOrNull { kotlin.math.abs(it - i) }
    }
    fun move(by: Int): Boolean {
        val pos = withValue.indexOf(selected)
        val next = withValue.getOrNull(pos + by) ?: return false
        selected = next
        return true
    }

    Box(
        modifier
            .fillMaxWidth()
            .height((48 + 56 * levelNames.size + 28).dp)
            // A day is a few dp wide, so TalkBack steps through them with actions instead.
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
                customActions = listOf(
                    CustomAccessibilityAction(tr("Previous day")) { move(-1) },
                    CustomAccessibilityAction(tr("Next day")) { move(1) },
                )
            },
    ) {
        Canvas(
            Modifier.fillMaxWidth().height((48 + 56 * levelNames.size + 28).dp)
                .pointerInput(levels, labelColPx) {
                    // Snap to the nearest day that has a value, so a tap never selects nothing.
                    detectTapGestures { pos -> dayAt(pos.x, size.width.toFloat())?.let { selected = it } }
                }
                .pointerInput(levels, labelColPx) {
                    // Days are narrow: sliding a finger along the chart is the reliable way to pick one.
                    detectHorizontalDragGestures { change, _ ->
                        dayAt(change.position.x, size.width.toFloat())?.let { selected = it }
                    }
                },
        ) {
            if (days.isEmpty()) return@Canvas
            val top = 48.dp.toPx()
            val laneH = 56.dp.toPx()
            val chartW = size.width - labelColPx
            val step = chartW / days.size
            val bar = 10.dp.toPx()
            fun x(i: Int) = step * i + step / 2
            fun y(level: Int) = top + laneH * (levelNames.size - 1 - level) + laneH / 2

            // Lanes: a hairline and the level's name at the right edge.
            levelNames.forEachIndexed { l, name ->
                val ly = y(l) + laneH / 2
                drawLine(CalmColors.border, Offset(0f, ly), Offset(chartW, ly), strokeWidth = 1.dp.toPx())
                val t = measurer.measure(name, labelStyle)
                drawText(t, topLeft = Offset(chartW + 8.dp.toPx(), y(l) - t.size.height / 2f))
            }
            // Steps first, so the bars sit on top of them.
            steps.forEach { (a, b) ->
                val sx = (x(a.end) + x(b.start)) / 2
                drawLine(CalmColors.cardRaised, Offset(sx, y(a.level)), Offset(sx, y(b.level)), strokeWidth = bar * 0.6f, cap = StrokeCap.Round)
            }
            runs.forEach { r ->
                val left = x(r.start) - step / 2 + 1.dp.toPx()
                val right = x(r.end) + step / 2 - 1.dp.toPx()
                drawRoundRect(
                    color = levelColors[r.level],
                    topLeft = Offset(left, y(r.level) - bar / 2),
                    size = Size((right - left).coerceAtLeast(bar), bar),
                    cornerRadius = CornerRadius(bar / 2, bar / 2),
                )
            }
            // Weekly date ticks, newest at the right.
            for (i in days.indices.reversed() step 7) {
                val t = measurer.measure(days[i].format(tickFmt), tickStyle)
                val tx = (x(i) - t.size.width / 2f).coerceIn(0f, chartW - t.size.width)
                drawText(t, topLeft = Offset(tx, top + laneH * levelNames.size + 8.dp.toPx()))
            }
            // The selected day: a guide line, a dot on its level and the date above.
            val sl = levels.getOrNull(selected)
            if (sl != null) {
                val sx = x(selected)
                drawLine(
                    CalmColors.textSecondary, Offset(sx, top - 6.dp.toPx()), Offset(sx, top + laneH * levelNames.size),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
                drawCircle(CalmColors.page, radius = bar, center = Offset(sx, y(sl)))
                drawCircle(levelColors[sl], radius = bar * 0.7f, center = Offset(sx, y(sl)))
                val t = measurer.measure(selectedLabel(days[selected], levelNames[sl]), tooltipStyle)
                val padH = 12.dp.toPx(); val padV = 6.dp.toPx()
                val w = t.size.width + padH * 2; val h = t.size.height + padV * 2
                val left = (sx - w / 2).coerceIn(0f, size.width - w)
                drawRoundRect(CalmColors.text, Offset(left, 0f), Size(w, h), CornerRadius(h / 2, h / 2))
                drawText(t, topLeft = Offset(left + padH, padV))
            }
        }
    }
}
