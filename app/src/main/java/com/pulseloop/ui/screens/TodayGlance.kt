package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.OffSignal
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.dither

/*
 * Today, in three layers (asked for after the page became a wall of cards):
 *  - Glance: the score rings, then one line of what today means and, only when it applies, one
 *    line saying something looks off.
 *  - Act: a quick-log row, and at most two cards chosen by the time of day ([TodayMoments]).
 *  - Browse: the metric tiles, compact.
 */

/** The cards that earn a place on Today at a given hour. Pure, so the schedule is testable. */
object TodayMoments {
    enum class Moment { LAST_NIGHT, FOOD, TONIGHT, TAG_DAY }

    /** Never more than this many moment cards at once. */
    const val MAX = 2

    /**
     * @param hour local hour of day, 0–23
     * @param hasSleep whether there is a last-night session to summarise
     * @param mealsToday meals logged so far today
     * @param hasBedtime whether bedtime guidance could be computed
     */
    fun at(hour: Int, hasSleep: Boolean, mealsToday: Int, hasBedtime: Boolean): List<Moment> {
        val evening = hour >= 17 || hour < 4
        val late = hour >= 19 || hour < 4
        return buildList {
            if (hour in 4..10 && hasSleep) add(Moment.LAST_NIGHT)
            if (hour in 11..15 && mealsToday == 0) add(Moment.FOOD)
            if (evening && hasBedtime) add(Moment.TONIGHT)
            if (late) add(Moment.TAG_DAY)
        }.take(MAX)
    }
}

/**
 * The line under the score rings: an alert when last night's signals look off, then one sentence
 * on today. Each is a single tap away from its detail.
 */
@Composable
fun TodayGlance(
    alert: List<OffSignal>?,
    insight: String?,
    onAlert: () -> Unit,
    onInsight: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!alert.isNullOrEmpty()) {
            val shape = RoundedCornerShape(4.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .dither(PulseColors.warning, 0.12f)
                    .border(1.dp, PulseColors.warning, shape)
                    .clickable(onClick = onAlert)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = PulseColors.warning)
                Text(
                    "Unusual last night: " + alert.joinToString(", ") { it.title.lowercase() },
                    fontSize = 13.sp, color = PulseColors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        insight?.let {
            Text(
                it,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = PulseColors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onInsight != null) Modifier.clickable(onClick = onInsight) else Modifier)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/** Meal · Tag day · Measure · Workout — the things a user does on Today, one tap each. */
@Composable
fun QuickLogRow(onMeal: () -> Unit, onTag: () -> Unit, onMeasure: () -> Unit, onWorkout: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickChip("Meal", Icons.Filled.Restaurant, PulseColors.calories, Modifier.weight(1f), onMeal)
        QuickChip("Tag day", Icons.Filled.LocalOffer, PulseColors.accent, Modifier.weight(1f), onTag)
        QuickChip("Measure", Icons.Filled.FavoriteBorder, PulseColors.heartRate, Modifier.weight(1f), onMeasure)
        QuickChip("Workout", Icons.Filled.DirectionsRun, PulseColors.steps, Modifier.weight(1f), onWorkout)
    }
}

@Composable
private fun QuickChip(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, fontSize = 12.sp, color = PulseColors.textPrimary, maxLines = 1)
    }
}

/** Morning moment: last night in one card. */
@Composable
fun LastNightCard(durationText: String, score: Int?, window: String?, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PulseColors.card)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("LAST NIGHT", fontSize = 11.sp, letterSpacing = 1.8.sp, color = PulseColors.sleep)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(durationText, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            score?.let {
                Text("score $it", fontSize = 14.sp, color = PulseColors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        window?.let { Text(it, fontSize = 13.sp, color = PulseColors.textMuted) }
    }
}

/** Meal chip: photo, barcode or a manual entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealSheet(onDismiss: () -> Unit, onPick: (start: String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = PulseColors.secondaryBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log a meal", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            SheetOption("Photo", "Snap your plate; the AI estimates it", Icons.Filled.CameraAlt) { onPick("photo") }
            SheetOption("Barcode", "Scan a packaged food", Icons.Filled.QrCodeScanner) { onPick("barcode") }
            SheetOption("Add by hand", "Type the name and calories", Icons.Filled.Add) { onPick("add") }
        }
    }
}

/** Tag-day chip: every journal tag for today, and the way to the effects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDaySheet(db: PulseLoopDatabase, onDismiss: () -> Unit, onSeeEffects: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PulseColors.secondaryBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            JournalTagsCard(db, java.time.LocalDate.now(), "Tag your day") {
                Text(
                    "See what affects you",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent,
                    modifier = Modifier.clickable(onClick = onSeeEffects).padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetOption(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = PulseColors.accent)
        Column {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = PulseColors.textSecondary)
        }
    }
}

/** The whole coach brief (the Today line shows only its first lines), with a way into the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachBriefSheet(
    title: String,
    body: String,
    chips: List<String>,
    askCoach: Boolean,
    onDismiss: () -> Unit,
    onAsk: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PulseColors.secondaryBackground,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("TODAY'S BRIEF", fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.accent)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = PulseColors.textSecondary)
            if (chips.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.take(3).forEach {
                        Text(
                            it, fontSize = 12.sp, color = PulseColors.textPrimary, maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(PulseColors.card)
                                .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            if (askCoach) {
                androidx.compose.material3.Button(
                    onClick = onAsk,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ask the coach about it") }
            }
        }
    }
}
