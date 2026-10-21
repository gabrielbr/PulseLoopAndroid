package com.pulseloop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.health.accuracy.AccuracyCheckPermissions
import com.pulseloop.health.accuracy.MetricSurvey
import com.pulseloop.health.accuracy.ReferenceAppLabels
import com.pulseloop.health.accuracy.ReferenceMetric
import com.pulseloop.health.accuracy.ReferenceSourceScanner
import com.pulseloop.health.accuracy.ReferenceSourceSurvey
import com.pulseloop.health.accuracy.ReferenceSurveyText
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → Accuracy check, Phase 0 (docs/accuracy-check-plan.md): grant read access, then scan
 * Health Connect for which apps write what, how often and how late. Starting a check comes in a
 * later phase; this screen is useful on its own, and it answers the questions the later phases
 * depend on (does Health Sync name the device, how sparse is its heart rate, how far behind is it).
 */
@Composable
fun AccuracyCheckSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember { HealthConnectSdk.availability(context) == HealthConnectAvailability.AVAILABLE }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReferenceSourceScanner.Result.Done?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scanning = true
        note = null
        scope.launch {
            when (val r = runCatching { ReferenceSourceScanner.scan(context) }.getOrNull()) {
                is ReferenceSourceScanner.Result.Done -> result = r
                ReferenceSourceScanner.Result.Unavailable -> note = "Health Connect isn't available on this phone."
                null -> note = "The scan failed. Try again in a moment."
            }
            scanning = false
        }
    }

    LaunchedEffect(Unit) { granted = ReferenceSourceScanner.grantedPermissions(context) }
    val readable = AccuracyCheckPermissions.readable(granted)

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got
        if (AccuracyCheckPermissions.readable(got).isEmpty()) {
            note = "No access granted. You can allow it in Health Connect's settings."
        } else scan()
    }

    SettingsSubScreen(title = "Accuracy check", onBack = onBack) {
        AccuracyCard {
            Text("Compare your ring with another device", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(
                "Wear the ring and a watch or band you trust for a few days. PulseLoop reads that device's data from " +
                    "Health Connect and shows where the ring agrees and where it falls short.",
                fontSize = 13.sp, color = PulseColors.textSecondary,
            )
            Text(
                "First step: see which apps share what with Health Connect. Starting a check comes in a later update.",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
            if (!available) {
                Text("Health Connect isn't available on this phone.", fontSize = 12.sp, color = PulseColors.danger)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (readable.size < ReferenceMetric.entries.size) {
                        Button(onClick = { launcher.launch(AccuracyCheckPermissions.all) }, enabled = !scanning) {
                            Text(if (readable.isEmpty()) "Allow access" else "Allow more")
                        }
                    }
                    if (readable.isNotEmpty()) {
                        OutlinedButton(onClick = { scan() }, enabled = !scanning) { Text("Scan last ${ReferenceSourceScanner.LOOKBACK_DAYS} days") }
                    }
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            note?.let { Text(it, fontSize = 12.sp, color = PulseColors.warning) }
        }

        val r = result
        if (r != null) {
            if (r.sources.isEmpty()) {
                AccuracyCard {
                    Text("No other app wrote readable data in the last ${ReferenceSourceScanner.LOOKBACK_DAYS} days.", fontSize = 13.sp, color = PulseColors.textSecondary)
                    Text(
                        "If your watch syncs through an app like Health Sync, check that it's set to write to Health Connect.",
                        fontSize = 12.sp, color = PulseColors.textMuted,
                    )
                }
            }
            r.sources.forEach { SourceCard(context, it) }
            if (r.unreadable.isNotEmpty() || r.failed.isNotEmpty()) {
                AccuracyCard {
                    if (r.unreadable.isNotEmpty()) {
                        Text("Not allowed to read: " + r.unreadable.joinToString(", ") { it.label }, fontSize = 12.sp, color = PulseColors.textMuted)
                    }
                    if (r.failed.isNotEmpty()) {
                        Text("Couldn't read: " + r.failed.joinToString(", ") { it.label }, fontSize = 12.sp, color = PulseColors.warning)
                    }
                }
            }
            OutlinedButton(onClick = {
                val text = ReferenceSurveyText.render(r.sources, ReferenceSourceScanner.LOOKBACK_DAYS, { ReferenceAppLabels.label(context, it) }, r.unreadable)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Accuracy check sources", text))
                note = "Summary copied. It holds counts and timings only, no readings."
            }, modifier = Modifier.fillMaxWidth()) { Text("Copy summary") }
        }
    }
}

@Composable
private fun SourceCard(context: Context, s: ReferenceSourceSurvey) {
    AccuracyCard {
        Text(ReferenceAppLabels.label(context, s.packageName), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Text(s.packageName, fontSize = 11.sp, color = PulseColors.textMuted)
        if (s.packageName in ReferenceAppLabels.BRIDGES) {
            Text(
                "A bridge app: it copies another app's data (for example Huawei Health) into Health Connect.",
                fontSize = 12.sp, color = PulseColors.textSecondary,
            )
        }
        Text(
            if (s.devices.isEmpty()) "Device: not recorded"
            else "Device: " + s.devices.joinToString("; ") { ReferenceSurveyText.device(it) },
            fontSize = 12.sp, color = PulseColors.textSecondary,
        )
        s.echo?.takeIf { it.looksLikeEcho }?.let {
            Text(
                "${it.matching} of ${it.compared} heart-rate samples match your ring exactly. This looks like PulseLoop's own " +
                    "data coming back — check that the app syncs from your watch only, not from Health Connect.",
                fontSize = 12.sp, color = PulseColors.danger,
            )
        }
        s.metrics.forEach { MetricRow(it) }
    }
}

@Composable
private fun MetricRow(m: MetricSurvey) {
    val last = Instant.ofEpochMilli(m.lastAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(m.metric.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
        Text(
            "${m.sampleCount} samples, ${ReferenceSurveyText.cadence(m)} · latest $last · " +
                "arrives ~${ReferenceSurveyText.duration(m.medianDeliveryDelayMs)} late",
            fontSize = 12.sp, color = PulseColors.textSecondary,
        )
        if (m.metric == ReferenceMetric.SLEEP) {
            Text(
                if (ReferenceSurveyText.hasSleepStages(m)) "With stages: " + m.sleepStageTypes.sorted().joinToString(", ") { ReferenceSurveyText.sleepStage(it) }
                else "No sleep stages, only time asleep",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
    }
}

@Composable
private fun AccuracyCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
