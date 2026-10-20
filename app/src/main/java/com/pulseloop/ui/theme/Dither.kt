package com.pulseloop.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Bitmap texture instead of colour shades. Where the app used to tint a colour down (a mint wash
 * behind a selected chip, an amber wash behind a warning, a fading area under a chart), it now draws
 * that colour at full strength through an ordered 4×4 Bayer dither: a share of the pixels on, the
 * rest clear. The palette stays the handful of brand colours; "lighter" means fewer dots.
 *
 * One dither pixel is 1dp (at least 2 device pixels) so the pattern reads as a texture, not noise.
 */
object Dither {
    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )
    const val STEPS = 16

    private val cache = HashMap<Long, ShaderBrush>()

    /** Number of lit cells out of [STEPS] for a coverage in 0..1; any visible coverage keeps one. */
    fun steps(coverage: Float): Int {
        if (coverage <= 0f) return 0
        return (coverage * STEPS).roundToInt().coerceIn(1, STEPS)
    }

    /** A repeating brush of [color] with [lit] of every 16 cells on, each cell [cellPx] square. */
    fun brush(color: Color, lit: Int, cellPx: Int): ShaderBrush {
        val key = (color.toArgb().toLong() shl 32) or (lit.toLong() shl 8) or cellPx.toLong()
        synchronized(cache) {
            cache[key]?.let { return it }
            val side = 4 * cellPx
            val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
            val argb = color.copy(alpha = 1f).toArgb()
            for (y in 0 until side) for (x in 0 until side) {
                val on = BAYER[(y / cellPx) * 4 + (x / cellPx)] < lit
                if (on) bmp.setPixel(x, y, argb)
            }
            val b = ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
            cache[key] = b
            return b
        }
    }

    internal fun DrawScope.cellPx(): Int = 1.dp.toPx().roundToInt().coerceAtLeast(2)
}

fun DrawScope.ditherBrush(color: Color, coverage: Float): Brush? {
    val lit = Dither.steps(coverage)
    if (lit == 0) return null
    return with(Dither) { brush(color, lit, cellPx()) }
}

/**
 * Fills the element with [color] dithered to [coverage] (0..1, where an alpha tint used to be), and
 * outlines everything drawn on top of it — text and icons alike — so it stays readable over the dots.
 */
fun Modifier.dither(color: Color, coverage: Float, shape: Shape = RectangleShape): Modifier = drawWithContent {
    ditherBrush(color, coverage)?.let { drawOutline(shape.createOutline(size, layoutDirection, this), it) }
    drawContentOutlined()
}

/**
 * A dithered fade from [fromCoverage] at the top to [toCoverage] at the bottom, in stepped bands —
 * the bitmap take on a vertical gradient. Callers clip to their own shape first. Content on top is
 * outlined, as with [dither].
 */
fun Modifier.ditherFade(color: Color, fromCoverage: Float, toCoverage: Float): Modifier = drawWithContent {
    val path = Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
    drawDitherFade(path, color, fromCoverage, toCoverage, 0f, size.height)
    drawContentOutlined()
}

/** The outline round text and icons on a dithered ground: the page colour, this wide. */
private val OUTLINE_COLOR = PulseColors.background
private val OUTLINE_WIDTH = 1.5.dp

/** Eight compass offsets of one outline width; drawn under the content they form its outline. */
private val OUTLINE_DIRECTIONS = listOf(
    -1f to -1f, 0f to -1f, 1f to -1f,
    -1f to 0f, 1f to 0f,
    -1f to 1f, 0f to 1f, 1f to 1f,
)

/**
 * Draws the element's content twice over: first eight offset copies flattened to [OUTLINE_COLOR]
 * (one layer, tinted with SrcIn so every glyph and icon keeps its shape but loses its colour),
 * then the content itself. The dots stop at the outline, so a 12dp label or a 20dp icon reads on
 * any dither density.
 */
private fun ContentDrawScope.drawContentOutlined() {
    val w = OUTLINE_WIDTH.toPx()
    val tint = Paint().apply { colorFilter = ColorFilter.tint(OUTLINE_COLOR, BlendMode.SrcIn) }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(-w, -w, size.width + w, size.height + w), tint)
        OUTLINE_DIRECTIONS.forEach { (dx, dy) ->
            translate(dx * w, dy * w) { this@drawContentOutlined.drawContent() }
        }
        canvas.restore()
    }
    drawContent()
}

/** Fills [path] with [color] fading in dither steps from [fromCoverage] at [top] to [toCoverage] at [bottom]. */
fun DrawScope.drawDitherFade(
    path: Path,
    color: Color,
    fromCoverage: Float,
    toCoverage: Float,
    top: Float,
    bottom: Float,
) {
    val from = Dither.steps(fromCoverage)
    val to = Dither.steps(toCoverage)
    val bands = maxOf(1, kotlin.math.abs(from - to) + 1)
    val bandH = (bottom - top) / bands
    clipPath(path) {
        for (i in 0 until bands) {
            val lit = if (bands == 1) from else (from + (to - from) * i / (bands - 1).toFloat()).roundToInt()
            if (lit <= 0) continue
            val brush = with(Dither) { brush(color, lit, cellPx()) }
            drawRect(brush, topLeft = Offset(0f, top + i * bandH), size = Size(size.width, bandH + 1f))
        }
    }
}

/** A ring or gauge track: solid when opaque, otherwise its colour dithered to its alpha. */
fun DrawScope.trackBrush(color: Color): Brush =
    if (color.alpha >= 0.99f) SolidColor(color)
    else ditherBrush(color.copy(alpha = 1f), color.alpha) ?: SolidColor(Color.Transparent)

/** Fills [path] with [color] dithered to a flat [coverage]. */
fun DrawScope.drawDither(path: Path, color: Color, coverage: Float) {
    val brush = ditherBrush(color, coverage) ?: return
    drawPath(path, brush)
}
