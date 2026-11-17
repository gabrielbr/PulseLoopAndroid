package com.pulseloop.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The event stream of the ring connected only for comparison (`RingRole.COMPARISON`). It is a
 * separate bus on purpose: everything listening to [PulseEventBus] (persistence, the accuracy
 * watcher, the device row, Health Connect, the sync coordinator) belongs to the app's own ring,
 * and a second ring's readings must never reach any of it. Only `ComparisonRing` listens here.
 */
object ComparisonBus {
    private val _events = MutableSharedFlow<PulseEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<PulseEvent> = _events.asSharedFlow()
    private val pending = Channel<PulseEvent>(capacity = Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (event in pending) runCatching { _events.emit(event) }
        }
    }

    fun publish(event: PulseEvent) { pending.trySend(event) }
}

/** Which job a [RingBLEClient] does: the app's ring, or a second one connected only to compare. */
enum class RingRole { PRIMARY, COMPARISON }
