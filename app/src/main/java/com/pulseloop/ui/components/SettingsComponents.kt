package com.pulseloop.ui.components

import com.pulseloop.ui.theme.dither
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.calm.CalmColors
import com.pulseloop.ui.calm.Look
import com.pulseloop.ui.calm.lookCard

/**
 * Ported from SettingsComponents.swift (iOS #49).
 * One tappable Settings category row plus the grouped-section card that hosts them.
 */
data class SettingsRowItem(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val trailingValue: String? = null,
    val action: () -> Unit,
)

/**
 * An uppercase section header plus a rounded card wrapping its rows with hairline dividers.
 * Renders nothing when [rows] is empty, so a fully gated-off group leaves no empty header.
 */
@Composable
fun SettingsSection(title: String, rows: List<SettingsRowItem>) {
    if (rows.isEmpty()) return
    val calm = Look.calm
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (calm) {
            // The new look: a sentence-case group name, and none at all when the group's one row
            // already says it ("Notifications" over "Notifications" said it twice).
            if (!(rows.size == 1 && rows[0].title == title)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Look.textSecondary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        } else {
            // The design system's one tracked caps label (PulseType.eyebrow), not a hand-rolled one.
            Text(
                title.uppercase(),
                style = com.pulseloop.ui.theme.PulseType.eyebrow,
                color = PulseColors.textSecondary,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        Column(Modifier.fillMaxWidth().lookCard()) {
            rows.forEachIndexed { index, row ->
                SettingsRow(row)
                if (index < rows.size - 1) {
                    HorizontalDivider(
                        color = Look.border,
                        thickness = if (calm) 1.dp else 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp), // align with the row's text column
                    )
                }
            }
        }
    }
}

/**
 * A single row: icon tile, title, optional trailing value, chevron. The old look tints each tile
 * in its own colour; the new look draws one quiet raised tile, since the colours carried no meaning.
 */
@Composable
fun SettingsRow(item: SettingsRowItem) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val calm = Look.calm

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) (if (calm) CalmColors.cardRaised else PulseColors.elevated) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = item.action)
            .heightIn(min = if (calm) 56.dp else 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(if (calm) 8.dp else 4.dp))
                .then(if (calm) Modifier.background(CalmColors.cardRaised) else Modifier.dither(item.tint, 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, Modifier.size(20.dp), tint = if (calm) Look.textSecondary else item.tint)
        }

        // The title takes the free width (it used to be unweighted beside a weighted Spacer, so a
        // long translated title and a long trailing value — a model slug — fought for the row).
        Text(
            item.title,
            fontSize = 16.sp,
            color = Look.text,
            modifier = Modifier.weight(1f),
        )

        item.trailingValue?.let { trailing ->
            Text(
                trailing,
                fontSize = 14.sp,
                color = Look.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }

        Chevron()
    }
}
