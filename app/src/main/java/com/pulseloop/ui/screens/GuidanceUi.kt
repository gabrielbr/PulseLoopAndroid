package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.service.BedtimeGuidance
import com.pulseloop.service.OffSignal
import com.pulseloop.service.ReadinessRepository
import com.pulseloop.service.VitalsCheck
import com.pulseloop.ui.theme.PulseColors

/** What Today's guidance cards need, loaded together from one pass over the nights. */
data class GuidanceState(
    val offSignals: List<OffSignal> = emptyList(),
    val bedtime: BedtimeGuidance.Result? = null,
    val sleepGoalMinutes: Int = 480,
)

@Composable
fun rememberGuidance(db: PulseLoopDatabase, key: Any?): GuidanceState? {
    val state by produceState<GuidanceState?>(initialValue = null, db, key) {
        value = runCatching {
            val inputs = ReadinessRepository.loadInputs(db)
            val nights = inputs.history + listOfNotNull(inputs.lastNight)
            GuidanceState(
                offSignals = inputs.lastNight?.let { VitalsCheck.offSignals(it, inputs.history) } ?: emptyList(),
                bedtime = BedtimeGuidance.compute(nights, inputs.sleepGoalMinutes),
                sleepGoalMinutes = inputs.sleepGoalMinutes,
            )
        }.getOrNull()
    }
    return state
}

/** Shown only when two or more of last night's signals are outside the usual range. */
@Composable
fun UnusualSignalsCard(signals: List<OffSignal>) {
    if (!VitalsCheck.shouldAlert(signals)) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(PulseColors.warning.copy(alpha = 0.12f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Some signals look unusual", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.warning)
        signals.forEach {
            Text("${it.title}: ${it.detail}", fontSize = 13.sp, color = PulseColors.textPrimary)
        }
        Text(
            "Compared with your own recent nights. This can follow illness, stress, alcohol or a hard day. It isn't a diagnosis; consider an easier day.",
            fontSize = 12.sp, color = PulseColors.textSecondary,
        )
    }
}

/** Tonight: the suggested bedtime window and the caffeine cutoff that goes with it. */
@Composable
fun TonightCard(g: BedtimeGuidance.Result, sleepGoalMinutes: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PulseColors.card).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("BEDTIME", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = PulseColors.sleep)
            Text(
                "${BedtimeGuidance.fmt(g.windowStart)}–${BedtimeGuidance.fmt(g.windowEnd)}",
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
            )
            Text(
                "For ${sleepGoalMinutes / 60}h asleep before your usual ${BedtimeGuidance.fmt(g.usualWake)}",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LAST CAFFEINE", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = PulseColors.calories)
            Text(BedtimeGuidance.fmt(g.caffeineCutoff), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            Text("8 hours before bed", fontSize = 12.sp, color = PulseColors.textMuted)
        }
    }
}
