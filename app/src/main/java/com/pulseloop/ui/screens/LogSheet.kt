package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DrinkEntryEntity
import com.pulseloop.service.DrinkPrefs
import com.pulseloop.service.DrinksRepository
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch

/**
 * Where the Actions sheet opens. [MENU] lists everything; the others jump straight to one step, for
 * the shortcuts on Today.
 */
enum class LogStep { MENU, MEAL, DRINK, TAGS }

/**
 * The Actions sheet: one place to record anything, or ask the coach, reached the same way from
 * every tab. Logging is the most frequent thing people do in the app, and it used to live on six
 * different screens; the coach moved here when Nutrition took its tab.
 *
 * Everything here is for now. Adding something to an earlier day happens on that thing's own
 * screen (the Nutrition tab's day arrows, Activity's "Log Past Activity", Cycle's past period),
 * so the sheet stays one short list. Meal, drink and medicine share one row of icons because
 * they are the same kind of entry; Period is offered only to a profile set to female.
 *
 * Meals, drinks and day tags are logged inside the sheet. Medications, periods and workouts open
 * their own screens, whose first block is the action, because they need more than one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(
    db: PulseLoopDatabase,
    start: LogStep,
    onDismiss: () -> Unit,
    onCoach: () -> Unit,
    onMeal: (start: String) -> Unit,
    onMoreDrinks: () -> Unit,
    onMeds: () -> Unit,
    onPeriod: () -> Unit,
    onWorkout: () -> Unit,
    onSeeEffects: () -> Unit,
) {
    var step by remember(start) { mutableStateOf(start) }
    // Unknown until the profile is read, and hidden meanwhile: a row that appears late is better
    // than one that vanishes under the user's thumb.
    val isFemale by produceState(false, db) { value = db.userProfileDao().get()?.sex == "female" }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PulseColors.secondaryBackground,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Each step slides in from the side it was reached from, so the sheet reads as one
            // place with pages rather than a panel that blinks.
            androidx.compose.animation.AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState != LogStep.MENU
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { w -> if (forward) w / 6 else -w / 6 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                },
                label = "logStep",
            ) { shownStep ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (shownStep) {
                    LogStep.MENU -> {
                        Text(tr("Actions"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        SheetOption(tr("Ask the coach"), tr("About your day, a plan, a meal idea"), Icons.Filled.AutoAwesome, onCoach)
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconAction(tr("Meal"), Icons.Filled.Restaurant, PulseColors.calories) { step = LogStep.MEAL }
                            IconAction(tr("Drink"), Icons.Filled.LocalBar, PulseColors.drinks) { step = LogStep.DRINK }
                            IconAction(tr("Medicine"), Icons.Filled.Medication, PulseColors.info, onMeds)
                        }
                        if (isFemale) SheetOption(tr("Period"), tr("Started or ended today"), Icons.Filled.WaterDrop, onPeriod)
                        SheetOption(tr("Tag your day"), tr("Alcohol, stress, a late meal…"), Icons.Filled.LocalOffer) { step = LogStep.TAGS }
                        SheetOption(tr("Workout"), tr("Start one now"), Icons.Filled.DirectionsRun, onWorkout)
                    }
                    LogStep.MEAL -> {
                        Text(tr("Log a meal"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        SheetOption(tr("Photo"), tr("Snap your plate; the AI estimates it"), Icons.Filled.CameraAlt) { onMeal("photo") }
                        SheetOption(tr("Barcode"), tr("Scan a packaged food"), Icons.Filled.QrCodeScanner) { onMeal("barcode") }
                        SheetOption(tr("Add by hand"), tr("Type the name and calories"), Icons.Filled.Add) { onMeal("add") }
                    }
                    LogStep.DRINK -> QuickDrinks(db, onMoreDrinks)
                    LogStep.TAGS -> JournalTagsCard(db, java.time.LocalDate.now(), tr("Tag your day"), allowYesterday = true) {
                        Text(
                            tr("See what affects you"),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClick = onSeeEffects)
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
                }
            }
        }
    }
}

/** One of the icon-and-word buttons that share the top row: the same kind of entry, side by side. */
@Composable
private fun RowScope.IconAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp).clip(shape)
            .background(PulseColors.card)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The four usual drinks, one tap each, with an undo; everything else is on the Drinks screen. */
@Composable
private fun QuickDrinks(db: PulseLoopDatabase, onMore: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Most-logged first, read once: re-ranking under the user's thumb would move the button they
    // are about to tap again.
    val types = remember {
        DrinksRepository.topTypes(listOf(DrinkPrefs.lastType))
    }
    var logged by remember { mutableStateOf<Pair<String, List<DrinkEntryEntity>>?>(null) }

    Text(tr("Log a drink"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { t ->
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(PulseColors.card)
                    .border(1.dp, PulseColors.borderSubtle, shape)
                    .clickable(role = Role.Button) {
                        scope.launch { logged = t.short to DrinksRepository.log(db, t) }
                    }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(t.icon, null, tint = PulseColors.drinks, modifier = Modifier.size(24.dp))
                Text(t.short, fontSize = 12.sp, color = PulseColors.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // Same height whether or not something was just logged, so the buttons above don't jump.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        val last = logged
        Text(
            if (last != null) trf("Logged {0}", last.first.lowercase()) else "",
            fontSize = 14.sp, color = PulseColors.accent, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (last != null) {
            androidx.compose.material3.TextButton(
                onClick = { scope.launch { last.second.forEach { DrinksRepository.delete(db, it) }; logged = null } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(tr("Undo"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary, maxLines = 1)
            }
        }
    }
    SheetOption(tr("More drinks"), tr("Other types, several at once, or earlier tonight"), Icons.Filled.LocalBar, onMore)
}
