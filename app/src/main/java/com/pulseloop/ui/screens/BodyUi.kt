package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.tr

import com.pulseloop.ui.components.switchRow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.BodyMeasurementEntity
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectBodyImport
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitConverter
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class BodySnapshot(
    val weight: BodyMeasurementEntity?,
    val bodyFatPct: Double?,
    val leanMassKg: Double?,
    val heightCm: Double?,
    val trend: List<BodyMeasurementEntity>,
)

/**
 * Profile screen card: sync weight, height and body composition from a smart scale through Health
 * Connect, and show what came in (latest weigh-in, body fat, lean mass, BMI, a 90-day weight line).
 * [onUpdated] lets the profile editor above reload the values the import just changed.
 */
@Composable
fun HealthConnectBodyCard(onUpdated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val available = remember { HealthConnectSdk.availability(context) == HealthConnectAvailability.AVAILABLE }
    var enabled by remember { mutableStateOf(HealthConnectBodyImport.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    // Stored in kg; shown in the user's unit setting (BMI still computes from kg).
    val units = remember { ApiKeyStore(context) }.resolvedUnitSystem
    val unit = UnitConverter.weightUnit(units)

    val snapshot by produceState<BodySnapshot?>(null, refresh) {
        value = runCatching {
            val dao = db.bodyMeasurementDao()
            val profile = db.userProfileDao().get()
            BodySnapshot(
                weight = dao.latest(BodyMeasurementEntity.TYPE_WEIGHT),
                bodyFatPct = profile?.bodyFatPct,
                leanMassKg = profile?.leanMassKg,
                heightCm = profile?.heightCm,
                trend = dao.since(BodyMeasurementEntity.TYPE_WEIGHT, System.currentTimeMillis() - 90L * 86_400_000L),
            )
        }.getOrNull()
    }

    fun importNow() {
        busy = true
        scope.launch {
            val n = runCatching { HealthConnectBodyImport.run(context) }.getOrDefault(0)
            busy = false
            note = if (n == 0) tr("No body measurements found in Health Connect yet.") else null
            refresh++
            onUpdated()
        }
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val any = granted.any { it in HealthConnectBodyImport.permissions }
        enabled = any
        HealthConnectBodyImport.setEnabled(context, any)
        if (any) importNow() else note = tr("No access granted. You can allow it in Health Connect's settings.")
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.switchRow(checked = enabled, enabled = available && !busy, onCheckedChange = { on ->
                    if (on) launcher.launch(HealthConnectBodyImport.permissions)
                    else { enabled = false; HealthConnectBodyImport.setEnabled(context, false) }
                }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Sync from your scale"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(
                    tr("Weight, height and body composition from Health Connect (Fitdays and other scale apps)."),
                    fontSize = 12.sp, color = PulseColors.textSecondary,
                )
            }
            Switch(checked = enabled, enabled = available && !busy, onCheckedChange = null)
        }
        if (!available) {
            Text(tr("Health Connect isn't available on this phone."), fontSize = 12.sp, color = PulseColors.textMuted)
        }

        val s = snapshot
        if (enabled && s?.weight != null) {
            val w = s.weight
            val whenText = Instant.ofEpochMilli(w.measuredAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("%.1f".format(UnitConverter.weight(w.value, units)), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("$unit · $whenText", fontSize = 12.sp, color = PulseColors.textMuted, modifier = Modifier.padding(bottom = 5.dp))
            }
            val facts = buildList {
                s.bodyFatPct?.let { add("Body fat ${"%.1f".format(it)}%") }
                s.leanMassKg?.let { add("Lean ${"%.1f".format(UnitConverter.weight(it, units))} $unit") }
                s.heightCm?.takeIf { it > 0 }?.let { h -> add("BMI ${"%.1f".format(w.value / ((h / 100) * (h / 100)))}") }
            }
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 13.sp, color = PulseColors.textSecondary)
            if (s.trend.size >= 2) WeightLine(s.trend, units)
            Text("From ${w.source}", fontSize = 11.sp, color = PulseColors.textMuted)
        }
        note?.let { Text(it, fontSize = 12.sp, color = PulseColors.textMuted) }
        if (enabled) {
            TextButton(onClick = { importNow() }, enabled = !busy) { Text(if (busy) tr("Syncing…") else tr("Sync now")) }
        }
    }
}

/** A plain 90-day weight line. */
@Composable
private fun WeightLine(points: List<BodyMeasurementEntity>, units: UnitSystem) {
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val t0 = points.first().measuredAt
    val t1 = points.last().measuredAt
    val unit = UnitConverter.weightUnit(units)
    fun shown(kg: Double) = "%.1f".format(UnitConverter.weight(kg, units))
    val summary = "Weight, last 90 days: latest ${shown(points.last().value)} $unit, " +
        "range ${shown(min)}–${shown(max)} $unit, ${points.size} weigh-ins"
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = summary }) {
        val range = (max - min).takeIf { it > 0.2 } ?: 0.2
        fun x(t: Long) = if (t1 == t0) size.width else (t - t0).toFloat() / (t1 - t0) * size.width
        fun y(v: Double) = size.height - ((v - min) / range * size.height * 0.85 + size.height * 0.075).toFloat()
        val path = Path()
        points.forEachIndexed { i, p ->
            val o = Offset(x(p.measuredAt), y(p.value))
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, PulseColors.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
    Row(Modifier.fillMaxWidth()) {
        Text(shown(points.first().value), fontSize = 11.sp, color = PulseColors.textMuted, modifier = Modifier.weight(1f))
        val delta = UnitConverter.weight(points.last().value - points.first().value, units)
        Text(
            "${if (delta >= 0) "+" else ""}${(delta * 10).roundToInt() / 10.0} $unit in 90 days",
            fontSize = 11.sp, color = PulseColors.textMuted,
        )
    }
}
