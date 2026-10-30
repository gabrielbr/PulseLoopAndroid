package com.pulseloop.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.LabReportEntity
import com.pulseloop.service.LabPrefs
import com.pulseloop.service.LabReports
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.dither
import com.pulseloop.ui.theme.ditherBrush
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

// Lab reports (AGENTS.md, "Lab reports"): any lab report added as a PDF, read by the coach,
// checked by the user, and each marker charted report by report.

private fun dayLabel(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

private fun shortDay(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("MMM yy"))

private fun flagColor(flag: String): Color =
    if (LabReports.isOutside(flag)) PulseColors.warning else if (flag == "NORMAL") PulseColors.accent else PulseColors.textMuted

private fun flagLabel(flag: String): String = when (flag) {
    "HIGH" -> tr("High")
    "LOW" -> tr("Low")
    "ABNORMAL" -> tr("Outside range")
    "NORMAL" -> tr("In range")
    else -> tr("No range")
}

private fun kindLabel(kind: String): String = when (kind) {
    "blood" -> tr("Blood")
    "urine" -> tr("Urine")
    "hormones" -> tr("Hormones")
    "stool" -> tr("Stool")
    else -> tr("Other")
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

private fun openPdf(context: Context, report: LabReportEntity) {
    val name = report.pdfFile ?: return
    val file = LabReports.pdfFile(context, name)
    if (!file.exists()) {
        Toast.makeText(context, tr("This report's PDF isn't on this phone."), Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, tr("No app on this phone opens PDFs."), Toast.LENGTH_SHORT).show()
    }
}

/** A warning line on an amber dither, the app's notice. */
@Composable
private fun LabNotice(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).border(1.dp, PulseColors.warning, RoundedCornerShape(4.dp))
            .dither(PulseColors.warning, 0.12f).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 13.sp, color = PulseColors.textPrimary)
    }
}

@Composable
private fun LabCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Overline(text: String, color: Color = PulseColors.textMuted) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.6.sp, color = color)
}

@Composable
fun LabReportsScreen(onBack: () -> Unit, onMarker: (String) -> Unit, onAskCoach: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val reports by remember(db) { db.labDao().reportsFlow() }.collectAsState(initial = null)
    val results by remember(db) { db.labDao().resultsFlow() }.collectAsState(initial = null)
    val markers = remember(reports, results) { LabReports.markers(reports.orEmpty(), results.orEmpty()) }

    var reading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<LabReports.Draft?>(null) }
    var coachUses by remember { mutableStateOf(LabPrefs.coachUses) }
    var deleting by remember { mutableStateOf<LabReportEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            reading = true
            readError = null
            val id = UUID.randomUUID().toString()
            val file = LabReports.copyIn(context, uri, id)
            if (file == null) {
                readError = tr("Couldn't copy that file.")
            } else when (val r = LabReports.read(context, file, id, displayName(context, uri))) {
                is LabReports.ReadResult.Success -> draft = r.draft
                is LabReports.ReadResult.Failure -> { readError = r.message; file.delete() }
            }
            reading = false
        }
    }

    fun discard() {
        draft?.let { LabReports.pdfFile(context, it.pdfFile).delete() }
        draft = null
    }

    val current = draft
    if (current != null) {
        BackHandler { discard() }
        LabReviewScreen(
            draft = current,
            onChange = { draft = it },
            onDiscard = ::discard,
            onSave = {
                scope.launch {
                    LabReports.save(db, current)
                    draft = null
                    Toast.makeText(context, trf("Saved {0} markers", current.results.size), Toast.LENGTH_SHORT).show()
                }
            },
        )
        return
    }

    SettingsSubScreen(title = tr("Lab reports"), onBack = onBack) {
        val all = reports.orEmpty()
        val latest = all.firstOrNull()
        LabCard {
            Overline(tr("Lab reports"))
            if (all.isEmpty()) {
                Text(tr("Add any lab report as a PDF: blood, urine, hormones or anything else. The coach reads it, you check the values, and every marker gets a chart across your exams."),
                    fontSize = 14.sp, color = PulseColors.textSecondary)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${all.size}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text(trf("reports · {0} markers tracked", markers.size), fontSize = 14.sp, color = PulseColors.textMuted,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                latest?.let { r ->
                    Text(listOfNotNull(trf("Latest: {0}", dayLabel(r.collectedDay)), r.lab, r.kindsCsv.split(",").filter { it.isNotBlank() }.joinToString(", ") { kindLabel(it).lowercase() }.ifBlank { null })
                        .joinToString(" · "), fontSize = 12.sp, color = PulseColors.textMuted)
                }
            }
            Button(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                enabled = !reading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PulseColors.accent, contentColor = PulseColors.onAccent),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Add a lab report (PDF)"))
            }
            if (reading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = PulseColors.accent)
                    Text(tr("The coach is reading the report. This can take a minute."), fontSize = 13.sp, color = PulseColors.textSecondary)
                }
            }
            readError?.let { Text(it, fontSize = 13.sp, color = PulseColors.danger) }
        }

        if (latest != null) {
            val outside = results.orEmpty().filter { it.reportId == latest.id && LabReports.isOutside(it.flag) }
            if (outside.isNotEmpty()) {
                LabNotice(trf("{0} markers outside the lab's range in your latest report. Talk them over with your doctor.", outside.size))
            }
        }

        if (markers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
                    .clickable(role = Role.Button, onClick = onAskCoach).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = PulseColors.accent)
                Column(Modifier.weight(1f)) {
                    Text(tr("Ask the coach for a plan"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text(tr("From these results, with goals you approve"), fontSize = 12.sp, color = PulseColors.textMuted)
                }
                Text("›", fontSize = 22.sp, color = PulseColors.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Let the coach use these results"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text(tr("It reads the values you saved, never the PDF again."), fontSize = 12.sp, color = PulseColors.textMuted)
                }
                Switch(checked = coachUses, onCheckedChange = { coachUses = it; LabPrefs.coachUses = it })
            }

            markers.groupBy { it.panel }.forEach { (panel, list) ->
                Overline(panel)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)) {
                    list.forEachIndexed { i, m ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
                        MarkerRow(m) { onMarker(m.key) }
                    }
                }
            }
        }

        if (all.isNotEmpty()) {
            Overline(tr("Reports"))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)) {
                all.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
                    val count = results.orEmpty().count { it.reportId == r.id }
                    Row(
                        Modifier.fillMaxWidth().clickable { openPdf(context, r) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(dayLabel(r.collectedDay), r.lab).joinToString(" · "), fontSize = 14.sp,
                                fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                            Text(listOfNotNull(
                                r.kindsCsv.split(",").filter { it.isNotBlank() }.joinToString(", ") { kindLabel(it) }.ifBlank { null },
                                trf("{0} markers", count),
                            ).joinToString(" · "), fontSize = 12.sp, color = PulseColors.textMuted)
                        }
                        IconButton(onClick = { deleting = r }) {
                            Icon(Icons.Filled.Delete, tr("Delete report"), tint = PulseColors.textMuted)
                        }
                    }
                }
            }
            Text(tr("Tap a report to open its PDF."), fontSize = 11.sp, color = PulseColors.textMuted)
        }

        Text(tr("Values come from your reports as you checked them. PulseLoop doesn't diagnose; the ranges are the lab's own."),
            fontSize = 11.sp, color = PulseColors.textMuted)
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete this report?")) },
            text = { Text(tr("Its markers and its PDF are removed from this phone.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { LabReports.delete(context, db, r) }
                    deleting = null
                }) { Text(tr("Delete"), color = PulseColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun MarkerRow(m: LabReports.Marker, onClick: () -> Unit) {
    val l = m.latest
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(LabReports.valueLabel(l.valueNum, l.valueText) + (l.unit?.let { " $it" } ?: ""), fontSize = 12.sp, color = PulseColors.textMuted)
        }
        val nums = m.points.mapNotNull { it.valueNum }
        if (nums.size >= 2) {
            val color = flagColor(l.flag)
            Canvas(Modifier.size(width = 56.dp, height = 20.dp)) {
                val lo = nums.min(); val hi = nums.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                val path = Path()
                nums.forEachIndexed { i, v ->
                    val x = size.width * i / (nums.size - 1)
                    val y = size.height - ((v - lo) / span * size.height).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Text(flagLabel(l.flag), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = flagColor(l.flag))
        Text("›", fontSize = 20.sp, color = PulseColors.textMuted)
    }
}

// ── One marker over time ─────────────────────────────────────────────────────

@Composable
fun LabMarkerScreen(markerKey: String, onBack: () -> Unit, onAskCoach: (markerName: String) -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val reports by remember(db) { db.labDao().reportsFlow() }.collectAsState(initial = null)
    val results by remember(db) { db.labDao().resultsFlow() }.collectAsState(initial = null)
    val marker = remember(reports, results) {
        LabReports.markers(reports.orEmpty(), results.orEmpty()).firstOrNull { it.key == markerKey }
    }
    SettingsSubScreen(title = marker?.name ?: tr("Lab reports"), onBack = onBack) {
        val m = marker ?: return@SettingsSubScreen
        val l = m.latest
        LabCard {
            Overline(m.panel)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LabReports.valueLabel(l.valueNum, l.valueText), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(listOfNotNull(l.unit, dayLabel(l.day)).joinToString(" · "), fontSize = 14.sp, color = PulseColors.textMuted,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            val ref = LabReports.referenceLabel(l.refLow, l.refHigh, l.refText, l.unit)
            Text(
                when {
                    LabReports.isOutside(l.flag) && ref != null -> trf("{0} · the lab's range is {1}", flagLabel(l.flag), ref)
                    ref != null -> trf("{0} · the lab's range is {1}", flagLabel(l.flag), ref)
                    else -> tr("The report printed no range for this marker.")
                },
                fontSize = 13.sp, color = flagColor(l.flag),
            )
        }

        if (m.points.count { it.valueNum != null } >= 1) {
            LabCard {
                Overline(tr("Every exam"))
                LabChart(m)
                Text(tr("Shaded: the lab's reference range. Amber points are outside it."), fontSize = 11.sp, color = PulseColors.textMuted)
            }
        }

        LabCard {
            Overline(tr("Values"))
            m.points.reversed().forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel(p.day), fontSize = 14.sp, color = PulseColors.textSecondary, modifier = Modifier.weight(1f))
                    Text(LabReports.valueLabel(p.valueNum, p.valueText) + (p.unit?.let { " $it" } ?: ""), fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = if (LabReports.isOutside(p.flag)) PulseColors.warning else PulseColors.textPrimary)
                }
            }
        }

        if (LabReports.isOutside(l.flag)) {
            LabNotice(trf("Outside the lab's range on {0}. Worth bringing to your doctor.", dayLabel(l.day)))
        }

        OutlinedButton(onClick = { onAskCoach(m.name) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.AutoAwesome, null, tint = PulseColors.accent)
            Spacer(Modifier.width(8.dp))
            Text(tr("Ask the coach for a plan"), color = PulseColors.accent)
        }
    }
}

/**
 * One point per exam, evenly spaced and labelled with its date, over the lab's range drawn as an
 * accent dither band. Points outside the range are amber. A text result has no point.
 */
@Composable
private fun LabChart(m: LabReports.Marker) {
    val pts = m.points.filter { it.valueNum != null }
    val measurer = rememberTextMeasurer()
    val l = m.latest
    val values = pts.map { it.valueNum!! }
    val bounds = values + listOfNotNull(l.refLow, l.refHigh)
    val lo0 = bounds.min(); val hi0 = bounds.max()
    val pad = ((hi0 - lo0).takeIf { it > 0 } ?: (kotlin.math.abs(hi0).takeIf { it > 0 } ?: 1.0)) * 0.15
    val lo = lo0 - pad; val hi = hi0 + pad
    val label = TextStyle(fontSize = 11.sp, color = PulseColors.textPrimary, fontWeight = FontWeight.Bold)
    val tick = TextStyle(fontSize = 10.sp, color = PulseColors.textMuted)
    val description = pts.joinToString(", ") { "${dayLabel(it.day)}: ${LabReports.valueLabel(it.valueNum, null)}" }
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description }) {
        val top = 24.dp.toPx(); val bottom = size.height - 22.dp.toPx(); val side = 24.dp.toPx()
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(i: Int) = if (pts.size == 1) size.width / 2 else side + (size.width - 2 * side) * i / (pts.size - 1)
        // The lab's range from the latest report: open-ended ranges run to the chart's edge.
        if (l.refLow != null || l.refHigh != null) {
            val bandTop = l.refHigh?.let { y(it) } ?: top
            val bandBottom = l.refLow?.let { y(it) } ?: bottom
            ditherBrush(PulseColors.accent, 0.14f)?.let {
                drawRect(it, Offset(0f, bandTop), Size(size.width, (bandBottom - bandTop).coerceAtLeast(1f)))
            }
        }
        if (pts.size >= 2) {
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.valueNum!!)) else path.lineTo(x(i), y(p.valueNum!!)) }
            drawPath(path, PulseColors.accent, style = Stroke(width = 2.dp.toPx()))
        }
        pts.forEachIndexed { i, p ->
            val c = Offset(x(i), y(p.valueNum!!))
            drawCircle(PulseColors.background, 6.dp.toPx(), c)
            drawCircle(if (LabReports.isOutside(p.flag)) PulseColors.warning else PulseColors.accent, 4.5.dp.toPx(), c)
            val v = measurer.measure(LabReports.valueLabel(p.valueNum, null), label)
            drawText(v, topLeft = Offset(c.x - v.size.width / 2, c.y - v.size.height - 8.dp.toPx()))
            val d = measurer.measure(shortDay(p.day), tick)
            drawText(d, topLeft = Offset((c.x - d.size.width / 2).coerceIn(0f, size.width - d.size.width), size.height - d.size.height))
        }
    }
}

// ── Reviewing a report the coach read ───────────────────────────────────────

@Composable
private fun LabReviewScreen(
    draft: LabReports.Draft,
    onChange: (LabReports.Draft) -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Int?>(null) }
    SettingsSubScreen(title = tr("New lab report"), onBack = onDiscard) {
        LabCard {
            Text(draft.fileName ?: tr("Lab report"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(trf("{0} pages · read by the coach", draft.pageCount), fontSize = 12.sp, color = PulseColors.textMuted)
            if (draft.pagesSkipped > 0) {
                Text(trf("Only the first {0} pages were read.", LabReports.MAX_PAGES), fontSize = 12.sp, color = PulseColors.warning)
            }
        }
        LabNotice(tr("Check every value against the PDF before saving. Fix anything the coach misread."))
        LabCard {
            Overline(tr("Report"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Collected on"), fontSize = 12.sp, color = PulseColors.textMuted)
                    Text(draft.collectedOn?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: tr("Not found: set the date"),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (draft.collectedOn == null) PulseColors.warning else PulseColors.textPrimary)
                }
                TextButton(onClick = {
                    val d = draft.collectedOn ?: LocalDate.now()
                    android.app.DatePickerDialog(context, { _, y, mo, day ->
                        onChange(draft.copy(collectedOn = LocalDate.of(y, mo + 1, day)))
                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }) { Text(tr("Change")) }
            }
            OutlinedTextField(
                value = draft.lab, onValueChange = { onChange(draft.copy(lab = it)) },
                label = { Text(tr("Laboratory")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (draft.kinds.isNotEmpty()) {
                Text(draft.kinds.joinToString(", ") { kindLabel(it) }, fontSize = 12.sp, color = PulseColors.textMuted)
            }
        }
        LabCard {
            Overline(trf("{0} markers found", draft.results.size))
            draft.results.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = i }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        LabReports.referenceLabel(r.refLow, r.refHigh, r.refText, r.unit)?.let {
                            Text(trf("Reference: {0}", it), fontSize = 12.sp, color = PulseColors.textMuted)
                        }
                    }
                    Text(LabReports.valueLabel(r.valueNum, r.valueText) + (r.unit.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    if (LabReports.isOutside(r.flag)) Text(flagLabel(r.flag), fontSize = 12.sp, color = PulseColors.warning)
                }
            }
            Text(tr("Tap a row to fix or remove it."), fontSize = 12.sp, color = PulseColors.accent)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr("Discard")) }
            Button(
                onClick = onSave,
                enabled = draft.results.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PulseColors.accent, contentColor = PulseColors.onAccent),
            ) { Text(trf("Save {0} markers", draft.results.size)) }
        }
    }

    editing?.let { index ->
        val r = draft.results.getOrNull(index) ?: return@let
        var name by remember(index) { mutableStateOf(r.name) }
        var value by remember(index) { mutableStateOf(LabReports.valueLabel(r.valueNum, r.valueText).takeIf { it != "—" } ?: "") }
        var unit by remember(index) { mutableStateOf(r.unit) }
        var low by remember(index) { mutableStateOf(r.refLow?.let(LabReports::formatValue) ?: "") }
        var high by remember(index) { mutableStateOf(r.refHigh?.let(LabReports::formatValue) ?: "") }
        fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("Edit marker")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value, { value = it }, label = { Text(tr("Value")) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(unit, { unit = it }, label = { Text(tr("Unit")) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(low, { low = it }, label = { Text(tr("Range low")) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(high, { high = it }, label = { Text(tr("Range high")) }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = num(value)
                    val updated = r.copy(
                        name = name.trim().ifBlank { r.name },
                        valueNum = n, valueText = value.trim(), unit = unit.trim(),
                        refLow = num(low), refHigh = num(high),
                    )
                    onChange(draft.copy(results = draft.results.toMutableList().also { it[index] = updated }))
                    editing = null
                }) { Text(tr("Save")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onChange(draft.copy(results = draft.results.filterIndexed { i, _ -> i != index }))
                        editing = null
                    }) { Text(tr("Remove"), color = PulseColors.danger) }
                    TextButton(onClick = { editing = null }) { Text(tr("Cancel")) }
                }
            },
        )
    }
}
