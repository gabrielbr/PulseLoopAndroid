package com.pulseloop.ui.components

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.theme.PulseColors
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pulseloop.data.entity.ActivityGpsPointEntity

/**
 * Ported from WorkoutMapView.swift.
 * Route map for a workout — a Canvas-drawn GPS polyline with start/end markers.
 * Uses pure Compose Canvas (no external Google Maps dependency).
 * Falls back to a placeholder when there are fewer than 2 points.
 */
@Composable
fun WorkoutMapView(
    points: List<ActivityGpsPointEntity>,
    modifier: Modifier = Modifier,
    height: Int = 200,
) {
    if (points.size < 2) {
        // Placeholder
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PulseColors.card),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(com.pulseloop.ui.i18n.tr("No route yet"), style = MaterialTheme.typography.labelMedium, color = com.pulseloop.ui.theme.PulseColors.textPrimary)
                Text(com.pulseloop.ui.i18n.tr("Move outdoors to start tracking your route."), style = MaterialTheme.typography.labelSmall, color = com.pulseloop.ui.theme.PulseColors.textSecondary)
            }
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PulseColors.card)
                // TalkBack can't see a drawn route: say what it is.
                .semantics { contentDescription = trf("Route map: {0} GPS points", points.size) },
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val w = size.width; val h = size.height
                val lats = points.map { it.latitude }
                val lons = points.map { it.longitude }
                val minLat = lats.min(); val maxLat = lats.max()
                val minLon = lons.min(); val maxLon = lons.max()
                // One scale for both axes, with longitude shrunk by cos(latitude), so the route
                // keeps its real shape instead of being stretched to fill the box.
                val lonFactor = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
                val latR = if (maxLat == minLat) 0.001 else maxLat - minLat
                val lonR = (if (maxLon == minLon) 0.001 else maxLon - minLon) * lonFactor
                val inset = 16f
                val scale = minOf((w - 2 * inset) / lonR, (h - 2 * inset) / latR)
                val offX = (w - lonR * scale) / 2
                val offY = (h - latR * scale) / 2

                fun toScreen(lat: Double, lon: Double): Offset {
                    val x = (offX + (lon - minLon) * lonFactor * scale).toFloat()
                    val y = (h - offY - (lat - minLat) * scale).toFloat()
                    return androidx.compose.ui.geometry.Offset(x, y)
                }

                // Draw polyline
                val path = Path()
                points.forEachIndexed { i, pt ->
                    val p = toScreen(pt.latitude, pt.longitude)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path = path,
                    color = PulseColors.distance,
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )

                // Start marker
                val start = toScreen(points.first().latitude, points.first().longitude)
                drawCircle(PulseColors.steps, 6f, start)
                drawCircle(PulseColors.textPrimary, 5f, start)

                // End marker. Not mint: the map isn't a tap target.
                val end = toScreen(points.last().latitude, points.last().longitude)
                drawCircle(PulseColors.textPrimary, 8f, end)
                drawCircle(PulseColors.textPrimary, 12f, end, style = Stroke(width = 1.5f))

                // Point count overlay
            }

            // Info overlay
            val accuracy = points.last().horizontalAccuracy
            val infoText = if (accuracy != null) trf("±{0}m · {1} pts", accuracy.toInt(), points.size) else trf("{0} pts", points.size)
            Text(
                infoText,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = PulseColors.textSecondary,
            )
        }
    }
}
