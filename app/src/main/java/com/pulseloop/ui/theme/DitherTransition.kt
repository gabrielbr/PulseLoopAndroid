package com.pulseloop.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

/**
 * The app's screen transition: the new screen dissolves in through the same 4×4 Bayer dither the
 * rest of the UI is drawn with, along a gradient that sweeps from the top down, and the sweep's
 * front edge glitters in the accent colour. Fairy mode swaps the dots for a smooth fade, as the
 * rest of the dither does.
 *
 * Both screens are drawn: the incoming one keeps the cells the sweep has passed, the outgoing one
 * keeps the rest, from the same mask. So whichever of the two the navigator puts on top (it puts
 * the outgoing one on top for a back press), the picture is the same.
 */
object DitherTransition {
    /** Long enough to read as a wipe, short enough not to hold anyone up. */
    const val DURATION_MS = 420

    /** How much of the screen's height the dithered gradient at the front spans. */
    private const val GRADIENT = 0.35f

    /** Horizontal bands the gradient is cut into; one per dither step reads as smooth. */
    private const val BANDS = 32

    /**
     * NavHost's own transitions only hold both screens on screen for [DURATION_MS]: the alpha
     * barely moves, and the picture is the mask drawn by [DitherTransitionBox].
     */
    val enter: EnterTransition = fadeIn(tween(DURATION_MS, easing = LinearEasing), initialAlpha = 0.99f)
    val exit: ExitTransition = fadeOut(tween(DURATION_MS, easing = LinearEasing), targetAlpha = 0.99f)

    /** Share of the cells revealed at height [t] (0 top, 1 bottom) when the sweep is at [p]. */
    internal fun coverage(t: Float, p: Float): Float {
        val front = p * (1f + GRADIENT)
        return ((front - t) / GRADIENT).coerceIn(0f, 1f)
    }

    /** The accent glitter: strongest in the middle of the gradient, none outside it. */
    internal fun sparkle(t: Float, p: Float): Float {
        val mid = p * (1f + GRADIENT) - GRADIENT / 2f
        return (1f - abs(t - mid) / (GRADIENT / 2f)).coerceIn(0f, 1f) * 0.55f
    }

    /**
     * Draws the mask over content already drawn in an offscreen layer: [entering] keeps the
     * revealed cells, the outgoing screen removes them. Then the accent edge on top.
     */
    internal fun DrawScope.drawWipe(p: Float, entering: Boolean, accent: Color) {
        val keepMode = if (entering) BlendMode.DstIn else BlendMode.DstOut
        if (FairyMode.on) {
            val stops = (0..BANDS).map { i -> val t = i / BANDS.toFloat(); t to Color.Black.copy(alpha = coverage(t, p)) }
            drawRect(Brush.verticalGradient(*stops.toTypedArray()), blendMode = keepMode)
            val glow = (0..BANDS).map { i -> val t = i / BANDS.toFloat(); t to accent.copy(alpha = sparkle(t, p) * 0.6f) }
            drawRect(Brush.verticalGradient(*glow.toTypedArray()))
            return
        }
        val bandH = size.height / BANDS
        for (i in 0 until BANDS) {
            val t = (i + 0.5f) / BANDS
            val lit = Dither.steps(coverage(t, p))
            val top = i * bandH
            val brush: Brush? = when {
                // Entering keeps everything here; leaving keeps nothing.
                lit >= Dither.STEPS -> if (entering) null else SolidColor(Color.Black)
                // Entering keeps nothing here; leaving keeps everything.
                lit == 0 -> if (entering) SolidColor(Color.Transparent) else null
                else -> with(Dither) { brush(Color.Black, lit, cellPx()) }
            }
            if (brush != null) {
                drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, bandH + 1f), blendMode = keepMode)
            }
            val glitter = Dither.steps(sparkle(t, p))
            if (glitter > 0) {
                drawRect(
                    with(Dither) { brush(accent, glitter, cellPx()) },
                    topLeft = Offset(0f, top), size = Size(size.width, bandH + 1f),
                )
            }
        }
    }
}

/**
 * Wraps one navigation destination so it takes part in the dither wipe. Use it inside the
 * destination's content, where the navigator's [AnimatedVisibilityScope] is the receiver.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.DitherTransitionBox(content: @Composable () -> Unit) {
    val shown by transition.animateFloat(
        transitionSpec = { tween(DitherTransition.DURATION_MS, easing = LinearEasing) },
        label = "ditherWipe",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val entering = transition.targetState == EnterExitState.Visible
    // Eased once here so both screens read the same position off the same curve.
    val p = FastOutSlowInEasing.transform(if (entering) shown else 1f - shown)
    val accent = PulseColors.accent
    val settled = entering && shown >= 1f
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (settled) Modifier
                else Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        with(DitherTransition) { drawWipe(p, entering, accent) }
                    },
            ),
    ) { content() }
}
