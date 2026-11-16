package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.HealthInsightsRepository
import com.pulseloop.service.RestMode
import com.pulseloop.service.SymptomRadar
import com.pulseloop.ui.calm.CalmBadge
import com.pulseloop.ui.calm.CalmCard
import com.pulseloop.ui.calm.CalmColors
import com.pulseloop.ui.calm.CalmOutlinedButton
import com.pulseloop.ui.calm.CalmSection
import com.pulseloop.ui.calm.CalmShape
import com.pulseloop.ui.calm.CalmTopBar
import com.pulseloop.ui.calm.CalmType
import com.pulseloop.ui.calm.ExpandableText
import com.pulseloop.ui.calm.LevelScale
import com.pulseloop.ui.calm.StateTimeline
import com.pulseloop.ui.calm.UnderlineTabs
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * Symptom Radar on its own screen, the first in the new look: one statement, the scale it sits
 * on, what is under strain, and how the last five weeks went. Opened from My Health.
 */
@Composable
fun SymptomRadarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val snap by produceState<HealthInsightsRepository.Snapshot?>(null, refresh) {
        loadFailed = false
        value = runCatching { HealthInsightsRepository.load(db) }.onFailure { loadFailed = true }.getOrNull()
    }
    var restUntil by remember { mutableStateOf(RestMode.until(context)) }
    // 1 = Today, 0 = Yesterday.
    var tab by rememberSaveable { mutableIntStateOf(1) }

    Column(Modifier.fillMaxSize().background(CalmColors.page)) {
        CalmTopBar(tr("Symptom Radar"), onBack)
        LazyColumn(
            // Tab content caps at 720dp on wide windows, as the rest of the app does.
            Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 720.dp).fillMaxWidth(),
            contentPadding = PaddingValues(start = CalmShape.gutter, end = CalmShape.gutter, bottom = 48.dp),
        ) {
            val s = snap
            if (s == null) {
                item {
                    if (loadFailed) {
                        Column(Modifier.padding(top = 24.dp)) {
                            Text(tr("Couldn't load your health history."), style = CalmType.body, color = CalmColors.text)
                            PulseButton(PulseButtonStyle.Text, onClick = { refresh++ }) { Text(tr("Try again")) }
                        }
                    } else {
                        // Only after a moment, so a quick read doesn't flash a line before the screen.
                        var slow by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { delay(400); slow = true }
                        if (slow) Text(tr("Reading your history…"), style = CalmType.bodySmall, color = CalmColors.textMuted, modifier = Modifier.padding(top = 24.dp))
                    }
                }
                return@LazyColumn
            }
            val today = LocalDate.now()
            val result = if (tab == 1) s.radar else s.radarHistory.firstOrNull { it.first == today.minusDays(1) }?.second
            // No night that day is not the same as a short baseline: say which it is.
            val noNight = result == null
            val learning = noNight || result!!.baselineNights < SymptomRadar.MIN_BASELINE

            item { UnderlineTabs(listOf(tr("Yesterday"), tr("Today")), tab, { tab = it }, Modifier.padding(bottom = 24.dp)) }

            if (noNight) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("No night to read"), style = CalmType.display, color = CalmColors.text)
                        Text(tr("The radar reads a night of sleep from your ring. Wear it to bed and it will have one."), style = CalmType.body, color = CalmColors.textSecondary)
                    }
                }
            } else if (learning) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Learning your normal"), style = CalmType.display, color = CalmColors.text)
                        Text(
                            trf("The radar compares each night with your own usual. It starts after {0} nights; it has {1} so far.",
                                SymptomRadar.MIN_BASELINE, result!!.baselineNights),
                            style = CalmType.body, color = CalmColors.textSecondary,
                        )
                    }
                }
            } else {
                val r = result!!
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(r.level.label, style = CalmType.display, color = CalmColors.text)
                        ExpandableText(
                            r.level.summary + " " + tr("The radar compares last night's temperature, resting heart rate, heart rate variability, breathing and the day's activity with your own usual over the past four weeks. Several moving the wrong way together is what it looks for."),
                            collapsedLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        LevelScale(SymptomRadar.Level.entries.map { it.label }, LEVEL_COLORS, r.level.ordinal)
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 32.dp)) {
                    RestModeAction(restUntil) { days -> RestMode.set(context, days); restUntil = RestMode.until(context) }
                }
            }

            if (!learning) {
                val r = result!!
                val flagged = r.flagged
                item {
                    CalmSection(tr("Biometrics under strain")) {
                        if (flagged.isEmpty()) {
                            Text(tr("Nothing is outside your usual range."), style = CalmType.body, color = CalmColors.textSecondary)
                        } else flagged.forEach { sig ->
                            CalmCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sig.metric.title, style = CalmType.title, color = CalmColors.text, modifier = Modifier.weight(1f))
                                    CalmBadge(signalBadge(sig))
                                }
                                Text(signalDetail(sig), style = CalmType.bodySmall, color = CalmColors.textSecondary)
                            }
                        }
                    }
                }
            }

            if (s.radarHistory.any { it.second.baselineNights >= SymptomRadar.MIN_BASELINE }) item {
                CalmSection(tr("Recently")) {
                    val days = (SYMPTOM_HISTORY_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
                    val byDay = s.radarHistory.filter { it.second.baselineNights >= SymptomRadar.MIN_BASELINE }.toMap()
                    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
                    StateTimeline(
                        days = days,
                        levels = days.map { byDay[it]?.level?.ordinal },
                        levelNames = SymptomRadar.Level.entries.map { it.label },
                        levelColors = LEVEL_COLORS,
                        selectedLabel = { d, name -> "${d.format(fmt)} · $name" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Text(
                    tr("Not a diagnosis. Rest, fluids and an easier day help; see a doctor if you feel unwell."),
                    style = CalmType.bodySmall, color = CalmColors.textMuted,
                    modifier = Modifier.padding(top = CalmShape.sectionGap),
                )
            }
        }
    }
}

private const val SYMPTOM_HISTORY_DAYS = HealthInsightsRepository.RADAR_HISTORY_DAYS

private val LEVEL_COLORS get() = listOf(PulseColors.info, PulseColors.warning, PulseColors.danger)

@Composable
private fun RestModeAction(restUntil: LocalDate?, onRest: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalmOutlinedButton(if (restUntil != null) tr("End Rest Mode") else tr("Turn on Rest Mode"), onClick = { onRest(if (restUntil != null) null else 3) })
        Text(
            restUntil?.let { trf("On until {0}: activity nudges paused, the coach eases off.", it.format(DateTimeFormatter.ofPattern("EEE d MMM"))) }
                ?: tr("Feeling unwell? Pause activity nudges for 3 days."),
            style = CalmType.bodySmall, color = CalmColors.textMuted,
        )
    }
}

/**
 * "Inactivity" flags fewer steps, so a plain "Lower" beside it would read as less inactive:
 * the badge names the direction of the measured value.
 */
private fun signalBadge(sig: SymptomRadar.Signal): String = when {
    sig.metric == SymptomRadar.Metric.INACTIVE -> tr("Fewer steps")
    sig.value > sig.usual -> tr("Higher")
    else -> tr("Lower")
}

/** The value against the usual, in the app's language (Signal.detail is English-only). */
private fun signalDetail(sig: SymptomRadar.Signal): String {
    val steps = sig.metric == SymptomRadar.Metric.INACTIVE
    fun fmt(v: Double) = if (steps) "%,d".format(kotlin.math.round(v).toInt()) else "%.1f".format(v)
    val unit = if (steps) tr("steps") else sig.metric.unit
    return if (sig.value > sig.usual) trf("{0} {1}, higher than your usual {2}", fmt(sig.value), unit, fmt(sig.usual))
    else trf("{0} {1}, lower than your usual {2}", fmt(sig.value), unit, fmt(sig.usual))
}
