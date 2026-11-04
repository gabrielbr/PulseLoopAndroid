package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.components.PulseButton

import com.pulseloop.ui.theme.dither
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.ManualActivityError
import com.pulseloop.service.ManualActivityService
import com.pulseloop.ui.components.ActivityMeta
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch

/**
 * Ported from LogPastActivityView.swift (iOS #57d): a form for a completed workout the ring
 * missed. Creation goes through [ManualActivityService], the same shared path the
 * `create_activity_session_from_description` coach tool uses, so a manually logged past session
 * credits the daily rollup exactly like a live-recorded one.
 */
@Composable
fun LogPastActivityScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Captured once, like iOS's `maximumDate` — a stable upper bound keeps the date/time pickers
    // from reconfiguring on every recomposition.
    val maximumDate = remember { System.currentTimeMillis() }
    var selectedType by remember { mutableStateOf("run") }
    var startedAt by remember { mutableLongStateOf(maximumDate - 3600_000L) }
    var durationMinutes by remember { mutableIntStateOf(60) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val endedAt = startedAt + durationMinutes * 60_000L
    val isValid = durationMinutes > 0 && endedAt <= maximumDate

    val is24h = android.text.format.DateFormat.is24HourFormat(context)

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
    // The shared pushed-screen bar. The second 20sp title ("What did you do?") and its how-to
    // line are gone: the three section labels below already say what to choose.
    com.pulseloop.ui.components.PulseTopBar(title = tr("Log Past Activity"), onBack = onBack)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { SectionLabel(tr("Activity type")) }
        item {
            ActivityTypeGrid(selectedType) { type ->
                selectedType = type
                saveError = null
            }
        }

        item { SectionLabel(tr("When")) }
        item {
            EditFieldRow(tr("Started")) {
                DateTimeButton(startedAt) { startedAt = it; saveError = null }
            }
        }
        item {
            EditFieldRow(tr("Ends")) {
                Text(
                    formatDateTime(endedAt, is24h),
                    fontSize = 14.sp,
                    color = if (isValid) PulseColors.textSecondary else PulseColors.warning,
                )
            }
        }

        item { SectionLabel(tr("Duration")) }
        item {
            PastActivityDurationCard(durationMinutes) { newValue ->
                durationMinutes = newValue
                saveError = null
            }
        }

        if (!isValid) {
            item { Text(tr("The workout must finish before now."), fontSize = 12.sp, color = PulseColors.warning) }
        }
        saveError?.let { error ->
            item { Text(error, fontSize = 12.sp, color = PulseColors.danger) }
        }

        item {
            PulseButton(
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val session = ManualActivityService.create(
                                db = db,
                                type = selectedType,
                                startedAt = startedAt,
                                durationMinutes = durationMinutes.toDouble(),
                                now = maximumDate,
                            )
                            onSaved(session.id)
                        } catch (e: ManualActivityError) {
                            saveError = e.message
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = isValid && !saving,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PulseColors.accent),
            ) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Log Activity"), fontWeight = FontWeight.SemiBold)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = com.pulseloop.ui.theme.PulseType.eyebrow, color = PulseColors.textSecondary,
    )
}

@Composable
private fun ActivityTypeGrid(selectedType: String, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActivityMeta.ORDER.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { type ->
                    ActivityTypeButton(type, isSelected = type == selectedType, onSelect = onSelect, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActivityTypeButton(type: String, isSelected: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .clip(shape)
            .then(if (isSelected) Modifier.dither(PulseColors.accent, 0.18f) else Modifier.background(PulseColors.cardSoft))
            .border(1.5.dp, if (isSelected) PulseColors.accent.copy(alpha = 0.9f) else Color.Transparent, shape)
            .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(type) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isSelected) PulseColors.accent else PulseColors.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ActivityMeta.icon(type), null,
                tint = if (isSelected) PulseColors.onAccent else PulseColors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            ActivityMeta.label(type),
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(Icons.Filled.CheckCircle, null, tint = PulseColors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PastActivityDurationCard(minutes: Int, onChange: (Int) -> Unit) {
    val atFloor = minutes <= 5
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PulseColors.cardSoft)
            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DurationStepButton(Icons.Filled.Remove, tr("Decrease duration by 5 minutes"), enabled = !atFloor) { onChange(maxOf(5, minutes - 5)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(durationText(minutes), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(tr("DURATION"), style = com.pulseloop.ui.theme.PulseType.eyebrow, color = PulseColors.textSecondary)
            }
            DurationStepButton(Icons.Filled.Add, tr("Increase duration by 5 minutes"), enabled = true) { onChange(minutes + 5) }
        }
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { value ->
                val selected = minutes == value
                Text(
                    "${value}m",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (selected) PulseColors.accent else PulseColors.textSecondary,
                    modifier = Modifier
                        .weight(1f)
                        // 34dp pill, 48dp touch target.
                        .minimumInteractiveComponentSize()
                        .height(34.dp)
                        .clip(CircleShape)
                        .then(if (selected) Modifier.dither(PulseColors.accent, 0.18f) else Modifier.background(PulseColors.card))
                        .border(1.dp, if (selected) PulseColors.accent.copy(alpha = 0.9f) else Color.Transparent, CircleShape)
                        .selectable(selected = selected, role = Role.RadioButton) { onChange(value) }
                        .wrapContentHeight(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DurationStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(PulseColors.card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) PulseColors.textPrimary else PulseColors.textMuted)
    }
}

private fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> trf("{0} min", remaining)
        remaining == 0 -> trf("{0} hr", hours)
        else -> trf("{0} hr {1} min", hours, remaining)
    }
}
