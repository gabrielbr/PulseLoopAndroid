package com.pulseloop.coach.schema

import com.pulseloop.ui.theme.dither
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.theme.PulseColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.orchestration.PendingAction
import com.pulseloop.coach.orchestration.PendingActionKind

/**
 * Ported from CoachActionCardView.swift.
 * Confirm/Cancel card for a PendingAction the coach proposed but hasn't run.
 */
@Composable
fun CoachActionCardView(
    action: PendingAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val isDestructive = action.kind == PendingActionKind.DELETE_ACTIVITY_SESSION
    val tone = if (isDestructive) PulseColors.danger else PulseColors.warning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dither(tone, 0.08f, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = tone, modifier = Modifier.size(22.dp))
            Text(com.pulseloop.ui.i18n.tr("CONFIRM CHANGE"), style = com.pulseloop.ui.theme.PulseType.eyebrow)
        }

        Text(action.summary, fontSize = 14.sp, color = PulseColors.textPrimary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseButton(PulseButtonStyle.Outlined,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(com.pulseloop.ui.i18n.tr("Cancel"))
            }
            // Tonal, tinted by the risk: the tab's one filled accent stays the Actions button.
            PulseButton(PulseButtonStyle.Tonal,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = tone.copy(alpha = 0.22f), contentColor = tone),
            ) {
                Text(action.confirmLabel)
            }
        }
    }
}
