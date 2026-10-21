package com.pulseloop.service

/**
 * The version of PulseLoop's scoring and insight formulas. Bump it whenever a change would make a
 * past day's number differ if it were computed again. Findings are stamped with it, the "data
 * behind this score" view shows it, and comparisons never reach back across a bump, so an app
 * update is never reported as a change in the person (OpenStrap Edge's rule).
 */
object Algorithms {
    const val VERSION = 3

    /** What each version changed, newest first, for the data view. */
    val HISTORY = listOf(
        3 to "HRV withheld when sparse, flat or scattered; readiness withheld below half its weight; corrected journal statistics",
        2 to "Readiness gained body temperature and recovery index; sleep scored against learned sleep need",
        1 to "First readiness, sleep and activity scores",
    )

    /** When the current version first ran on this device; findings before it aren't compared across. */
    fun firstRunOfCurrent(context: android.content.Context): Long {
        val p = context.applicationContext.getSharedPreferences("algorithms", android.content.Context.MODE_PRIVATE)
        if (p.getInt("version", 0) != VERSION) {
            p.edit().putInt("version", VERSION).putLong("since", System.currentTimeMillis()).apply()
        }
        return p.getLong("since", System.currentTimeMillis())
    }
}
