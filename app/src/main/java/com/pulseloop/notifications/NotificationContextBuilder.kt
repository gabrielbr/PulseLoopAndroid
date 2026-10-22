package com.pulseloop.notifications

import com.pulseloop.ui.i18n.tr

import com.pulseloop.coach.context.EnvironmentContext
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Ported from NotificationContextBuilder.swift.
 * Compact ~12-hour context the notification generator sees.
 * Reuses CoachContextBuilder for shared profile/goals/today/sleep/memory
 * and adds a rolling 12h HR/SpO₂ window plus the slot.
 */
object NotificationContextBuilder {

    data class Stats(val min: Double, val max: Double, val avg: Double, val count: Int)

    suspend fun build(
        slot: CoachNotificationSlot,
        db: PulseLoopDatabase,
        now: Long = System.currentTimeMillis(),
        environment: EnvironmentContext? = null,
    ): NotificationContextPacket {
        val cutoff = now - 12 * 3600_000L

        val profile = db.userProfileDao().get()
        val goal = db.userGoalDao().get()
        val device = db.deviceDao().current()

        // Today's activity
        val todayStart = localStartOfDay(now)
        // What the app shows: accuracy corrections applied (read-time only).
        val corrections = com.pulseloop.health.accuracy.AccuracyCorrections.load(db)
        val todayActivity = db.activityDailyDao().byDay(todayStart)?.let(corrections::day)

        // Latest sleep
        val latestSleep = db.sleepSessionDao().recent(1).firstOrNull()?.let(corrections::sleep)

        // 12h HR & SpO2 stats
        val hrRows = db.measurementDao().range(MeasurementKind.HEART_RATE.name, cutoff, now).map(corrections::measurement)
        val spo2Rows = db.measurementDao().range(MeasurementKind.SPO2.name, cutoff, now).map(corrections::measurement)

        val hrStats = if (hrRows.isNotEmpty()) {
            Stats(
                min = hrRows.minOf { it.value },
                max = hrRows.maxOf { it.value },
                avg = hrRows.map { it.value }.average(),
                count = hrRows.size,
            )
        } else Stats(0.0, 0.0, 0.0, 0)

        val spo2Stats = if (spo2Rows.isNotEmpty()) {
            Stats(
                min = spo2Rows.minOf { it.value },
                max = spo2Rows.maxOf { it.value },
                avg = spo2Rows.map { it.value }.average(),
                count = spo2Rows.size,
            )
        } else Stats(0.0, 0.0, 0.0, 0)

        // Recent workouts
        val recentWorkouts = db.activitySessionDao().recent(3)

        // Coach memories
        val memories = db.coachMemoryDao().allRanked()

        // Data quality warnings (simplified from CoachContextBuilder.DataQualityAnalyzer)
        val warnings = mutableListOf<String>()
        val profileComplete = profile != null && profile.name != null && profile.age != null && profile.heightCm != null && profile.weightKg != null
        if (!profileComplete) {
            warnings.add(tr("User profile is incomplete — insights may be less personalized."))
        }
        // lastFullSyncAt (not lastSyncAt, which the ring re-stamps on every bare CONNECT before
        // any data streams) — iOS #61c's freshness-gate fix.
        val lastFullSync = device?.lastFullSyncAt
        if (lastFullSync != null && (now - lastFullSync) > 12 * 3600_000L) {
            warnings.add(tr("Ring hasn't synced in >12h — today's data may be stale."))
        }
        if (hrRows.size < 10) {
            warnings.add(tr("Limited HR data available for today."))
        }

        return NotificationContextPacket(
            slot = slot.name.lowercase(),
            // Local time with its offset, not UTC: the model reads the hour off this, and 20:00
            // in UTC-3 written as 23:00Z (or 08:00 UTC+9 as 23:00Z the day before) misleads it.
            generatedAt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString(),
            timezone = ZoneId.systemDefault().id,
            profileName = profile?.name,
            goals = NotificationContextPacket.GoalContext(
                stepsDaily = goal?.steps ?: 10000,
                activeMinutesDaily = goal?.activeMinutes ?: 45,
                sleepHours = (goal?.sleepMinutes ?: 480) / 60,
                exerciseDaysWeekly = goal?.workoutsPerWeek ?: 4,
            ),
            today = NotificationContextPacket.DayContext(
                steps = todayActivity?.steps,
                calories = todayActivity?.calories,
                distanceMeters = todayActivity?.distanceMeters,
                activeMinutes = todayActivity?.activeMinutes,
            ),
            latestSleep = if (latestSleep != null) {
                NotificationContextPacket.SleepContext(
                    totalMin = latestSleep.totalMinutes,
                    startAt = latestSleep.startAt,
                    endAt = latestSleep.endAt,
                )
            } else null,
            latestVitals = NotificationContextPacket.VitalsContext(
                latestHr = hrRows.lastOrNull()?.value,
                latestSpo2 = spo2Rows.lastOrNull()?.value,
            ),
            hrLast12h = NotificationContextPacket.MetricStats(
                min = hrStats.min,
                max = hrStats.max,
                avg = hrStats.avg,
                count = hrStats.count,
            ),
            spo2Last12h = NotificationContextPacket.MetricStats(
                min = spo2Stats.min,
                max = spo2Stats.max,
                avg = spo2Stats.avg,
                count = spo2Stats.count,
            ),
            recentWorkouts = recentWorkouts.map { wo ->
                NotificationContextPacket.WorkoutContext(
                    type = wo.type,
                    calories = wo.calories,
                    distanceMeters = wo.distanceMeters,
                    avgHeartRate = wo.avgHeartRate,
                )
            },
            memories = memories.map { m ->
                NotificationContextPacket.MemoryContext(
                    key = m.key,
                    value = m.value,
                    memoryType = m.key, // simplified
                )
            },
            dataQualityWarnings = warnings,
            environment = environment,
            scores = com.pulseloop.service.ScoresContext.build(db),
            nutrition = com.pulseloop.service.NutritionContext.build(db, now),
            body = com.pulseloop.service.BodyContext.build(db, now),
            primaryGoal = runCatching { com.pulseloop.service.GoalPlan.load(db, now) }.getOrNull(),
            drinks = com.pulseloop.service.DrinksContext.build(db, now),
        )
    }

    private fun localStartOfDay(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli()
}
