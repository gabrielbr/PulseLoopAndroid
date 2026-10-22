package com.pulseloop.ui.components

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import androidx.compose.ui.graphics.Color
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ui.theme.PulseColors

/**
 * Ported from DeviceHeroStatus (DeviceHeroCard.swift, iOS #49).
 * Pure, view-agnostic description of the ring's state for the Settings hero card.
 * All branching lives here so [DeviceHeroCard] stays declarative and this stays unit-testable
 * (Compose `Color` is plain JVM bytecode, so no Robolectric needed).
 */
data class DeviceHeroStatus(
    val title: String,
    val statusLine: String,
    val statusTint: Color,
    val batteryText: String?,
    val syncText: String?,
    val action: Action,
) {
    enum class Action {
        DISCONNECT,
        CONNECT,
        SET_UP,
        /** A connect attempt is in flight (connecting / reconnecting / scanning). */
        PENDING,
    }

    val actionTitle: String
        get() = when (action) {
            Action.DISCONNECT -> tr("Disconnect")
            Action.CONNECT -> tr("Connect")
            Action.SET_UP -> tr("Set up a ring")
            Action.PENDING -> tr("Connecting…")
        }

    /** The action button is inert while a connection attempt is in flight, so a tap can't
     *  cancel or restart an in-progress (re)connect. */
    val actionEnabled: Boolean get() = action != Action.PENDING

    companion object {
        /**
         * `connectedName` reflects the live connection (null unless connected); `knownName` is
         * the last stored device (survives disconnect). Separating them distinguishes "known but
         * disconnected" ([Action.CONNECT]) from "never paired" ([Action.SET_UP]).
         */
        fun make(
            state: RingConnectionState,
            connectedName: String?,
            knownName: String?,
            batteryPercent: Int?,
            lastSyncAt: Long?,
            now: Long,
            /** The ring hung up between syncs on its own (`IdleLinkPolicy`), not a fault. */
            idle: Boolean = false,
        ): DeviceHeroStatus {
            val title = connectedName ?: knownName ?: tr("No ring connected")

            val statusLine: String
            val statusTint: Color
            when (state) {
                RingConnectionState.CONNECTED -> {
                    statusLine = tr("Connected"); statusTint = PulseColors.success
                }
                RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING -> {
                    statusLine = tr("Connecting…"); statusTint = PulseColors.warning
                }
                RingConnectionState.SCANNING -> {
                    statusLine = tr("Searching…"); statusTint = PulseColors.warning
                }
                RingConnectionState.FAILED -> {
                    statusLine = tr("Connection failed"); statusTint = PulseColors.danger
                }
                RingConnectionState.IDLE, RingConnectionState.DISCONNECTED -> {
                    statusLine = when {
                        knownName == null -> tr("No ring paired")
                        idle && state == RingConnectionState.DISCONNECTED -> tr("Idle between syncs")
                        else -> tr("Disconnected")
                    }
                    statusTint = PulseColors.textSecondary
                }
            }

            val batteryText = batteryPercent?.let { "${it.coerceIn(0, 100)}%" }

            val syncText = lastSyncAt?.let { trf("Synced {0}", relativeShort(it, now)) }

            val action = when (state) {
                RingConnectionState.CONNECTED -> Action.DISCONNECT
                RingConnectionState.CONNECTING,
                RingConnectionState.RECONNECTING,
                RingConnectionState.SCANNING -> Action.PENDING
                RingConnectionState.IDLE,
                RingConnectionState.DISCONNECTED,
                RingConnectionState.FAILED -> if (knownName != null) Action.CONNECT else Action.SET_UP
            }

            return DeviceHeroStatus(
                title = title, statusLine = statusLine, statusTint = statusTint,
                batteryText = batteryText, syncText = syncText, action = action,
            )
        }

        /** Short relative-past phrase (iOS `RelativeDateTimeFormatter` `.short` equivalent). */
        fun relativeShort(fromMillis: Long, nowMillis: Long): String {
            val seconds = ((nowMillis - fromMillis) / 1000).coerceAtLeast(0)
            return when {
                seconds < 60 -> "just now"
                seconds < 3_600 -> trf("{0}m ago", seconds / 60)
                seconds < 86_400 -> trf("{0}h ago", seconds / 3_600)
                else -> trf("{0}d ago", seconds / 86_400)
            }
        }
    }
}
