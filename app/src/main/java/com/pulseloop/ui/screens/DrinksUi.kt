package com.pulseloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Liquor
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsBar
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Drinks (docs/drinks-plan.md). Laid out like Today: glance (the week), act (log, tonight's plan),
 * then one insight. Trends and support sit behind tabs and settings behind the gear, so the first
 * screen answers "how am I doing and what do I do now" without scrolling.
 */

private fun std(v: Double) = DrinkInsights.fmt(v)

/** Short names for the one-tap buttons; the full label is on the "More" sheet. */
private val DrinkType.short: String
    get() = when (this) {
        DrinkType.BEER -> "Beer"
        DrinkType.BEER_600 -> "600 ml"
        DrinkType.DRAFT -> "Chopp"
        DrinkType.WINE -> "Wine"
        DrinkType.SPIRIT -> "Shot"
        DrinkType.CAIPIRINHA -> "Caipirinha"
        DrinkType.COCKTAIL -> "Cocktail"
        DrinkType.OTHER -> "Other"
    }

private val DrinkType.icon: ImageVector
    get() = when (this) {
        DrinkType.BEER, DrinkType.BEER_600, DrinkType.DRAFT -> Icons.Filled.SportsBar
        DrinkType.WINE -> Icons.Filled.WineBar
        DrinkType.SPIRIT -> Icons.Filled.Liquor
        DrinkType.CAIPIRINHA, DrinkType.COCKTAIL -> Icons.Filled.LocalBar
        DrinkType.OTHER -> Icons.Filled.LocalDrink
    }

// ── Building blocks ─────────────────────────────────────────────────────────────────────────

@Composable
private fun DrinkCard(title: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty().uppercase(), fontSize = 12.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted, modifier = Modifier.weight(1f))
                action?.invoke()
            }
        }
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
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 36.dp))
        TextButton(onClick = onPlus, modifier = Modifier.semantics { contentDescription = "Increase $label" }) {
            Text("+", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** One option of a single-choice group: put the group under `Modifier.selectableGroup()`. */
@Composable
private fun Pill(label: String, on: Boolean, color: Color = PulseColors.accent, onClick: () -> Unit) {
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
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
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

/** Segments for the three tabs, same look as the tag card's Today / Yesterday switch. */
@Composable
private fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, PulseColors.borderSubtle, shape).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
                    .background(if (on) PulseColors.accent else PulseColors.cardSoft)
                    .selectable(selected = on, role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────────────────────

/** The Drinks screen; on first open, the welcome that turns it on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrinksScreen(onBack: () -> Unit, onBreathe: () -> Unit, onCoach: ((String) -> Unit)?) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    DrinkPrefs.init(context)
    var enabled by remember { mutableStateOf(DrinkPrefs.enabled) }
    var refresh by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    val entries by remember { db.drinkDao().allFlow() }.collectAsState(initial = emptyList())
    val snap by produceState<DrinksRepository.Snapshot?>(null, refresh, entries, enabled) {
        value = if (enabled) runCatching { DrinksRepository.load(db) }.getOrNull() else null
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Drinks", modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (enabled) {
                IconButton(onClick = { settingsOpen = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Filled.Settings, "Drinks settings", tint = PulseColors.textSecondary)
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!enabled) {
                DrinksWelcome { enabled = true }
            } else {
                Segments(listOf("This week", "Trends", "Support"), tab) { tab = it }
                val s = snap
                if (s == null) {
                    Text("Reading your drinks…", color = PulseColors.textMuted, fontSize = 13.sp)
                } else when (tab) {
                    0 -> {
                        WeekHero(s)
                        LogCard(db, s) { refresh++ }
                        InsightCard(s)
                    }
                    1 -> TrendsTab(s)
                    else -> SupportTab(s, onBreathe, onCoach) { refresh++ }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PulseColors.secondaryBackground,
        ) {
            DrinkSettings(
                onChange = { refresh++ },
                onTurnedOff = { settingsOpen = false; enabled = false; refresh++ },
            )
        }
    }
}

// ── Welcome ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Benefit(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = PulseColors.drinks, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 14.sp, color = PulseColors.textSecondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinksWelcome(onStart: () -> Unit) {
    var typical by remember { mutableIntStateOf(DrinkPrefs.baselinePerWeek.roundToInt().takeIf { it > 0 } ?: 10) }
    var limit by remember { mutableIntStateOf(suggestedLimit(10)) }
    var dry by remember { mutableIntStateOf(DrinkPrefs.dryDaysTarget) }
    var grams by remember { mutableStateOf(DrinkPrefs.gramsPerStandard) }
    var limitTouched by remember { mutableStateOf(false) }
    var showSize by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Drink less, sleep better", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Benefit(Icons.Filled.LocalBar, "Log a drink in one tap")
        Benefit(Icons.Filled.EventAvailable, "A weekly limit and dry days you choose")
        Benefit(Icons.Filled.Bedtime, "See what drinking does to your own nights")
    }
    DrinkCard("Set up · under a minute") {
        Stepper(
            "A typical week now", "$typical",
            { typical = (typical - 1).coerceAtLeast(0); if (!limitTouched) limit = suggestedLimit(typical) },
            { typical = (typical + 1).coerceAtMost(80); if (!limitTouched) limit = suggestedLimit(typical) },
            note = "In drinks, roughly",
        )
        Stepper(
            "Weekly limit", "$limit",
            { limit = (limit - 1).coerceAtLeast(0); limitTouched = true },
            { limit = (limit + 1).coerceAtMost(60); limitTouched = true },
            note = "We suggest a quarter less to start",
        )
        Stepper("Dry days a week", "$dry", { dry = (dry - 1).coerceAtLeast(0) }, { dry = (dry + 1).coerceAtMost(7) })
        Row(Modifier.fillMaxWidth().clickable { showSize = !showSize }, verticalAlignment = Alignment.CenterVertically) {
            Text("Standard drink: ${grams.roundToInt()} g of alcohol", fontSize = 13.sp, color = PulseColors.textMuted, modifier = Modifier.weight(1f))
            Icon(if (showSize) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = PulseColors.textMuted)
        }
        if (showSize) {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10.0 to "10 g (WHO)", 8.0 to "8 g (UK)", 14.0 to "14 g (US)").forEach { (g, l) ->
                    Pill(l, grams == g, PulseColors.drinks) { grams = g }
                }
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
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text("Start tracking") }
    Text(
        "Private to this phone. If you drink most days, cut down gradually and talk to a doctor: stopping suddenly can be dangerous.",
        fontSize = 12.sp, color = PulseColors.textMuted,
    )
}

/** A first limit a bit under the user's typical week: 25 % fewer, never above 14. */
internal fun suggestedLimit(typical: Int): Int = (typical * 0.75).roundToInt().coerceIn(0, 14)

// ── This week ───────────────────────────────────────────────────────────────────────────────

/** The glance: a gauge of the week against the limit, one sentence, and the seven days. */
@Composable
private fun WeekHero(s: DrinksRepository.Snapshot) {
    val total = s.week.standardDrinks
    val over = s.weeklyLimit > 0 && total > s.weeklyLimit
    val color = if (over) PulseColors.warning else PulseColors.drinks
    val (title, sub) = DrinkInsights.headline(s.week, s.weeklyLimit, s.dryDaysTarget, s.today)
    DrinkCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                val fraction = if (s.weeklyLimit > 0) (total / s.weeklyLimit).toFloat().coerceIn(0f, 1f) else 0f
                val track = PulseColors.cardSoft
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (fraction > 0f) drawArc(color, 135f, 270f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(std(total), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text("of ${std(s.weeklyLimit)}", fontSize = 11.sp, color = PulseColors.textMuted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (over) PulseColors.warning else PulseColors.textPrimary)
                Text(sub, fontSize = 13.sp, color = PulseColors.textSecondary)
                if (s.streaks.current >= 2) {
                    Text("${s.streaks.current} dry days in a row", fontSize = 13.sp, color = PulseColors.accent)
                }
            }
        }
        WeekStrip(s)
    }
}

/** Monday to Sunday: drinks per day, dry days ticked, today outlined, days to come faint. */
@Composable
private fun WeekStrip(s: DrinksRepository.Snapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        s.weekDays.forEach { (day, grams) ->
            val isToday = day == s.today
            val future = day.isAfter(s.today)
            val known = !day.isBefore(s.trackedSince)
            val drank = grams > 0
            val heavy = grams >= DrinkInsights.HEAVY_DAY_GRAMS
            val dotColor = when {
                heavy -> PulseColors.warning
                drank -> PulseColors.drinks
                else -> Color.Transparent
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    fontSize = 11.sp, color = if (isToday) PulseColors.textPrimary else PulseColors.textMuted,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                )
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(dotColor)
                        .border(
                            width = if (isToday) 2.dp else 1.dp,
                            color = when {
                                isToday -> PulseColors.textPrimary
                                drank -> dotColor
                                future || !known -> PulseColors.borderSubtle
                                else -> PulseColors.accent
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        drank -> Text(std(DrinkInsights.standardDrinks(grams, s.gramsPerStandard)), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PulseColors.onAccent)
                        !future && known && !isToday -> Icon(Icons.Filled.Check, "Dry", tint = PulseColors.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Act: the four drinks the user logs most, "More" for the rest, undo, and tonight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogCard(db: PulseLoopDatabase, s: DrinksRepository.Snapshot, onChange: () -> Unit) {
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    var planOpen by remember { mutableStateOf(false) }
    var showTonight by remember { mutableStateOf(false) }
    var lastLogged by remember { mutableStateOf<Pair<String, List<DrinkEntryEntity>>?>(null) }
    var toDelete by remember { mutableStateOf<DrinkEntryEntity?>(null) }

    DrinkCard("Log a drink", action = {
        Text(
            "More",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent,
            modifier = Modifier.clickable { more = true }.padding(4.dp),
        )
    }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.topTypes.forEach { t ->
                val shape = RoundedCornerShape(8.dp)
                Column(
                    Modifier.weight(1f).clip(shape)
                        .background(PulseColors.cardSoft)
                        .border(1.dp, PulseColors.borderSubtle, shape)
                        .clickable {
                            scope.launch {
                                val rows = DrinksRepository.log(db, t)
                                lastLogged = "+1 ${t.short.lowercase()}" to rows
                                onChange()
                            }
                        }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(t.icon, null, tint = PulseColors.drinks, modifier = Modifier.size(24.dp))
                    Text(t.short, fontSize = 11.sp, color = PulseColors.textPrimary, maxLines = 1, softWrap = false)
                }
            }
        }
        lastLogged?.let { (text, ids) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Logged $text", fontSize = 13.sp, color = PulseColors.accent, modifier = Modifier.weight(1f))
                Text(
                    "Undo",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
                    modifier = Modifier.clickable {
                        scope.launch { ids.forEach { DrinksRepository.delete(db, it) }; lastLogged = null; onChange() }
                    }.padding(4.dp),
                )
            }
        }

        // Tonight: what's logged, and the plan, on one line each.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = s.recent.isNotEmpty()) { showTonight = !showTonight }) {
                val tonight = s.todayStandard
                Text(
                    if (tonight > 0) "Tonight: ${std(tonight)} drink${if (tonight == 1.0) "" else "s"}" else "Nothing tonight",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
                )
                if (s.recent.isNotEmpty()) {
                    Text(if (showTonight) "Hide" else "See and edit recent drinks", fontSize = 12.sp, color = PulseColors.textMuted)
                }
            }
            PlanChip(s) { planOpen = true }
        }
        s.planToday?.takeIf { it > 0 }?.let { planned ->
            Bar((s.todayStandard / planned).toFloat(), if (s.todayStandard > planned) PulseColors.warning else PulseColors.drinks)
        }
        if (showTonight) {
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
                    scope.launch { DrinksRepository.delete(db, e); onChange() }
                }) { Text("Delete", color = PulseColors.danger) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }

    if (more) {
        ModalBottomSheet(
            onDismissRequest = { more = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PulseColors.secondaryBackground,
        ) {
            MoreDrinkSheet { type, count, hoursAgo, price ->
                scope.launch {
                    val rows = DrinksRepository.log(db, type, count, System.currentTimeMillis() - hoursAgo * 3_600_000L, price = price)
                    lastLogged = "$count ${type.short.lowercase()}" to rows
                    more = false
                    onChange()
                }
            }
        }
    }
    if (planOpen) {
        ModalBottomSheet(
            onDismissRequest = { planOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PulseColors.secondaryBackground,
        ) {
            PlanSheet(s) { planOpen = false; onChange() }
        }
    }
}

@Composable
private fun PlanChip(s: DrinksRepository.Snapshot, onClick: () -> Unit) {
    val planned = s.planToday
    val shape = RoundedCornerShape(16.dp)
    val label = when {
        planned == null -> "Plan tonight"
        planned == 0 -> "Plan: dry night"
        else -> "Plan: $planned"
    }
    Text(
        label,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (planned == null) PulseColors.accent else PulseColors.textPrimary,
        modifier = Modifier.clip(shape)
            .border(1.dp, if (planned == null) PulseColors.accent else PulseColors.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PlanSheet(s: DrinksRepository.Snapshot, onDone: () -> Unit) {
    var plan by remember { mutableIntStateOf(s.planToday ?: 2) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Plan tonight", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Text("Pick the number before the first drink. It's easier to keep a number you chose beforehand.", fontSize = 13.sp, color = PulseColors.textSecondary)
        Stepper("Drinks tonight", if (plan == 0) "none" else "$plan", { plan = (plan - 1).coerceAtLeast(0) }, { plan = (plan + 1).coerceAtMost(12) })
        Button(onClick = { DrinkPrefs.setPlan(s.today, plan); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Save plan") }
        if (s.planToday != null) {
            TextButton(onClick = { DrinkPrefs.setPlan(s.today, null); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Clear plan") }
        }
    }
}

@Composable
private fun RecentDrinkRow(e: DrinkEntryEntity, gramsPerStd: Double, fmt: DateTimeFormatter, zone: ZoneId, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(DrinkType.of(e.type).icon, null, tint = PulseColors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(DrinkType.of(e.type).label, fontSize = 14.sp, color = PulseColors.textPrimary)
            Text(
                "${Instant.ofEpochMilli(e.timestamp).atZone(zone).format(fmt)} · ${std(DrinkInsights.standardDrinks(e.grams, gramsPerStd))} drink" +
                    if (e.mealId != null) " · from food log" else "",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete ${DrinkType.of(e.type).label}", tint = PulseColors.textMuted, modifier = Modifier.size(18.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreDrinkSheet(onLog: (DrinkType, Int, Long, Double?) -> Unit) {
    var type by remember { mutableStateOf(DrinkPrefs.lastType) }
    var count by remember { mutableIntStateOf(1) }
    var hoursAgo by remember { mutableLongStateOf(0L) }
    var price by remember { mutableStateOf(DrinkPrefs.pricePerDrink.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Log drinks", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DrinkType.entries.forEach { t -> Pill(t.label, type == t, PulseColors.drinks) { type = t } }
        }
        Text("${type.volumeMl.roundToInt()} ml at ${type.abvPct}% · about ${type.kcal.roundToInt()} kcal", fontSize = 12.sp, color = PulseColors.textMuted)
        Stepper("How many", "$count", { count = (count - 1).coerceAtLeast(1) }, { count = (count + 1).coerceAtMost(12) })
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
        Button(
            onClick = { onLog(type, count, hoursAgo, price.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log $count") }
    }
}

/** One insight, the most useful one right now; the detail opens on request. */
@Composable
private fun InsightCard(s: DrinksRepository.Snapshot) {
    var open by remember { mutableStateOf(false) }
    val d = s.dose
    val learning = d.dryNights < DrinkInsights.MIN_NIGHTS
    val headline = s.morningAfter?.let { DrinksRepository.morningAfterLine(it) }
        ?: d.heavier?.hrvPct?.let { "After 3 or more drinks, your HRV runs ${kotlin.math.abs(it)}% ${if (it < 0) "lower" else "higher"} than on dry nights." }
        ?: d.light?.hrvPct?.let { "After 1–2 drinks, your HRV runs ${kotlin.math.abs(it)}% ${if (it < 0) "lower" else "higher"} than on dry nights." }
        ?: if (learning) "Learning your dry nights: ${d.dryNights} of ${DrinkInsights.MIN_NIGHTS}. Then PulseLoop shows what each drink does to your sleep."
        else "Log a few evenings with drinks and PulseLoop will compare those nights with your dry ones."
    DrinkCard("Your nights") {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Bedtime, null, tint = PulseColors.sleep, modifier = Modifier.size(20.dp))
            Text(headline, fontSize = 14.sp, color = PulseColors.textPrimary, modifier = Modifier.weight(1f))
        }
        if (learning) {
            Bar(d.dryNights.toFloat() / DrinkInsights.MIN_NIGHTS, PulseColors.sleep)
        } else {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (open) "Less" else "Details", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.accent, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = PulseColors.accent)
            }
            if (open) {
                ImpactRow("After 1–2 drinks", d.light)
                ImpactRow("After 3 or more", d.heavier)
                s.recoveryNights?.let {
                    Text("HRV usually takes ${"%.1f".format(it)} night${if (it > 1.05) "s" else ""} to come back.", fontSize = 13.sp, color = PulseColors.textSecondary)
                }
                Text("Against your ${d.dryNights} dry nights since you started. A pattern in your data, not proof of cause.", fontSize = 11.sp, color = PulseColors.textMuted)
            }
        }
    }
}

@Composable
private fun ImpactRow(label: String, i: DrinkInsights.Impact?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary)
        val text = if (i == null) "Not enough nights yet" else buildList {
            i.hrvPct?.let { add("HRV ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)}%") }
            i.restingHr?.let { add("resting HR ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)}") }
            i.asleepMinutes?.let { add("sleep ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)} min") }
        }.joinToString(" · ").ifEmpty { "No clear difference" } + " · ${i.nights} nights"
        Text(text, fontSize = 12.sp, color = PulseColors.textSecondary)
    }
}

// ── Trends ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary, maxLines = 1)
        Text(label, fontSize = 11.sp, color = PulseColors.textMuted, maxLines = 2)
    }
}

@Composable
private fun TrendsTab(s: DrinksRepository.Snapshot) {
    val tracked = s.weeks.filter { it.first.plusDays(6) >= s.trackedSince }
    val completed = tracked.dropLast(1)
    val avg = completed.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(avg?.let { std(it) } ?: "—", "average week", Modifier.weight(1f).fillMaxHeight())
        Stat("${s.streaks.best}", "best dry streak", Modifier.weight(1f).fillMaxHeight())
        Stat("${s.streaks.current}", "dry days now", Modifier.weight(1f).fillMaxHeight())
    }
    DrinkCard("Last 8 weeks") {
        WeeksChart(s)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(16.dp).height(2.dp).background(PulseColors.textMuted))
            Text("your limit of ${std(s.weeklyLimit)}", fontSize = 11.sp, color = PulseColors.textMuted)
        }
    }
    s.savings?.takeIf { it.drinksAvoided >= 0.5 }?.let { sv ->
        DrinkCard("Compared with your typical week") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(std(sv.drinksAvoided), "fewer drinks", Modifier.weight(1f).fillMaxHeight())
                sv.money?.let { Stat("%.0f".format(it), "saved", Modifier.weight(1f).fillMaxHeight()) }
                Stat("%,d".format(sv.kcal.roundToInt()), "kcal not drunk", Modifier.weight(1f).fillMaxHeight())
            }
        }
    } ?: run {
        if (DrinkPrefs.baselinePerWeek <= 0) {
            Text("Set your typical week in settings to see drinks, money and calories saved.", fontSize = 12.sp, color = PulseColors.textMuted)
        }
    }
}

@Composable
private fun WeeksChart(s: DrinksRepository.Snapshot) {
    val max = maxOf(s.weeks.maxOfOrNull { it.second } ?: 0.0, s.weeklyLimit, 1.0)
    val limitColor = PulseColors.textMuted
    val barColor = PulseColors.drinks
    val warn = PulseColors.warning
    val fmt = DateTimeFormatter.ofPattern("d/M")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = s.weeks.size.coerceAtLeast(1)
            val slot = size.width / n
            s.weeks.forEachIndexed { i, (_, v) ->
                val h = (v / max * size.height).toFloat()
                drawRect(
                    if (s.weeklyLimit > 0 && v > s.weeklyLimit) warn else barColor,
                    topLeft = Offset(i * slot + slot * 0.2f, size.height - h),
                    size = Size(slot * 0.6f, h),
                )
            }
            if (s.weeklyLimit > 0) {
                val y = (size.height - s.weeklyLimit / max * size.height).toFloat()
                drawLine(limitColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            s.weeks.forEachIndexed { i, (start, _) ->
                Text(
                    if (i % 2 == 1 || i == s.weeks.lastIndex) start.format(fmt) else "",
                    fontSize = 10.sp, color = PulseColors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Support ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SupportRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = PulseColors.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = PulseColors.textMuted)
        }
    }
}

@Composable
private fun SupportTab(s: DrinksRepository.Snapshot, onBreathe: () -> Unit, onCoach: ((String) -> Unit)?, onAuditDone: () -> Unit) {
    DrinkCard("When you want a drink") {
        SupportRow(Icons.Filled.Air, "Ride it out", "3 minutes of slow breathing. An urge usually passes within 20 minutes.", onBreathe)
        if (onCoach != null) {
            SupportRow(Icons.Filled.AutoAwesome, "Talk it through", "Plan the next few days with the coach.") {
                onCoach("I'd like help with my drinking. This week I'm at ${std(s.week.standardDrinks)} of my ${std(s.weeklyLimit)}-drink limit, with ${s.week.dryDays} dry days. Can you help me plan the next few days?")
            }
        }
    }
    AuditCard(s, onAuditDone)
    DrinkCard("Support in Brazil") {
        SupportRow(Icons.Filled.Phone, "CVV · 188", "Free emotional support, any time, by phone or chat.", null)
        SupportRow(Icons.Filled.LocalHospital, "CAPS AD", "Free public care for alcohol use. Ask at your local health unit.", null)
        SupportRow(Icons.Filled.EventAvailable, "Alcoólicos Anônimos", "Meetings across the country: aa.org.br", null)
        Text("Anywhere: your doctor. If you drink most days, don't stop all at once; a doctor can help you cut down safely.", fontSize = 12.sp, color = PulseColors.textMuted)
    }
}

@Composable
private fun AuditCard(s: DrinksRepository.Snapshot, onDone: () -> Unit) {
    var taking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var sex by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { sex = runCatching { PulseLoopDatabase.getInstance(context).userProfileDao().get()?.sex }.getOrNull() }
    val score = DrinkPrefs.auditScore
    DrinkCard("3-question check") {
        if (score != null) {
            val level = AuditC.level(score, sex)
            Text(level.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (level == AuditC.Level.LOW) PulseColors.accent else PulseColors.warning)
            Text(AuditC.advice(level), fontSize = 13.sp, color = PulseColors.textSecondary)
            Text("Score $score of 12 · ${DrinkPrefs.auditDate}", fontSize = 11.sp, color = PulseColors.textMuted)
        } else {
            Text("The WHO's short alcohol check (AUDIT-C). Three questions, under a minute. A screen, not a diagnosis.", fontSize = 13.sp, color = PulseColors.textSecondary)
        }
        if (s.auditDue) {
            OutlinedButton(onClick = { taking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (score == null) "Take the check" else "Retake (every 3 months)")
            }
        }
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
                                        .heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = answers[qi] == oi, onClick = null)
                                    Text(o, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = answers.all { it >= 0 }, onClick = {
                    DrinkPrefs.setAudit(LocalDate.now(), AuditC.score(answers))
                    taking = false
                    onDone()
                }) { Text("See result") }
            },
            dismissButton = { TextButton(onClick = { taking = false }) { Text("Cancel") } },
        )
    }
}

// ── Settings (sheet) ────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinkSettings(onChange: () -> Unit, onTurnedOff: () -> Unit) {
    var limit by remember { mutableIntStateOf(DrinkPrefs.weeklyLimit.roundToInt()) }
    var dry by remember { mutableIntStateOf(DrinkPrefs.dryDaysTarget) }
    var typical by remember { mutableIntStateOf(DrinkPrefs.baselinePerWeek.roundToInt()) }
    var grams by remember { mutableStateOf(DrinkPrefs.gramsPerStandard) }
    var price by remember { mutableStateOf(DrinkPrefs.pricePerDrink.takeIf { it > 0 }?.let { "%.2f".format(it) } ?: "") }
    var confirmOff by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Drinks settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
        Stepper("Weekly limit", "$limit", { limit = (limit - 1).coerceAtLeast(0); DrinkPrefs.weeklyLimit = limit.toDouble(); onChange() },
            { limit = (limit + 1).coerceAtMost(60); DrinkPrefs.weeklyLimit = limit.toDouble(); onChange() })
        Stepper("Dry days a week", "$dry", { dry = (dry - 1).coerceAtLeast(0); DrinkPrefs.dryDaysTarget = dry; onChange() },
            { dry = (dry + 1).coerceAtMost(7); DrinkPrefs.dryDaysTarget = dry; onChange() })
        Stepper("Typical week before", "$typical", { typical = (typical - 1).coerceAtLeast(0); DrinkPrefs.baselinePerWeek = typical.toDouble(); onChange() },
            { typical = (typical + 1).coerceAtMost(80); DrinkPrefs.baselinePerWeek = typical.toDouble(); onChange() },
            note = "For drinks, money and calories saved")
        Text("Standard drink", fontSize = 14.sp, color = PulseColors.textPrimary)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10.0 to "10 g (WHO)", 8.0 to "8 g (UK)", 14.0 to "14 g (US)").forEach { (g, l) ->
                Pill(l, grams == g, PulseColors.drinks) { grams = g; DrinkPrefs.gramsPerStandard = g; onChange() }
            }
        }
        OutlinedTextField(
            value = price,
            onValueChange = {
                price = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7)
                DrinkPrefs.pricePerDrink = price.replace(',', '.').toDoubleOrNull() ?: 0.0
                onChange()
            },
            label = { Text("Usual price of a drink (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Your drinks stay on this phone. They aren't sent to Health Connect or included in diagnostics.", fontSize = 11.sp, color = PulseColors.textMuted)
        TextButton(onClick = { confirmOff = true }) {
            Icon(Icons.Filled.Close, null, tint = PulseColors.danger, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Turn off Drinks", color = PulseColors.danger)
        }
    }
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text("Turn off Drinks?") },
        text = { Text("The tile and the morning-after line disappear. Your logged drinks stay on this phone until you delete them or clear your data.") },
        confirmButton = { TextButton(onClick = { DrinkPrefs.setEnabled(false); confirmOff = false; onTurnedOff() }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Keep") } },
    )
}

// ── Today tile ──────────────────────────────────────────────────────────────────────────────

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
        modifier = Modifier.clickable(role = Role.Button, onClick = onAdd).padding(vertical = 2.dp),
    )
}
