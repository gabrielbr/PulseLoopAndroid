package com.pulseloop

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.pulseloop.data.DataRepairs
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.diagnostics.CrashLogger
import com.pulseloop.diagnostics.DiagnosticsSubscriber
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.widgets.WidgetRefreshWorker
import com.pulseloop.widgets.WidgetSnapshotPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Installs the crash logger as early as possible so uncaught
 * exceptions from anywhere in the app are persisted for the next diagnostics export.
 */
class PulseLoopApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        com.pulseloop.service.DrinkPrefs.init(this)
        com.pulseloop.service.Postpartum.init(this)
        com.pulseloop.ui.theme.FairyMode.init(this)
        com.pulseloop.ui.i18n.L10n.init(this)
        com.pulseloop.health.source.HealthConnectSource.isEnabled(this) // warms [HealthConnectSource.enabledNow]
        // Application-scoped so capture runs whether or not the Debug screen is open.
        DiagnosticsSubscriber(PulseLoopDatabase.getInstance(this)).start(appScope)
        // Accuracy check: a different ring ends a running check at once (it can't be told apart later).
        com.pulseloop.health.accuracy.AccuracyRingWatcher.start(this, appScope)
        com.pulseloop.health.accuracy.AccuracyNotifications.createChannel(this)
        // One-time cleanup of activity totals corrupted by the old bucket handling; runs off the
        // main thread and is prefs-gated so it executes once. Safe to race the first sync — the
        // ring can't connect before this completes a couple of DELETE statements.
        appScope.launch { DataRepairs.runIfNeeded(this@PulseLoopApplication) }
        appScope.launch { DataRepairs.repairSleepDurationsIfNeeded(this@PulseLoopApplication) }
        appScope.launch { DataRepairs.repairLuckRingActivityIfNeeded(this@PulseLoopApplication) }
        // Phones already restored from an archive without settings: drinks there, Drinks off.
        appScope.launch {
            runCatching { com.pulseloop.service.DrinkPrefs.adoptRestoredHistory(PulseLoopDatabase.getInstance(this@PulseLoopApplication)) }
        }

        // Home-screen widgets (iOS #44): publish the snapshot on every foreground/background
        // edge (the iOS scene-phase triggers — catches goal/unit/profile edits that don't run
        // through the sync pipeline), and keep a ~30 min periodic republish alive so widget
        // staleness and the midnight day-rollover appear without the app opening.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                    WidgetSnapshotPublisher.publish(this)
                }
                // Bedtime/DND (issue #83): reconcile the window log with the current Mode in case
                // the listener was unbound across an edge. The listener records the edges.
                if (event == Lifecycle.Event.ON_START && QuietHoursPrefs.isZenTrackingActive(this)) {
                    com.pulseloop.settings.ZenModeTracker.recordCurrentFilter(this)
                }
            },
        )
        WidgetRefreshWorker.schedule(this)
        appScope.launch { runCatching { com.pulseloop.service.StepCalibration.sample(this@PulseLoopApplication) } }
        // Scheduled backups: the daily worker, plus a catch-up on start when one is overdue.
        com.pulseloop.data.AutoBackup.schedule(this)
        appScope.launch {
            val c = this@PulseLoopApplication
            if (com.pulseloop.data.AutoBackup.isEnabled(c) &&
                com.pulseloop.data.AutoBackup.isDue(com.pulseloop.data.AutoBackup.lastAt(c), com.pulseloop.data.AutoBackup.intervalDays(c), System.currentTimeMillis())
            ) com.pulseloop.data.AutoBackup.runNow(c)
        }

    }
}
