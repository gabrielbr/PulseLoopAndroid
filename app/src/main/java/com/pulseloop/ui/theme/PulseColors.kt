package com.pulseloop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's design tokens. Chrome follows the GMind brand (the dark values of GMind's
 * `ui-kit` UiTokens): page #121214, surface #1C1B1F, ink #F2F2F2, muted #A8A7AD, hairline
 * #2C2B30, and mint #43F3B7 as the one accent, with ink-green #033423 for text on it. The
 * metric, zone and stage colours are tuned to sit beside the mint rather than compete with
 * it. The zone hexes below are the single source of truth that [MetricColors] and
 * ZonePalette alias.
 */
object PulseColors {
    val background = Color(0xFF121214)
    val secondaryBackground = Color(0xFF161619)
    val card = Color(0xFF1C1B1F)
    val cardSoft = Color(0xFF242328)
    val elevated = Color(0xFF2C2B30)
    val textPrimary = Color(0xFFF2F2F2)
    val textSecondary = Color(0xFFA8A7AD)
    val textMuted = Color(0xFF7C7B82)
    /** GMind mint: the primary action, the selected state, the screen frame. */
    val accent = Color(0xFF43F3B7)
    val accentSoft = Color(0x2E43F3B7)   // accent @ 18%
    /** Text and icons on a mint fill (GMind ON_MINT, 9:1 on mint). Never white on mint. */
    val onAccent = Color(0xFF033423)
    val success = Color(0xFF43F3B7)
    val warning = Color(0xFFFFC46B)
    val danger = Color(0xFFFF7A70)     // GMind coral (dark)
    val info = Color(0xFF5AD8F0)

    // Per-metric accents
    val steps = Color(0xFF9BEA6B)       // leaf green, distinct from the mint accent
    val heartRate = Color(0xFFFF7A70)   // coral
    val spo2 = Color(0xFF5AD8F0)
    val sleep = Color(0xFF9A8CFF)
    val calories = Color(0xFFFF9A5C)
    val distance = Color(0xFF5BA8FF)
    val battery = Color(0xFFA7F3D0)
    val stress = Color(0xFFFF9A5C)
    val hrv = Color(0xFFB59CFF)
    val temperature = Color(0xFF3FD0C8)
    val bloodPressure = Color(0xFFFF7FA8)
    val bloodSugar = Color(0xFFFFC46B)
    val fatigue = Color(0xFFC98CFF)
    val drinks = Color(0xFFE38FB5)     // wine rose
    val readiness = Color(0xFF43F3B7)   // the headline score wears the brand mint

    // Vitals reference-zone colors (AppTheme.swift zone*) — aliased by
    // ZonePalette (fine iOS-parity palette) and [MetricColors] (coarse semantic palette)
    val zoneBlue = Color(0xFF5BA8FF)
    val zoneMint = Color(0xFF43F3B7)
    val zoneCyan = Color(0xFF5AD8F0)
    val zoneAmber = Color(0xFFFFC46B)
    val zoneSoftAmber = Color(0xFFFFDDA6)
    val zoneOrange = Color(0xFFFF9A5C)
    val zoneRed = Color(0xFFFF7A70)
    val zoneCritical = Color(0xFFFF4B3E)

    // Sleep stage colors (DesignSystem/Charts.swift SleepStageColors)
    val stageDeep = Color(0xFF4B3BD6)
    val stageLight = Color(0xFF9A8CFF)
    val stageRem = Color(0xFF43F3B7)
    val stageAwake = Color(0xFFFFC46B)

    val borderSubtle = Color(0xFF2C2B30)  // GMind hairline (LINE, dark)
    val borderStrong = Color(0xFF3D3C42)

    /** The GMind frame: a 4dp mint border round every screen. */
    val frame = accent
}
