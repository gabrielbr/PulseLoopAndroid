package com.pulseloop.ui.calm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The switch between the current look and the new calmer one (docs/new-look-plan.md). Screens
 * move over one per release; until every screen has, the new look is off by default and turned
 * on from the Debug screen. It is a display preference about this phone, not the user's data, so
 * it stays out of the backup archive.
 */
object NewLook {
    private const val PREFS = "new_look"
    private const val KEY = "on"

    /** Compose state, so flipping it redraws every screen that reads it. */
    var on by mutableStateOf(false)
        private set

    fun load(c: Context) {
        on = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(c: Context, value: Boolean) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        on = value
    }
}
