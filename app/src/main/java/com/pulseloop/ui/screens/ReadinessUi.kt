package com.pulseloop.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.Readiness
import com.pulseloop.service.ReadinessCopy
import com.pulseloop.service.ReadinessRepository
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
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
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
private fun HeaderRing(label: String, value: Int?, caption: String, color: Color, onTap: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onTap).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScoreRing(value = value, color = color, size = 84.dp, stroke = 7.dp)
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
        Text(caption, fontSize = 11.sp, color = PulseColors.textMuted, maxLines = 1)
    }
}

/**
 * Today's header: Readiness, Sleep and Activity side by side, then one sentence about today.
 * Activity is goal progress, not a score yet (phase 4), and its caption says so.
 */
@Composable
fun ScoreHeader(
    readiness: Readiness.Result?,
    sleepScore: Int?,
    stepsPercent: Int?,
    onReadiness: () -> Unit,
    onSleep: () -> Unit,
    onActivity: () -> Unit,
) {
    val score = readiness as? Readiness.Result.Score
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PulseColors.card).padding(12.dp),
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
                value = stepsPercent?.coerceAtMost(100),
                centerText = stepsPercent?.let { "$it%" } ?: "—",
                caption = "of step goal",
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
    var expanded by remember { mutableStateOf<Readiness.ContributorKind?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.ArrowBack, "Back")
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
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(PulseColors.warning.copy(alpha = 0.12f)).padding(12.dp),
                )
            }

            if (score != null) {
                Text("Contributors", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textMuted)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PulseColors.card),
                ) {
                    score.contributors.forEachIndexed { i, c ->
                        ContributorRow(c, expanded == c.kind) { expanded = if (expanded == c.kind) null else c.kind }
                        if (i < score.contributors.lastIndex) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
                        }
                    }
                }
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

@Composable
private fun ContributorRow(c: Readiness.Contributor, expanded: Boolean, onTap: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(c.kind.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary, modifier = Modifier.weight(1f))
            Text(c.band.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = bandColor(c.band))
        }
        ProgressBar(c.score / 100f, bandColor(c.band))
        Text(c.detail, fontSize = 12.sp, color = PulseColors.textSecondary)
        if (expanded) {
            Text(ReadinessCopy.explanation(c.kind), fontSize = 13.sp, color = PulseColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
