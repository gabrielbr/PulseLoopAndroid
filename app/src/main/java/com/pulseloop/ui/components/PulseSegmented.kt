package com.pulseloop.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.PulseType
import com.pulseloop.ui.calm.CalmColors
import com.pulseloop.ui.calm.CalmType
import com.pulseloop.ui.calm.Look

/**
 * The one segmented control (Sleep's Day/Week/Month/Year, a vital's Today/Week/Month): Material's
 * single-choice segmented row in the app's colours. Two hand-built versions used to drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PulseSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (key, label) ->
            SegmentedButton(
                selected = key == selected,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                // The new look marks the choice with a raised surface and full-strength text rather
                // than a mint fill: mint is for what to tap, and every segment is tappable.
                colors = if (Look.calm) SegmentedButtonDefaults.colors(
                    activeContainerColor = CalmColors.cardRaised,
                    activeContentColor = CalmColors.text,
                    activeBorderColor = CalmColors.textMuted,
                    inactiveContainerColor = CalmColors.card,
                    inactiveContentColor = CalmColors.textSecondary,
                    inactiveBorderColor = CalmColors.textMuted,
                ) else SegmentedButtonDefaults.colors(
                    activeContainerColor = PulseColors.accentSoft,
                    activeContentColor = PulseColors.textPrimary,
                    activeBorderColor = PulseColors.accent,
                    inactiveContainerColor = PulseColors.card,
                    inactiveContentColor = PulseColors.textSecondary,
                    inactiveBorderColor = PulseColors.borderStrong,
                ),
            ) {
                Text(label, style = if (Look.calm) CalmType.bodySmall else PulseType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
