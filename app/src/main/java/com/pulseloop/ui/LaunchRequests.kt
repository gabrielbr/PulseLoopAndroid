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
}
