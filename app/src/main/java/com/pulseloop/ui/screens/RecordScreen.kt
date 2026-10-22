package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.tr

import com.pulseloop.ui.theme.PulseColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulseloop.service.HeartRateZones
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.ActivityMeta
import kotlinx.coroutines.delay

/**
 * Ported from RecordViews.swift.
 * Live workout recording screen with HR zone, distance, pace, and elapsed time.
 */
@Composable
fun RecordScreen(
    activityName: String = tr("Workout"),
    elapsedSeconds: Int = 0,
    distanceMeters: Double = 0.0,
    heartRate: Int? = null,
    /** The heart rate carries an accuracy-check correction (labelled wherever shown). */
    heartRateAdjusted: Boolean = false,
    spO2: Int? = null,
    isPaused: Boolean = false,
    useGps: Boolean = true,
    hrZone: HeartRateZones.Zone = HeartRateZones.Zone.REST,
    units: UnitSystem = UnitSystem.METRIC,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val elapsed = formatElapsed(elapsedSeconds)
    val metricSet = ActivityMeta.metricSet(activityName)
    // Pace honors the unit preference and rounds seconds before the min:sec split
    // (299.85 s → 5:00, not 4:00) via the shared helper — iOS #43. Cycling shows average
    // speed instead ("min/km reads oddly on a bike" — iOS ActivityMetricSet).
    val paceOrSpeedLabel: String
    val paceOrSpeedValue: String
    if (metricSet.showsSpeed) {
        paceOrSpeedLabel = tr("Speed")
        paceOrSpeedValue = ActivityMeta.speed(distanceMeters, elapsedSeconds, units) ?: "—"
    } else {
        paceOrSpeedLabel = tr("Pace")
        paceOrSpeedValue = ActivityMeta.pace(distanceMeters, elapsedSeconds, units)
            ?.let { it + ActivityMeta.paceUnit(units) } ?: "—"
    }

    // System back used to pop this screen while the workout kept recording, with no way back to
    // it. Ask instead: keep going, or finish (which lands on the summary).
    var showLeaveDialog by remember { mutableStateOf(false) }
    BackHandler { showLeaveDialog = true }
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(tr("Workout in progress")) },
            text = { Text(tr("Your workout is still recording. Finish it now, or keep recording?")) },
            confirmButton = {
                TextButton(onClick = { showLeaveDialog = false; onFinish() }) { Text(tr("Finish")) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text(tr("Keep recording")) }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Activity name + status
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // Weighted (fill = false) so a long translated name wraps instead of pushing PAUSED off.
            Text(
                ActivityMeta.label(activityName), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            )
            if (isPaused) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(tr("PAUSED"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Large elapsed + HR
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(elapsed, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                val hrText = heartRate?.let { "$it bpm" } ?: "-- bpm"
                val zoneColor = when (hrZone) {
                    HeartRateZones.Zone.REST, HeartRateZones.Zone.FAT_BURN -> PulseColors.zoneMint
                    HeartRateZones.Zone.CARDIO -> PulseColors.zoneAmber
                    HeartRateZones.Zone.PEAK -> PulseColors.zoneOrange
                    HeartRateZones.Zone.MAX -> PulseColors.zoneRed
                }
                Text(hrText, style = MaterialTheme.typography.headlineLarge, color = zoneColor)
                if (heartRateAdjusted) {
                    Text("adjusted by your accuracy check", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tr(hrZone.label), style = MaterialTheme.typography.labelMedium, color = zoneColor)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Stat tiles — GPS tiles only for GPS sessions, matching iOS (`if session.useGps`);
        // iOS has no live calories tile, so the permanent "--" placeholder is dropped.
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (useGps && metricSet.showsDistance) {
                item {
                    val distText = if (units == UnitSystem.METRIC && distanceMeters < 1000) {
                        "%.0f m".format(distanceMeters)
                    } else {
                        "%.2f %s".format(UnitConverter.distance(distanceMeters, units), UnitConverter.distanceUnit(units))
                    }
                    StatTile(tr("Distance"), distText)
                }
            }
            if (useGps && (metricSet.showsPace || metricSet.showsSpeed)) {
                item { StatTile(paceOrSpeedLabel, paceOrSpeedValue) }
            }
            item { StatTile(tr("SpO₂"), spO2?.let { "$it%" } ?: "--") }
        }

        Spacer(Modifier.weight(1f))

        // Action buttons
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isPaused) {
                Button(onClick = onResume, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Resume"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                OutlinedButton(onClick = onPause, Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Filled.Pause, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Pause"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(onClick = onFinish, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Finish"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatElapsed(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
