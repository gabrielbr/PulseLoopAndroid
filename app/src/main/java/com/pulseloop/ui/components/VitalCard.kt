package com.pulseloop.ui.components

import androidx.compose.ui.semantics.Role
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.theme.dither
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.viewmodels.VitalCardState

/**
 * The shared dashboard card chrome for every vital. Ported from DesignSystem/VitalCard.swift.
 * Renders the header (accent dot + title + optional Estimated chip), the big value, a color-coded
 * status + trend, the metric body slot (chart or gauge — pass any composable, e.g. the existing
 * Charts.kt components), and an optional "last updated" footer. Tapping opens the metric detail.
 *
 * All numbers/labels come pre-computed in [VitalCardState] — the card runs no threshold math.
 */
@Composable
fun VitalCard(
    state: VitalCardState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Whether to show the big top-left value + status row. Gauge cards set this false because the
     *  gauge already shows the value/status in its center (avoids the duplicated number). */
    showsValueRow: Boolean = true,
    /** Replaces the default "Updated …" footer (used by gauge cards for a context + trend line). */
    footerOverride: String? = null,
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp) // one card radius app-wide (was 24/30dp)
    val footerText = footerOverride ?: state.lastUpdatedText

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 16.dp else 22.dp,
                vertical = if (compact) 16.dp else 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            CardHeader(state, compact, footerText)
            if (showsValueRow && !compact) ValueRow(state)
            content()
        }
    }
}

// ─────────────────────────── Header ───────────────────────────

@Composable
private fun CardHeader(state: VitalCardState, compact: Boolean, footerText: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Accent dot with a soft glow, matching the iOS shadowed circle.
        val accent = state.accentToken.toColor()
        Box(
            Modifier
                .size(8.dp)
                .shadow(5.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .clip(CircleShape)
                .background(accent),
        )
        // The title takes the free width (instead of a Spacer) and the footer is bounded: both
        // used to be unweighted, so a long translated title could squeeze the footer to nothing.
        Text(
            state.title.uppercase(),
            style = com.pulseloop.ui.theme.PulseType.eyebrow,
            
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // "Updated …" lives top-right so it doesn't add a footer row and grow the card height.
        // Suppressed when the ESTIMATED chip is present so the two don't crowd the header.
        if (footerText != null && !compact && !state.isEstimated) {
            Text(
                footerText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        if (state.isEstimated) EstimatedChip()
    }
}

@Composable
private fun EstimatedChip() {
    Text(
        tr("ESTIMATED"),
        style = com.pulseloop.ui.theme.PulseType.eyebrow,
        fontWeight = FontWeight.SemiBold,
        
        color = ZonePalette.ZoneAmber,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .dither(ZonePalette.ZoneAmber, 0.14f)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ─────────────────────── Value + status + trend ───────────────────────

@Composable
private fun ValueRow(state: VitalCardState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                state.valueText,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = com.pulseloop.ui.theme.UbuntuMono,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.unitText != null) {
                Text(
                    state.unitText!!,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                state.statusText.uppercase(),
                style = com.pulseloop.ui.theme.PulseType.eyebrow,
                fontWeight = FontWeight.SemiBold,
                
                color = state.statusToken.toColor(),
                // Wraps beside the trend instead of pushing it off the card.
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.trend.deltaText != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        state.trend.symbol,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.trend.deltaText!!,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.subtitleText != null) {
            Text(
                state.subtitleText!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
