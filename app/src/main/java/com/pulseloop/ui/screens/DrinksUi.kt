package com.pulseloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.DrinkEntryEntity
import com.pulseloop.service.AuditC
import com.pulseloop.service.DrinkInsights
import com.pulseloop.service.DrinkPrefs
import com.pulseloop.service.DrinkType
import com.pulseloop.service.DrinksRepository
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.dither
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/*
 * Drinks (docs/drinks-plan.md): log, plan, see what drinking does to your nights, and cut down.
 * Opt-in, private to the phone, and worded without judgement.
 */

private fun std(v: Double) = DrinkInsights.fmt(v)

@Composable
private fun DrinkCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        title?.let { Text(it.uppercase(), fontSize = 12.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted) }
        content()
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, note: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = PulseColors.textPrimary)
            note?.let { Text(it, fontSize = 12.sp, color = PulseColors.textMuted) }
        }
        TextButton(onClick = onMinus, modifier = Modifier.semantics { contentDescription = "Decrease $label" }) {
            Text("−", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary, modifier = Modifier.widthIn(min = 36.dp))
        TextButton(onClick = onPlus, modifier = Modifier.semantics { contentDescription = "Increase $label" }) {
            Text("+", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/**
 * A rounded chip. A [choice] pill is one option of a single-choice group (put the group under
 * `Modifier.selectableGroup()`) and reads as a selected/unselected radio button; otherwise it is
 * a plain action button.
 */
@Composable
private fun Pill(label: String, on: Boolean, color: Color = PulseColors.accent, choice: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
        maxLines = 1,
        modifier = Modifier.clip(shape)
            .background(if (on) color else PulseColors.cardSoft)
            .border(1.dp, if (on) color else PulseColors.borderSubtle, shape)
            .then(
                if (choice) Modifier.selectable(selected = on, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).dither(color, 0.25f)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

/** The Drinks screen; on first open, the welcome that turns it on. */
@Composable
fun DrinksScreen(onBack: () -> Unit, onBreathe: () -> Unit, onCoach: ((String) -> Unit)?) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    DrinkPrefs.init(context)
    var enabled by remember { mutableStateOf(DrinkPrefs.enabled) }
    var refresh by remember { mutableIntStateOf(0) }
    val entries by remember { db.drinkDao().allFlow() }.collectAsState(initial = emptyList())
    val snap by produceState<DrinksRepository.Snapshot?>(null, refresh, entries, enabled) {
        value = if (enabled) runCatching { DrinksRepository.load(db) }.getOrNull() else null
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Drinks", modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!enabled) {
                DrinksWelcome { enabled = true }
            } else {
                val s = snap
                if (s == null) {
                    Text("Reading your drinks…", color = PulseColors.textMuted, fontSize = 13.sp)
                } else {
                    WeekSection(s)
                    LogSection(db, s) { refresh++ }
                    PlanSection(s) { refresh++ }
                    ImpactSection(s)
                    ProgressSection(s)
                    HelpSection(onBreathe, onCoach, s)
                    AuditSection(s) { refresh++ }
                    DrinkSettingsSection { on -> enabled = on; refresh++ }
                    Text(
                        "Your drinks stay on this phone. They aren't sent to Health Connect or included in diagnostics.",
                        fontSize = 11.sp, color = PulseColors.textMuted,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinksWelcome(onStart: () -> Unit) {
    var typical by remember { mutableIntStateOf(DrinkPrefs.baselinePerWeek.roundToInt().takeIf { it > 0 } ?: 10) }
    var limit by remember { mutableIntStateOf(7) }
    var dry by remember { mutableIntStateOf(DrinkPrefs.dryDaysTarget) }
    var grams by remember { mutableStateOf(DrinkPrefs.gramsPerStandard) }
    var limitTouched by remember { mutableStateOf(false) }
    DrinkCard {
        Text("Drink less, see the difference", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Text(
            "Log drinks in a tap, set a weekly limit and dry days, and see what drinking does to your own nights: HRV, resting heart rate and sleep, from your ring.",
            fontSize = 14.sp, color = PulseColors.textSecondary,
        )
    }
    DrinkCard("Where you are now") {
        Stepper("A typical week", "$typical", { typical = (typical - 1).coerceAtLeast(0); if (!limitTouched) limit = suggestedLimit(typical) },
            { typical = (typical + 1).coerceAtMost(80); if (!limitTouched) limit = suggestedLimit(typical) },
            note = "Standard drinks, roughly. A can of beer or a glass of wine is about 1.3.")
    }
    DrinkCard("Your plan") {
        Stepper("Weekly limit", "$limit", { limit = (limit - 1).coerceAtLeast(0); limitTouched = true }, { limit = (limit + 1).coerceAtMost(60); limitTouched = true },
            note = "Start a little under your typical week. Small steps last.")
        Stepper("Dry days a week", "$dry", { dry = (dry - 1).coerceAtLeast(0) }, { dry = (dry + 1).coerceAtMost(7) })
        Text("Standard drink size", fontSize = 14.sp, color = PulseColors.textPrimary)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10.0 to "10 g (WHO)", 8.0 to "8 g (UK unit)", 14.0 to "14 g (US)").forEach { (g, l) ->
                Pill(l, grams == g, PulseColors.drinks) { grams = g }
            }
        }
    }
    Button(
        onClick = {
            DrinkPrefs.baselinePerWeek = typical.toDouble()
            DrinkPrefs.weeklyLimit = limit.toDouble()
            DrinkPrefs.dryDaysTarget = dry
            DrinkPrefs.gramsPerStandard = grams
            DrinkPrefs.setEnabled(true)
            onStart()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Start tracking") }
    Text(
        "If you drink most days, cut down gradually rather than stopping all at once, and talk to a doctor: stopping suddenly after heavy daily drinking can be dangerous.",
        fontSize = 12.sp, color = PulseColors.textMuted,
    )
}

/** A first limit a bit under the user's typical week: 25 % fewer, never above 14. */
internal fun suggestedLimit(typical: Int): Int = (typical * 0.75).roundToInt().coerceIn(0, 14)

@Composable
private fun WeekSection(s: DrinksRepository.Snapshot) {
    val w = s.week
    DrinkCard("This week") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(std(w.standardDrinks), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text("of ${std(s.weeklyLimit)} drinks", fontSize = 14.sp, color = PulseColors.textMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        val over = s.weeklyLimit > 0 && w.standardDrinks > s.weeklyLimit
        Bar(if (s.weeklyLimit > 0) (w.standardDrinks / s.weeklyLimit).toFloat() else 0f, if (over) PulseColors.warning else PulseColors.drinks)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("${w.dryDays} of ${s.dryDaysTarget}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("dry days", fontSize = 12.sp, color = PulseColors.textMuted)
            }
            Column(Modifier.weight(1f)) {
                Text("${s.streaks.current}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("dry days in a row", fontSize = 12.sp, color = PulseColors.textMuted)
            }
            Column(Modifier.weight(1f)) {
                Text(if (w.drankToday) std(s.todayStandard) else "0", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text("today", fontSize = 12.sp, color = PulseColors.textMuted)
            }
        }
        if (w.heavyDays > 0) {
            Text(
                "${w.heavyDays} day${if (w.heavyDays > 1) "s" else ""} this week with 60 g of alcohol or more, the level WHO calls heavy drinking.",
                fontSize = 12.sp, color = PulseColors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogSection(db: PulseLoopDatabase, s: DrinksRepository.Snapshot, onChange: () -> Unit) {
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    var lastLogged by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<DrinkEntryEntity?>(null) }
    DrinkCard("Log a drink") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DrinkType.entries.forEach { t ->
                Pill("+ ${t.label}", false, choice = false) {
                    scope.launch {
                        DrinksRepository.log(db, t)
                        lastLogged = "Logged 1 ${t.label.lowercase()}"
                        onChange()
                    }
                }
            }
            Pill("More…", false, choice = false) { more = true }
        }
        lastLogged?.let { Text(it, fontSize = 12.sp, color = PulseColors.accent) }
        if (s.recent.isNotEmpty()) {
            val zone = ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ofPattern("EEE HH:mm")
            s.recent.forEach { e -> RecentDrinkRow(e, s.gramsPerStandard, fmt, zone) { toDelete = e } }
        }
    }
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete drink?") },
            text = { Text("This ${DrinkType.of(e.type).label.lowercase()} will be removed from your log.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch { db.drinkDao().delete(e.id); onChange() }
                }) { Text("Delete", color = PulseColors.danger) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
    if (more) MoreDrinkDialog(onDismiss = { more = false }) { type, count, hoursAgo, price ->
        scope.launch {
            DrinksRepository.log(db, type, count, System.currentTimeMillis() - hoursAgo * 3_600_000L, price = price)
            lastLogged = "Logged $count ${type.label.lowercase()}"
            more = false
            onChange()
        }
    }
}

@Composable
private fun RecentDrinkRow(e: DrinkEntryEntity, gramsPerStd: Double, fmt: DateTimeFormatter, zone: ZoneId, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(DrinkType.of(e.type).label, fontSize = 14.sp, color = PulseColors.textPrimary)
            Text(
                "${Instant.ofEpochMilli(e.timestamp).atZone(zone).format(fmt)} · ${std(DrinkInsights.standardDrinks(e.grams, gramsPerStd))} std · ${e.kcal.roundToInt()} kcal" +
                    if (e.mealId != null) " · from your food log" else "",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete ${DrinkType.of(e.type).label}", tint = PulseColors.textMuted, modifier = Modifier.size(18.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreDrinkDialog(onDismiss: () -> Unit, onLog: (DrinkType, Int, Long, Double?) -> Unit) {
    var type by remember { mutableStateOf(DrinkPrefs.lastType) }
    var count by remember { mutableIntStateOf(1) }
    var hoursAgo by remember { mutableLongStateOf(0L) }
    var price by remember { mutableStateOf(DrinkPrefs.pricePerDrink.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log drinks") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DrinkType.entries.forEach { t -> Pill(t.label, type == t, PulseColors.drinks) { type = t } }
                }
                Text("${type.volumeMl.roundToInt()} ml at ${type.abvPct}% · ${type.kcal.roundToInt()} kcal", fontSize = 12.sp, color = PulseColors.textMuted)
                Stepper("How many", "$count", { count = (count - 1).coerceAtLeast(1) }, { count = (count + 1).coerceAtMost(12) })
                Text("When", fontSize = 14.sp, color = PulseColors.textPrimary)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to "Now", 1L to "1 h ago", 3L to "3 h ago", 12L to "Last night").forEach { (h, l) ->
                        Pill(l, hoursAgo == h, PulseColors.drinks) { hoursAgo = h }
                    }
                }
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                    label = { Text("Price each (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onLog(type, count, hoursAgo, price.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }) }) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlanSection(s: DrinksRepository.Snapshot, onChange: () -> Unit) {
    var plan by remember(s.planToday) { mutableIntStateOf(s.planToday ?: 2) }
    DrinkCard("Plan tonight") {
        if (s.planToday == null) {
            Text("Going out? Decide before the first drink how many you'll have. It's easier to keep a number you chose sober.", fontSize = 13.sp, color = PulseColors.textSecondary)
            Stepper("Tonight", if (plan == 0) "none" else "$plan", { plan = (plan - 1).coerceAtLeast(0) }, { plan = (plan + 1).coerceAtMost(12) })
            OutlinedButton(onClick = { DrinkPrefs.setPlan(s.today, plan); onChange() }, modifier = Modifier.fillMaxWidth()) { Text("Set tonight's plan") }
        } else {
            val planned = s.planToday
            val had = s.todayStandard
            Text(
                when {
                    planned == 0 && had <= 0 -> "Tonight's plan: no drinks. So far, so good."
                    planned == 0 -> "Tonight's plan was no drinks; ${std(had)} logged."
                    had <= planned -> "${std(had)} of $planned planned"
                    else -> "${std(had)} logged, $planned planned. Water and food between drinks slow the next one."
                },
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary,
            )
            if (planned > 0) Bar((had / planned).toFloat(), if (had > planned) PulseColors.warning else PulseColors.drinks)
            TextButton(onClick = { DrinkPrefs.setPlan(s.today, null); onChange() }) { Text("Change plan") }
        }
    }
}

@Composable
private fun ImpactSection(s: DrinksRepository.Snapshot) {
    DrinkCard("What drinking does to you") {
        s.morningAfter?.let { Text(DrinksRepository.morningAfterLine(it), fontSize = 14.sp, color = PulseColors.textPrimary) }
        val d = s.dose
        if (d.dryNights < DrinkInsights.MIN_NIGHTS) {
            Text(
                "After ${DrinkInsights.MIN_NIGHTS} dry nights with your ring on, PulseLoop compares your nights after drinking with your dry ones. ${d.dryNights} so far.",
                fontSize = 13.sp, color = PulseColors.textSecondary,
            )
        } else {
            ImpactRow("After 1–2 drinks", d.light)
            ImpactRow("After 3 or more", d.heavier)
            s.recoveryNights?.let {
                Text("Your HRV usually takes ${"%.1f".format(it)} night${if (it > 1.05) "s" else ""} to come back after drinking.", fontSize = 13.sp, color = PulseColors.textSecondary)
            }
        }
        Text("Compared with your own dry nights since you started tracking. A pattern in your data, not proof of cause.", fontSize = 11.sp, color = PulseColors.textMuted)
    }
}

@Composable
private fun ImpactRow(label: String, i: DrinkInsights.Impact?) {
    Column {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary)
        val text = if (i == null) "Not enough nights yet (${DrinkInsights.MIN_NIGHTS} needed)." else buildList {
            i.hrvPct?.let { add("HRV ${kotlin.math.abs(it)}% ${if (it < 0) "lower" else "higher"}") }
            i.restingHr?.let { add("resting HR ${if (it >= 0) "+" else ""}$it bpm") }
            i.asleepMinutes?.let { add("${kotlin.math.abs(it)} min ${if (it < 0) "less" else "more"} sleep") }
        }.joinToString(" · ").ifEmpty { "No clear difference" } + " (${i.nights} nights)"
        Text(text, fontSize = 12.sp, color = PulseColors.textSecondary)
    }
}

@Composable
private fun ProgressSection(s: DrinksRepository.Snapshot) {
    DrinkCard("Last 8 weeks") {
        val max = maxOf(s.weeks.maxOfOrNull { it.second } ?: 0.0, s.weeklyLimit, 1.0)
        val limitColor = PulseColors.textMuted
        val barColor = PulseColors.drinks
        val warn = PulseColors.warning
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = s.weeks.size.coerceAtLeast(1)
            val slot = size.width / n
            s.weeks.forEachIndexed { i, (_, v) ->
                val h = (v / max * size.height).toFloat()
                drawRect(
                    if (s.weeklyLimit > 0 && v > s.weeklyLimit) warn else barColor,
                    topLeft = Offset(i * slot + slot * 0.2f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(slot * 0.6f, h),
                )
            }
            if (s.weeklyLimit > 0) {
                val y = (size.height - s.weeklyLimit / max * size.height).toFloat()
                drawLine(limitColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        Text("Dashed line: your weekly limit of ${std(s.weeklyLimit)}.", fontSize = 11.sp, color = PulseColors.textMuted)
        Text("Best dry streak: ${s.streaks.best} day${if (s.streaks.best == 1) "" else "s"}", fontSize = 14.sp, color = PulseColors.textPrimary)
        s.savings?.let { sv ->
            if (sv.drinksAvoided >= 0.5) {
                Text(
                    "Against your typical week: ${std(sv.drinksAvoided)} drinks fewer" +
                        (sv.money?.let { " · %.0f saved".format(it) } ?: "") +
                        " · ${"%,d".format(sv.kcal.roundToInt())} kcal not drunk",
                    fontSize = 14.sp, color = PulseColors.textPrimary,
                )
            }
        }
    }
}

@Composable
private fun HelpSection(onBreathe: () -> Unit, onCoach: ((String) -> Unit)?, s: DrinksRepository.Snapshot) {
    DrinkCard("When you want a drink") {
        Text("An urge usually peaks and passes within about 20 minutes. Slow breathing helps you ride it out.", fontSize = 13.sp, color = PulseColors.textSecondary)
        Button(onClick = onBreathe, modifier = Modifier.fillMaxWidth()) { Text("Ride it out: 3 minutes of breathing") }
        if (onCoach != null) {
            OutlinedButton(
                onClick = { onCoach("I'd like help with my drinking. This week I'm at ${std(s.week.standardDrinks)} of my ${std(s.weeklyLimit)}-drink limit, with ${s.week.dryDays} dry days. Can you help me plan the next few days?") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Talk it through with the coach") }
        }
        Text(
            "Support in Brazil: CAPS AD (free public alcohol and drug care, ask your local health unit), Alcoólicos Anônimos (aa.org.br), and CVV on 188 any time you need to talk. Anywhere: your doctor.",
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
    }
}

@Composable
private fun AuditSection(s: DrinksRepository.Snapshot, onDone: () -> Unit) {
    var taking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sex = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { sex.value = runCatching { PulseLoopDatabase.getInstance(context).userProfileDao().get()?.sex }.getOrNull() }
    DrinkCard("3-question check") {
        val score = DrinkPrefs.auditScore
        if (score != null) {
            val level = AuditC.level(score, sex.value)
            Text("${level.label} (score $score of 12, ${DrinkPrefs.auditDate})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(AuditC.advice(level), fontSize = 13.sp, color = PulseColors.textSecondary)
        } else {
            Text("AUDIT-C, the World Health Organization's short alcohol screen. Three questions, under a minute.", fontSize = 13.sp, color = PulseColors.textSecondary)
        }
        if (s.auditDue) OutlinedButton(onClick = { taking = true }, modifier = Modifier.fillMaxWidth()) { Text(if (score == null) "Take the check" else "Retake (every 3 months)") }
        Text("A screen, not a diagnosis.", fontSize = 11.sp, color = PulseColors.textMuted)
    }
    if (taking) {
        val answers = remember { mutableStateListOf(-1, -1, -1) }
        AlertDialog(
            onDismissRequest = { taking = false },
            title = { Text("3-question check") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AuditC.QUESTIONS.forEachIndexed { qi, (q, options) ->
                        Text(q, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Column(Modifier.selectableGroup()) {
                            options.forEachIndexed { oi, o ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .selectable(selected = answers[qi] == oi, role = Role.RadioButton) { answers[qi] = oi }
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    androidx.compose.material3.RadioButton(selected = answers[qi] == oi, onClick = null)
                                    Text(o, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = answers.all { it >= 0 }, onClick = {
                    DrinkPrefs.setAudit(java.time.LocalDate.now(), AuditC.score(answers))
                    taking = false
                    onDone()
                }) { Text("See result") }
            },
            dismissButton = { TextButton(onClick = { taking = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinkSettingsSection(onEnabledChange: (Boolean) -> Unit) {
    var limit by remember { mutableIntStateOf(DrinkPrefs.weeklyLimit.roundToInt()) }
    var dry by remember { mutableIntStateOf(DrinkPrefs.dryDaysTarget) }
    var typical by remember { mutableIntStateOf(DrinkPrefs.baselinePerWeek.roundToInt()) }
    var grams by remember { mutableStateOf(DrinkPrefs.gramsPerStandard) }
    var price by remember { mutableStateOf(DrinkPrefs.pricePerDrink.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "") }
    var confirmOff by remember { mutableStateOf(false) }
    DrinkCard("Settings") {
        Stepper("Weekly limit", "$limit", { limit = (limit - 1).coerceAtLeast(0); DrinkPrefs.weeklyLimit = limit.toDouble(); onEnabledChange(true) },
            { limit = (limit + 1).coerceAtMost(60); DrinkPrefs.weeklyLimit = limit.toDouble(); onEnabledChange(true) })
        Stepper("Dry days a week", "$dry", { dry = (dry - 1).coerceAtLeast(0); DrinkPrefs.dryDaysTarget = dry; onEnabledChange(true) },
            { dry = (dry + 1).coerceAtMost(7); DrinkPrefs.dryDaysTarget = dry; onEnabledChange(true) })
        Stepper("Typical week before", "$typical", { typical = (typical - 1).coerceAtLeast(0); DrinkPrefs.baselinePerWeek = typical.toDouble(); onEnabledChange(true) },
            { typical = (typical + 1).coerceAtMost(80); DrinkPrefs.baselinePerWeek = typical.toDouble(); onEnabledChange(true) },
            note = "Used for drinks, money and calories saved.")
        Text("Standard drink size", fontSize = 14.sp, color = PulseColors.textPrimary)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10.0 to "10 g (WHO)", 8.0 to "8 g (UK unit)", 14.0 to "14 g (US)").forEach { (g, l) ->
                Pill(l, grams == g, PulseColors.drinks) { grams = g; DrinkPrefs.gramsPerStandard = g; onEnabledChange(true) }
            }
        }
        OutlinedTextField(
            value = price,
            onValueChange = {
                price = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7)
                DrinkPrefs.pricePerDrink = price.replace(',', '.').toDoubleOrNull() ?: 0.0
            },
            label = { Text("Usual price of a drink (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { confirmOff = true }) { Text("Turn off Drinks", color = PulseColors.danger) }
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text("Turn off Drinks?") },
        text = { Text("The tile and morning-after card disappear. Your logged drinks stay on this phone until you delete them or clear your data.") },
        confirmButton = { TextButton(onClick = { DrinkPrefs.setEnabled(false); confirmOff = false; onEnabledChange(false) }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Keep") } },
    )
}

/** The Today tile: this week against the limit, dry days, and a one-tap "+1". */
@Composable
fun ColumnScope.DrinksTileContent(week: DrinkInsights.Week?, limit: Double, dryTarget: Int, onAdd: () -> Unit) {
    val total = week?.standardDrinks ?: 0.0
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(std(total), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary, maxLines = 1)
        Text("of ${std(limit)} this week", fontSize = 11.sp, color = PulseColors.textMuted, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
    }
    Bar(if (limit > 0) (total / limit).toFloat() else 0f, if (limit > 0 && total > limit) PulseColors.warning else PulseColors.drinks)
    Text("${week?.dryDays ?: 0} of $dryTarget dry days", fontSize = 11.sp, color = PulseColors.textSecondary, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Text(
        "+ ${DrinkPrefs.lastType.label}",
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent, maxLines = 1,
        // 48dp touch target; the label stays centred at its own size.
        modifier = Modifier.clickable(role = Role.Button, onClick = onAdd).heightIn(min = 48.dp).wrapContentHeight(),
    )
}
