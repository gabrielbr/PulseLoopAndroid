package com.pulseloop.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.pulseloop.R

// GMind branding: Ubuntu Mono throughout, mint #43F3B7 as the one accent with ink-green
// #033423 on it, and GMind's neutral page/surface greys. Full token set lives in PulseColors.kt.

/** Ubuntu Mono, the GMind type family. Medium/SemiBold resolve to the nearest face (regular/bold). */
val UbuntuMono = FontFamily(
    Font(R.font.ubuntu_mono, FontWeight.Normal),
    Font(R.font.ubuntu_mono_bold, FontWeight.Bold),
    Font(R.font.ubuntu_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

private val DarkColorScheme = darkColorScheme(
    primary = PulseColors.accent,
    onPrimary = PulseColors.onAccent,
    primaryContainer = PulseColors.accentSoft,
    onPrimaryContainer = PulseColors.accent,
    secondary = PulseColors.accent,
    onSecondary = PulseColors.onAccent,
    tertiary = PulseColors.sleep,
    background = PulseColors.background,
    surface = PulseColors.secondaryBackground,
    onBackground = PulseColors.textPrimary,
    onSurface = PulseColors.textPrimary,
    surfaceVariant = PulseColors.card,
    onSurfaceVariant = PulseColors.textSecondary,
    surfaceContainer = PulseColors.card,
    surfaceContainerHigh = PulseColors.cardSoft,
    surfaceContainerHighest = PulseColors.elevated,
    error = PulseColors.danger,
    onError = Color(0xFF17161A),
    outline = PulseColors.borderStrong,
    outlineVariant = PulseColors.borderSubtle,
)

/**
 * Ubuntu Mono for every style, and line height as a ratio of the font size (em) rather than
 * Material's fixed sp. Most text here overrides `fontSize` inline, which kept the style's sp line
 * height: a 13sp label inherited bodyLarge's 24sp and a wrapped line opened a gap under it.
 */
private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family, lineHeight = (lineHeight.value / fontSize.value).em)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

private val PulseTypography = Typography().withFamily(UbuntuMono)

/**
 * Always the dark scheme: every screen draws on [PulseColors]' dark tokens, so a light Material
 * scheme only ever mixed ink text onto dark cards.
 */
@Composable
fun PulseLoopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = PulseTypography,
        content = content,
    )
}
