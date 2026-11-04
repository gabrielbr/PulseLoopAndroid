package com.pulseloop.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/** Material's own styles sit on the type scale too, so `MaterialTheme.typography.*` can't go below 12sp. */
class PulseTypographyTest {
    @Test
    fun everyMaterialStyleIsOnTheScale() {
        val t = PulseTypography
        val styles = mapOf(
            "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
        )
        val off = styles.filter { (_, s) -> s.fontSize.value.toInt() !in PulseType.SCALE || s.fontSize.value % 1f != 0f }
        assertTrue("Off the scale: " + off.map { "${it.key}=${it.value.fontSize}" }, off.isEmpty())
    }
}
