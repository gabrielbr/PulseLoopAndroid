package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ui.theme.PulseColors
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Today's food in one read: what was eaten and whether nutrition tracking is on at all. */
private data class FoodToday(
    val enabled: Boolean,
    val eaten: Double,
    val proteinG: Double,
    val meals: Int,
    val calorieGoal: Double?,
)

private fun hiddenPrefs(context: Context) = context.getSharedPreferences("food_card", Context.MODE_PRIVATE)

/**
 * The Today screen's way into the food log (it used to live only under Settings). Shows what was
 * eaten next to what the ring says was burned, with one tap to photo, barcode or manual logging.
 * Before nutrition tracking is on it is an invitation the user can hide; logging a meal from it
 * turns tracking on.
 */
@Composable
fun FoodCard(
    db: PulseLoopDatabase,
    /** Calories burned so far today (ring or on-device estimate), null when unknown. */
    burned: Double?,
    onOpen: () -> Unit,
    onLog: (start: String) -> Unit,
) {
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(hiddenPrefs(context).getBoolean("hidden", false)) }
    val food by produceState<FoodToday?>(null, db) {
        value = runCatching {
            val goal = db.userGoalDao().get()
            val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val meals = db.mealEntryDao().byDay(todayStart)
            FoodToday(
                enabled = goal?.nutritionEnabled == true,
                eaten = meals.sumOf { it.calories },
                proteinG = meals.sumOf { it.proteinG },
                meals = meals.size,
                calorieGoal = goal?.intakeCalories,
            )
        }.getOrNull()
    }
    val f = food ?: run {
        // Still reading. Unless the user hid the invitation the card will show either way, so
        // hold its place on Today instead of popping it in and pushing everything below down.
        if (!hidden) Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)).background(PulseColors.card))
        return
    }
    if (!f.enabled && hidden) return

    val fmt = remember { NumberFormat.getIntegerInstance() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PulseColors.card)
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(PulseColors.calories))
            Spacer(Modifier.width(8.dp))
            Text("FOOD", fontSize = 12.sp, letterSpacing = 1.8.sp, color = PulseColors.textSecondary, modifier = Modifier.weight(1f))
            if (!f.enabled) {
                Text(
                    tr("Hide"), fontSize = 12.sp, color = PulseColors.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp).clickable(role = Role.Button) {
                        hidden = true
                        hiddenPrefs(context).edit().putBoolean("hidden", true).apply()
                    }.heightIn(min = 48.dp).wrapContentHeight().padding(horizontal = 12.dp),
                )
            }
        }

        if (f.enabled) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt.format(f.eaten.roundToInt()), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(" kcal eaten", fontSize = 13.sp, color = PulseColors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
            }
            val parts = buildList {
                burned?.takeIf { it > 0 }?.let { add(trf("{0} burned", fmt.format(it.roundToInt()))) }
                f.calorieGoal?.takeIf { it > 0 }?.let { add(trf("goal {0}", fmt.format(it.roundToInt()))) }
                if (f.proteinG > 0) add(trf("{0} g protein", f.proteinG.roundToInt()))
                if (f.meals == 0) add("nothing logged yet")
            }
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "), fontSize = 13.sp, color = PulseColors.textSecondary)
            }
        } else {
            Text(
                tr("Log meals to see food next to the calories you burn, and to link late meals and drinks to your sleep."),
                fontSize = 13.sp, color = PulseColors.textSecondary,
            )
        }

        // Thirds of the card: a label like "Código de barras" needs two lines, and the three
        // buttons keep one height when it takes them.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoodAction(tr("Photo"), Icons.Filled.CameraAlt, Modifier.weight(1f).fillMaxHeight()) { onLog("photo") }
            FoodAction(tr("Barcode"), Icons.Filled.QrCodeScanner, Modifier.weight(1f).fillMaxHeight()) { onLog("barcode") }
            FoodAction(tr("Add"), Icons.Filled.Add, Modifier.weight(1f).fillMaxHeight()) { onLog("add") }
        }
    }
}

@Composable
private fun FoodAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .clip(shape)
            .background(PulseColors.cardSoft)
            .border(1.dp, PulseColors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = PulseColors.accent)
        Spacer(Modifier.width(6.dp))
        Text(
            label, fontSize = 13.sp, color = PulseColors.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
