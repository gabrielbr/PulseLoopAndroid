package com.pulseloop.ui.components

import com.pulseloop.ui.i18n.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.theme.FairyMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tatá's transformation (see [FairyMode]): a rose glow, a wand's trail of stars sweeping in, a burst
 * of hearts and stars, and the app turning pink underneath them. Plays once, over everything, and
 * swallows touches while it does.
 */
@Composable
fun FairyTransformOverlay() {
    if (!FairyMode.celebrating) return
    val reduceMotion = rememberReduceMotion()
    val progress = remember { Animatable(0f) }
    val particles = remember { sparkles(if (reduceMotion) 18 else 90) }

    LaunchedEffect(Unit) {
        val total = if (reduceMotion) 1400 else 4200
        progress.animateTo(0.45f, tween((total * 0.45f).toInt(), easing = LinearEasing))
        FairyMode.transform()
        progress.animateTo(1f, tween((total * 0.55f).toInt(), easing = LinearEasing))
        FairyMode.celebrationDone()
    }

    val p = progress.value
    // Fade in over the first 12 %, hold, fade out over the last 15 %.
    val veil = when {
        p < 0.12f -> p / 0.12f
        p > 0.85f -> (1f - p) / 0.15f
        else -> 1f
    }.coerceIn(0f, 1f)

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = maxOf(size.width, size.height)
            // The glow: deep plum at the edges, rose at the heart, brightest at the moment of change.
            val flash = (1f - kotlin.math.abs(p - 0.45f) / 0.2f).coerceIn(0f, 1f)
            drawRect(
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFFD6EC).copy(alpha = 0.55f * veil + 0.35f * flash),
                        Color(0xFFFF5FA8).copy(alpha = 0.45f * veil),
                        Color(0xFF2A0A1E).copy(alpha = 0.85f * veil),
                    ),
                    center = center, radius = radius * (0.35f + 0.5f * p),
                ),
            )
            if (!reduceMotion) wandTrail(p, veil)
            particles.forEach { drawParticle(it, p, center, radius) }
        }
        val textIn = ((p - 0.42f) / 0.18f).coerceIn(0f, 1f)
        Column(
            Modifier.alpha(textIn * veil).scale(0.7f + 0.3f * textIn),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                tr("Tatá, te amo!"),
                fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
            )
            Text("💗", fontSize = 28.sp, textAlign = TextAlign.Center)
        }
    }
}

private enum class SparkleShape { STAR, HEART, DOT }

private class Sparkle(
    val shape: SparkleShape,
    val angle: Float,
    val speed: Float,
    val size: Float,
    val spin: Float,
    val delay: Float,
    val twinkle: Float,
    val color: Color,
)

private fun sparkles(n: Int): List<Sparkle> {
    val r = Random(0x7A7A)
    return List(n) {
        Sparkle(
            shape = when (r.nextInt(10)) { in 0..3 -> SparkleShape.HEART; in 4..8 -> SparkleShape.STAR; else -> SparkleShape.DOT },
            angle = r.nextFloat() * 2f * PI.toFloat(),
            speed = 0.25f + r.nextFloat() * 0.75f,
            size = 10f + r.nextFloat() * 26f,
            spin = (r.nextFloat() - 0.5f) * 540f,
            delay = 0.30f + r.nextFloat() * 0.25f,
            twinkle = r.nextFloat() * 6f,
            color = FairyMode.Palette.sparkles[r.nextInt(FairyMode.Palette.sparkles.size)],
        )
    }
}

/** A sparkle bursts out from the centre once its [Sparkle.delay] passes, drifting upward as it slows and fades. */
private fun DrawScope.drawParticle(s: Sparkle, p: Float, center: Offset, radius: Float) {
    val t = ((p - s.delay) / (1f - s.delay)).coerceIn(0f, 1f)
    if (t <= 0f) return
    val ease = 1f - (1f - t) * (1f - t)
    val dist = ease * s.speed * radius * 0.55f
    val pos = Offset(center.x + cos(s.angle) * dist, center.y + sin(s.angle) * dist - t * t * radius * 0.12f)
    val alpha = ((1f - t) * (0.65f + 0.35f * sin(t * 20f + s.twinkle))).coerceIn(0f, 1f)
    val sz = s.size * density / 2.5f * (0.6f + 0.6f * sin(t * PI.toFloat()))
    rotate(s.spin * t, pos) {
        when (s.shape) {
            SparkleShape.STAR -> drawPath(starPath(pos, sz), s.color.copy(alpha = alpha))
            SparkleShape.HEART -> drawPath(heartPath(pos, sz), s.color.copy(alpha = alpha))
            SparkleShape.DOT -> drawCircle(s.color.copy(alpha = alpha), sz / 3, pos)
        }
    }
}

/** The wand: a curve of small stars sweeping in from the bottom left to the centre, its head brightest. */
private fun DrawScope.wandTrail(p: Float, veil: Float) {
    val head = (p / 0.45f).coerceIn(0f, 1f)
    if (head <= 0f || p > 0.6f) return
    val fade = if (p > 0.45f) 1f - (p - 0.45f) / 0.15f else 1f
    val start = Offset(size.width * 0.05f, size.height * 0.95f)
    val end = Offset(size.width / 2, size.height / 2)
    val ctrl = Offset(size.width * 0.15f, size.height * 0.35f)
    fun at(u: Float) = Offset(
        (1 - u) * (1 - u) * start.x + 2 * (1 - u) * u * ctrl.x + u * u * end.x,
        (1 - u) * (1 - u) * start.y + 2 * (1 - u) * u * ctrl.y + u * u * end.y,
    )
    val steps = 24
    for (i in 0..steps) {
        val u = head * i / steps
        val a = (i / steps.toFloat()) * fade * veil
        val sz = (4f + 10f * i / steps) * density / 2.5f
        drawPath(starPath(at(u), sz), Color(0xFFFFF1F8).copy(alpha = a))
    }
    drawCircle(Color.White.copy(alpha = 0.9f * fade * veil), 6f * density, at(head))
}

private fun starPath(c: Offset, r: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.42f
        val a = -PI / 2 + i * PI / 5
        val x = c.x + (cos(a) * rad).toFloat()
        val y = c.y + (sin(a) * rad).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun heartPath(c: Offset, r: Float): Path = Path().apply {
    moveTo(c.x, c.y + r * 0.9f)
    cubicTo(c.x - r * 1.4f, c.y, c.x - r * 0.9f, c.y - r * 1.1f, c.x, c.y - r * 0.35f)
    cubicTo(c.x + r * 0.9f, c.y - r * 1.1f, c.x + r * 1.4f, c.y, c.x, c.y + r * 0.9f)
    close()
}
