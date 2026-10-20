package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.MeasurementKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Loads [Readiness.Inputs] from Room and computes today's readiness.
 *
 * Real rows only (`*Real` queries, DemoDataPolicy): readiness is a judgement about the wearer's
 * body, and a seeded demo night would make it a judgement about nobody. A day the ring has no
 * activity row for is skipped rather than counted as zero steps, so a day the ring sat on the
 * charger doesn't read as a sedentary day.
 */
object ReadinessRepository {

    /** Baseline depth: Oura compares against roughly the last two months. */
    private const val HISTORY_DAYS = 60L

    suspend fun compute(
        db: PulseLoopDatabase,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Readiness.Result = Readiness.compute(loadInputs(db, now, zone))

    suspend fun loadInputs(
        db: PulseLoopDatabase,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Readiness.Inputs {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = today.minusDays(HISTORY_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()

        // One night per waking day: the longest session, so a nap never stands in for the night.
        val sessions = db.sleepSessionDao().inRangeReal(from, now)
            .filter { it.totalMinutes > 0 }
            .groupBy { it.date }
            .mapValues { (_, s) -> s.maxBy { it.totalMinutes } }
            .values

        val windowStart = sessions.minOfOrNull { it.startAt } ?: from
        val windowEnd = sessions.maxOfOrNull { it.endAt } ?: now
        suspend fun samples(kind: MeasurementKind) = db.measurementDao()
            .rangeReal(kind.name, windowStart, windowEnd)
            .map { VitalSample(it.timestamp, it.value) }
        val hr = samples(MeasurementKind.HEART_RATE)
        val hrv = samples(MeasurementKind.HRV)
        val spo2 = samples(MeasurementKind.SPO2)
        val temperature = samples(MeasurementKind.TEMPERATURE)

        val nights = sessions.map { s ->
            val day = Instant.ofEpochMilli(s.date).atZone(zone).toLocalDate()
            NightlyVitals.summarize(
                day = day,
                startAt = s.startAt,
                endAt = s.endAt,
                asleepMinutes = s.totalMinutes,
                heartRate = hr.filter { it.timestampMs in s.startAt..s.endAt },
                hrv = hrv.filter { it.timestampMs in s.startAt..s.endAt },
                spo2 = spo2.filter { it.timestampMs in s.startAt..s.endAt },
                temperature = temperature.filter { it.timestampMs in s.startAt..s.endAt },
            )
        }
        val lastNight = nights.firstOrNull { it.day == today }

        val dailySteps = db.activityDailyDao().recentReal(limit = 30)
            .filter { it.date < todayStart && it.steps > 0 }
            .sortedByDescending { it.date }
            .map { it.steps }

        return Readiness.Inputs(
            lastNight = lastNight,
            history = nights.filter { it.day != today && it.day.isBefore(today) },
            // Sleep is scored against what this user needs once that is learned (SleepNeed), the
            // goal until then.
            sleepGoalMinutes = SleepNeed.estimate(nights, db.userGoalDao().get()?.sleepMinutes ?: 480, today).minutes,
            dailySteps = dailySteps,
            zone = zone,
        )
    }

    /** The waking day [now] falls in, for callers that key caches by it. */
    fun dayOf(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
