package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.ring.MeasurementKind

/**
 * A workout the app was recording when its process died: closed, not left running forever.
 *
 * Nothing persisted the live workout but its samples, so an update install, Android reclaiming
 * memory or a crash mid-workout left the session `recording` (or `paused`) in the database for
 * good. The Activity tab lists finished workouts only, so the workout never appeared, and nothing
 * ever tried to finish it: `ActivitySessionDao.active()` had no caller. At process start no
 * workout can be live (the manager and its foreground service start empty), so any session still
 * marked active is one of these.
 *
 * It ends where the workout's own evidence ends: its last live heart-rate sample or GPS point
 * before the first quiet stretch of [MAX_GAP_MS], never more than [MAX_LENGTH_MS] after it
 * started. All-day history samples don't count: they keep arriving long after the workout. A
 * paused workout ends where it was paused. With no evidence at all there is nothing to report,
 * so it is marked cancelled instead of inventing a duration.
 */
object WorkoutRecovery {
    const val MAX_GAP_MS = 10 * 60_000L
    const val MAX_LENGTH_MS = 6 * 3_600_000L

    /** Where an orphaned workout really ended, or null when nothing shows it ran at all. */
    fun endAt(session: ActivitySessionEntity, evidence: List<Long>, now: Long): Long? {
        if (session.statusRaw == "paused") return session.endedAt?.takeIf { it > session.startedAt }
        val limit = minOf(now, session.startedAt + MAX_LENGTH_MS)
        var last: Long? = null
        var previous = session.startedAt
        for (t in evidence.filter { it in session.startedAt..limit }.sorted()) {
            if (t - previous > MAX_GAP_MS) break
            last = t
            previous = t
        }
        return last?.takeIf { it > session.startedAt }
    }

    /**
     * Close every orphaned workout: one still marked active that started before [startedBefore],
     * the moment this process's workout manager came up. A workout the user starts while this
     * runs started after it, so it is never touched. Returns how many were finished.
     */
    suspend fun recover(db: PulseLoopDatabase, startedBefore: Long, now: Long = System.currentTimeMillis()): Int {
        var finished = 0
        val orphans = db.activitySessionDao().recent(200)
            .filter { (it.statusRaw == "recording" || it.statusRaw == "paused") && it.startedAt < startedBefore }
        for (s in orphans) {
            val until = minOf(now, s.startedAt + MAX_LENGTH_MS)
            val hr = db.measurementDao().range(MeasurementKind.HEART_RATE.name, s.startedAt, until)
                .filter { it.sourceRaw !in setOf("history", "demo", "mock") && it.value > 0 }
                .map { it.timestamp }
            val gps = if (s.useGps) db.activityGpsPointDao().forSession(s.id).map { it.timestamp } else emptyList()
            val end = endAt(s, hr + gps, now)
            if (end == null) {
                db.activitySessionDao().upsert(s.copy(statusRaw = "cancelled", endedAt = now, updatedAt = now))
            } else {
                val closed = s.copy(statusRaw = "finished", endedAt = end, updatedAt = now)
                val summarized = ActivityAggregates.recompute(db, closed)
                db.activitySessionDao().upsert(summarized)
                ActivityRollup.credit(db, summarized)
                finished++
            }
        }
        return finished
    }
}
