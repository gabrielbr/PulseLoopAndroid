package com.pulseloop.ui.components

import com.pulseloop.ui.i18n.tr

import com.pulseloop.ui.theme.dither
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.R
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.wearables.WearableModel

/**
 * Ported from DeviceHeroCard.swift (iOS #49).
 * Rich ring-connectivity card at the top of Settings. The card (or its trailing chevron) opens
 * the Wearable screen; a separate action button connects/sets up. The two are distinct controls
 * so both are reachable by touch and accessibility services.
 */
@Composable
fun DeviceHeroCard(
    bleState: RingBLEClient.BLEState,
    storedDevice: DeviceEntity?,
    lastSyncAt: Long?,
    onOpenWearable: () -> Unit,
    onConnect: () -> Unit,
    onSetUp: () -> Unit,
    onDisconnect: () -> Unit = {},
) {
    val deviceType = bleState.activeDeviceType ?: storedDevice?.deviceType
    val wearableModel = WearableModel.model(bleState.activeWearableModelID)
        ?: WearableModel.model(storedDevice?.wearableModelID)
    val battery = bleState.batteryPercent ?: storedDevice?.batteryPercent
    val connected = bleState.connectionState == RingConnectionState.CONNECTED
    val status = DeviceHeroStatus.make(
        state = bleState.connectionState,
        connectedName = if (connected) wearableModel?.displayName ?: bleState.activeDeviceType?.displayName else null,
        knownName = wearableModel?.displayName ?: deviceType?.displayName,
        batteryPercent = battery,
        lastSyncAt = lastSyncAt,
        now = System.currentTimeMillis(),
        idle = bleState.idleSince != null,
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = tr("Open ring settings"), role = Role.Button, onClick = onOpenWearable),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RingArtView(
                tint = PulseColors.accent,
                size = 72f,
                imageRes = wearableModel?.imageRes ?: fallbackRingImage(deviceType),
            )

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    status.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status.statusLine,
                    fontSize = 14.sp,
                    color = status.statusTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                status.syncText?.let { syncText ->
                    Text(
                        syncText,
                        fontSize = 12.sp,
                        color = PulseColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                status.batteryText?.let { batteryText ->
                    // Tinted by level: always green read "fine" at 5%.
                    val pct = batteryText.filter { it.isDigit() }.toIntOrNull()
                    val batteryTone = when {
                        pct == null -> PulseColors.textSecondary
                        pct <= 20 -> PulseColors.danger
                        pct <= 50 -> PulseColors.warning
                        else -> PulseColors.success
                    }
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .dither(batteryTone, 0.12f)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.BatteryFull,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = batteryTone,
                        )
                        Text(
                            batteryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = batteryTone,
                        )
                    }
                }
                Chevron() // the row's click label says it
            }
        }

        // The connected card is purely informational and opens Wearable settings, where
        // Disconnect lives. Setup/reconnect remain available here only when not connected.
        if (status.action != DeviceHeroStatus.Action.DISCONNECT) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    status.actionTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.actionEnabled) PulseColors.accent else PulseColors.textMuted,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .then(if (status.actionEnabled) Modifier.dither(PulseColors.accent, 0.18f) else Modifier.background(PulseColors.elevated))
                        .clickable(enabled = status.actionEnabled, role = Role.Button) {
                            when (status.action) {
                                DeviceHeroStatus.Action.CONNECT -> onConnect()
                                DeviceHeroStatus.Action.SET_UP -> onSetUp()
                                // Unreachable today: the row above only renders when action !=
                                // DISCONNECT, and DeviceHeroStatus produces DISCONNECT only for
                                // the connected state — so this is a tripwire, not a fix (the PR
                                // description once claimed a dead button here; there never was
                                // one). Exhaustiveness needs the branch, and if the render
                                // condition is ever relaxed it must call through, not do nothing.
                                DeviceHeroStatus.Action.DISCONNECT -> onDisconnect()
                                // Already inert: actionEnabled is false while a connect is in flight.
                                DeviceHeroStatus.Action.PENDING -> {}
                            }
                        }
                        .padding(horizontal = 12.dp)
                        .wrapContentHeight(),
                )
            }
        }
    }
}

/**
 * Representative product image for the connected/known ring family — the connection only
 * reveals the family, not the exact model. Null falls back to the generic ring in RingArtView.
 */
private fun fallbackRingImage(type: RingDeviceType?): Int? = when (type) {
    RingDeviceType.JRING -> R.drawable.ring_jring
    RingDeviceType.YCBT -> R.drawable.ring_colmi_r10
    // No dedicated product art yet for either YCBT family or LuckRing — falls back to the generic ring.
    RingDeviceType.COLMI_R02, RingDeviceType.TK5, RingDeviceType.COLMI_SMART_HEALTH,
    RingDeviceType.LUCK_RING, RingDeviceType.CRP, RingDeviceType.RWFIT, null -> null
}
