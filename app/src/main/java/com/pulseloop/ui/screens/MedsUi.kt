package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MedDoseEntity
import com.pulseloop.data.entity.MedEntity
import com.pulseloop.service.Meds
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private val PRESET_TIMES = listOf("07:00", "08:00", "12:00", "18:00", "20:00", "22:00")

/** Medications and supplements: today's doses to answer, and the list with a week's adherence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    // null until Room answers, so the "add what you take" intro doesn't flash for someone who has meds.
    val medsLoaded by remember { db.medDao().allFlow() }.collectAsState(initial = null)
    val meds = medsLoaded.orEmpty()
    val answers by remember { db.medDao().dosesSinceFlow(today.minusDays(8).toEpochDay()) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<MedEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val doses = Meds.dosesOn(today, meds, answers, LocalDateTime.now())

    fun answer(d: Meds.Dose, status: String?) = scope.launch {
        if (status == null) db.medDao().deleteDose(d.id)
        else db.medDao().upsertDose(MedDoseEntity(d.id, d.med.id, d.day.toEpochDay(), d.time, status))
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
            // Kept clear of the back button: the translated title is long.
            Text(
                tr("Medications & supplements"), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (medsLoaded != null && meds.isEmpty()) {
                Text(tr("Add what you take on a schedule. PulseLoop asks once per dose and keeps a week's record, and the coach can see it."), fontSize = 14.sp, color = PulseColors.textSecondary)
            }
            if (doses.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("TODAY", fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                    doses.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${d.time} · ${d.med.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                                Text(
                                    listOfNotNull(d.med.dose, d.state.label).joinToString(" · "),
                                    fontSize = 12.sp,
                                    color = when (d.state) {
                                        Meds.State.TAKEN -> PulseColors.accent
                                        Meds.State.MISSED -> PulseColors.warning
                                        else -> PulseColors.textMuted
                                    },
                                )
                            }
                            when (d.state) {
                                // Bounded so long translations can't squeeze the dose name beside them.
                                Meds.State.TAKEN, Meds.State.SKIPPED ->
                                    TextButton(onClick = { answer(d, null) }, modifier = Modifier.widthIn(max = 104.dp)) { Text(tr("Undo"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                else -> {
                                    TextButton(onClick = { answer(d, "skipped") }, modifier = Modifier.widthIn(max = 96.dp)) { Text(tr("Skip"), color = PulseColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    TextButton(onClick = { answer(d, "taken") }, modifier = Modifier.widthIn(max = 96.dp)) { Text(tr("Taken"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
            }
            meds.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)
                        .clickable { editing = m }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + (m.dose?.let { " · $it" } ?: ""), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (m.active) PulseColors.textPrimary else PulseColors.textMuted)
                        val adherence = Meds.adherence(m, answers, today)
                        if (com.pulseloop.service.Postpartum.breastfeeding) {
                            Text(
                                tr("While breastfeeding: check in e-lactancia or LactMed, and ask your clinician"),
                                fontSize = 12.sp, color = PulseColors.accent,
                                modifier = Modifier.clickable {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://www.e-lactancia.org/buscar/?term=" + android.net.Uri.encode(m.name)),
                                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                            )
                        }
                        Text(
                            (if (m.kind == "supplement") tr("Supplement") else tr("Medication")) + " · " + Meds.times(m).joinToString(", ") +
                                (adherence?.let { trf(" · {0}% taken this week", it) } ?: "") + if (!m.active) tr(" · paused") else "",
                            fontSize = 12.sp, color = PulseColors.textMuted,
                        )
                    }
                }
            }
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Add medication or supplement")) }
            Text(
                tr("A record for you and your doctor. PulseLoop doesn't check interactions or doses; ask your pharmacist."),
                fontSize = 11.sp, color = PulseColors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (adding || editing != null) {
        val m = editing
        MedDialog(
            initial = m,
            onDismiss = { adding = false; editing = null },
            onSave = { saved -> scope.launch { db.medDao().upsert(saved) }; adding = false; editing = null },
            onDelete = m?.let { med -> { scope.launch { db.medDao().delete(med.id); db.medDao().deleteDosesFor(med.id) }; editing = null } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedDialog(initial: MedEntity?, onDismiss: () -> Unit, onSave: (MedEntity) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var dose by remember { mutableStateOf(initial?.dose ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: "medication") }
    var times by remember { mutableStateOf(initial?.let { Meds.times(it).toSet() } ?: setOf("08:00")) }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    @Composable
    fun chip(label: String, on: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Text(
            label, fontSize = 13.sp,
            color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
            modifier = Modifier.clip(shape).background(if (on) PulseColors.accent else PulseColors.cardSoft)
                .border(1.dp, if (on) PulseColors.accent else PulseColors.borderSubtle, shape)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("Add") else tr("Edit")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dose, { dose = it.take(40) }, label = { Text(tr("Dose (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chip(tr("Medication"), kind == "medication") { kind = "medication" }
                    chip(tr("Supplement"), kind == "supplement") { kind = "supplement" }
                }
                Text(tr("Times"), fontSize = 13.sp, color = PulseColors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (PRESET_TIMES + times).distinct().sorted().forEach { t ->
                        chip(t, t in times) { times = if (t in times) times - t else times + t }
                    }
                }
                if (initial != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Active"), fontSize = 14.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = active, onCheckedChange = { active = it })
                    }
                    onDelete?.let { del -> TextButton(onClick = del) {
                        Icon(Icons.Filled.Delete, null, tint = PulseColors.danger, modifier = Modifier.size(16.dp))
                        Text(tr(" Delete"), color = PulseColors.danger)
                    } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && times.isNotEmpty(), onClick = {
                onSave((initial ?: MedEntity(name = name.trim(), timesCsv = "")).copy(
                    name = name.trim(), dose = dose.trim().ifEmpty { null }, kind = kind,
                    timesCsv = times.sorted().joinToString(","), active = active,
                ))
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
