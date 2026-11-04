package com.pulseloop.mcp

import com.pulseloop.coach.context.CoachContextBuilder
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.health.accuracy.AccuracyCorrections
import com.pulseloop.ring.MeasurementKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The `summary` tool: today, last night and the week as one compact text. Reuses
 * [CoachContextBuilder.build] for the device, goals, 7-day steps and the data-quality notes the
 * coach gets, and reads the rest the way the screens do — real rows win over demo rows
 * (`DemoDataPolicy`), accuracy corrections applied (read-time only).
 *
 * Deliberately left out of the packet it reuses: labs, postpartum, drinks and the goal plan.
 * Each has its own read tool or none, and the postpartum block is the closest thing to the EPDS
 * the coach sees, so the network summary never carries it.
 */
object McpSummary {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun build(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val packet = CoachContextBuilder.build(db)
        val corrections = AccuracyCorrections.load(db)
        val today = LocalDate.now(zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val out = StringBuilder()

        out.append("PulseLoop summary for ").append(today).append(" (").append(zone.id).append(")\n")

        // Today
        val goals = packet.goals
        val act = db.activityDailyDao().byDay(todayStart)?.let(corrections::day)
        out.append("\nToday\n")
        if (act == null) out.append("- No activity synced yet today.\n")
        else {
            out.append("- Steps: ").append(act.steps).append(" of ").append(goals.stepsDaily).append(" goal\n")
            out.append("- Distance: ").append("%.1f".format(act.distanceMeters / 1000.0)).append(" km\n")
            out.append("- Active minutes: ").append(act.activeMinutes).append(" of ").append(goals.activeMinutesDaily).append(" goal\n")
            if (act.calories > 0) out.append("- Calories (ring): ").append(Math.round(act.calories)).append(" kcal\n")
        }
        latest(db, MeasurementKind.HEART_RATE.name, now)?.let { m ->
            out.append("- Latest heart rate: ").append(Math.round(corrections.heartRate(m.value))).append(" bpm at ").append(time(m.timestamp, zone)).append('\n')
        }
        latest(db, MeasurementKind.SPO2.name, now)?.let { m ->
            out.append("- Latest SpO2: ").append(Math.round(corrections.spo2(m.value))).append("% at ").append(time(m.timestamp, zone)).append('\n')
        }
        packet.trends.hrResting?.let { out.append("- Resting heart rate (last 24 h, estimate): ").append(Math.round(it)).append(" bpm\n") }

        // Last night and the week's nights
        val sleepReal = db.sleepSessionDao().hasReal()
        val nights = (if (sleepReal) db.sleepSessionDao().recentReal(7) else db.sleepSessionDao().recent(7))
            .filter { it.date >= todayStart - 7 * DAY_MS }
            .map(corrections::sleep)
        out.append("\nLast night\n")
        val last = nights.maxByOrNull { it.date }
        if (last == null || last.date < todayStart - DAY_MS) out.append("- No sleep recorded for last night.\n")
        else {
            out.append("- Asleep: ").append(hm(last.totalMinutes)).append(" (goal ").append(goals.sleepHours).append(" h)\n")
            out.append("- In bed: ").append(time(last.startAt, zone)).append("–").append(time(last.endAt, zone)).append('\n')
        }

        // The week
        out.append("\nLast 7 days\n")
        val steps = packet.trends.steps7d.filter { it > 0 }
        if (steps.isEmpty()) out.append("- No step data.\n")
        else out.append("- Steps: average ").append(Math.round(steps.average())).append(" a day over ").append(steps.size).append(" day(s)\n")
        val asleep = nights.map { it.totalMinutes }.filter { it > 0 }
        if (asleep.isEmpty()) out.append("- No nights recorded.\n")
        else out.append("- Sleep: average ").append(hm(asleep.average().toInt())).append(" over ").append(asleep.size).append(" night(s)\n")

        // Ring
        val d = packet.device
        out.append("\nRing\n")
        out.append("- ").append(d.name ?: "Ring").append(": ").append(d.state.lowercase())
        d.batteryPercent?.let { out.append(", battery ").append(it).append('%') }
        out.append(", last sync ").append(d.lastSyncAt ?: "never").append('\n')

        if (packet.dataQualityWarnings.isNotEmpty()) {
            out.append("\nNotes\n")
            packet.dataQualityWarnings.forEach { out.append("- ").append(it).append('\n') }
        }
        return out.toString().trimEnd()
    }

    /** The newest reading of [kind] in the last 24 h, real rows first. */
    private suspend fun latest(db: PulseLoopDatabase, kind: String, now: Long): MeasurementEntity? {
        val dao = db.measurementDao()
        val rows = if (dao.hasReal(kind)) dao.rangeReal(kind, now - DAY_MS, now) else dao.range(kind, now - DAY_MS, now)
        return rows.lastOrNull()
    }

    private fun time(ts: Long, zone: ZoneId): String = Instant.ofEpochMilli(ts).atZone(zone).format(TIME)

    private fun hm(minutes: Int): String = "${minutes / 60} h ${"%02d".format(minutes % 60)} min"

    private const val DAY_MS = 24 * 3600_000L
}
