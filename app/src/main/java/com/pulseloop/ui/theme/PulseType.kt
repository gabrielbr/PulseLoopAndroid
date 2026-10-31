package com.pulseloop.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The app's type scale: one size per role, so a screen can't drift to its own 13sp or 15sp.
 * Inline `fontSize` values snap to [SCALE]; `PulseTypeScaleTest` fails on any other size in the
 * main screens. 12sp is the floor for anything that carries meaning.
 *
 * Each style carries Ubuntu Mono itself: `Text(style = …)` replaces the theme's text style
 * rather than merging into it, so a style without a family would fall back to the system font.
 */
object PulseType {
    /** Every size the main screens may use, smallest to largest. */
    val SCALE = listOf(12, 14, 16, 20, 24, 28, 32, 40)

    /** The tracked ALL-CAPS label over a card or a section: one size, one tracking, everywhere. */
    val eyebrow = TextStyle(
        fontFamily = UbuntuMono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        lineHeight = 1.3.em,
    )

    /** Captions, units, axis ticks: the smallest text that says something. */
    val label = TextStyle(fontFamily = UbuntuMono, fontSize = 12.sp, lineHeight = 1.3.em)

    val body = TextStyle(fontFamily = UbuntuMono, fontSize = 14.sp, lineHeight = 1.4.em)

    /** A row's or card's title. */
    val title = TextStyle(fontFamily = UbuntuMono, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 1.3.em)

    /** A screen's own title, when it has one. */
    val headline = TextStyle(fontFamily = UbuntuMono, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 1.2.em)
}
