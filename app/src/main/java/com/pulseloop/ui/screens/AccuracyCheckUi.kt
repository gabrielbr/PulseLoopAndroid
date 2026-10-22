package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.health.accuracy.AccuracyCheckController
import com.pulseloop.health.accuracy.ExportUnit
import com.pulseloop.health.accuracy.RingIdentity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → Accuracy check (docs/accuracy-check-plan.md). With no check open: grant read access,
 * scan Health Connect for which apps write what, how often and how late, and start a check
 * against one of them. With one open: its progress or the resume choice, and the report.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccuracyCheckSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val available = remember { HealthConnectSdk.availability(context) == HealthConnectAvailability.AVAILABLE }
    // null until Health Connect has answered, so "Allow access" doesn't flash for someone who granted it.
    var granted by remember { mutableStateOf<Set<String>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReferenceSourceScanner.Result.Done?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf<ReferenceSourceSurvey?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    // Optional-wrapped so "not loaded yet" (null) differs from "no open check" / "no ring" (empty):
    // reading null as "none" flashed the start-a-check card over a running check, and
    // "Pair your ring first" over a paired one, until Room's first emission.
    val openLoad by remember(db) { db.accuracyDao().openFlow().map { java.util.Optional.ofNullable(it) } }.collectAsState(initial = null)
    val open: AccuracyCheckEntity? = openLoad?.orElse(null)
    val history by remember(db) { db.accuracyDao().allFlow() }.collectAsState(initial = emptyList())
    val ringLoad by remember(db) { db.deviceDao().currentFlow().map { java.util.Optional.ofNullable(it) } }.collectAsState(initial = null)
    val ring: com.pulseloop.data.entity.DeviceEntity? = ringLoad?.orElse(null)
    val hasRing = ring?.let { it.id != RingIdentity.DEMO_DEVICE_ID } == true

    fun scan() {
        scanning = true
        note = null
        scope.launch {
            when (val r = runCatching { ReferenceSourceScanner.scan(context) }.getOrNull()) {
                is ReferenceSourceScanner.Result.Done -> result = r
                ReferenceSourceScanner.Result.Unavailable -> note = tr("Health Connect isn't available on this phone.")
                null -> note = tr("The scan failed. Try again in a moment.")
            }
            scanning = false
        }
    }

    LaunchedEffect(Unit) {
        granted = ReferenceSourceScanner.grantedPermissions(context)
        // Foreground is where Health Connect reads are reliable: end a check whose time is up, and
        // pick up any type the reference started writing.
        runCatching { AccuracyCheckController.endIfDue(context) }
        runCatching { AccuracyCheckController.refreshPauseSet(context) }
    }
    val readable = AccuracyCheckPermissions.readable(granted.orEmpty())

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got
        if (AccuracyCheckPermissions.readable(got).isEmpty()) {
            note = tr("No access granted. You can allow it in Health Connect's settings.")
        } else scan()
    }

    val viewingCheck = viewing?.let { id -> history.firstOrNull { it.id == id } }
    if (viewingCheck != null) {
        SettingsSubScreen(title = tr("Accuracy report"), onBack = { viewing = null }) {
            AccuracyReportSection(viewingCheck)
        }
        return
    }

    SettingsSubScreen(title = tr("Accuracy check"), onBack = onBack) {
        val current = open
        when (current?.state) {
            AccuracyCheckEntity.STATE_RUNNING -> RunningCard(current, scope)
            AccuracyCheckEntity.STATE_AWAITING_RESUME -> ResumeCard(current, scope)
            else -> {}
        }
        if (current != null) AccuracyReportSection(current)

        if (openLoad != null && current == null) {
            AccuracyCard {
                Text(tr("Compare your ring with another device"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(
                    tr("Wear the ring and a watch or band you trust for a few days. PulseLoop reads that device's data from " +
                        "Health Connect and shows where the ring agrees and where it falls short."),
                    fontSize = 13.sp, color = PulseColors.textSecondary,
                )
                Text(
                    tr("While a check runs, PulseLoop stops exporting the types the other device also writes, so Health Connect " +
                        "doesn't count your steps, sleep and heart rate twice."),
                    fontSize = 12.sp, color = PulseColors.textMuted,
                )
                if (!available) {
                    Text(tr("Health Connect isn't available on this phone."), fontSize = 12.sp, color = PulseColors.danger)
                } else if (granted != null) {
                    // FlowRow: "Allow more" + "Scan last N days" don't fit one line once translated.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (readable.size < ReferenceMetric.entries.size) {
                            Button(onClick = { launcher.launch(AccuracyCheckPermissions.request) }, enabled = !scanning) {
                                Text(if (readable.isEmpty()) tr("Allow access") else tr("Allow more"))
                            }
                        }
                        if (readable.isNotEmpty()) {
                            OutlinedButton(onClick = { scan() }, enabled = !scanning) { Text(trf("Scan last {0} days", ReferenceSourceScanner.LOOKBACK_DAYS)) }
                        }
                        if (scanning) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                    }
                }
                if (ringLoad != null && !hasRing) Text(tr("Pair your ring first: a check compares it against the other device."), fontSize = 12.sp, color = PulseColors.textMuted)
                note?.let { Text(it, fontSize = 12.sp, color = PulseColors.warning) }
            }

            val r = result
            if (r != null) {
                if (r.sources.isEmpty()) {
                    AccuracyCard {
                        Text(trf("No other app wrote readable data in the last {0} days.", ReferenceSourceScanner.LOOKBACK_DAYS), fontSize = 13.sp, color = PulseColors.textSecondary)
                        Text(
                            tr("If your watch syncs through an app like Health Sync, check that it's set to write to Health Connect."),
                            fontSize = 12.sp, color = PulseColors.textMuted,
                        )
                    }
                }
                r.sources.forEach { src ->
                    SourceCard(context, src, onUse = if (hasRing && src.echo?.looksLikeEcho != true) ({ starting = src }) else null)
                }
                if (r.unreadable.isNotEmpty() || r.failed.isNotEmpty()) {
                    AccuracyCard {
                        if (r.unreadable.isNotEmpty()) {
                            Text(tr("Not allowed to read: ") + r.unreadable.joinToString(", ") { it.label }, fontSize = 12.sp, color = PulseColors.textMuted)
                        }
                        if (r.failed.isNotEmpty()) {
                            Text(tr("Couldn't read: ") + r.failed.joinToString(", ") { it.label }, fontSize = 12.sp, color = PulseColors.warning)
                        }
                    }
                }
                OutlinedButton(onClick = {
                    val text = ReferenceSurveyText.render(r.sources, ReferenceSourceScanner.LOOKBACK_DAYS, { ReferenceAppLabels.label(context, it) }, r.unreadable)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(tr("Accuracy check sources"), text))
                    note = tr("Summary copied. It holds counts and timings only, no readings.")
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("Copy summary")) }
            }
        }

        if (hasRing) AccuracyCorrectionsCard()

        val past = history.filter { it.state == AccuracyCheckEntity.STATE_DONE }
        if (past.isNotEmpty()) {
            AccuracyCard {
                Text(tr("Past checks"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                past.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewing = c.id }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.deviceLabel} vs ${c.referenceLabel}", fontSize = 13.sp, color = PulseColors.textPrimary)
                            Text(AccuracyUiText.span(c), fontSize = 12.sp, color = PulseColors.textMuted)
                        }
                        Text(
                            tr("View"), fontSize = 13.sp, color = PulseColors.accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                    }
                }
            }
        }
    }

    starting?.let { src ->
        StartDialog(
            context = context,
            source = src,
            onDismiss = { starting = null },
            onStart = { label, days ->
                scope.launch {
                    note = when (AccuracyCheckController.start(context, src, label, days)) {
                        is AccuracyCheckController.StartResult.Started -> null
                        AccuracyCheckController.StartResult.AlreadyOpen -> tr("A check is already open.")
                        AccuracyCheckController.StartResult.NoRing -> tr("Pair your ring first.")
                        AccuracyCheckController.StartResult.NothingToCompare -> tr("That app shares nothing PulseLoop can compare.")
                    }
                    starting = null
                }
            },
        )
    }
}

/** Wording shared by the check screens. */
internal object AccuracyUiText {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM")

    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)

    fun span(c: AccuracyCheckEntity): String = "${date(c.startedAt)} – ${date(c.endedAt ?: c.plannedEndAt)}"

    fun dayOf(c: AccuracyCheckEntity, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val total = ((c.plannedEndAt - c.startedAt + 43_200_000L) / 86_400_000L).toInt().coerceAtLeast(1)
        val day = ((now - c.startedAt) / 86_400_000L + 1).toInt().coerceIn(1, total)
        return day to total
    }

    fun endReason(c: AccuracyCheckEntity): String = when (c.endReason) {
        AccuracyCheckEntity.END_RING_CHANGED -> tr("Ended early: a different ring was paired.")
        AccuracyCheckEntity.END_EARLY -> tr("Ended early.")
        else -> "Finished."
    }

    fun units(units: Collection<ExportUnit>): String =
        ExportUnit.entries.filter { it in units }.joinToString(", ") { it.metric.label.lowercase() }.replaceFirstChar { it.uppercase() }
}

@Composable
private fun RunningCard(check: AccuracyCheckEntity, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val paused by remember(db) { db.accuracyDao().allPausedUnitsFlow() }.collectAsState(initial = emptyList())
    val units = paused.filter { it.checkId == check.id }.mapNotNull { ExportUnit.byName(it.unit) }
    var confirmEnd by remember { mutableStateOf(false) }
    val (day, total) = AccuracyUiText.dayOf(check)
    AccuracyCard {
        Text(trf("Accuracy check · day {0} of {1}", day, total), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Text(trf("{0} vs {1}, until {2}.", check.deviceLabel, check.referenceLabel, AccuracyUiText.date(check.plannedEndAt)), fontSize = 13.sp, color = PulseColors.textSecondary)
        Text(trf("Wear both as usual. Health Connect export paused for: {0}.", AccuracyUiText.units(units)), fontSize = 12.sp, color = PulseColors.warning)
        OutlinedButton(onClick = { confirmEnd = true }) { Text(tr("End now")) }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(tr("End the check now?")) },
            text = { Text(tr("The report covers the days so far. You'll then choose what happens to the paused Health Connect export.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    scope.launch { AccuracyCheckController.end(context, AccuracyCheckEntity.END_EARLY) }
                }) { Text(tr("End now")) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(tr("Keep going")) } },
        )
    }
}

@Composable
private fun ResumeCard(check: AccuracyCheckEntity, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    AccuracyCard {
        Text(tr("Check finished: resume Health Connect export"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Text(AccuracyUiText.endReason(check) + " ${AccuracyUiText.span(check)}.", fontSize = 13.sp, color = PulseColors.textSecondary)
        Text(
            trf("During the check, {0} wrote the same types to Health Connect. Exporting the ring's copy of those days now would count them twice.", check.referenceLabel),
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
        Button(onClick = {
            busy = true
            scope.launch { AccuracyCheckController.resolve(context, AccuracyCheckEntity.RESUME_SKIP); busy = false }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Skip these days (recommended)")) }
        OutlinedButton(onClick = {
            busy = true
            scope.launch { AccuracyCheckController.resolve(context, AccuracyCheckEntity.RESUME_EXPORT); busy = false }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Export them too")) }
        Text(
            tr("Export them too if you're taking the other device off and want the ring's full history in Health Connect."),
            fontSize = 11.sp, color = PulseColors.textMuted,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartDialog(
    context: Context,
    source: ReferenceSourceSurvey,
    onDismiss: () -> Unit,
    onStart: (label: String, days: Int) -> Unit,
) {
    val defaultLabel = remember(source) {
        val app = ReferenceAppLabels.label(context, source.packageName)
        val model = source.devices.firstOrNull()?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ").trim() }?.takeIf { it.isNotBlank() }
        if (model != null) "$app ($model)" else app
    }
    var label by remember { mutableStateOf(defaultLabel) }
    var days by remember { mutableStateOf(AccuracyCheckController.DEFAULT_DAYS) }
    val units = source.metrics.map { ExportUnit.of(it.metric) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Start an accuracy check")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it }, singleLine = true,
                    label = { Text(tr("Reference device")) },
                    supportingText = if (source.packageName in ReferenceAppLabels.BRIDGES) {
                        { Text(tr("A bridge app: name the watch behind it.")) }
                    } else null,
                )
                Text(tr("How long"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                // Four translated "N days" chips are wider than a dialog: let them wrap.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccuracyCheckController.DURATIONS_DAYS.forEach { d ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(trf("{0} days", d)) })
                    }
                }
                Text(
                    trf("Compared: {0}. These stop exporting to Health Connect until the check ends and you choose what to do with those days.", AccuracyUiText.units(units)),
                    fontSize = 12.sp,
                )
                Text(tr("Wear both devices as you normally would, including at night."), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(label.trim(), days) }) { Text(tr("Start")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun SourceCard(context: Context, s: ReferenceSourceSurvey, onUse: (() -> Unit)? = null) {
    AccuracyCard {
        Text(ReferenceAppLabels.label(context, s.packageName), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Text(s.packageName, fontSize = 11.sp, color = PulseColors.textMuted)
        if (s.packageName in ReferenceAppLabels.BRIDGES) {
            Text(
                tr("A bridge app: it copies another app's data (for example Huawei Health) into Health Connect."),
                fontSize = 12.sp, color = PulseColors.textSecondary,
            )
        }
        Text(
            if (s.devices.isEmpty()) tr("Device: not recorded")
            else tr("Device: ") + s.devices.joinToString("; ") { ReferenceSurveyText.device(it) },
            fontSize = 12.sp, color = PulseColors.textSecondary,
        )
        s.echo?.takeIf { it.looksLikeEcho }?.let {
            Text(
                trf("{0} of {1} heart-rate samples match your ring exactly. This looks like PulseLoop's own data coming back — check that the app syncs from your watch only, not from Health Connect.", it.matching, it.compared),
                fontSize = 12.sp, color = PulseColors.danger,
            )
        }
        s.metrics.forEach { MetricRow(it) }
        if (onUse != null) Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text(tr("Compare my ring with this")) }
    }
}

@Composable
private fun MetricRow(m: MetricSurvey) {
    val last = Instant.ofEpochMilli(m.lastAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(m.metric.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
        Text(
            trf("{0} samples, {1} · latest {2} · arrives ~{3} late", m.sampleCount, ReferenceSurveyText.cadence(m), last, ReferenceSurveyText.duration(m.medianDeliveryDelayMs)),
            fontSize = 12.sp, color = PulseColors.textSecondary,
        )
        if (m.metric == ReferenceMetric.SLEEP) {
            Text(
                if (ReferenceSurveyText.hasSleepStages(m)) tr("With stages: ") + m.sleepStageTypes.sorted().joinToString(", ") { ReferenceSurveyText.sleepStage(it) }
                else tr("No sleep stages, only time asleep"),
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
    }
}

@Composable
internal fun AccuracyCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * The caption a screen carries while it shows accuracy-corrected figures (plan, Corrections rule
 * 4: labelled wherever shown). [note] is [com.pulseloop.health.accuracy.Corrections.describe].
 */
@Composable
internal fun AdjustedCaption(note: String) {
    Text(
        trf("Adjusted by your accuracy check: {0}. Settings → Accuracy check to change.", note),
        fontSize = 11.sp, color = PulseColors.textMuted,
    )
}

/** The paired ring's accuracy corrections, for a composable that formats stored figures itself. */
@Composable
internal fun rememberAccuracyCorrections(): com.pulseloop.health.accuracy.Corrections {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val tick by remember(db) { com.pulseloop.health.accuracy.AccuracyCorrections.flow(db) }
        .collectAsState(initial = com.pulseloop.health.accuracy.Corrections.NONE)
    val loaded by androidx.compose.runtime.produceState(com.pulseloop.health.accuracy.Corrections.NONE, tick) {
        value = com.pulseloop.health.accuracy.AccuracyCorrections.load(db)
    }
    return loaded
}

/** Today's one-line banner while a check is open. */
@Composable
internal fun AccuracyCheckBanner(c: AccuracyCheckEntity, onOpen: () -> Unit) {
    val text = if (c.state == AccuracyCheckEntity.STATE_RUNNING) {
        val (day, total) = AccuracyUiText.dayOf(c)
        trf("Accuracy check · day {0} of {1} · Health Connect export paused", day, total)
    } else tr("Accuracy report ready · choose what to do with the paused export")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 12.sp, color = PulseColors.warning, modifier = Modifier.weight(1f))
        Text(
            tr("Open"), fontSize = 12.sp, color = PulseColors.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}
