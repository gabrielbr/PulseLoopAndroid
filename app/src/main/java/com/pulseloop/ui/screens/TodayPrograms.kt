package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.Cycle
import com.pulseloop.service.Meds
import com.pulseloop.service.Postpartum
import com.pulseloop.service.RestMode
import com.pulseloop.ui.theme.PulseColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What the user has switched on and uses day to day — cycle, medications, postpartum — one tap
 * from Today instead of two levels down under Vitals → My Health. Nothing shows for a program
 * that isn't in use, and nothing shows until Room has answered, so a chip never flashes in and
 * out on launch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgramsRow(db: PulseLoopDatabase, onCycle: () -> Unit, onMeds: () -> Unit, onPostpartum: () -> Unit) {
    val today = remember { LocalDate.now() }
    val periods by remember(db) { db.periodDao().allFlow() }.collectAsState(initial = null)
    val meds by remember(db) { db.medDao().allFlow() }.collectAsState(initial = null)
    val answers by remember(db) { db.medDao().dosesSinceFlow(today.toEpochDay()) }.collectAsState(initial = null)
    if (periods == null || meds == null || answers == null) return

    val chips = buildList {
        if (Postpartum.active()) Postpartum.birthDate?.let { birth ->
            add(Chip(Icons.Filled.ChildCare, PulseColors.accent, trf("Postpartum · week {0}", Postpartum.weeksSince(birth, today)), onPostpartum))
        }
        Cycle.status(periods!!.map { it.toPeriod() }, today)?.let { s ->
            add(Chip(Icons.Filled.WaterDrop, PulseColors.danger, trf("Cycle · day {0}", s.cycleDay), onCycle))
        }
        val doses = Meds.dosesOn(today, meds!!.filter { it.active }, answers!!, LocalDateTime.now())
        if (doses.isNotEmpty()) {
            val taken = doses.count { it.state == Meds.State.TAKEN }
            add(Chip(Icons.Filled.Medication, PulseColors.info, trf("Meds · {0} of {1}", taken, doses.size), onMeds))
        }
    }
    if (chips.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { ProgramChip(it) }
    }
}

private data class Chip(val icon: ImageVector, val tint: Color, val label: String, val onClick: () -> Unit)

@Composable
private fun ProgramChip(c: Chip) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(onClick = c.onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(c.icon, null, Modifier.size(18.dp), tint = c.tint)
        Text(c.label, fontSize = 13.sp, color = PulseColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Rest Mode, where the "I'm not well" moment happens. On: say so and offer to end it. Off, and
 * last night looked unusual: offer it. Otherwise nothing.
 */
@Composable
fun RestModeLine(offerIt: Boolean) {
    val context = LocalContext.current
    var until by remember { mutableStateOf(RestMode.until(context)) }
    val u = until
    if (u == null && !offerIt) return
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(PulseColors.card).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SelfImprovement, null, Modifier.size(18.dp), tint = PulseColors.readiness)
        Text(
            if (u != null) trf("Rest Mode on until {0}", u.format(DateTimeFormatter.ofPattern("EEE d MMM")))
            else tr("Feeling unwell? Pause activity nudges for 3 days."),
            fontSize = 13.sp, color = PulseColors.textPrimary,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp),
        )
        Text(
            if (u != null) tr("Turn off") else tr("Rest Mode"),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.readiness,
            maxLines = 1,
            modifier = Modifier
                .widthIn(max = 140.dp)
                .heightIn(min = 48.dp)
                .clickable {
                    RestMode.set(context, if (u != null) null else 3)
                    until = RestMode.until(context)
                }
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 12.dp),
        )
    }
}
