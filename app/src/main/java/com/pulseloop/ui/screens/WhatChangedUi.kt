package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.FindingEntity
import com.pulseloop.service.Algorithms
import com.pulseloop.service.FindingsRepository
import com.pulseloop.ui.theme.PulseColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "What changed": your nights against your own normal, day by day, kept as a log. Positive changes
 * are listed too. A stretch where nothing stood out says so plainly.
 */
@Composable
fun WhatChangedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }
    val from = remember { FindingsRepository.dayKey(today.minusDays(90), zone) }
    LaunchedEffect(Unit) { runCatching { FindingsRepository.refresh(db) } }
    val rows by remember { db.findingDao().sinceFlow(from) }.collectAsState(initial = emptyList())
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    Column(Modifier.fillMaxSize().background(PulseColors.background)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
            Text(tr("What changed"), modifier = Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val recentKey = FindingsRepository.dayKey(today.minusDays(2), zone)
            val recent = rows.filter { it.day >= recentKey }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("LAST 3 DAYS"), fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                if (recent.isEmpty()) {
                    Text(tr("Nothing stood out"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PulseColors.textPrimary)
                    Text(tr("Your nights look like your usual. That's an answer too."), fontSize = 13.sp, color = PulseColors.textSecondary)
                } else {
                    recent.distinctBy { it.kind }.forEach { FindingRow(it) }
                }
            }
            val older = rows.filter { it.day < recentKey }.groupBy { it.day }
            if (older.isNotEmpty()) {
                Text("EARLIER", fontSize = 11.sp, letterSpacing = 1.6.sp, color = PulseColors.textMuted)
                older.forEach { (day, list) ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(FindingsRepository.dateOf(day, zone).format(fmt), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textMuted)
                        list.forEach { FindingRow(it) }
                    }
                }
            }
            Text(
                tr("Each night is compared with your own previous four weeks once there are two weeks to compare with. ") +
                    "Findings carry the formula version that made them (now v${Algorithms.VERSION}). Patterns, not diagnoses.",
                fontSize = 11.sp, color = PulseColors.textMuted,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FindingRow(f: FindingEntity) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape)
                .background(if (f.positive) PulseColors.accent else PulseColors.warning),
        )
        Column(Modifier.weight(1f)) {
            Text(f.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            Text(f.detail, fontSize = 13.sp, color = PulseColors.textSecondary)
        }
    }
}
