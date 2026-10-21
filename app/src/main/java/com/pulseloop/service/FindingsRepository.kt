package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.FindingEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Recomputes the last [WINDOW_DAYS] of findings and stores them; older rows are history. */
object FindingsRepository {
    const val WINDOW_DAYS = 30L

    fun dayKey(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    suspend fun refresh(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        val inputs = ReadinessRepository.loadInputs(db, now, zone)
        val nights = inputs.history + listOfNotNull(inputs.lastNight)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val radarToday = runCatching { HealthInsightsRepository.load(db, now, zone).radar?.level }.getOrNull()
        val rows = (0 until WINDOW_DAYS).flatMap { back ->
            val day = today.minusDays(back)
            Findings.forDay(day, nights, radar = if (back == 0L) radarToday else null).map { f ->
                FindingEntity(
                    id = "${dayKey(day, zone)}:${f.kind.name}",
                    day = dayKey(day, zone),
                    kind = f.kind.name,
                    title = f.title,
                    detail = f.detail,
                    positive = f.kind.positive,
                    algorithmVersion = Algorithms.VERSION,
                )
            }
        }
        val from = dayKey(today.minusDays(WINDOW_DAYS - 1), zone)
        // Today's Symptom Radar row is only computed for today; keep earlier days' radar rows.
        val keptRadar = db.findingDao().since(from).filter { it.kind == Finding.Kind.RADAR.name && it.day != dayKey(today, zone) }
        db.findingDao().deleteWindow(from, Algorithms.VERSION)
        db.findingDao().upsertAll(rows + keptRadar)
    }
}
