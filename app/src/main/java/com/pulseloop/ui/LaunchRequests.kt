package com.pulseloop.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A screen an outside entry point asked for (the "Log meal" icon shortcut, a meal-reminder
 * notification). MainActivity sets it from the launching intent; PulseLoopApp navigates and
 * clears it. A flow rather than a start destination so it also works when the app is already
 * open and the intent arrives through onNewIntent.
 */
object LaunchRequests {
    val route = MutableStateFlow<String?>(null)

    /** A message to send to the coach when its screen opens (e.g. discussing a meal suggestion). */
    val coachMessage = MutableStateFlow<String?>(null)

    /** Today's Workout chip: open the Activity tab with the workout-type picker up. */
    val openWorkoutPicker = MutableStateFlow(false)
}
