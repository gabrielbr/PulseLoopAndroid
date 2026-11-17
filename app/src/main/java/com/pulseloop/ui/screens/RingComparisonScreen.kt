package com.pulseloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.AppServices
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.SleepStage
import com.pulseloop.service.ComparisonReadings
import com.pulseloop.service.RingComparison
import com.pulseloop.service.RingComparison.Reading
import com.pulseloop.service.WorkoutHeart
import com.pulseloop.ui.calm.Look
import com.pulseloop.ui.calm.LookLabel
import com.pulseloop.ui.calm.lookCard
import com.pulseloop.ui.components.ActivityMeta
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.util.TimeUtil
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compare rings (docs/ring-comparison.md): a second ring worn alongside the app's own, side by
 * side, day by day. The second ring's readings live in `comparison_readings` and are shown only
 * here; the app's ring keeps feeding everything else.
 */
@Composable
fun RingComparisonScreen(onBack: () -> Unit, onOpenAccuracyCheck: () -> Unit) {
    val context = LocalContext.current
    val services = remember { AppServices.get(context) }
    val comparison = services.comparison
    val second by comparison.state.collectAsState()
    val main by services.bleClient.state.collectAsState()
    var setUp by remember { mutableStateOf(comparison.isSetUp) }
    // Refresh when the second ring connects (setUp turns true on the first connect).
    LaunchedEffect(second.connectionState) { setUp = comparison.isSetUp }

    SettingsSubScreen(title = tr("Compare rings"), onBack = onBack) {
        if (!setUp) {
            FindSecondRing(mainAddress = services.bleClient.lastKnownAddress)
        } else {
            RingsCard(main, second, onStop = {
                comparison.stopComparing()
                setUp = false
            })
            ComparisonDays(comparison.address ?: return@SettingsSubScreen, onOpenAccuracyCheck)
        }
    }
}

/** The empty state and the scan list: one sentence of why, one action, then the rings found. */
@Composable
private fun FindSecondRing(mainAddress: String?) {
    val context = LocalContext.current
    val comparison = remember { AppServices.get(context).comparison }
    val st by comparison.state.collectAsState()
    var scanning by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (scanning) comparison.stopScanning() } }

    Column(Modifier.fillMaxWidth().lookCard().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Wear a second ring to see how the two compare"), style = Look.titleStyle, color = Look.text)
        Text(
            tr("Its readings stay apart: they never change your scores, and nothing from it goes to Health Connect or Strava. Your main ring keeps working as it does now."),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary,
        )
        if (!scanning) {
            PulseButton(PulseButtonStyle.Tonal, onClick = { scanning = true; comparison.startScanning() }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Find a ring"))
            }
        }
    }
    if (scanning) {
        val found = st.discovered.filter { it.id != mainAddress }
        LookLabel(tr("Rings nearby"))
        Column(Modifier.fillMaxWidth().lookCard()) {
            if (found.isEmpty()) {
                Text(
                    tr("Looking… keep the second ring close to the phone, and closed in the vendor's app."),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            found.forEachIndexed { i, ring ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { scanning = false; comparison.pair(ring.id) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ring.name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = Look.text)
                        Text(ring.id, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted)
                    }
                    Text(tr("Compare"), style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = PulseColors.accent)
                }
                if (i < found.lastIndex) HorizontalDivider(color = Look.border)
            }
        }
    }
}

/** The two rings, which colour and line each one is drawn in, and the way out. */
@Composable
internal fun RingsCard(
    main: com.pulseloop.ring.RingBLEClient.BLEState,
    second: com.pulseloop.ring.RingBLEClient.BLEState,
    onStop: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().lookCard()) {
        RingRow(tr("Main ring"), main, MAIN_COLOR, dashed = false)
        HorizontalDivider(color = Look.border)
        RingRow(tr("Comparison ring"), second, SECOND_COLOR, dashed = true)
        HorizontalDivider(color = Look.border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            PulseButton(PulseButtonStyle.Text, onClick = { confirm = true }) {
                Text(tr("Stop comparing"), color = Look.textSecondary)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Stop comparing?")) },
            text = { Text(tr("The comparison ring disconnects and is forgotten here. It isn't unpaired from its own app, and your main ring isn't affected.")) },
            confirmButton = { PulseButton(PulseButtonStyle.Text, onClick = { confirm = false; onStop() }) { Text(tr("Stop comparing")) } },
            dismissButton = { PulseButton(PulseButtonStyle.Text, onClick = { confirm = false }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun RingRow(role: String, st: com.pulseloop.ring.RingBLEClient.BLEState, color: Color, dashed: Boolean) {
    val name = com.pulseloop.wearables.WearableModel.model(st.activeWearableModelID)?.displayName
        ?: st.activeDeviceType?.displayName ?: "—"
    val status = when (st.connectionState) {
        RingConnectionState.CONNECTED -> tr("Connected")
        RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING, RingConnectionState.SCANNING -> tr("Connecting")
        else -> tr("Not connected")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LineSwatch(color, dashed)
        Column(Modifier.weight(1f)) {
            Text(role, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textSecondary)
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Look.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(status, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = if (st.connectionState == RingConnectionState.CONNECTED) PulseColors.success else Look.textSecondary)
            st.batteryPercent?.let { Text("$it%", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted) }
        }
    }
}

/** A short line in the ring's colour, dashed for the comparison ring: colour is never the only cue. */
@Composable
private fun LineSwatch(color: Color, dashed: Boolean) {
    Canvas(Modifier.width(24.dp).height(12.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
        )
    }
}

private val MAIN_COLOR get() = PulseColors.heartRate
private val SECOND_COLOR get() = PulseColors.info

/** Everything both rings recorded on one local day. */
internal class DayData(
    val dayStart: Long,
    val mainHr: List<Reading>,
    val secondHr: List<Reading>,
    val mainSteps: Int?,
    val secondSteps: Int?,
    val mainSleep: Int?,
    val secondSleep: Int?,
    val workouts: List<ActivitySessionEntity>,
)

@Composable
private fun ComparisonDays(address: String, onOpenAccuracyCheck: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppServices.get(context).db }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<DayData?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(address, offset, reloadTick) {
        val dayStart = TimeUtil.startOfTodayLocal() - offset * 86_400_000L
        val dayEnd = dayStart + 86_400_000L
        val dao = db.comparisonDao()
        val mainHr = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, dayStart, dayEnd)
            .filter { it.value > 0 }.map { Reading(it.timestamp, it.value) }
        val secondHr = dao.range(address, MeasurementKind.HEART_RATE.name, dayStart, dayEnd).map { Reading(it.timestamp, it.value) }
        val mainSteps = db.activityDailyDao().byDay(dayStart)?.takeIf { it.source != "mock" && it.source != "demo" }?.steps
        val buckets = dao.range(address, ComparisonReadings.STEPS, dayStart, dayEnd - 1).sumOf { it.value }.toInt()
        val total = dao.range(address, ComparisonReadings.STEPS_TOTAL, dayStart, dayStart).maxOfOrNull { it.value }?.toInt() ?: 0
        val secondSteps = maxOf(buckets, total).takeIf { it > 0 }
        // Sleep is filed under the waking day, as everywhere in the app.
        val mainSleep = db.sleepSessionDao().ringByDay(dayStart)?.totalMinutes?.takeIf { it > 0 }
        val sleepRows = dao.range(address, ComparisonReadings.SLEEP, dayStart - 12 * 3_600_000L, dayEnd)
            .filter { TimeUtil.wakingDayLocal(it.timestamp) == dayStart }
        val secondSleep = sleepRows.count { it.value.toInt() != SleepStage.AWAKE.ordinal }.takeIf { it > 0 }
        val workouts = db.activitySessionDao().finishedStartedBetween(dayStart, dayEnd)
        data = DayData(dayStart, mainHr, secondHr, mainSteps, secondSteps, mainSleep, secondSleep, workouts)
    }
    // New readings arrive while the screen is open (a sync, a workout): look again now and then.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); reloadTick++ } }

    DayHeader(offset, onPrev = { offset++ }, onNext = { if (offset > 0) offset-- })
    val d = data ?: return
    HeartCard(d)
    TotalsCard(d)
    if (d.workouts.isNotEmpty()) WorkoutsCard(d)
    Text(
        tr("Neither ring is known to be right: two rings can agree and both be off. To check one against a watch, use the accuracy check."),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Look.textMuted,
    )
    PulseButton(PulseButtonStyle.Text, onClick = onOpenAccuracyCheck) { Text(tr("Open the accuracy check")) }
}

@Composable
internal fun DayHeader(offset: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    val label = when (offset) {
        0 -> tr("Today")
        1 -> tr("Yesterday")
        else -> com.pulseloop.ui.components.PhoneFormat.date("EEEMMMd", TimeUtil.startOfTodayLocal() - offset * 86_400_000L)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous day"), tint = Look.text) }
        Text(label, style = Look.titleStyle, color = Look.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = onNext, enabled = offset > 0) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next day"), tint = if (offset > 0) Look.text else Look.border)
        }
    }
}

@Composable
internal fun HeartCard(d: DayData) {
    val end = minOf(d.dayStart + 86_400_000L, System.currentTimeMillis())
    val slot = 5 * 60_000L
    val mainCov = RingComparison.coverage(d.mainHr, d.dayStart, end, slot)
    val secondCov = RingComparison.coverage(d.secondHr, d.dayStart, end, slot)
    val agree = RingComparison.agreement(d.mainHr, d.secondHr, 3 * 60_000L)
    Column(Modifier.fillMaxWidth().lookCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Heart rate"), style = Look.titleStyle, color = Look.text)
        if (d.mainHr.isEmpty() && d.secondHr.isEmpty()) {
            Text(tr("No heart rate from either ring on this day."), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary)
            return@Column
        }
        DualChart(d.mainHr, d.secondHr, d.dayStart, d.dayStart + 86_400_000L)
        Row(Modifier.fillMaxWidth()) {
            Text("0:00", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = Look.textMuted, modifier = Modifier.weight(1f))
            Text("24:00", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = Look.textMuted)
        }
        CompareHeader()
        CompareRow(tr("Average"), RingComparison.averageHeartRate(d.mainHr, d.dayStart, end)?.let { "${it.roundToInt()}" }, RingComparison.averageHeartRate(d.secondHr, d.dayStart, end)?.let { "${it.roundToInt()}" }, "bpm")
        CompareRow(tr("Hours with readings"), hours(mainCov), hours(secondCov), null)
        val gap = agree.medianAbsDiff
        Text(
            if (gap == null) tr("The two rings never measured within 3 minutes of each other, so they can't be compared reading by reading yet.")
            else {
                val mean = agree.meanDiff ?: 0.0
                val lean = when {
                    abs(mean) < 1.0 -> tr("neither reads higher on average")
                    mean > 0 -> trf("the comparison ring reads {0} higher on average", "${mean.roundToInt()}")
                    else -> trf("the comparison ring reads {0} lower on average", "${(-mean).roundToInt()}")
                }
                trf("When both measured within 3 minutes ({0} times), they were typically {1} bpm apart, and {2}.", "${agree.pairs}", "${gap.roundToInt()}", lean)
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.textSecondary,
        )
    }
}

private fun hours(c: RingComparison.Coverage): String? =
    if (c.slots == 0) null else "%.1f h".format(c.slotsWithReadings * 5 / 60.0)

@Composable
internal fun TotalsCard(d: DayData) {
    Column(Modifier.fillMaxWidth().lookCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompareHeader()
        CompareRow(tr("Steps"), d.mainSteps?.let { "%,d".format(it) }, d.secondSteps?.let { "%,d".format(it) }, null)
        CompareRow(tr("Asleep (night before)"), d.mainSleep?.let { minutesLabel(it) }, d.secondSleep?.let { minutesLabel(it) }, null)
    }
}

private fun minutesLabel(m: Int) = "${m / 60}h ${"%02d".format(m % 60)}"

@Composable
internal fun WorkoutsCard(d: DayData) {
    Column(Modifier.fillMaxWidth().lookCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Workouts"), style = Look.titleStyle, color = Look.text)
        CompareHeader()
        d.workouts.forEach { w ->
            val end = w.endedAt ?: return@forEach
            fun avg(r: List<Reading>) = WorkoutHeart.summary(r.filter { it.atMs in w.startedAt..end }.map { WorkoutHeart.Sample(it.atMs, it.value) }, end, null)
            val a = avg(d.mainHr); val b = avg(d.secondHr)
            val title = ActivityMeta.label(w.type) + " · " + com.pulseloop.ui.components.PhoneFormat.clock(
                android.text.format.DateFormat.is24HourFormat(LocalContext.current), w.startedAt,
            )
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Look.text, modifier = Modifier.padding(top = 4.dp))
            CompareRow(tr("Average"), a?.average?.let { "${it.roundToInt()}" }, b?.average?.let { "${it.roundToInt()}" }, "bpm")
            CompareRow(tr("Highest"), a?.max?.let { "${it.roundToInt()}" }, b?.max?.let { "${it.roundToInt()}" }, "bpm")
            val mins = ((end - w.startedAt) / 60_000.0)
            CompareRow(tr("Minutes with readings"),
                a?.let { "${it.coveredMinutes.roundToInt()}/${mins.roundToInt()}" },
                b?.let { "${it.coveredMinutes.roundToInt()}/${mins.roundToInt()}" }, null)
        }
    }
}

/** Column heads: each ring's line, as drawn in the rings card above, so a long ring or column
 *  name can't truncate here (Portuguese "Comparação" at 1.3× did). TalkBack reads the names. */
@Composable
private fun CompareHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f))
        Box(Modifier.width(VALUE_W).semantics { contentDescription = tr("Main ring") }, contentAlignment = Alignment.CenterEnd) { LineSwatch(MAIN_COLOR, false) }
        Box(Modifier.width(VALUE_W).semantics { contentDescription = tr("Comparison ring") }, contentAlignment = Alignment.CenterEnd) { LineSwatch(SECOND_COLOR, true) }
    }
}

private val VALUE_W = 96.dp

@Composable
private fun CompareRow(label: String, main: String?, second: String?, unit: String?) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$label: " + tr("main") + " ${main ?: "—"}, " + tr("comparison") + " ${second ?: "—"}" + (unit?.let { " $it" } ?: "")
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = Look.text, modifier = Modifier.weight(1f))
        Text(main ?: "—", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Look.text, textAlign = TextAlign.End, modifier = Modifier.width(VALUE_W))
        Text(second ?: "—", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = Look.text, textAlign = TextAlign.End, modifier = Modifier.width(VALUE_W))
    }
}

/** Both rings' heart rate over the day, in 5-minute means; a gap over 15 minutes breaks a line. */
@Composable
private fun DualChart(a: List<Reading>, b: List<Reading>, start: Long, end: Long) {
    fun bucket(r: List<Reading>) = r.groupBy { (it.atMs - start) / 300_000L }.toSortedMap()
        .map { (k, xs) -> (k * 5.0) to xs.map { it.value }.average() }
    val pa = bucket(a); val pb = bucket(b)
    val all = (pa + pb).map { it.second }
    val lo = (all.minOrNull() ?: 50.0) - 5; val hi = (all.maxOrNull() ?: 100.0) + 5
    val span = (end - start) / 60_000.0
    val main = MAIN_COLOR; val second = SECOND_COLOR; val grid = Look.border
    Canvas(
        Modifier.fillMaxWidth().height(160.dp).semantics {
            contentDescription = tr("Heart rate from both rings over the day")
        },
    ) {
        fun x(m: Double) = (m / span * size.width).toFloat()
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        fun draw(points: List<Pair<Double, Double>>, color: Color, dashed: Boolean) {
            val path = Path()
            points.forEachIndexed { i, (m, v) ->
                if (i == 0 || m - points[i - 1].first > 15) path.moveTo(x(m), y(v)) else path.lineTo(x(m), y(v))
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            if (points.size == 1) drawCircle(color, 3.dp.toPx(), Offset(x(points[0].first), y(points[0].second)))
        }
        draw(pa, main, false)
        draw(pb, second, true)
    }
}
