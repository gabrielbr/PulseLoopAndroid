package com.pulseloop.health.accuracy

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Ends a running check the moment a different ring becomes the paired one (plan, "Changing
 * rings"). Process-scoped, started from `PulseLoopApplication`: ring data carries no device id, so
 * the switch has to be caught as it happens — a periodic check would score the new ring's first
 * hours as the old ring's.
 */
object AccuracyRingWatcher {
    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        val db = PulseLoopDatabase.getInstance(app)
        scope.launch {
            db.deviceDao().currentFlow()
                .filterNotNull()
                .map { it.takeIf { d -> d.id != RingIdentity.DEMO_DEVICE_ID } }
                .filterNotNull()
                .distinctUntilChanged { a, b -> RingIdentity.keyOf(a) == RingIdentity.keyOf(b) }
                .collect { runCatching { AccuracyCheckController.onRingChanged(app, it) } }
        }
    }
}
