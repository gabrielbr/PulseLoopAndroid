package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A day's cardio load from the ring's all-day heart rate, scored against the user's own quiet
 * waking heart rate rather than a population resting value (OpenStrap Edge's "strain"): a fit
 * person with a low resting HR gets the same credit for the same effort. Banister's TRIMP over
 * heart-rate reserve, with each reading standing for the gap to the next (at most 30 minutes),
 * which is what a ring sampling every 5–30 minutes can honestly support. A coarse daily figure,
 * labelled as an estimate.
 */
object CardioLoad {
    const val MIN_QUIET_DAYS = 3
    private const val MAX_GAP_MIN = 30.0

    enum class Band(private val labelEn: String) { LIGHT("Light"), USUAL("Usual"), HIGH("High"), VERY_HIGH("Very high");
        val label: String get() = tr(labelEn)
    }

    data class Result(val load: Int, val quietHr: Int, val usual: Int?, val band: Band?, val elevatedMinutes: Int)

    /** A day's quiet waking HR: the 20th percentile of its daytime readings outside sleep. */
    fun quietOfDay(daytime: List<Double>): Double? {
        if (daytime.size < 12) return null
        val s = daytime.sorted()
        return s[(s.size * 0.2).toInt().coerceAtMost(s.size - 1)]
    }

    /** The user's quiet level: the median of the last 28 days' daily quiet values, from 3 days on. */
    fun quietLevel(dailyQuiet: List<Double>): Double? {
        val v = dailyQuiet.takeLast(28)
        if (v.size < MIN_QUIET_DAYS) return null
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun hrMax(age: Int?): Double = 208 - 0.7 * (age ?: 35).coerceIn(15, 90)

    /** TRIMP-style load of one day's readings (timestamp ms → bpm, sorted), and minutes above quiet + 20 % reserve. */
    fun load(samples: List<Pair<Long, Double>>, quiet: Double, hrMax: Double): Pair<Double, Int> {
        if (samples.size < 2 || hrMax <= quiet) return 0.0 to 0
        var trimp = 0.0
        var elevated = 0.0
        samples.zipWithNext().forEach { (a, b) ->
            val minutes = ((b.first - a.first) / 60_000.0).coerceIn(0.0, MAX_GAP_MIN)
            val reserve = ((a.second - quiet) / (hrMax - quiet)).coerceIn(0.0, 1.0)
            if (reserve > 0) trimp += minutes * reserve * 0.64 * exp(1.92 * reserve)
            if (reserve >= 0.2) elevated += minutes
        }
        return trimp to elevated.roundToInt()
    }

    fun band(load: Double, usual: Double?): Band? {
        if (usual == null || usual <= 0) return null
        val r = load / usual
        return when {
            r < 0.5 -> Band.LIGHT
            r <= 1.3 -> Band.USUAL
            r <= 2.0 -> Band.HIGH
            else -> Band.VERY_HIGH
        }
    }

    /**
     * Today's load from up to 29 days of readings: quiet levels from the days before today, the
     * user's usual load from those days too. [asleep] says whether a moment was inside a night.
     */
    fun compute(
        hr: List<Pair<Long, Double>>,
        asleep: (Long) -> Boolean,
        today: LocalDate,
        zone: ZoneId,
        age: Int?,
    ): Result? {
        val byDay = hr.filter { it.second in 30.0..230.0 }.groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
        val days = byDay.keys.filter { it.isBefore(today) }.sorted()
        val quietByDay = days.associateWith { d ->
            quietOfDay(byDay.getValue(d).filter { (t, _) ->
                val h = Instant.ofEpochMilli(t).atZone(zone).hour
                h in 8..21 && !asleep(t)
            }.map { it.second })
        }
        val quiet = quietLevel(quietByDay.values.filterNotNull()) ?: return null
        val max = hrMax(age)
        val todaySamples = byDay[today].orEmpty().sortedBy { it.first }
        val (todayLoad, elevated) = load(todaySamples, quiet, max)
        val history = days.takeLast(28).map { load(byDay.getValue(it).sortedBy { s -> s.first }, quiet, max).first }.filter { it > 0 }
        val usual = history.takeIf { it.size >= MIN_QUIET_DAYS }?.sorted()?.let { it[it.size / 2] }
        return Result(todayLoad.roundToInt(), quiet.roundToInt(), usual?.roundToInt(), band(todayLoad, usual), elevated)
    }
}
