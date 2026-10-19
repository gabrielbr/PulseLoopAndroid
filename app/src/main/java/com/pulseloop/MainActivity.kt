package com.pulseloop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulseloop.ui.theme.PulseColors
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pulseloop.health.HealthConnectPermissionReconcile
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.strava.StravaAuth
import com.pulseloop.strava.StravaTokenStore
import com.pulseloop.ui.PulseLoopApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-activity host for the PulseLoop Compose UI.
 * Requests all required runtime permissions on startup:
 *   - Android 12+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT
 *   - Android < 12: ACCESS_FINE_LOCATION (for BLE scanning)
 *   - Android 13+: POST_NOTIFICATIONS
 */
class MainActivity : ComponentActivity() {

    // Request multiple permissions at once on Android 12+
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            // Permissions granted — services can start
            CoachNotifications.schedule(this)
        }
    }

    // Single permission request for older Android or notification-only
    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            CoachNotifications.schedule(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light bar icons always: the app is dark-only, so following a light system theme would
        // put dark icons on the dark page.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        CoachNotifications.createChannel(this)
        com.pulseloop.notifications.BatteryNotifications.createChannel(this)
        requestAllPermissions()

        setContent {
            // GMind brand frame: a 4dp mint border round the app, drawn inside the system bars
            // (under the status bar, above the navigation bar) rather than at the window edge, where
            // the bars covered it. The bars' insets are consumed here, so nothing inside pads for
            // them a second time.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(PulseColors.background)
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                    .border(4.dp, PulseColors.frame)
                    .padding(4.dp),
            ) {
                PulseLoopApp()
            }
        }

        // Handle Strava redirect on cold start (app launched from browser callback).
        handleStravaRedirect(intent)
        handleLaunchAction(intent)
        publishShortcuts()
    }

    override fun onResume() {
        super.onResume()
        // Re-request on resume in case user granted in Settings
        requestAllPermissions()
        if (hasAllBlePermissions() && hasNotificationPermission()) {
            CoachNotifications.schedule(this)
        }
        // Health Connect Phase 6: detect out-of-band grant/revocation on every foreground return
        // and reset the affected export watermarks (auto on grow; a full revocation is surfaced on
        // the settings screen). No-op unless the export is enabled — deliberately NOT gated on a
        // stored grant, so a re-grant made after a full revocation (stored set then empty) is still
        // seen as a grow and its watermark reset backfills the re-granted types.
        HealthConnectPermissionReconcile.onAppStart(this, lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStravaRedirect(intent)
        handleLaunchAction(intent)
    }

    /**
     * Strava OAuth callback (`pulseloop://localhost/strava-auth?…`). Reached via `onNewIntent` for
     * the usual case, and via `onCreate` when the browser round-trip outlived our process.
     *
     * Failures are recorded in the token store rather than dropped, so the Strava settings screen
     * can say what went wrong — a silent return leaves the user staring at a "Connect" button that
     * appears to do nothing.
     */
    /** The "Log meal" shortcut and meal-reminder notifications open straight onto the camera. */
    private fun handleLaunchAction(intent: Intent?) {
        if (intent?.action == ACTION_LOG_MEAL) {
            com.pulseloop.ui.LaunchRequests.route.value = "nutrition/photo"
            intent.action = null // a recreated Activity must not reopen it
        }
    }

    /**
     * Long-press the app icon → "Log meal". Dynamic rather than a static shortcuts.xml so the
     * intent targets this build's own package (debug builds carry an applicationId suffix).
     */
    private fun publishShortcuts() {
        runCatching {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "log_meal")
                .setShortLabel("Log meal")
                .setLongLabel("Log a meal")
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_shortcut_meal))
                .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_LOG_MEAL))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
    }

    companion object {
        const val ACTION_LOG_MEAL = "com.pulseloop.action.LOG_MEAL"
    }

    private fun handleStravaRedirect(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "pulseloop") return
        if (!StravaAuth.isConfigured) return
        val store = StravaTokenStore(this)

        // Strava returns `error=access_denied` when the user declines on the consent screen.
        uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let { error ->
            store.takePendingAuthState()
            store.saveLastError(
                if (error == "access_denied") "Strava authorization was declined." else "Strava denied authorization: $error"
            )
            return
        }

        // CSRF: the state we generated must come back. One-shot, cleared either way.
        if (!StravaAuth.validateState(store, uri.getQueryParameter("state"))) {
            store.saveLastError("Strava authorization could not be verified. Please try connecting again.")
            return
        }

        // The consent screen lets the user untick "Upload your activities". Catch it here rather
        // than letting every future upload fail with an opaque 401.
        if (!StravaAuth.grantedScopeIncludesWrite(uri.getQueryParameter("scope"))) {
            store.saveLastError("Strava did not grant upload permission. Reconnect and keep \"Upload your activities\" checked.")
            return
        }

        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: run {
            store.saveLastError("Strava returned an unexpected authorization response.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                store.save(StravaAuth.exchangeCode(code))
                store.clearLastError()
            } catch (e: Exception) {
                // The code may have expired, or the configured secrets are wrong.
                store.saveLastError("Could not complete the Strava sign-in: ${e.message ?: "unknown error"}")
            }
        }
    }

    // ── Permission checks ──────────────────────────────────────────────

    fun hasAllBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ── Permission requests ─────────────────────────────────────────────

    private fun requestAllPermissions() {
        val missing = mutableListOf<String>()

        // BLE / Location — GPS tracking needs location on all API levels
        if (!hasFineLocation()) missing.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBleScan()) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!hasBleConnect()) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isNotEmpty()) {
            if (missing.size == 1) {
                singlePermissionLauncher.launch(missing.first())
            } else {
                blePermissionLauncher.launch(missing.toTypedArray())
            }
        } else {
            // All granted — schedule notifications
            CoachNotifications.schedule(this)
        }
    }

    private fun hasBleScan() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasBleConnect() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
