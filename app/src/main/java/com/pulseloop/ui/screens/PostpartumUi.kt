package com.pulseloop.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.SleepStage
import com.pulseloop.service.Postpartum
import com.pulseloop.service.ReadinessRepository
import com.pulseloop.ui.i18n.L10n
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ── Profile: switching the mode on ──────────────────────────────────────────────────────────

/** User Profile → Life stage. The postpartum mode is only ever switched on here, by her. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PostpartumProfileCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(Postpartum.enabled) }
    var birth by remember { mutableStateOf(Postpartum.birthDate) }
    var feeding by remember { mutableStateOf(Postpartum.feeding) }
    var picking by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    fun save() {
        Postpartum.set(enabled, birth, feeding)
        scope.launch { syncCalorieGoal(context) }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("LIFE STAGE"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Postpartum / breastfeeding"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                Text(
                    tr("Pauses cycle predictions while breastfeeding, adds an alcohol-to-feeding estimate, a mood check-in and warning signs, and tells the coach."),
                    fontSize = 12.sp, color = PulseColors.textMuted,
                )
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                if (it && birth == null) picking = true
                save()
            })
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Baby's birth date"), fontSize = 14.sp, color = PulseColors.textSecondary, modifier = Modifier.weight(1f))
                TextButton(onClick = { picking = true }) { Text(birth?.format(fmt) ?: tr("Set")) }
            }
            Text(tr("Feeding"), fontSize = 14.sp, color = PulseColors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Postpartum.Feeding.EXCLUSIVE to tr("Breastfeeding only"),
                    Postpartum.Feeding.MIXED to tr("Breastfeeding and formula"),
                    Postpartum.Feeding.NONE to tr("Not breastfeeding"),
                ).forEach { (f, label) -> Chip(label, feeding == f) { feeding = f; save() } }
            }
            if (birth != null) OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(tr("Open postpartum view")) }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (birth ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?.takeIf { !it.isAfter(LocalDate.now()) }?.let { birth = it; save() }
                    picking = false
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Cancel")) } },
        ) {
            Text(tr("When was your baby born?"), modifier = Modifier.padding(start = 24.dp, top = 16.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

/**
 * Keeps the intake goal's breastfeeding allowance in step with the mode: adds the difference between
 * what the stage calls for and what was added before, so switching off or weaning takes exactly that
 * back and a goal the user edited by hand otherwise stays hers.
 */
suspend fun syncCalorieGoal(context: Context, today: LocalDate = LocalDate.now()) {
    val db = PulseLoopDatabase.getInstance(context)
    val goal = db.userGoalDao().get() ?: return
    val target = Postpartum.birthDate?.takeIf { Postpartum.active(today) }
        ?.let { Postpartum.calorieBonus(it, today, Postpartum.feeding) } ?: 0
    val delta = target - Postpartum.appliedCalorieBonus
    if (delta == 0 || goal.intakeCalories == null) return
    db.userGoalDao().upsert(goal.copy(intakeCalories = goal.intakeCalories + delta))
    Postpartum.appliedCalorieBonus = target
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        label, fontSize = 13.sp,
        color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
        modifier = Modifier.clip(shape).background(if (on) PulseColors.accent else PulseColors.cardSoft)
            .border(1.dp, if (on) PulseColors.accent else PulseColors.borderSubtle, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ── The postpartum view ─────────────────────────────────────────────────────────────────────

private data class PostpartumData(
    val alcoholClearAt: Long?,
    val lastNight: Postpartum.NightBreaks?,
    val restingHr: List<Pair<Int, Double>>,
    val hrv: List<Pair<Int, Double>>,
    val lam: Postpartum.Lam?,
)

private suspend fun loadPostpartum(db: PulseLoopDatabase, birth: LocalDate, now: Long, zone: ZoneId): PostpartumData {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val drinks = db.drinkDao().between(now - 13 * 3_600_000L, now + 1).map { it.timestamp to it.grams }
    val inputs = runCatching { ReadinessRepository.loadInputs(db, now, zone) }.getOrNull()
    val nights = inputs?.let { it.history + listOfNotNull(it.lastNight) }.orEmpty()
    val last = db.sleepSessionDao().recent(3).firstOrNull { it.endAt > now - 30 * 3_600_000L }
    val breaks = last?.let { s ->
        val blocks = db.sleepStageBlockDao().forSession(s.id).sortedBy { it.startAt }
        val minutes = ((s.endAt - s.startAt) / 60_000L).toInt().coerceIn(0, 24 * 60)
        val stages = MutableList(minutes) { SleepStage.AWAKE }
        blocks.forEach { b ->
            val from = ((b.startAt - s.startAt) / 60_000L).toInt()
            val stage = runCatching { SleepStage.valueOf(b.stageRaw) }.getOrDefault(SleepStage.UNKNOWN)
            for (m in from until from + b.durationMinutes) if (m in stages.indices) stages[m] = stage
        }
        Postpartum.nightBreaks(stages)
    }
    val periods = db.periodDao().all().map { LocalDate.ofEpochDay(it.startDay) }
    return PostpartumData(
        alcoholClearAt = if (Postpartum.breastfeeding) Postpartum.alcoholClearAt(drinks, now) else null,
        lastNight = breaks,
        restingHr = Postpartum.weeklyMedians(nights.mapNotNull { n -> n.restingHr?.let { n.day to it } }, birth),
        hrv = Postpartum.weeklyMedians(nights.mapNotNull { n -> n.hrv?.let { n.day to it } }, birth),
        lam = if (Postpartum.breastfeeding) Postpartum.lam(birth, today, Postpartum.feeding, periods) else null,
    )
}

@Composable
fun PostpartumScreen(onBack: () -> Unit, onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }
    val birth = Postpartum.birthDate?.takeIf { Postpartum.active(today) }
    var data by remember { mutableStateOf<PostpartumData?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var checkIn by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(Postpartum.epdsHistory()) }
    LaunchedEffect(birth) {
        if (birth != null) {
            data = try {
                loadPostpartum(db, birth, System.currentTimeMillis(), zone)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        // Set even on a failed read: the cards below are gated on it, and the warning signs
        // among them must never stay hidden.
        loaded = true
    }

    if (checkIn) {
        EpdsCheckIn(onDone = { result ->
            if (result != null) { Postpartum.saveEpds(result); history = Postpartum.epdsHistory() }
            checkIn = false
        })
        return
    }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
            Text(tr("Postpartum"), modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (birth == null) {
                Card {
                    Text(tr("Set your baby's birth date in your profile to see this view."), fontSize = 14.sp, color = PulseColors.textSecondary)
                    Button(onClick = onOpenProfile) { Text(tr("Open profile")) }
                }
                return@Column
            }
            val weeks = Postpartum.weeksSince(birth, today)
            Card {
                Text(trf("Week {0} after birth", weeks), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                Text(
                    when (Postpartum.feeding) {
                        Postpartum.Feeding.EXCLUSIVE -> tr("Breastfeeding only")
                        Postpartum.Feeding.MIXED -> tr("Breastfeeding and formula")
                        Postpartum.Feeding.NONE -> tr("Not breastfeeding")
                    },
                    fontSize = 13.sp, color = PulseColors.textSecondary,
                )
                Text(
                    tr("Your body is still recovering. Broken nights and a resting heart rate that wanders are expected for months, so compare yourself with last week, not with before."),
                    fontSize = 13.sp, color = PulseColors.textPrimary,
                )
            }

            // The data cards land between the hero and the mood check-in; drawn before the read
            // finished, everything below jumped down as they arrived. Hold the rest until then.
            if (!loaded) return@Column
            val d = data
            d?.alcoholClearAt?.let { clear ->
                val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(clear).atZone(zone))
                Card {
                    Text(trf("Alcohol: estimated clear for feeding around {0}", time), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.warning)
                    Text(
                        tr("CDC suggests waiting at least 2 hours per standard drink before breastfeeding. It's an estimate: bodies differ, and pumping and dumping doesn't make it faster. Not drinking is the safest choice."),
                        fontSize = 12.sp, color = PulseColors.textSecondary,
                    )
                }
            }

            d?.lastNight?.let { n ->
                Card {
                    Text(tr("LAST NIGHT"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                    Text(
                        trf("{0} asleep · woke {1} times · longest stretch {2}", fmtMin(n.asleepMin), n.wakings, fmtMin(n.longestSleepMin)),
                        fontSize = 15.sp, color = PulseColors.textPrimary,
                    )
                    Text(
                        tr("After birth, sleep is broken rather than short. The longest unbroken stretch is the number to watch; it usually grows over the first months."),
                        fontSize = 12.sp, color = PulseColors.textMuted,
                    )
                }
            }

            if (d != null && (d.restingHr.isNotEmpty() || d.hrv.isNotEmpty())) {
                Card {
                    Text(tr("RECOVERY, WEEK BY WEEK"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                    val weeksShown = (d.restingHr.map { it.first } + d.hrv.map { it.first }).distinct().sorted().takeLast(8)
                    weeksShown.forEach { w ->
                        val rhr = d.restingHr.firstOrNull { it.first == w }?.second
                        val hrv = d.hrv.firstOrNull { it.first == w }?.second
                        Text(
                            trf("Week {0}: resting HR {1} · HRV {2}", w, rhr?.let { "${it.roundToInt()} bpm" } ?: "–", hrv?.let { "${it.roundToInt()} ms" } ?: "–"),
                            fontSize = 13.sp, color = PulseColors.textPrimary,
                        )
                    }
                    Text(
                        tr("Resting heart rate often drops below your old normal around week 7 and takes months to settle; HRV tends to climb back. Differences between women are large."),
                        fontSize = 12.sp, color = PulseColors.textMuted,
                    )
                }
            }

            // Mood check-in: the EPDS, the validated questionnaire. Answers stay on this phone.
            Card {
                Text(tr("MOOD CHECK-IN"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                val last = history.lastOrNull()
                if (last != null) {
                    Text(trf("Last check-in {0}: score {1} of 30", last.date, last.score), fontSize = 14.sp, color = PulseColors.textPrimary)
                    EpdsGuidance(last.score, last.selfHarm)
                } else {
                    Text(tr("Ten quick questions about the last 7 days, used by health services worldwide to spot postpartum depression early."), fontSize = 13.sp, color = PulseColors.textSecondary)
                }
                Postpartum.nextCheckIn(birth, today, history)?.let { next ->
                    Text(trf("Suggested next check-in: {0}", next.format(DateTimeFormatter.ofPattern("d MMM"))), fontSize = 12.sp, color = PulseColors.textMuted)
                }
                Button(onClick = { checkIn = true }) { Text(tr("Start check-in")) }
                Text(tr("Your answers stay on this phone. They aren't in backups, diagnostics or Health Connect."), fontSize = 11.sp, color = PulseColors.textMuted)
            }

            d?.lam?.let { lam ->
                Card {
                    Text(tr("BREASTFEEDING AS CONTRACEPTION (LAM)"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                    LamRow(tr("Baby under 6 months"), lam.babyUnderSixMonths, if (lam.babyUnderSixMonths) trf("{0} days to go", lam.daysToSixMonths) else null)
                    LamRow(tr("No period since 8 weeks after birth"), lam.noPeriodSinceBirth, null)
                    LamRow(tr("Breastfeeding only, day and night"), lam.fullBreastfeeding, null)
                    Text(
                        if (lam.allHold) tr("All three hold. LAM is about 98% effective only while all three do.")
                        else tr("Not all three hold, so LAM doesn't apply now."),
                        fontSize = 13.sp, color = PulseColors.textPrimary,
                    )
                    Text(tr("This is information, not contraception advice. Talk to your clinician about the method that suits you."), fontSize = 11.sp, color = PulseColors.textMuted)
                }
            }

            Card {
                Text(tr("NUTRITION"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                val bonus = Postpartum.calorieBonus(birth, today, Postpartum.feeding)
                if (bonus > 0) Text(trf("Breastfeeding uses about {0} kcal a day more; your intake goal includes it.", bonus), fontSize = 13.sp, color = PulseColors.textPrimary)
                Text(
                    tr("Drink to thirst and keep water where you feed. Ask your clinician about iodine, DHA and your baby's vitamin D. This is no time for a diet."),
                    fontSize = 12.sp, color = PulseColors.textSecondary,
                )
            }

            WarningSignsCard()
            ResourcesCard(context)
            Text(
                tr("PulseLoop doesn't detect mastitis, fever, depression, preeclampsia, milk supply or returning fertility. The ring's numbers are context, not a diagnosis."),
                fontSize = 11.sp, color = PulseColors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun LamRow(label: String, holds: Boolean, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (holds) "✓" else "✗", fontSize = 15.sp, color = if (holds) PulseColors.accent else PulseColors.warning, modifier = Modifier.width(24.dp))
        Text(label, fontSize = 13.sp, color = PulseColors.textPrimary, modifier = Modifier.weight(1f))
        // Bounded so a translated "days to go" can't squeeze the condition beside it.
        detail?.let { Text(it, fontSize = 12.sp, color = PulseColors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) }
    }
}

/** CDC "Hear Her" urgent maternal warning signs, for up to a year after birth. Always plain, never hidden. */
@Composable
private fun WarningSignsCard() {
    Card {
        Text(tr("Get care now if you notice"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PulseColors.danger)
        listOf(
            tr("A headache that won't go away or gets worse"),
            tr("Dizziness or fainting"),
            tr("Changes in your vision"),
            tr("Fever of 38 °C or higher"),
            tr("Extreme swelling of your hands or face"),
            tr("Thoughts of harming yourself or your baby"),
            tr("Trouble breathing, chest pain or a racing heart"),
            tr("Severe belly pain, nausea or vomiting"),
            tr("Heavy bleeding or discharge"),
            tr("A painful, red or swollen leg or arm"),
            tr("A hot, painful, red area on a breast with fever or chills"),
            tr("Overwhelming tiredness"),
        ).forEach { Text("• $it", fontSize = 13.sp, color = PulseColors.textPrimary) }
        Text(tr("Blood pressure of 160/110 or higher is an emergency. A ring's blood pressure isn't validated: confirm with a cuff, and never let a normal ring reading reassure you."), fontSize = 12.sp, color = PulseColors.textSecondary)
        Text(tr("Source: CDC Hear Her urgent maternal warning signs."), fontSize = 11.sp, color = PulseColors.textMuted)
    }
}

@Composable
private fun ResourcesCard(context: Context) {
    Card {
        Text(tr("HELP"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
        ResourceRow(tr("SAMU (emergencies)"), "192") { dial(context, "192") }
        ResourceRow(tr("CVV (emotional support, 24 h)"), "188") { dial(context, "188") }
        ResourceRow(tr("Disque Saúde (find a human milk bank)"), "136") { dial(context, "136") }
        ResourceRow(tr("Medicines while breastfeeding"), "e-lactancia") { open(context, "https://www.e-lactancia.org/") }
        ResourceRow(tr("Human milk banks (rBLH)"), "rblh.fiocruz.br") { open(context, "https://rblh.fiocruz.br/") }
    }
}

@Composable
private fun ResourceRow(label: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = PulseColors.textPrimary, modifier = Modifier.weight(1f))
        Text(action, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.accent)
    }
}

private fun dial(context: Context, number: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ── EPDS ────────────────────────────────────────────────────────────────────────────────────

@Serializable
private data class EpdsOption(val text: String, val score: Int)

@Serializable
private data class EpdsItem(val text: String, val options: List<EpdsOption>)

@Serializable
private data class EpdsForm(val instruction: String, val items: List<EpdsItem>)

@Serializable
private data class EpdsAsset(val en: EpdsForm, val pt: EpdsForm, val citation_en: String, val citation_pt: String)

private val epdsJson = Json { ignoreUnknownKeys = true }

/** What a score means, and item 10 first: any thought of self-harm gets the crisis lines, whatever the total. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpdsGuidance(score: Int, selfHarm: Int) {
    val context = LocalContext.current
    if (selfHarm > 0) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.danger.copy(alpha = 0.15f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tr("You said thoughts of harming yourself have come up. Please talk to someone today."), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
            Text(tr("CVV listens 24 hours a day, free and anonymous. In danger now, call SAMU."), fontSize = 13.sp, color = PulseColors.textPrimary)
            // FlowRow: the two translated labels don't fit side by side, and a crisis button
            // pushed off the edge of the screen is the worst place for an overflow.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dial(context, "188") }) { Text(tr("Call CVV 188")) }
                OutlinedButton(onClick = { dial(context, "192") }) { Text(tr("Call SAMU 192")) }
            }
        }
    }
    Text(
        when (Postpartum.epdsBand(score)) {
            Postpartum.EpdsBand.LOW -> tr("A low score. Keep checking in; how you feel can change in the months after birth.")
            Postpartum.EpdsBand.POSSIBLE -> tr("This score suggests possible depression. Talk to your doctor, nurse or midwife soon.")
            Postpartum.EpdsBand.LIKELY -> tr("This score suggests depression is likely. Please talk to your doctor, nurse or midwife this week.")
        },
        fontSize = 13.sp, color = PulseColors.textPrimary,
    )
    Text(tr("A screening questionnaire, not a diagnosis."), fontSize = 11.sp, color = PulseColors.textMuted)
}

/** The Edinburgh Postnatal Depression Scale, worded exactly as validated (assets/epds.json). */
@Composable
private fun EpdsCheckIn(onDone: (Postpartum.EpdsResult?) -> Unit) {
    val context = LocalContext.current
    val asset = remember {
        runCatching { epdsJson.decodeFromString(EpdsAsset.serializer(), context.assets.open("epds.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }
    if (asset == null) { LaunchedEffect(Unit) { onDone(null) }; return }
    val portuguese = L10n.isPortuguese
    val form = if (portuguese) asset.pt else asset.en
    val answers = remember { mutableStateListOf<Int?>().apply { repeat(form.items.size) { add(null) } } }
    var result by remember { mutableStateOf<Postpartum.EpdsResult?>(null) }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = { onDone(result) }, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
            Text(tr("Mood check-in"), modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = result
            if (r != null) {
                Card {
                    Text(trf("Your score: {0} of 30", r.score), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    EpdsGuidance(r.score, r.selfHarm)
                }
                Button(onClick = { onDone(r) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Done")) }
                return@Column
            }
            Text(form.instruction, fontSize = 14.sp, color = PulseColors.textSecondary)
            form.items.forEachIndexed { i, item ->
                Card {
                    Text("${i + 1}. ${item.text}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                    item.options.forEachIndexed { o, opt ->
                        val on = answers[i] == o
                        Text(
                            opt.text, fontSize = 14.sp,
                            color = if (on) PulseColors.onAccent else PulseColors.textPrimary,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (on) PulseColors.accent else PulseColors.cardSoft)
                                .clickable { answers[i] = o }.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Button(
                enabled = answers.all { it != null },
                onClick = {
                    val scores = form.items.mapIndexed { i, item -> item.options[answers[i]!!].score }
                    result = Postpartum.EpdsResult(LocalDate.now().toString(), scores.sum(), scores.last())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("See my score")) }
            Text(if (portuguese) asset.citation_pt else asset.citation_en, fontSize = 11.sp, color = PulseColors.textMuted)
            Spacer(Modifier.height(24.dp))
        }
    }
}
