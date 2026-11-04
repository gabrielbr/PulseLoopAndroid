package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.components.PulseButton

import com.pulseloop.ui.i18n.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.pulseloop.AppServices
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.health.accuracy.ReferenceSourceScanner
import com.pulseloop.health.accuracy.RingIdentity
import com.pulseloop.health.source.HealthConnectSource
import com.pulseloop.health.source.HealthConnectSourceImporter
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Settings → Health Connect as your data source (docs/health-connect-source.md). */
@Composable
fun HealthConnectSourceScreen(onBack: () -> Unit) {
    SettingsSubScreen(title = tr("Use without a ring"), onBack = onBack) {
        HealthConnectSourceContent()
    }
}

/**
 * Pick the app whose data PulseLoop should use — Oura, Samsung Health, Fitbit — then import. Also
 * the onboarding step for people who don't have a PulseLoop ring ([onEnabled] moves it on).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthConnectSourceContent(onEnabled: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember { AppServices.get(context) }
    val available = remember { HealthConnectSdk.availability(context) == HealthConnectAvailability.AVAILABLE }
    // null until Room answers, so a paired ring isn't briefly read as "no ring" (which flashed the
    // Allow-access / app-picker path before the "a ring is paired" warning replaced it).
    val ringLoad by remember(services.db) { services.db.deviceDao().currentFlow().map { java.util.Optional.ofNullable(it) } }
        .collectAsState(initial = null)
    val ring: com.pulseloop.data.entity.DeviceEntity? = ringLoad?.orElse(null)
    val hasRing = ring?.let { it.id != RingIdentity.DEMO_DEVICE_ID } == true || services.bleClient.hasLastKnownRing
    var enabled by remember { mutableStateOf(HealthConnectSource.isEnabled(context)) }
    var source by remember { mutableStateOf(HealthConnectSource.sourcePackage(context)) }
    // null until Health Connect has answered: an empty set would show "Allow access" for a moment
    // to someone who already granted it.
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ReferenceSourceScanner.Result.Done?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun doScan() {
        scanning = true
        note = null
        scope.launch {
            when (val r = runCatching { ReferenceSourceScanner.scan(context) }.getOrNull()) {
                is ReferenceSourceScanner.Result.Done -> scan = r
                ReferenceSourceScanner.Result.Unavailable -> note = tr("Health Connect isn't available on this phone.")
                null -> note = tr("Couldn't read Health Connect. Try again in a moment.")
            }
            scanning = false
        }
    }

    fun importNow() {
        importing = true
        note = null
        scope.launch {
            note = when (val r = runCatching { HealthConnectSourceImporter.run(context) }.getOrNull()) {
                is HealthConnectSourceImporter.Outcome.Done -> trf("Imported {0} readings.", "%,d".format(r.stored))
                // The importer's message lists record types in English: logcat, not the screen.
                is HealthConnectSourceImporter.Outcome.Failed -> {
                    android.util.Log.w("HcSource", r.message)
                    tr("Couldn't read most of your data from Health Connect. Try again in a moment.")
                }
                HealthConnectSourceImporter.Outcome.NoPermission -> tr("PulseLoop isn't allowed to read Health Connect any more.")
                HealthConnectSourceImporter.Outcome.Unavailable -> tr("Health Connect isn't available on this phone.")
                else -> tr("The import failed. Try again in a moment.")
            }
            importing = false
            refresh++
        }
    }

    LaunchedEffect(Unit) {
        val got = ReferenceSourceScanner.grantedPermissions(context)
        granted = got
        if (got.any { it in HealthConnectSource.readPermissions } && !enabled) doScan()
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got
        if (got.none { it in HealthConnectSource.readPermissions }) {
            note = tr("No access granted. You can allow it in Health Connect's settings.")
        } else if (!enabled) doScan()
    }
    val loaded = granted != null && ringLoad != null
    val canRead = granted.orEmpty().any { it in HealthConnectSource.readPermissions }
    val missing = HealthConnectSource.request - granted.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccuracyCard {
            Text(tr("Use the device you already wear"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(
                tr("No PulseLoop ring? If your Oura, Galaxy Ring, Fitbit or watch app writes to Health Connect, PulseLoop can " +
                    "read it from there: sleep, heart rate, HRV, blood oxygen, steps, calories, workouts and periods. " +
                    "Every screen, the coach and your insights then work from that data."),
                fontSize = 14.sp, color = PulseColors.textSecondary,
            )
            Text(
                tr("PulseLoop reads only the app you choose, and doesn't write that data back to Health Connect."),
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
            if (!available) {
                Text(tr("Health Connect isn't available on this phone."), fontSize = 12.sp, color = PulseColors.danger)
            } else if (!loaded) {
                // Still reading the ring row and the grants: show neither the warning nor the button yet.
            } else if (hasRing && !enabled) {
                Text(
                    tr("A ring is paired. Forget it in Settings → Ring & sync first: the two would count the same steps and nights."),
                    fontSize = 12.sp, color = PulseColors.warning,
                )
            } else if (!canRead) {
                PulseButton(PulseButtonStyle.Tonal, onClick = { launcher.launch(HealthConnectSource.request) }) { Text(tr("Allow access")) }
            }
        }

        if (enabled && source != null) {
            val pkg = source!!
            AccuracyCard {
                Text(trf("Using {0}", HealthConnectSource.label(context, pkg)), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                val last = remember(refresh) { HealthConnectSource.lastImportAt(context) }
                val count = remember(refresh) { HealthConnectSource.lastImportCount(context) }
                Text(
                    last?.let {
                        trf("Last import {0}", localDateTime(context, it)) +
                            trf(" · {0} readings", "%,d".format(count))
                    } ?: tr("Not imported yet"),
                    fontSize = 14.sp, color = PulseColors.textSecondary,
                )
                Text(
                    tr("PulseLoop checks for new data every hour and whenever you open the app. A night usually appears once ") +
                        trf("{0} has synced it in the morning.", HealthConnectSource.label(context, pkg)),
                    fontSize = 12.sp, color = PulseColors.textMuted,
                )
                if (canRead && !HealthConnectSource.canReadInBackground(granted.orEmpty())) {
                    Text(tr("Allow background access so imports also run while PulseLoop is closed."), fontSize = 12.sp, color = PulseColors.warning)
                }
                // FlowRow: the two translated button labels plus the spinner overflow a phone's card width.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PulseButton(PulseButtonStyle.Tonal, onClick = { importNow() }, enabled = !importing && canRead) { Text(tr("Import now")) }
                    if (missing.isNotEmpty()) {
                        PulseButton(PulseButtonStyle.Outlined, onClick = { launcher.launch(HealthConnectSource.request) }) { Text(tr("Allow more")) }
                    }
                    if (importing) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                }
                PulseButton(PulseButtonStyle.Text, onClick = {
                    HealthConnectSource.disable(context)
                    enabled = false
                    doScan()
                }) { Text(tr("Stop using Health Connect"), color = PulseColors.danger) }
            }
        } else if (loaded && canRead && !hasRing) {
            val r = scan
            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Looking for apps writing to Health Connect…"), fontSize = 14.sp, color = PulseColors.textSecondary)
                }
            } else if (r != null) {
                val usable = r.sources.filter { it.echo?.looksLikeEcho != true }
                if (usable.isEmpty()) {
                    AccuracyCard {
                        Text(trf("No app wrote health data in the last {0} days.", ReferenceSourceScanner.LOOKBACK_DAYS), fontSize = 14.sp, color = PulseColors.textSecondary)
                        Text(
                            tr("In the Oura app: Settings → Health Connect, and turn on sharing. Then come back and look again."),
                            fontSize = 12.sp, color = PulseColors.textMuted,
                        )
                        PulseButton(PulseButtonStyle.Outlined, onClick = { doScan() }) { Text(tr("Look again")) }
                    }
                }
                usable.forEach { src ->
                    AccuracyCard {
                        Text(HealthConnectSource.label(context, src.packageName), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        Text(src.metrics.joinToString(", ") { it.metric.label }, fontSize = 14.sp, color = PulseColors.textSecondary)
                        // Tonal: one card per app, so filled would put several mint buttons on one screen.
                        PulseButton(PulseButtonStyle.Tonal, onClick = {
                            HealthConnectSource.enable(context, src.packageName)
                            enabled = true
                            source = src.packageName
                            // enable() queues the first import as background work, which outlives this
                            // screen; onboarding moves on at once, settings shows the pass running here.
                            if (onEnabled != null) onEnabled() else importNow()
                        }, modifier = Modifier.fillMaxWidth()) { Text(trf("Use {0}", HealthConnectSource.label(context, src.packageName))) }
                    }
                }
            }
        }
        note?.let { Text(it, fontSize = 12.sp, color = PulseColors.textMuted) }
    }
}

/** Settings' top card while Health Connect stands in for a ring: which app, and when it last imported. */
@Composable
fun HealthConnectSourceHeroCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val pkg = HealthConnectSource.sourcePackage(context) ?: return
    val last = HealthConnectSource.lastImportAt(context)
    // Same frame as the ring's hero card (8dp radius, hairline, chevron), so the hub reads the same
    // whichever source feeds it.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("DATA SOURCE"), style = com.pulseloop.ui.theme.PulseType.eyebrow, color = PulseColors.textSecondary)
            Text(trf("{0} via Health Connect", HealthConnectSource.label(context, pkg)), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(
                last?.let { trf("Last import {0}", localDateTime(context, it)) }
                    ?: tr("Waiting for the first import"),
                fontSize = 14.sp, color = PulseColors.textSecondary,
            )
        }
        com.pulseloop.ui.components.Chevron()
    }
}

/** Date and time in the phone's own format: its locale, and its 12/24-hour setting. */
private fun localDateTime(context: android.content.Context, millis: Long): String {
    val date = java.util.Date(millis)
    return android.text.format.DateFormat.getMediumDateFormat(context).format(date) + ", " +
        android.text.format.DateFormat.getTimeFormat(context).format(date)
}
