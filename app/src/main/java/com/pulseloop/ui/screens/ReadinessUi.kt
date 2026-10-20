package com.pulseloop.ui.screens

import com.pulseloop.ui.theme.dither
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.Readiness
import com.pulseloop.service.ReadinessCopy
import com.pulseloop.service.ReadinessRepository
import com.pulseloop.service.ScoreContributor
import com.pulseloop.service.asScoreContributor
import com.pulseloop.ui.theme.PulseColors

/*
 * Oura-style score surfaces (docs/oura-ux-analysis.md, phase 2): the three-ring header on Today
 * and the Readiness detail screen. All numbers come from service/Readiness.kt; this file only
 * draws them and never invents one — a Learning or NoSleep result renders as a dash.
 */

/** Colour for a score band, used for rings, status words and contributor bars. */
fun bandColor(band: Readiness.Band): Color = when (band) {
    Readiness.Band.OPTIMAL -> PulseColors.success
    Readiness.Band.GOOD -> PulseColors.info
    Readiness.Band.FAIR -> PulseColors.warning
    Readiness.Band.PAY_ATTENTION -> PulseColors.danger
}

/** Loads today's readiness, recomputing whenever [key] changes (new data, a new day). */
@Composable
fun rememberReadiness(db: PulseLoopDatabase, key: Any?): Readiness.Result? {
    val result by produceState<Readiness.Result?>(initialValue = null, db, key) {
        value = runCatching { ReadinessRepository.compute(db) }.getOrNull()
    }
    return result
}

/** A score arc: track, coloured progress, the number centred. [value] null draws an empty ring. */
@Composable
fun ScoreRing(
    value: Int?,
    color: Color,
    size: Dp,
    stroke: Dp,
    centerText: String = value?.toString() ?: "—",
    centerSize: Int = 22,
) {
    Box(
        Modifier.size(size).clearAndSetSemantics {
            // One spoken value instead of a bare number; an empty ring says so.
            contentDescription = value?.let { "Score $it out of 100" } ?: "No score"
            if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0, 100).toFloat(), 0f..100f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(
                color = PulseColors.borderStrong, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w, cap = StrokeCap.Round),
            )
            if (value != null && value > 0) {
                drawArc(
                    color = color, startAngle = 135f, sweepAngle = 270f * (value.coerceIn(0, 100) / 100f),
                    useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            centerText, fontSize = centerSize.sp, fontWeight = FontWeight.SemiBold,
            color = if (value != null) PulseColors.textPrimary else PulseColors.textMuted,
        )
    }
}

@Composable
private fun HeaderRing(
    label: String,
    value: Int?,
    caption: String,
    color: Color,
    onTap: () -> Unit,
    modifier: Modifier,
    centerText: String = value?.toString() ?: "—",
) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTap).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScoreRing(value = value, color = color, size = 84.dp, stroke = 7.dp, centerText = centerText)
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
        Text(caption, fontSize = 11.sp, color = PulseColors.textMuted, maxLines = 1)
    }
}

/**
 * Today's header: Readiness, Sleep and Activity side by side, then one sentence about today.
 */
@Composable
fun ScoreHeader(
    readiness: Readiness.Result?,
    sleepScore: Int?,
    activity: com.pulseloop.service.ActivityScore.Result?,
    onReadiness: () -> Unit,
    onSleep: () -> Unit,
    onActivity: () -> Unit,
) {
    val score = readiness as? Readiness.Result.Score
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            HeaderRing(
                label = "Readiness",
                value = score?.value,
                caption = when (readiness) {
                    is Readiness.Result.Score -> readiness.band.label
                    is Readiness.Result.Learning -> "Learning"
                    Readiness.Result.NoSleep -> "No sleep yet"
                    null -> " "
                },
                color = score?.let { bandColor(it.band) } ?: PulseColors.readiness,
                onTap = onReadiness,
                modifier = Modifier.weight(1f),
            )
            HeaderRing(
                label = "Sleep",
                value = sleepScore,
                caption = sleepScore?.let { Readiness.Band.of(it).label } ?: "No data",
                color = PulseColors.sleep,
                onTap = onSleep,
                modifier = Modifier.weight(1f),
            )
            HeaderRing(
                label = "Activity",
                value = activity?.value,
                caption = activity?.band?.label ?: "No data",
                color = PulseColors.steps,
                onTap = onActivity,
                modifier = Modifier.weight(1f),
            )
        }
        if (readiness != null) {
            Text(
                ReadinessCopy.headline(readiness),
                fontSize = 14.sp, color = PulseColors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                textAlign = TextAlign.Center,
            )
            if (readiness is Readiness.Result.Learning) {
                ProgressBar(
                    fraction = readiness.nightsCollected / readiness.nightsNeeded.toFloat(),
                    color = PulseColors.readiness,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(PulseColors.borderStrong)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Readiness detail: score, sentence, coverage caution, contributors, and what's missing. */
@Composable
fun ReadinessScreen(db: PulseLoopDatabase, onBack: () -> Unit) {
    val result = rememberReadiness(db, Unit)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                "Readiness", modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val score = result as? Readiness.Result.Score
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreRing(
                    value = score?.value,
                    color = score?.let { bandColor(it.band) } ?: PulseColors.readiness,
                    size = 168.dp, stroke = 12.dp, centerSize = 48,
                )
                score?.let {
                    Text(it.band.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = bandColor(it.band))
                }
                if (result != null) {
                    Text(
                        ReadinessCopy.headline(result), fontSize = 15.sp, color = PulseColors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                if (result is Readiness.Result.Learning) {
                    ProgressBar(result.nightsCollected / result.nightsNeeded.toFloat(), PulseColors.readiness, Modifier.padding(horizontal = 32.dp))
                }
            }

            score?.let { ReadinessCopy.coverageNote(it) }?.let { note ->
                Text(
                    note, fontSize = 13.sp, color = PulseColors.warning,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .dither(PulseColors.warning, 0.12f).padding(12.dp),
                )
            }

            if (score != null) {
                ContributorsCard(score.contributors.map { it.asScoreContributor() })
                if (score.missing.isNotEmpty()) {
                    Text(
                        "Not enough data yet: " + score.missing.joinToString { it.title.lowercase() } + ".",
                        fontSize = 12.sp, color = PulseColors.textMuted,
                    )
                }
            }

            Text(ReadinessCopy.DERIVED_FOOTER, fontSize = 12.sp, color = PulseColors.textMuted)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** "Contributors" heading and a card of tappable rows; shared by Readiness, Sleep and Activity. */
@Composable
fun ContributorsCard(contributors: List<ScoreContributor>, title: String = "Contributors") {
    if (contributors.isEmpty()) return
    var expanded by remember(contributors) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textMuted)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card)) {
            contributors.forEachIndexed { i, c ->
                ContributorRow(c, expanded == c.title) { expanded = if (expanded == c.title) null else c.title }
                if (i < contributors.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
                }
            }
        }
    }
}

@Composable
private fun ContributorRow(c: ScoreContributor, expanded: Boolean, onTap: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary, modifier = Modifier.weight(1f))
            Text(c.band.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bandColor(c.band))
        }
        ProgressBar(c.score / 100f, bandColor(c.band))
        Text(c.detail, fontSize = 12.sp, color = PulseColors.textSecondary)
        if (expanded) {
            Text(c.explanation, fontSize = 13.sp, color = PulseColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Loads today's activity score, recomputing whenever [key] changes. */
@Composable
fun rememberActivityScore(db: PulseLoopDatabase, key: Any?): com.pulseloop.service.ActivityScore.Result? {
    val result by produceState<com.pulseloop.service.ActivityScore.Result?>(initialValue = null, db, key) {
        value = runCatching { com.pulseloop.service.ActivityScore.load(db) }.getOrNull()
    }
    return result
}

/** Activity score detail: score, band, contributors, and the estimate footer. */
@Composable
fun ActivityScoreScreen(db: PulseLoopDatabase, onBack: () -> Unit, onOpenActivity: () -> Unit) {
    val result = rememberActivityScore(db, Unit)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                "Activity", modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreRing(
                    value = result?.value,
                    color = result?.let { bandColor(it.band) } ?: PulseColors.steps,
                    size = 168.dp, stroke = 12.dp, centerSize = 48,
                )
                result?.let {
                    Text(it.band.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = bandColor(it.band))
                }
                Text(
                    activityHeadline(result), fontSize = 15.sp, color = PulseColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            result?.let { ContributorsCard(it.contributors) }
            Text(
                "Open today's activity",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.steps,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpenActivity).padding(8.dp),
            )
            Text(
                "Activity is estimated by PulseLoop from your ring's steps and the workouts you log. It is not a medical measurement.",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun activityHeadline(result: com.pulseloop.service.ActivityScore.Result?): String {
    if (result == null) return "No activity recorded yet. Sync your ring to see today's score."
    val weakest = result.contributors.minByOrNull { it.score }?.takeIf { it.score < 70 }?.title?.lowercase()
    return when (result.band) {
        Readiness.Band.OPTIMAL -> "Great balance of movement and recovery. Keep it up."
        Readiness.Band.GOOD -> "A solid active day so far."
        Readiness.Band.FAIR, Readiness.Band.PAY_ATTENTION ->
            if (weakest != null) "There's room to move more. Start with your $weakest." else "There's room to move more today."
    }
}
