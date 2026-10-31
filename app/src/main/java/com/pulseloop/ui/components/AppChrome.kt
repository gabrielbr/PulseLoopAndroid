package com.pulseloop.ui.components

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.pulseloop.service.HealthHeadline
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.pulseloop.ui.theme.PulseType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ui.theme.PulseColors
import java.util.Calendar

/**
 * Fixed top header shared by every tab: the greeting as a small uppercase eyebrow over a
 * one-line health status that opens My Health (or the screen the status is about); the
 * connection-status pill plus a settings gear on the right.
 */
@Composable
fun AppHeader(
    state: RingConnectionState,
    batteryPercent: Int?,
    onOpenSettings: () -> Unit,
    /** The ring hung up between syncs on its own (see `IdleLinkPolicy`), not a fault. */
    idle: Boolean = false,
    /** Set while Health Connect stands in for a ring ("Oura · Health Connect"); replaces the BLE state. */
    sourceLabel: String? = null,
    /** The profile's first name, added to the greeting. */
    name: String? = null,
    /** The health line under the greeting (`HealthHeadline`); null while it is first read. */
    headline: HealthHeadline.Headline? = null,
    onOpenHeadline: (String) -> Unit = {},
    /** The pill opens the ring's own screen; the gear beside it opens the Settings hub. */
    onOpenRing: () -> Unit = onOpenSettings,
    /** Landscape: one row, no greeting, so the short viewport keeps its height for content. */
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // No opaque background: the caller layers this header on the glass
            // top bar (hazeEffect in PulseLoopApp), which supplies the material.
            .padding(start = 12.dp, end = 8.dp, top = if (compact) 0.dp else 4.dp, bottom = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The greeting is the eyebrow now; the line below it is the door to My Health.
        // Both are one line, ellipsised, so a long translation or a new headline never
        // changes the header's height.
        val firstName = name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
        val greeting = (if (firstName != null) "${greetingForHour()}, $firstName" else greetingForHour()).uppercase()
        val line = headline?.text ?: tr("My Health")
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    onClickLabel = tr("Open My Health"),
                    role = Role.Button,
                ) { onOpenHeadline(headline?.route ?: "my_health") }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (!compact) Text(
                greeting,
                style = PulseType.eyebrow,
                color = PulseColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dot = when {
                    headline == null -> PulseColors.textMuted
                    headline.attention -> PulseColors.warning
                    headline.kind == HealthHeadline.Kind.LEARNING || headline.kind == HealthHeadline.Kind.NONE -> PulseColors.textMuted
                    else -> PulseColors.success
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Text(
                    line,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Chevron()
            }
        }
        ConnectionStatusPill(
            state = state,
            batteryPercent = batteryPercent,
            idle = idle,
            sourceLabel = sourceLabel,
            // Bounded: the pill is measured before the weighted greeting, so an unbounded
            // "Falha na sincronização" or "Oura · Health Connect" squeezed the greeting to nothing.
            // The capsule stays its size; the 48dp box around it is the touch target.
            modifier = Modifier.widthIn(max = 168.dp),
            touchModifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    onClickLabel = tr("Open ring settings"),
                    role = Role.Button,
                    onClick = onOpenRing,
                ),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, tr("Settings"), tint = PulseColors.textSecondary)
        }
    }
}

/**
 * Colored-dot + label capsule describing the BLE connection state.
 * Ported from RootViews.swift `ConnectionStatusPill`, including the
 * "Disconnected, not Searching…" verdict for idle background scanning (iOS #41).
 */
@Composable
fun ConnectionStatusPill(
    state: RingConnectionState,
    batteryPercent: Int?,
    modifier: Modifier = Modifier,
    /** Disconnected because the ring idled out after a sync — shown neutral, not as an error. */
    idle: Boolean = false,
    sourceLabel: String? = null,
    /** Wraps the capsule; give it the click and the 48dp target so the capsule can stay small. */
    touchModifier: Modifier = Modifier,
) {
    val idleNow = idle && state == RingConnectionState.DISCONNECTED
    // Holds still under "Remove animations"; the label already says "Connecting…".
    val isPulsing = sourceLabel == null && (state == RingConnectionState.CONNECTING || state == RingConnectionState.RECONNECTING) &&
        !rememberReduceMotion()
    val dotColor = if (sourceLabel != null) PulseColors.success else when (state) {
        RingConnectionState.CONNECTED -> PulseColors.success
        // Connecting is amber, not the connected colour: without the pulse (Remove
        // animations) the two would otherwise be identical.
        RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING -> PulseColors.warning
        else -> if (idleNow) PulseColors.textMuted else PulseColors.danger  // scanning/failed/idle/disconnected all read "not connected"
    }
    val label = sourceLabel ?: when (state) {
        RingConnectionState.CONNECTED ->
            if (batteryPercent != null && batteryPercent > 0) trf("Connected · {0}%", batteryPercent) else tr("Connected")
        RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING -> tr("Connecting…")
        RingConnectionState.FAILED -> tr("Sync failed")
        else -> if (idleNow) {
            if (batteryPercent != null && batteryPercent > 0) trf("Idle · {0}%", batteryPercent) else tr("Idle")
        } else tr("Disconnected")
    }
    // Only run the transition while pulsing: an infinite 1f→1f animation still ticks every frame.
    val pulse = if (isPulsing) {
        rememberInfiniteTransition(label = "pill").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "pillDot",
        ).value
    } else 1f

    Box(touchModifier, contentAlignment = Alignment.Center) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (isPulsing) pulse else 1f)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = PulseColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/** A row's trailing "open" mark: a mirrored Material arrow, not a "›" glyph that RTL can't flip. */
@Composable
fun Chevron(tint: Color = PulseColors.textMuted, modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(24.dp),
    )
}

/** Time-of-day greeting, mirroring RootViews.swift `greetingForHour()`. */
fun greetingForHour(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> tr("Good morning")
    in 12..16 -> tr("Good afternoon")
    in 17..21 -> tr("Good evening")
    else -> tr("Good night")
}

/**
 * The iOS card eyebrow: colored dot + letterspaced ALL-CAPS label, with an optional
 * trailing slot (e.g. "Updated 5m ago", a legend, or a chip). Matches VitalCard's
 * header so hand-built tiles read identically to the vitals cards.
 */
@Composable
fun CardEyebrow(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        // The label takes the free width (instead of a Spacer) so a long translated label wraps
        // rather than pushing the trailing slot off the card; the slot is bounded for the same reason.
        Text(
            label.uppercase(),
            style = PulseType.eyebrow,
            color = PulseColors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Box(Modifier.widthIn(max = 180.dp)) { it() } }
    }
}

/** Uppercase letterspaced section header used between card groups (RootViews `SectionHeader`). */
@Composable
fun SectionHeaderCaps(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = PulseColors.textSecondary,
            // Takes the free width (instead of a Spacer) so a long title wraps beside the action.
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}
