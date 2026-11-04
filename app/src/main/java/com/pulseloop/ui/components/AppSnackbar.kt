package com.pulseloop.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app's one snackbar host, owned by `PulseLoopApp`'s Scaffold and reached through
 * [LocalAppSnackbar]. It shows on the app's own scope, so a message outlives the card or screen
 * that asked for it (a detected workout's card disappears the moment it is added). A newer
 * message replaces the one showing rather than queueing behind it. TalkBack announces it.
 */
class AppSnackbar(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message)
        }
    }
}

/** Null outside `PulseLoopApp` (previews, onboarding-only hosts): callers skip the message. */
val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar?> { null }

/** The host's look: a card-coloured bar with primary text, no accent (it isn't a tap target). */
@Composable
fun AppSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = PulseColors.card,
            contentColor = PulseColors.textPrimary,
        )
    }
}
