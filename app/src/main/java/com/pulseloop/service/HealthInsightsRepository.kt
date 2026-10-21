package com.pulseloop.service

import android.content.Context
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.BodyMeasurementEntity
import com.pulseloop.ring.MeasurementKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Rest Mode: the user says they're unwell or recovering, so activity nudges stand down. */
object RestMode {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("rest_mode", Context.MODE_PRIVATE)

    /** Rest Mode lasts until the end of [untilDay]; null when off. */
    fun until(c: Context): LocalDate? = prefs(c).getString("until", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.takeIf { !it.isBefore(LocalDate.now()) }

    fun isOn(c: Context): Boolean = until(c) != null

    fun set(c: Context, days: Int?) {
        prefs(c).edit().apply {
            if (days == null) remove("until") else putString("until", LocalDate.now().plusDays((days - 1).toLong()).toString())
        }.apply()
    }
}

/** Unguided breathing / meditation sessions, kept on the phone. */
object MindfulSessions {
    data class Session(val startAt: Long, val endAt: Long, val avgHr: Int?, val startHr: Int?, val endHr: Int?) {
        val minutes: Int get() = ((endAt - startAt) / 60_000L).toInt()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("mindful_sessions", Context.MODE_PRIVATE)

    fun all(c: Context): List<Session> = runCatching {
        val arr = JSONArray(prefs(c).getString("sessions", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Session(o.getLong("s"), o.getLong("e"), o.optInt("hr", -1).takeIf { it > 0 }, o.optInt("h0", -1).takeIf { it > 0 }, o.optInt("h1", -1).takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())

    fun add(c: Context, s: Session) {
        val list = (all(c) + s).takeLast(500)
        val arr = JSONArray()
        list.forEach { x ->
            arr.put(JSONObject().put("s", x.startAt).put("e", x.endAt)
                .put("hr", x.avgHr ?: -1).put("h0", x.startHr ?: -1).put("h1", x.endHr ?: -1))
        }
        prefs(c).edit().putString("sessions", arr.toString()).apply()
    }

    /**
     * Saves a finished session with the heart rate the ring recorded during it, and ticks the
     * day's "Meditation or relaxing" journal tag so its effect on the night shows up.
     */
    suspend fun finish(c: Context, db: PulseLoopDatabase, startAt: Long, endAt: Long): Session {
        val hr = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, startAt, endAt + 60_000L)
            .map { it.value }.filter { it in 30.0..220.0 }
        val s = Session(startAt, endAt, hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), hr.firstOrNull()?.roundToInt(), hr.lastOrNull()?.roundToInt())
        add(c, s)
        runCatching {
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val tag = JournalInsights.Tag.RELAXATION.key
            db.journalDao().upsert(com.pulseloop.data.entity.JournalEntryEntity(com.pulseloop.data.entity.JournalEntryEntity.idFor(day, tag), day, tag))
        }
        return s
    }
}

/** Everything the My Health screen shows, loaded together. */
object HealthInsightsRepository {

    data class Habits(
        val bedtime: String?,
        val wake: String?,
        val sleepMin: Int?,
        val steps: Int?,
        val activeMin: Int?,
        val restingHr: Double?,
    )

    data class Snapshot(
        val nights: List<NightSummary>,
        val need: SleepNeed,
        val debt: SleepDebt?,
        val radar: SymptomRadar.Result?,
        val todayStress: DaytimeStress?,
        val resilience: Resilience?,
        val bodyClock: BodyClock?,
        val cardio: CardioCapacity?,
        val areas: List<HealthArea>,
        val habits: Habits,
        val hrToday: List<VitalSample>,
        val sleepWindows: List<LongRange>,
        val workoutWindows: List<LongRange>,
    )

    private const val DAY = 86_400_000L

    suspend fun load(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Snapshot {
        val inputs = ReadinessRepository.loadInputs(db, now, zone)
        val nights = (inputs.history + listOfNotNull(inputs.lastNight)).sortedBy { it.day }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val goal = db.userGoalDao().get()
        val profile = db.userProfileDao().get()

        val need = SleepNeed.estimate(nights, goal?.sleepMinutes ?: 480, today)
        val debt = SleepDebt.from(nights, need, today)

        // Activity per day (steps), and night respiratory rate where the ring logs it.
        val days = db.activityDailyDao().recentReal(120).associateBy { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
        val resp = db.measurementDao().rangeReal(MeasurementKind.RESPIRATORY_RATE.name, start(today.minusDays(60)), now)
        val radarNights = nights.map { n ->
            SymptomRadar.Night(
                day = n.day,
                temperature = n.temperature,
                restingHr = n.restingHr,
                hrv = n.hrv,
                respiratoryRate = resp.filter { it.timestamp in n.startAt..n.endAt }.map { it.value }.takeIf { it.size >= 2 }?.average(),
                stepsDayBefore = days[n.day.minusDays(1)]?.steps?.takeIf { it > 0 }?.toDouble(),
            )
        }
        val radar = SymptomRadar.compute(radarNights, today)

        // Stress: today's daytime readings, and 14 days for resilience.
        val stressRows = db.measurementDao().rangeReal(MeasurementKind.STRESS.name, start(today.minusDays(14)), now)
        val nightByDay = nights.associateBy { it.day }
        fun daytime(d: LocalDate) = stressRows.filter {
            val t = Instant.ofEpochMilli(it.timestamp).atZone(zone)
            t.toLocalDate() == d && t.hour in 8..21
        }.map { it.value }
        val todayStress = DaytimeStress.compute(daytime(today))
        val hrvBase = nights.mapNotNull { it.hrv }.takeIf { it.size >= 7 }?.average()
        val resilience = Resilience.compute((1..14).mapNotNull { back ->
            val d = today.minusDays(back.toLong())
            val z = DaytimeStress.compute(daytime(d)) ?: return@mapNotNull null
            Resilience.Day(z.stressedMin, z.restoredMin, nightByDay[d.plusDays(1)]?.hrv?.let { h -> hrvBase?.let { h / it } })
        })

        val bodyClock = BodyClock.compute(nights, zone)
        val recent = nights.takeLast(30)
        val ringVo2 = db.measurementDao().rangeReal(MeasurementKind.VO2MAX.name, start(today.minusDays(90)), now).lastOrNull()?.value
        val cardio = CardioCapacity.compute(
            restingHr = recent.mapNotNull { it.restingHr }.takeIf { it.size >= 5 }?.average(),
            hrv = recent.mapNotNull { it.hrv }.takeIf { it.size >= 5 }?.average(),
            age = profile?.age, sex = profile?.sex, ringVo2 = ringVo2,
        )

        // Long-term areas, with weekly trend lines.
        fun weekly(f: (NightSummary) -> Double?): List<Double> = nights.groupBy { it.day.toEpochDay() / 7 }.toSortedMap()
            .values.mapNotNull { w -> w.mapNotNull(f).takeIf { it.isNotEmpty() }?.average() }
        val areas = buildList {
            val asleep = nights.filter { it.asleepMinutes > 0 }.takeLast(60)
            if (asleep.size >= 7) {
                val ratio = asleep.map { it.asleepMinutes }.average() / need.minutes
                val mids = asleep.map { it.midpointMinutes(zone).toDouble() }
                val m = mids.average()
                val spread = kotlin.math.sqrt(mids.sumOf { (it - m) * (it - m) } / mids.size)
                val regularity = Readiness.curve(spread, listOf(30.0 to 100.0, 60.0 to 75.0, 120.0 to 25.0))
                val score = (0.7 * HealthArea.scoreOf(ratio) + 0.3 * regularity).roundToInt()
                add(HealthArea("Sleep health", HealthArea.rate(score), score,
                    weekly { n -> n.asleepMinutes.takeIf { it > 0 }?.div(60.0) },
                    "Averaging ${SleepFormat.duration(asleep.map { it.asleepMinutes }.average().roundToInt())} against a need of ${SleepFormat.duration(need.minutes)}"))
            }
            resilience?.let { r ->
                val score = when (r.level) {
                    Resilience.Level.LIMITED -> 35; Resilience.Level.ADEQUATE -> 52; Resilience.Level.SOLID -> 66
                    Resilience.Level.STRONG -> 80; Resilience.Level.EXCEPTIONAL -> 92
                }
                val trend = stressRows.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                    .toSortedMap().values.map { d -> d.map { it.value }.average() }
                add(HealthArea("Stress management", HealthArea.rate(score), score, trend, "Resilience ${r.level.label.lowercase()} over ${r.daysUsed} days"))
            }
            cardio?.let { c ->
                val expected = profile?.age?.let { CardioCapacity.expectedVo2(it, profile?.sex?.lowercase() == "female") }
                val score = expected?.let { HealthArea.scoreOf(c.vo2max / it * 0.85) } ?: 60
                add(HealthArea("Heart health", HealthArea.rate(score), score, weekly { it.restingHr },
                    "Cardio capacity ${c.level.label.lowercase()}, estimated from resting heart rate and age"))
            }
        }

        // Habits: rolling 30-day averages.
        val last30 = nights.filter { it.day.isAfter(today.minusDays(31)) }
        fun clock(ms: List<Long>): String? {
            if (ms.size < 3) return null
            // Average around 18:00 so times either side of midnight average correctly.
            val mins = ms.map { val t = Instant.ofEpochMilli(it).atZone(zone).toLocalTime(); ((t.hour * 60 + t.minute) - 1080 + 1440) % 1440 }
            val avg = (mins.average().roundToInt() + 1080) % 1440
            return "%d:%02d".format(avg / 60, avg % 60)
        }
        val act30 = db.activityDailyDao().recentReal(31).filter { it.date < start(today) }
        val habits = Habits(
            bedtime = clock(last30.map { it.startAt }),
            wake = clock(last30.map { it.endAt }),
            sleepMin = last30.map { it.asleepMinutes }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            steps = act30.map { it.steps }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            activeMin = act30.map { it.activeMinutes }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            restingHr = last30.mapNotNull { it.restingHr }.takeIf { it.isNotEmpty() }?.average(),
        )

        // Today's heart rate with its context.
        val dayStart = start(today)
        val hrToday = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, dayStart - 6 * 3_600_000L, now)
            .map { VitalSample(it.timestamp, it.value) }.filter { it.value in 30.0..220.0 }
        val sleepWindows = nights.filter { it.endAt >= dayStart - 6 * 3_600_000L }.map { it.startAt..it.endAt }
        val workoutWindows = db.activitySessionDao().recent(20)
            .filter { it.statusRaw == "finished" && (it.endedAt ?: 0) >= dayStart - 6 * 3_600_000L }
            .map { it.startedAt..(it.endedAt ?: it.startedAt) }

        return Snapshot(nights, need, debt, radar, todayStress, resilience, bodyClock, cardio, areas, habits, hrToday, sleepWindows, workoutWindows)
    }

    // ── Trends ───────────────────────────────────────────────────────────────

    enum class TrendMetric(val label: String, val unit: String) {
        SLEEP("Sleep", "h"), STEPS("Steps", ""), ACTIVE("Active minutes", "min"), RESTING_HR("Resting HR", "bpm"),
        HRV("HRV", "ms"), STRESS("Stress", ""), SPO2("Blood oxygen", "%"), TEMPERATURE("Temperature", "°C"), WEIGHT("Weight", "kg"),
    }

    /** One value per day for [metric] over the last [days]. */
    suspend fun daily(db: PulseLoopDatabase, metric: TrendMetric, days: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> {
        val from = now - days * DAY
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        suspend fun dailyMean(kind: MeasurementKind, range: ClosedFloatingPointRange<Double>) =
            db.measurementDao().rangeReal(kind.name, from, now).filter { it.value in range }
                .groupBy { dayOf(it.timestamp) }.mapValues { (_, v) -> v.map { it.value }.average() }
        return when (metric) {
            TrendMetric.SLEEP -> db.sleepSessionDao().inRangeReal(from, now).filter { it.totalMinutes > 0 }
                .groupBy { dayOf(it.date) }.mapValues { (_, s) -> s.maxOf { it.totalMinutes } / 60.0 }
            TrendMetric.STEPS -> db.activityDailyDao().recentReal(days.toInt()).filter { it.steps > 0 }
                .associate { dayOf(it.date) to it.steps.toDouble() }
            TrendMetric.ACTIVE -> db.activityDailyDao().recentReal(days.toInt()).filter { it.activeMinutes > 0 }
                .associate { dayOf(it.date) to it.activeMinutes.toDouble() }
            TrendMetric.RESTING_HR, TrendMetric.HRV -> {
                val kind = if (metric == TrendMetric.HRV) MeasurementKind.HRV else MeasurementKind.HEART_RATE
                val sessions = db.sleepSessionDao().inRangeReal(from, now).filter { it.totalMinutes > 0 }
                    .groupBy { it.date }.mapValues { (_, s) -> s.maxBy { it.totalMinutes } }.values
                sessions.mapNotNull { s ->
                    val samples = db.measurementDao().rangeReal(kind.name, s.startAt, s.endAt).map { VitalSample(it.timestamp, it.value) }
                    val n = NightlyVitals.summarize(dayOf(s.date), s.startAt, s.endAt, s.totalMinutes,
                        if (kind == MeasurementKind.HEART_RATE) samples else emptyList(),
                        if (kind == MeasurementKind.HRV) samples else emptyList())
                    (if (metric == TrendMetric.HRV) n.hrv else n.restingHr)?.let { n.day to it }
                }.toMap()
            }
            TrendMetric.STRESS -> dailyMean(MeasurementKind.STRESS, 1.0..100.0)
            TrendMetric.SPO2 -> dailyMean(MeasurementKind.SPO2, 70.0..100.0)
            TrendMetric.TEMPERATURE -> dailyMean(MeasurementKind.TEMPERATURE, 30.0..42.0)
            TrendMetric.WEIGHT -> db.bodyMeasurementDao().since(BodyMeasurementEntity.TYPE_WEIGHT, from)
                .groupBy { dayOf(it.measuredAt) }.mapValues { (_, v) -> v.last().value }
        }
    }
}
