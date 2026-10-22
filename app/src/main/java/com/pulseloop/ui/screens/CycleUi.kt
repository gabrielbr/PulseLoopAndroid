package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.pulseloop.data.entity.PeriodEntity
import com.pulseloop.service.Cycle
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun PeriodEntity.toPeriod() = Cycle.Period(LocalDate.ofEpochDay(startDay), endDay?.let { LocalDate.ofEpochDay(it) })

/** Cycle tracking: log period dates, see the day and phase, and an estimate of the next one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    // null until Room answers: read as empty, the card said "log your period to start" and the
    // button offered "period started today" to someone mid-period, then both changed a frame later.
    val periodsLoaded by remember { db.periodDao().allFlow() }.collectAsState(initial = null)
    val loaded = periodsLoaded != null
    val periods = periodsLoaded.orEmpty()
    val today = remember { LocalDate.now() }
    val status = Cycle.status(periods.map { it.toPeriod() }, today)
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    var picking by remember { mutableStateOf<String?>(null) } // "start" or "end"
    var pastStart by remember { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
            Text(
                tr("Cycle"), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!loaded) {
                    // Holds roughly the card's height until the dates are read.
                    Spacer(Modifier.height(72.dp))
                } else if (status == null) {
                    Text(tr("Log your period to start"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text(tr("PulseLoop shows the day of your cycle and its phase from the dates you log, next to your sleep and recovery."), fontSize = 13.sp, color = PulseColors.textSecondary)
                } else {
                    Text(trf("Day {0} · {1}", status.cycleDay, status.phase.label), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text(status.phase.note, fontSize = 13.sp, color = PulseColors.textSecondary)
                    val paused = com.pulseloop.service.Postpartum.pausesCycle(
                        com.pulseloop.service.Postpartum.active(today), com.pulseloop.service.Postpartum.birthDate,
                        com.pulseloop.service.Postpartum.feeding, periods.map { LocalDate.ofEpochDay(it.startDay) },
                    )
                    if (paused) Text(
                        tr("Predictions are paused after birth: while breastfeeding, and until your first period, cycles don't follow their usual pattern."),
                        fontSize = 13.sp, color = PulseColors.textSecondary,
                    )
                    status.nextStart?.takeIf { !paused }?.let { next ->
                        val d = status.daysToNext ?: 0
                        Text(
                            if (d >= 0) trf("Next period around {0} (in {1} day{2}), from your usual {3}-day cycle.", next.format(fmt), d, if (d == 1) "" else "s", status.usualLength)
                            else trf("Your usual {0}-day cycle would have ended {1} day{2} ago.", status.usualLength, -d, if (d == -1) "" else "s"),
                            fontSize = 13.sp, color = PulseColors.textPrimary,
                        )
                    }
                }
            }
            val open = periods.firstOrNull()?.takeIf { it.endDay == null && status?.inPeriod == true }
            if (!loaded) {
                Spacer(Modifier.height(40.dp))
            } else if (open != null) {
                Button(onClick = { scope.launch { db.periodDao().upsert(open.copy(endDay = today.toEpochDay())) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Period ended today"))
                }
            } else {
                Button(onClick = { scope.launch { db.periodDao().upsert(PeriodEntity(startDay = today.toEpochDay())) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Period started today"))
                }
            }
            OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.fillMaxWidth()) { Text(tr("Log a past period")) }

            if (periods.isNotEmpty()) {
                Text(tr("HISTORY"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                val starts = periods.map { it.startDay }.sorted()
                periods.forEach { p ->
                    val next = starts.firstOrNull { it > p.startDay }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val start = LocalDate.ofEpochDay(p.startDay)
                            Text(
                                start.format(fmt) + (p.endDay?.let { " – " + LocalDate.ofEpochDay(it).format(fmt) } ?: ""),
                                fontSize = 14.sp, color = PulseColors.textPrimary,
                            )
                            next?.let { Text(trf("Cycle of {0} days", it - p.startDay), fontSize = 12.sp, color = PulseColors.textMuted) }
                        }
                        IconButton(onClick = { scope.launch { db.periodDao().delete(p.id) } }) {
                            Icon(Icons.Filled.Delete, tr("Delete"), tint = PulseColors.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Text(
                tr("Phases come only from the dates you log. PulseLoop doesn't predict fertile days, and this isn't contraception or a medical tool."),
                fontSize = 11.sp, color = PulseColors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking != null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (pastStart ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null; pastStart = null },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    if (chosen != null && !chosen.isAfter(today)) {
                        if (picking == "start") { pastStart = chosen; picking = "end" }
                        else {
                            val s = pastStart ?: chosen
                            val e = if (chosen.isBefore(s)) s else chosen
                            scope.launch { db.periodDao().upsert(PeriodEntity(startDay = s.toEpochDay(), endDay = e.toEpochDay())) }
                            picking = null; pastStart = null
                        }
                    }
                }) { Text(if (picking == "start") tr("Next: end date") else tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { picking = null; pastStart = null }) { Text(tr("Cancel")) } },
        ) {
            Text(if (picking == "start") tr("When did it start?") else tr("When did it end?"), modifier = Modifier.padding(start = 24.dp, top = 16.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            DatePicker(state = state, showModeToggle = false)
        }
    }
}
