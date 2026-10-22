package com.pulseloop.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.text.Normalizer

/**
 * An Easter egg for Tatá: when the profile's name is Tatá, Tata or Thaissa, the app plays a fairy
 * transformation once (FairyTransformOverlay) and from then on wears pink instead of mint, with
 * smooth washes where the bitmap dither would be. Changing the name to anything else turns it off.
 *
 * Snapshot state, so every composable reading a [PulseColors] brand colour recomposes when it
 * flips, and draw code reading it redraws.
 */
object FairyMode {

    private val NAMES = setOf("tata", "thaissa", "thaisa")

    /** Whether the pink theme is on. */
    var on by mutableStateOf(false)
        private set

    /** The transformation still to play; the overlay clears it. */
    var celebrating by mutableStateOf(false)
        private set

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("fairy", Context.MODE_PRIVATE)
        on = prefs?.getBoolean("on", false) == true
    }

    /** Whether a profile name is one of hers: the first word, ignoring case and accents. */
    fun matches(name: String?): Boolean {
        val first = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val plain = Normalizer.normalize(first, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return plain in NAMES
    }

    /** Follow the profile's name: start the transformation the first time it matches, revert when it no longer does. */
    fun onProfileName(name: String?) {
        val match = matches(name)
        if (match && !on && !celebrating) celebrating = true
        if (!match && on) persist(false)
    }

    /** Called by the overlay at the moment of transformation, under the sparkles. */
    fun transform() = persist(true)

    fun celebrationDone() {
        celebrating = false
        if (!on) persist(true)
    }

    private fun persist(value: Boolean) {
        on = value
        prefs?.edit()?.putBoolean("on", value)?.apply()
    }

    /** Her palette: a soft rose pink with deep plum ink on it (7.7:1). */
    object Palette {
        val accent = Color(0xFFFF8FC7)
        val accentSoft = Color(0x2EFF8FC7)
        val onAccent = Color(0xFF3D0A26)
        val rem = Color(0xFFFFB3DA)
        /** Sparkle colours: pinks, a little gold and white. */
        val sparkles = listOf(
            Color(0xFFFF8FC7), Color(0xFFFFB3DA), Color(0xFFFF5FA8), Color(0xFFFFD6EC),
            Color(0xFFFFE08A), Color(0xFFFFFFFF), Color(0xFFE7B6FF),
        )
    }
}
