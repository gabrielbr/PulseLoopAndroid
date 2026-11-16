package com.pulseloop.ui.calm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * A small trend line for a row: the shape of the recent readings, nothing else. No axes and no
 * labels, because the value beside it is the number; TalkBack skips it for the same reason.
 * Draws nothing below two readings.
 */
@Composable
fun CalmSparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier.size(88.dp, 32.dp)) {
    // The newest readings only: a day of five-minute samples is more line than 88dp can show.
    val points = remember(values) { values.takeLast(MAX_POINTS) }
    if (points.size < 2) return
    Canvas(modifier.clearAndSetSemantics { }) {
        val inset = 4.dp.toPx()
        val w = size.width - inset * 2
        val h = size.height - inset * 2
        val min = points.min()
        val span = (points.max() - min).takeIf { it > 0 } ?: 1.0
        fun at(i: Int) = Offset(
            inset + w * i / (points.size - 1),
            inset + h - (h * ((points[i] - min) / span)).toFloat(),
        )
        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, color.copy(alpha = 0.8f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // The newest reading, so the eye lands where the value beside it comes from.
        drawCircle(color, radius = 3.dp.toPx(), center = at(points.size - 1))
    }
}

private const val MAX_POINTS = 60
