package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.JournalEntryEntity
import com.pulseloop.service.JournalInsights
import com.pulseloop.service.ReadinessRepository
import com.pulseloop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Journal tags and "what affects you" (docs/health-app-feature-research.md). Tagging is one tap per
 * chip; the effect of a day's tags is read from the night after it.
 */

private fun dayKey(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

/** Chips for one day's tags; tapping toggles a tag on or off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalTagsCard(
    db: PulseLoopDatabase,
    date: LocalDate,
    title: String,
    tags: List<JournalInsights.Tag> = JournalInsights.Tag.entries,
    /** Offer a Today / Yesterday switch, so a forgotten day can still be tagged the morning after. */
    allowYesterday: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    var onYesterday by remember(date) { mutableStateOf(false) }
    val shown = if (allowYesterday && onYesterday) date.minusDays(1) else date
    val day = remember(shown) { dayKey(shown) }
    val entries by remember(day) { db.journalDao().forDayFlow(day) }.collectAsState(initial = emptyList())
    val selected = entries.map { it.tag }.toSet()
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (allowYesterday && onYesterday) "Tag yesterday" else title,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (allowYesterday) {
                DaySwitch(onYesterday) { onYesterday = it }
            }
        }
        // Chips size to their label and wrap as a row, one line each: a fixed two-column grid
        // broke the longer labels over two lines in a monospace face.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                val on = tag.key in selected
                val chip = RoundedCornerShape(16.dp) // GMind pill: filter chips keep the 16dp radius
                Text(
                    tag.label,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(chip)
                        .background(if (on) PulseColors.accent else PulseColors.cardSoft)
                        .border(1.dp, if (on) PulseColors.accent else PulseColors.borderSubtle, chip)
                        .clickable {
                            scope.launch {
                                val id = JournalEntryEntity.idFor(day, tag.key)
                                if (on) db.journalDao().delete(id)
                                else db.journalDao().upsert(JournalEntryEntity(id = id, day = day, tag = tag.key))
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        footer?.invoke()
    }
}

/** Today / Yesterday, as two small segments. */
@Composable
private fun DaySwitch(yesterday: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.clip(shape).border(1.dp, PulseColors.borderSubtle, shape)) {
        listOf(false to "Today", true to "Yesterday").forEach { (isYesterday, label) ->
            val on = yesterday == isYesterday
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) PulseColors.onAccent else PulseColors.textSecondary,
                modifier = Modifier
                    .background(if (on) PulseColors.accent else PulseColors.cardSoft)
                    .clickable { onChange(isYesterday) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Journal screen: tag today and yesterday, then see which tags line up with your nights. */
@Composable
fun JournalScreen(db: PulseLoopDatabase, onBack: () -> Unit) {
    val today = remember { LocalDate.now() }
    // Recompute effects whenever any tag changes.
    val todayEntries by remember { db.journalDao().forDayFlow(dayKey(today)) }.collectAsState(initial = emptyList())
    val yesterdayEntries by remember { db.journalDao().forDayFlow(dayKey(today.minusDays(1))) }.collectAsState(initial = emptyList())
    val effects by produceState<List<JournalInsights.Effect>?>(null, todayEntries, yesterdayEntries) {
        value = runCatching {
            val zone = ZoneId.systemDefault()
            val inputs = ReadinessRepository.loadInputs(db)
            val nights = inputs.history + listOfNotNull(inputs.lastNight)
            val tagsByDay = db.journalDao().all()
                .groupBy { Instant.ofEpochMilli(it.day).atZone(zone).toLocalDate() }
                .mapValues { (_, e) -> e.map { it.tag }.toSet() }
            JournalInsights.effects(tagsByDay, nights)
        }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Filled.ArrowBack, "Back")
            }
            Text(
                "Journal", modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            JournalTagsCard(db, today, "Today")
            JournalTagsCard(db, today.minusDays(1), "Yesterday")

            Text("What affects you", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textMuted)
            val list = effects
            when {
                list == null -> Unit
                list.isEmpty() -> Text(
                    "Tag your days for a couple of weeks. Once a tag has at least ${JournalInsights.MIN_NIGHTS} nights after it, PulseLoop compares those nights with your others.",
                    fontSize = 13.sp, color = PulseColors.textSecondary,
                )
                else -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card),
                ) {
                    list.forEachIndexed { i, e ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(e.tag.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary)
                            val enough = e.nightsWith >= JournalInsights.MIN_NIGHTS && e.nightsWithout >= JournalInsights.MIN_NIGHTS
                            Text(
                                if (enough) e.summary().replaceFirstChar { it.uppercase() }
                                else "Tag ${maxOf(0, JournalInsights.MIN_NIGHTS - e.nightsWith)} more ${if (JournalInsights.MIN_NIGHTS - e.nightsWith == 1) "day" else "days"} to see an effect",
                                fontSize = 13.sp,
                                color = if (enough && e.hasEffect) PulseColors.textSecondary else PulseColors.textMuted,
                            )
                        }
                        if (i < list.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(PulseColors.borderSubtle))
                    }
                }
            }
            Text(
                "These are patterns in your own data, not proof of cause and effect. They get more reliable the more days you tag.",
                fontSize = 12.sp, color = PulseColors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
