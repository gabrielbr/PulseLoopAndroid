package com.pulseloop.service

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.MeasurementKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Workouts and naps the ring's data suggests, offered as proposals the user confirms, edits or
 * discards (OpenStrap Edge's rule): detection never writes a workout on its own, and the user's
 * answer always wins over a later re-detection of the same stretch.
 */
object ActivityDetection {

    data class Proposal(
        val kind: Kind, val startAt: Long, val endAt: Long, val detail: String,
        /** Every activity type, most likely first (ActivityClassifier); empty for a nap. */
        val guesses: List<String> = emptyList(),
        /** What the guess was made from, kept with the user's answer so later guesses learn. */
        val features: ActivityClassifier.Features? = null,
    ) {
        enum class Kind { WALK, RUN, CARDIO, NAP }
        /** The selected type: the classifier's first choice, else the kind's own. */
        val bestType: String get() = guesses.firstOrNull() ?: when (kind) {
            Kind.WALK -> "walk"; Kind.RUN -> "run"; else -> "other"
        }
        val key: String get() = "${kind.name}:$startAt:$endAt"
        val minutes: Int get() = ((endAt - startAt) / 60_000L).toInt()
    }

    private const val WINDOW = 15 * 60_000L
    const val WALK_STEPS_PER_15 = 1200   // ~80 steps a minute
    const val RUN_STEPS_PER_15 = 2100    // ~140 steps a minute

    /**
     * Walks and runs: runs of at least two consecutive 15-minute windows of brisk stepping.
     * [steps] are (start ms, steps) buckets of any length; they're summed into 15-minute windows.
     */
    fun stepSessions(steps: List<Pair<Long, Int>>): List<Proposal> {
        val windows = steps.groupBy { it.first / WINDOW * WINDOW }.mapValues { (_, v) -> v.sumOf { it.second } }.toSortedMap()
        val out = mutableListOf<Proposal>()
        var runStart: Long? = null; var last = 0L; var total = 0; var fast = 0; var count = 0
        fun close() {
            val s = runStart ?: return
            if (count >= 2) {
                val kind = if (fast * 2 > count) Proposal.Kind.RUN else Proposal.Kind.WALK
                out += Proposal(kind, s, last + WINDOW, tr("%,d steps").format(total))
            }
            runStart = null; total = 0; fast = 0; count = 0
        }
        for ((start, n) in windows) {
            if (n >= WALK_STEPS_PER_15 && (runStart == null || start == last + WINDOW)) {
                if (runStart == null) runStart = start
                last = start; total += n; count++; if (n >= RUN_STEPS_PER_15) fast++
            } else {
                close()
                if (n >= WALK_STEPS_PER_15) { runStart = start; last = start; total = n; count = 1; if (n >= RUN_STEPS_PER_15) fast = 1 }
            }
        }
        close()
        return out
    }

    /**
     * Cardio without many steps (cycling, a gym session): at least 20 minutes of readings at 40 %
     * or more of heart-rate reserve above the user's quiet level, with gaps of at most 15 minutes.
     */
    fun heartSessions(hr: List<Pair<Long, Double>>, quiet: Double, hrMax: Double): List<Proposal> {
        val threshold = quiet + 0.4 * (hrMax - quiet)
        val out = mutableListOf<Proposal>()
        var start: Long? = null; var last = 0L; var peak = 0.0
        fun close() {
            val s = start ?: return
            if (last - s >= 20 * 60_000L) out += Proposal(Proposal.Kind.CARDIO, s, last, trf("Heart rate up to {0} bpm", peak.toInt()))
            start = null; peak = 0.0
        }
        for ((t, bpm) in hr.sortedBy { it.first }) {
            if (bpm >= threshold) {
                if (start != null && t - last > 15 * 60_000L) close()
                if (start == null) start = t
                last = t; peak = maxOf(peak, bpm)
            } else if (start != null && t - last > 5 * 60_000L) close()
        }
        close()
        return out
    }

    /**
     * Naps: between 11:00 and 19:00, at least 25 minutes where heart rate stays at or below the
     * user's quiet level and the ring counted (almost) no steps.
     */
    fun naps(hr: List<Pair<Long, Double>>, steps: List<Pair<Long, Int>>, quiet: Double, zone: ZoneId): List<Proposal> {
        val out = mutableListOf<Proposal>()
        var start: Long? = null; var last = 0L; var n = 0
        fun stepsBetween(a: Long, b: Long) = steps.filter { it.first in a..b }.sumOf { it.second }
        fun close() {
            val s = start ?: return
            if (last - s >= 25 * 60_000L && n >= 4 && stepsBetween(s, last) < 60) {
                out += Proposal(Proposal.Kind.NAP, s, last, tr("Heart rate stayed at your quiet level with no steps"))
            }
            start = null; n = 0
        }
        for ((t, bpm) in hr.sortedBy { it.first }) {
            val h = Instant.ofEpochMilli(t).atZone(zone).hour
            if (h in 11..18 && bpm <= quiet) {
                if (start != null && t - last > 15 * 60_000L) close()
                if (start == null) start = t
                last = t; n++
            } else close()
        }
        close()
        return out
    }

    /**
     * Proposals that overlap anything already recorded, or a stretch the user already answered, are
     * dropped. Answers match by overlap, not by key: a re-detection (more data, a changed guess)
     * can shift a stretch's bounds or kind, and "Not this" must still hold for it.
     */
    fun open(proposals: List<Proposal>, recorded: List<LongRange>, answered: Set<String>): List<Proposal> {
        val answeredRanges = answered.mapNotNull { k ->
            val parts = k.split(':')
            val a = parts.getOrNull(1)?.toLongOrNull(); val b = parts.getOrNull(2)?.toLongOrNull()
            if (a != null && b != null) a..b else null
        }
        fun overlaps(r: LongRange, p: Proposal) = r.first < p.endAt && p.startAt < r.last
        return proposals.filter { p ->
            p.key !in answered && recorded.none { overlaps(it, p) } && answeredRanges.none { overlaps(it, p) }
        }
    }

    /**
     * Walk/run stretches and raised-heart stretches that touch (within 10 minutes) are one
     * activity: a run is not also offered as "cardio", and a ride with a walk to the bike is one.
     */
    fun mergeActive(ranges: List<LongRange>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        for (r in ranges.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 10 * 60_000L) out[out.size - 1] = last.first..maxOf(last.last, r.last)
            else out += r
        }
        return out
    }

    // ── Persistence of answers ───────────────────────────────────────────────────────────────

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("activity_detection", Context.MODE_PRIVATE)

    // ── Learning: confirmed stretches with the type the user chose (rides in the backup) ──
    fun learned(c: Context): List<ActivityClassifier.Example> =
        runCatching { decodeLearned(prefs(c).getString("learned", null)) }.getOrDefault(emptyList())

    fun learn(c: Context, f: ActivityClassifier.Features, type: String) {
        val all = (learned(c) + ActivityClassifier.Example(f, type)).takeLast(200)
        prefs(c).edit().putString("learned", encodeLearned(all)).apply()
    }

    /** One line per example: type and the six features, comma-separated. */
    internal fun encodeLearned(all: List<ActivityClassifier.Example>): String = all.joinToString("\n") { e ->
        val f = e.features
        listOf(e.type, f.minutes, f.cadence, f.stepBurst, f.hrMeanPct, f.hrPeakPct, f.hrCv).joinToString(",")
    }

    internal fun decodeLearned(raw: String?): List<ActivityClassifier.Example> =
        raw.orEmpty().lines().mapNotNull { line ->
            val p = line.split(',')
            val n = p.drop(1).mapNotNull { it.toDoubleOrNull() }
            if (p.size != 7 || n.size != 6) null
            else ActivityClassifier.Example(ActivityClassifier.Features(n[0], n[1], n[2], n[3], n[4], n[5]), p[0])
        }

    /** Whether a new suggestion may notify (Settings → Notifications → Detected workouts). */
    fun notifyEnabled(c: Context): Boolean = prefs(c).getBoolean("notify", true)
    fun setNotifyEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("notify", on).apply() }
    fun notified(c: Context): Set<String> = prefs(c).getStringSet("notified", emptySet()).orEmpty()
    fun markNotified(c: Context, keys: Collection<String>) {
        val keep = (notified(c) + keys).sortedBy { it.substringAfter(':').substringBefore(':').toLongOrNull() ?: 0 }.takeLast(300).toSet()
        prefs(c).edit().putStringSet("notified", keep).apply()
    }

    fun answered(c: Context): Set<String> = prefs(c).getStringSet("answered", emptySet()).orEmpty()
    fun answer(c: Context, p: Proposal) {
        val keep = (answered(c) + p.key).sortedBy { it.substringAfter(':').substringBefore(':').toLongOrNull() ?: 0 }.takeLast(300).toSet()
        prefs(c).edit().putStringSet("answered", keep).apply()
    }

    /** Open proposals for the last [days] days. */
    suspend fun load(c: Context, db: PulseLoopDatabase, days: Long = 3, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<Proposal> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val from = start(today.minusDays(days - 1))
        val hr29 = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, start(today.minusDays(29)), now)
            .map { it.timestamp to it.value }.filter { it.second in 30.0..230.0 }
        val inputs = ReadinessRepository.loadInputs(db, now, zone)
        val nights = (inputs.history + listOfNotNull(inputs.lastNight)).map { it.startAt..it.endAt }
        // Quiet level, as cardio load computes it.
        val quietDays = hr29.filter { it.first < start(today) }
            .groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
            .mapNotNull { (_, v) ->
                CardioLoad.quietOfDay(v.filter { (t, _) -> Instant.ofEpochMilli(t).atZone(zone).hour in 8..21 && nights.none { t in it } }.map { it.second })
            }
        val quiet = CardioLoad.quietLevel(quietDays)
        val profile = db.userProfileDao().get()
        val buckets = (0 until days).flatMap { db.activityBucketDao().byDay(start(today.minusDays(it))) }
            .map { it.startEpoch to it.steps }
        val recentHr = hr29.filter { it.first >= from && nights.none { n -> it.first in n } }
        val recentSteps = buckets.filter { it.first >= from }
        val hrMax = CardioLoad.hrMax(profile?.age)
        val active = stepSessions(recentSteps) + (if (quiet != null) heartSessions(recentHr, quiet, hrMax) else emptyList())
        val learned = learned(c)
        // One window per activity, then a guess at what it was (ActivityClassifier).
        val activities = mergeActive(active.map { it.startAt..it.endAt }).map { w ->
            val f = ActivityClassifier.features(w.first, w.last, recentSteps, recentHr, quiet ?: 60.0, hrMax)
            val guesses = ActivityClassifier.rank(f, learned)
            val kind = when (guesses.first()) { "walk" -> Proposal.Kind.WALK; "run" -> Proposal.Kind.RUN; else -> Proposal.Kind.CARDIO }
            val stepsIn = recentSteps.filter { it.first in w.first until w.last }.sumOf { it.second }
            val peak = recentHr.filter { it.first in w.first..w.last }.maxOfOrNull { it.second }
            val detail = listOfNotNull(
                stepsIn.takeIf { it >= 300 }?.let { tr("%,d steps").format(it) },
                peak?.let { trf("Heart rate up to {0} bpm", it.toInt()) },
            ).joinToString(" · ")
            Proposal(kind, w.first, w.last, detail, guesses, f)
        }
        val proposals = activities + (if (quiet != null) naps(recentHr, buckets, quiet, zone) else emptyList())
        val sessions = db.activitySessionDao().recent(50).map { it.startedAt..(it.endedAt ?: it.startedAt) }
        return open(proposals, sessions + nights, answered(c)).sortedByDescending { it.startAt }
    }
}

/** Accept a proposal: a workout becomes a recorded activity; a nap ticks the day's "Daytime nap" tag. */
suspend fun ActivityDetection.accept(c: Context, db: PulseLoopDatabase, p: ActivityDetection.Proposal, type: String) {
    if (p.kind == ActivityDetection.Proposal.Kind.NAP) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(p.startAt).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val tag = JournalInsights.Tag.NAP.key
        db.journalDao().upsert(com.pulseloop.data.entity.JournalEntryEntity(com.pulseloop.data.entity.JournalEntryEntity.idFor(day, tag), day, tag))
    } else {
        ManualActivityService.create(db, type, p.startAt, p.minutes.toDouble(), notes = tr("Detected by PulseLoop and confirmed"))
        // The answer teaches the next guess for a stretch like this one.
        p.features?.let { learn(c, it, type) }
    }
    answer(c, p)
}
