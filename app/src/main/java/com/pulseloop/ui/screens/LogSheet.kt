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
 * Where a "+ Log" sheet opens. [MENU] lists everything; the others jump straight to one step, for
 * the shortcuts on Today.
 */
enum class LogStep { MENU, MEAL, DRINK, TAGS }

/**
 * One place to record anything, reached the same way from every tab: logging is the most frequent
 * thing people do in the app, and it used to live on six different screens.
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
    onMeal: (start: String) -> Unit,
    onMoreDrinks: () -> Unit,
    onMeds: () -> Unit,
    onPeriod: () -> Unit,
    onWorkout: () -> Unit,
    onSeeEffects: () -> Unit,
) {
    var step by remember(start) { mutableStateOf(start) }
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
                        Text(tr("Log"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        SheetOption(tr("Meal"), tr("Photo, barcode or by hand"), Icons.Filled.Restaurant) { step = LogStep.MEAL }
                        SheetOption(tr("Drink"), tr("One tap for the usual ones"), Icons.Filled.LocalBar) { step = LogStep.DRINK }
                        SheetOption(tr("Medication or supplement"), tr("Mark today's doses"), Icons.Filled.Medication, onMeds)
                        SheetOption(tr("Period"), tr("Started or ended today, or a past one"), Icons.Filled.WaterDrop, onPeriod)
                        SheetOption(tr("Tag your day"), tr("Alcohol, stress, a late meal…"), Icons.Filled.LocalOffer) { step = LogStep.TAGS }
                        SheetOption(tr("Workout"), tr("Start one now"), Icons.Filled.DirectionsRun, onWorkout)
                    }
                    LogStep.MEAL -> {
                        Text(tr("Log a meal"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                        SheetOption(tr("Photo"), tr("Snap your plate; the AI estimates it"), Icons.Filled.CameraAlt) { onMeal("photo") }
                        SheetOption(tr("Barcode"), tr("Scan a packaged food"), Icons.Filled.QrCodeScanner) { onMeal("barcode") }
                        SheetOption(tr("Add by hand"), tr("Type the name and calories"), Icons.Filled.Add) { onMeal("add") }
                    }
                    LogStep.DRINK -> QuickDrinks(db, onMoreDrinks)
                    LogStep.TAGS -> JournalTagsCard(db, java.time.LocalDate.now(), tr("Tag your day"), allowYesterday = true) {
                        Text(
                            tr("See what affects you"),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent,
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

    Text(tr("Log a drink"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        types.forEach { t ->
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(PulseColors.card)
                    .border(1.dp, PulseColors.borderSubtle, shape)
                    .clickable {
                        scope.launch { logged = t.short to DrinksRepository.log(db, t) }
                    }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(t.icon, null, tint = PulseColors.drinks, modifier = Modifier.size(24.dp))
                Text(t.short, fontSize = 11.sp, color = PulseColors.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // Same height whether or not something was just logged, so the buttons above don't jump.
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        val last = logged
        Text(
            if (last != null) trf("Logged {0}", last.first.lowercase()) else "",
            fontSize = 13.sp, color = PulseColors.accent, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (last != null) {
            Text(
                tr("Undo"),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
                maxLines = 1,
                modifier = Modifier.clickable {
                    scope.launch { last.second.forEach { DrinksRepository.delete(db, it) }; logged = null }
                }.padding(12.dp),
            )
        }
    }
    SheetOption(tr("More drinks"), tr("Other types, several at once, or earlier tonight"), Icons.Filled.LocalBar, onMore)
}
