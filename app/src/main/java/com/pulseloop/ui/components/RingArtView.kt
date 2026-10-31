package com.pulseloop.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.pulseloop.ui.theme.ditherBrush
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A smart ring drawn in the app's own dither instead of a product photo: a band turning slowly
 * about a tilted axis, its near side dense and its far side sparse, so the dots alone give it
 * depth. Every ring looks the same here on purpose; the model's name says which one it is, and
 * [tint] (the model's colour) is the only thing that varies.
 *
 * [imageRes] is accepted and ignored, so callers that still pass a model's photo keep compiling.
 */
@Composable
fun RingArtView(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Float = 180f,
    @Suppress("UNUSED_PARAMETER") @DrawableRes imageRes: Int? = null,
) {
    // A black or gunmetal ring's colour would vanish on the dark page; those turn in the accent.
    val visible = if (tint.luminance() < 0.25f) com.pulseloop.ui.theme.PulseColors.accent else tint
    val color by animateColorAsState(visible, tween(250), label = "ringArtTint")
    // Looping decoration holds still under "Remove animations" (A11y.kt), like the header pill.
    val still = rememberReduceMotion()
    val spin = if (still) 0.6f else rememberInfiniteTransition(label = "ringSpin").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "ringAngle",
    ).value
    Canvas(modifier.size(size.dp)) {
        val radius = this.size.minDimension * 0.34f
        val band = this.size.minDimension * 0.075f
        val center = Offset(this.size.width / 2, this.size.height / 2)
        // The ring's own circle, turned about the vertical axis by [spin] and tipped towards
        // the viewer, then flattened onto the screen. Depth (z) sets the dot density.
        val tilt = 0.45f
        val points = (0 until SEGMENTS).map { i ->
            val a = 2 * PI * i / SEGMENTS
            val x0 = cos(a).toFloat()
            val y0 = sin(a).toFloat()
            val x1 = x0 * cos(spin)
            val z1 = x0 * sin(spin)
            val y2 = y0 * cos(tilt) - z1 * sin(tilt)
            val z2 = y0 * sin(tilt) + z1 * cos(tilt)
            Triple(Offset(center.x + x1 * radius, center.y + y2 * radius), z2, a)
        }
        // Far side first, so the near side is drawn over it.
        points.sortedBy { it.second }.forEach { (pos, z, _) ->
            val near = (z + 1f) / 2f
            val brush = ditherBrush(color, 0.25f + 0.75f * near) ?: return@forEach
            drawCircle(brush, radius = band * (0.75f + 0.35f * near), center = pos)
        }
        // A highlight that stays put while the ring turns under it: a dense glint on the near rim.
        points.filter { it.second > 0.55f }.forEach { (pos, _, _) ->
            val brush = ditherBrush(Color.White, 0.3f) ?: return@forEach
            drawCircle(brush, radius = band * 0.35f, center = pos)
        }
    }
}

/** Dots around the ring; enough to read as a solid band at the largest size. */
private const val SEGMENTS = 96
