package com.pulseloop.ui.calm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pulseloop.R
import com.pulseloop.ui.theme.PulseColors

/**
 * The new look's tokens: shadcn's dark neutrals (near-black page, a slightly lighter card, one
 * hairline border, two greys of secondary text), Inter, one 12dp radius and wide gaps. Mint stays
 * the one accent, read from [PulseColors] so Fairy mode still turns it pink.
 *
 * PulseLoop-only, by the owner's choice (2026-11-16): it departs from the Gabriel Design System's
 * pulseloop layer (Ubuntu Mono, 8dp radius, dither) on purpose. The departure is recorded in
 * docs/new-look-plan.md, and moves to the design-system repo's divergences list.
 */
object CalmColors {
    val page = Color(0xFF0A0A0B)
    val card = Color(0xFF161618)
    val cardRaised = Color(0xFF1E1E21)
    val border = Color(0xFF27272A)
    val text = Color(0xFFFAFAFA)
    /** Secondary text: 7.0:1 on [card]. */
    val textSecondary = Color(0xFFA1A1AA)
    /** Tertiary text: 5.3:1 on [card] (4.9:1 on [cardRaised]), so it still carries meaning at 12sp. */
    val textMuted = Color(0xFF8B8B94)
    val accent: Color get() = PulseColors.accent
    val onAccent: Color get() = PulseColors.onAccent
}

object CalmShape {
    val radius = 12.dp
    val gutter = 20.dp
    /** Between sections: the room that lets one idea end before the next starts. */
    val sectionGap = 40.dp
}

/** Inter in four weights. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Sizes stay on PulseType.SCALE (PulseTypeScaleTest). */
object CalmType {
    /** The one statement a screen leads with ("Minor signs"). */
    val display = TextStyle(fontFamily = Inter, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.15.em, letterSpacing = (-0.02).em)
    val sectionTitle = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.3.em, letterSpacing = (-0.01).em)
    val title = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.35.em)
    val body = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 1.5.em)
    val bodySmall = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 1.5.em)
    /** A screen's one headline number (Today's readiness). 40 is the top of the scale. */
    val score = TextStyle(fontFamily = Inter, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.05.em, letterSpacing = (-0.02).em)
    val value = TextStyle(fontFamily = Inter, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.1.em)
    val label = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 1.3.em)
    /** The small capitals over a card ("RESPIRATORY RATE"). */
    val eyebrow = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, lineHeight = 1.3.em)
}

/**
 * Runs [content] with Inter as the text family: Material's whole type scale and the ambient text
 * style. A converted screen still reuses some shared pieces (a program chip, the bedtime card);
 * inside this scope their text comes out in Inter too, so the screen keeps one family. Text that
 * names its own family explicitly (PulseType's styles) is unchanged until its piece is converted.
 */
@androidx.compose.runtime.Composable
fun CalmTypography(enabled: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (!enabled) { content(); return }
    val t = androidx.compose.material3.MaterialTheme.typography
    fun TextStyle.inter() = copy(fontFamily = Inter)
    val typography = t.copy(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.MaterialTheme.colorScheme,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
        typography = typography,
    ) {
        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.inter(), content)
    }
}
