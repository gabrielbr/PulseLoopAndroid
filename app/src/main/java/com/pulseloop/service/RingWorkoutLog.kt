package com.pulseloop.service

import android.content.Context
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import org.json.JSONObject

/**
 * Workouts the ring recorded in its own sport mode (jring `0x25`, see `RingDecoder.decodeRingWorkouts`),
 * offered on the Activity tab like another device's workouts and added on a tap (AGENTS.md
 * "Detected workouts": nothing reaches Health Connect or Strava without one).
 *
 * The ring sends them a minute at a time, so the minutes are kept here (by minute, idempotent on
 * re-sync) and grouped into sessions when the tab asks. Only the last [KEEP_DAYS] days are kept:
 * the ring re-sends what it still holds, and an accepted workout lives on as a recorded activity.
 * Device-derived and re-syncable, so it is not in the backup archive.
 */
object RingWorkoutLog {

    private const val PREFS = "ring_workouts"
    private const val KEY = "minutes"
    private const val KEEP_DAYS = 10L
    private const val DAY_MS = 86_400_000L
    /** A pause of up to this many minutes stays inside one workout. */
    private const val MAX_GAP_MIN = 3
    private const val MIN_MINUTES = 3

    data class Session(val sportType: Int, val startAt: Long, val endAt: Long, val total: Int)

    @Synchronized
    fun record(c: Context, sportType: Int, value: Int, atMs: Long) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val minute = atMs / 60_000L * 60_000L
        json.put(minute.toString(), "$sportType:$value")
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY_MS
        json.keys().asSequence().toList().forEach { k -> if ((k.toLongOrNull() ?: 0L) < cutoff) json.remove(k) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun minutes(c: Context): List<Triple<Long, Int, Int>> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return json.keys().asSequence().mapNotNull { k ->
            val parts = json.optString(k).split(':')
            val t = k.toLongOrNull() ?: return@mapNotNull null
            val type = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            Triple(t, type, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }.sortedBy { it.first }.toList()
    }

    /** Runs of the same sport with pauses of at most [MAX_GAP_MIN] minutes. Pure, for tests. */
    fun sessions(minutes: List<Triple<Long, Int, Int>>): List<Session> {
        val out = mutableListOf<Session>()
        var cur: MutableList<Triple<Long, Int, Int>>? = null
        fun close() {
            val run = cur ?: return
            if (run.size >= MIN_MINUTES) out += Session(run.first().second, run.first().first, run.last().first + 60_000L, run.sumOf { it.third })
            cur = null
        }
        for (m in minutes.sortedBy { it.first }) {
            val last = cur?.last()
            if (last != null && (m.second != last.second || m.first - last.first > (MAX_GAP_MIN + 1) * 60_000L)) close()
            val run = cur ?: mutableListOf<Triple<Long, Int, Int>>().also { cur = it }
            run.add(m)
        }
        close()
        return out
    }

    /** The PulseLoop type for a vendor sport index (the vendor's 139-name list, 1-based). */
    fun typeFor(sportType: Int): String = when (sportType) {
        1, 125 -> "walk"
        2, 38, 74, 90, 91, 115, 122 -> "run"
        3, 57, 58, 114 -> "cycle"
        4, 64, 87, 123, 124 -> "hike"
        14, 77, 83 -> "yoga"
        59, 60, 61, 62, 63, 108 -> "dance"
        48, 106 -> "squash"
        10, 17, 18, 22, 25, 44, 69, 70, 71, 72, 73, 75, 80, 81, 82, 110, 111, 112, 113, 116 -> "gym"
        6, 7, 8, 9, 11, 13, 15, 16, 19, 20, 21, 23, 24, 26, 45, 46, 47, 49, 50, 51, 52, 53, 54, 55,
        85, 94, 97, 126, 127, 128, 129, 130, 131 -> "sport"
        else -> "other"
    }

    /** Walk, run and climb carry steps per minute; every other sport carries calories ÷ 10. */
    fun countsSteps(sportType: Int): Boolean = sportType == 1 || sportType == 2 || sportType == 4

    /** Suggestions for the Activity tab, in [from]..[to]. */
    fun proposals(c: Context, from: Long, to: Long): List<ActivityDetection.Proposal> =
        sessions(minutes(c)).filter { it.endAt > from && it.startAt < to }.map { s ->
            val type = typeFor(s.sportType)
            val amount = if (countsSteps(s.sportType)) tr("%,d steps").format(s.total)
            else trf("about {0} kcal", s.total * 10)
            val ring = tr("your ring")
            ActivityDetection.Proposal(
                kind = when (type) {
                    "walk" -> ActivityDetection.Proposal.Kind.WALK
                    "run" -> ActivityDetection.Proposal.Kind.RUN
                    else -> ActivityDetection.Proposal.Kind.CARDIO
                },
                startAt = s.startAt,
                endAt = s.endAt,
                detail = listOf(trf("Recorded by {0}", ring), amount).joinToString(" · "),
                guesses = listOf(type) + ActivityClassifier.TYPES.filter { it != type },
                external = ActivityDetection.External("ring-${s.startAt}", ring),
            )
        }
}
