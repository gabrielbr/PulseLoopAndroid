package com.pulseloop.coach.schema

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.entity.CoachToolCallEntity
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.viewmodels.CoachViewModel

/**
 * Ported from CoachToolTraceDisclosure in CoachToolTraceView.swift. Collapsed: a wrench glyph +
 * up-to-2 friendly labels joined by " → ", else "Used N tools". Expanded: one row per call with a
 * success/error glyph, its friendly label, and (if present) a one-line result summary. No
 * per-row tap target — the swipe is only the group-level collapse toggle, mirroring iOS.
 */
@Composable
fun CoachToolTraceDisclosure(messageId: String, viewModel: CoachViewModel?) {
    if (viewModel == null) return
    val calls by remember(messageId) { viewModel.toolCallsForMessage(messageId) }
        .collectAsState(initial = emptyList())
    if (calls.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded },
        ) {
            Icon(Icons.Filled.Build, null, tint = PulseColors.textMuted, modifier = Modifier.size(12.dp))
            Text(
                collapsedLabel(calls),
                fontSize = 12.sp, color = PulseColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null, tint = PulseColors.textMuted, modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(com.pulseloop.ui.theme.Motion.EMPHASIS_MS, easing = com.pulseloop.ui.theme.Motion.ease)),
            exit = shrinkVertically(tween(com.pulseloop.ui.theme.Motion.STANDARD_MS, easing = com.pulseloop.ui.theme.Motion.ease)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                calls.forEach { call -> ToolTraceRow(call) }
            }
        }
    }
}

private fun collapsedLabel(calls: List<CoachToolCallEntity>): String {
    if (calls.size > 2) return com.pulseloop.ui.i18n.trf("Used {0} tools", calls.size)
    return calls.joinToString(" → ") { displayLabel(it) }
}

private fun displayLabel(call: CoachToolCallEntity): String =
    call.label.ifBlank { call.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() } }

@Composable
private fun ToolTraceRow(call: CoachToolCallEntity) {
    val isError = call.statusRaw == "error"
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (isError) Icons.Filled.Cancel else Icons.Filled.CheckCircle,
            null,
            tint = if (isError) PulseColors.danger else PulseColors.success,
            modifier = Modifier.size(12.dp).padding(top = 1.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(displayLabel(call), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = PulseColors.textSecondary)
            // The raw tool output (JSON) is a log, not a state: it stays in the usage sheet's data.
        }
    }
}
