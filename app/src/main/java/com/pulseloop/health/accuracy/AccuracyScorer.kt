package com.pulseloop.health.accuracy

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.AccuracyCheckDayEntity
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.data.entity.SleepStageBlockEntity
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.SleepStage
import com.pulseloop.service.DailyCalorieEstimator
import com.pulseloop.service.NightlyVitals
import com.pulseloop.service.VitalSample
import com.pulseloop.service.asleepMinutes
import com.pulseloop.util.TimeUtil
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlinx.coroutines.sync.withLock

/**
 * Reads both devices for a check's window and stores the scored days (plan, "Comparison
 * engine"). Foreground: it reads Health Connect, which background quotas make unreliable. The
 * reference's readings are reduced to [AccuracyCheckDayEntity] aggregates here and never stored.
 */
object AccuracyScorer {

    private const val TAG = "AccuracyScorer"
    private const val HOUR = 3_600_000L
    /** A reference day with fewer steps than this is a day the device wasn't worn, not a ring overcount. */
    const val MIN_REF_STEPS = 500.0
    /** A 15-minute bucket with this many steps or more is "active" for heart rate. */
    const val ACTIVE_BUCKET_STEPS = 300
    /** …and with fewer than this it's "at rest". In between is ambiguous and not scored. */
    const val REST_BUCKET_STEPS = 50
    const val MIN_HR_PAIRS_PER_DAY = 20
    const val MIN_SPO2_PAIRS_PER_DAY = 5
    /** A day is scored once the reference has delivered past its end, or after this long anyway. */
    const val DELIVERY_GRACE_MS = 24 * HOUR
    /** Don't re-read more often than this unless asked. */
    const val RESCORE_INTERVAL_MS = 30 * 60_000L

    /**
     * Bumped when the scoring rules change in a way that alters a finished check's result
     * (2: wear coverage). A check last scored under an older version is scored again once, by
     * itself, the next time the app can read Health Connect in the foreground; finished checks
     * otherwise keep their stored result for good.
     */
    const val SCORING_VERSION = 2

    private val rescoreLock = kotlinx.coroutines.sync.Mutex()

    private fun versions(context: Context) =
        context.applicationContext.getSharedPreferences("accuracy_scoring", Context.MODE_PRIVATE)

    /** True when [checkId] was last scored under older rules (or never). */
    fun outdated(context: Context, checkId: String): Boolean =
        versions(context).getInt(checkId, 0) < SCORING_VERSION

    /**
     * Scores every finished check that predates the current rules, oldest first. Each one that
     * can't be read now (no permission, Health Connect busy) is left for the next call.
     */
    suspend fun rescoreOutdated(context: Context, checks: List<AccuracyCheckEntity>) = rescoreLock.withLock {
        // Started from app launch and from the accuracy screen; one pass at a time, and a check
        // the other pass already re-scored drops out of the filter.
        checks.filter { !it.isOpen && outdated(context, it.id) }.sortedBy { it.startedAt }.forEach { c ->
            runCatching { score(context, c) }.onFailure { Log.w(TAG, "re-scoring ${c.id} failed", it) }
        }
    }

    enum class HrContext { REST, ACTIVE }

    sealed interface Result {
        data class Scored(val rows: Int, val waitingDays: Int, val echo: EchoCheck?) : Result
        data object Unavailable : Result
        data object Skipped : Result
    }

    suspend fun scoreIfStale(context: Context, check: AccuracyCheckEntity, now: Long = System.currentTimeMillis()): Result =
        if (check.scoredAt != null && now - check.scoredAt < RESCORE_INTERVAL_MS) Result.Skipped else score(context, check, now)

    suspend fun score(context: Context, check: AccuracyCheckEntity, now: Long = System.currentTimeMillis()): Result {
        if (HealthConnectSdk.availability(context) != HealthConnectAvailability.AVAILABLE) return Result.Unavailable
        val db = PulseLoopDatabase.getInstance(context)
        val dao = db.accuracyDao()
        val client = HealthConnectClient.getOrCreate(context)
        val zone = ZoneId.systemDefault()
        val from = check.startedAt
        val to = check.endedAt ?: minOf(now, check.plannedEndAt)
        val readable = AccuracyCheckPermissions.readable(ReferenceSourceScanner.grantedPermissions(context))
        val compared = dao.pausedUnits(check.id).mapNotNull { ExportUnit.byName(it.unit)?.metric }.toSet() intersect readable
        val origin = setOf(DataOrigin(check.referencePackage))
        val readFrom = from - 18 * HOUR // a night that began before the check's first morning
        val readTo = to + 18 * HOUR

        suspend fun <T : Record> read(type: KClass<T>): List<T> {
            val out = mutableListOf<T>()
            var token: String? = null
            var pages = 0
            do {
                val r = client.readRecords(
                    ReadRecordsRequest(
                        type,
                        TimeRangeFilter.between(Instant.ofEpochMilli(readFrom), Instant.ofEpochMilli(readTo)),
                        dataOriginFilter = origin, pageSize = 1000, pageToken = token,
                    ),
                )
                out += r.records
                token = r.pageToken
                pages++
            } while (token != null && pages < 40)
            return out
        }

        val ref = try {
            RefData(
                steps = if (ReferenceMetric.STEPS in compared) read(StepsRecord::class) else emptyList(),
                distance = if (ReferenceMetric.DISTANCE in compared) read(DistanceRecord::class) else emptyList(),
                calories = if (ReferenceMetric.ACTIVE_CALORIES in compared) read(ActiveCaloriesBurnedRecord::class) else emptyList(),
                hr = if (ReferenceMetric.HEART_RATE in compared) read(HeartRateRecord::class) else emptyList(),
                resting = if (ReferenceMetric.RESTING_HEART_RATE in compared) read(RestingHeartRateRecord::class) else emptyList(),
                sleep = if (ReferenceMetric.SLEEP in compared) read(SleepSessionRecord::class) else emptyList(),
                spo2 = if (ReferenceMetric.OXYGEN_SATURATION in compared) read(OxygenSaturationRecord::class) else emptyList(),
                hrv = if (ReferenceMetric.HRV in compared) read(HeartRateVariabilityRmssdRecord::class) else emptyList(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "reading the reference failed", e)
            return Result.Unavailable
        }

        val refHr = ref.hr.flatMap { r -> r.samples.map { TimedValue(it.time.toEpochMilli(), it.beatsPerMinute.toDouble()) } }.sortedBy { it.at }
        // The latest data time the reference has delivered, across everything it wrote.
        val coveredUntil = listOfNotNull(
            ref.steps.maxOfOrNull { it.endTime.toEpochMilli() },
            ref.distance.maxOfOrNull { it.endTime.toEpochMilli() },
            refHr.lastOrNull()?.at,
            ref.sleep.maxOfOrNull { it.endTime.toEpochMilli() },
            ref.spo2.maxOfOrNull { it.time.toEpochMilli() },
        ).maxOrNull() ?: 0L
        fun delivered(dataEnd: Long) = coveredUntil >= dataEnd - 30 * 60_000L || now >= dataEnd + DELIVERY_GRACE_MS

        val rows = mutableListOf<AccuracyCheckDayEntity>()
        var waiting = 0
        val ringHrAll = db.measurementDao().rangeReal(MeasurementKind.HEART_RATE.name, readFrom, readTo)
        val ringHr = ringHrAll.filter { it.timestamp in from..to }.map { TimedValue(it.timestamp, it.value) }

        val ringNights = ringNights(db, from, to, zone)
        val ringBuckets = fullDaysOrPartial(from, to, zone).flatMap { d ->
            db.activityBucketDao().byDay(LocalDate.ofEpochDay(d).atStartOfDay(zone).toInstant().toEpochMilli())
        }

        // ── Wear coverage: when each device was off (flat, charging, taken off), from the gaps in
        //    its own data. Only a device that logs heart rate often enough can be judged; for one
        //    that doesn't, quiet and off look the same, so it is taken as worn throughout.
        val coverageEnd = minOf(to, now)
        val ringEvidence = buildList {
            ringHrAll.forEach { add(it.timestamp) }
            ringBuckets.filter { it.steps > 0 }.forEach { add(it.startEpoch); add(it.startEpoch + BUCKET_MS) }
            ringNights.values.forEach { n -> n.blocks.forEach { b -> add(b.startAt); add(b.startAt + b.durationMinutes * 60_000L) } }
        }
        val refEvidence = buildList {
            refHr.forEach { add(it.at) }
            // A day-long steps record says nothing about when the device was on.
            ref.steps.filter { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() <= HOUR }
                .forEach { add(it.startTime.toEpochMilli()); add(it.endTime.toEpochMilli()) }
            ref.sleep.forEach { r ->
                add(r.startTime.toEpochMilli()); add(r.endTime.toEpochMilli())
                r.stages.forEach { st -> add(st.startTime.toEpochMilli()); add(st.endTime.toEpochMilli()) }
            }
            ref.spo2.forEach { add(it.time.toEpochMilli()) }
            ref.hrv.forEach { add(it.time.toEpochMilli()) }
        }
        val ringGaps = if (AccuracyComparison.judgeable(ringHrAll.map { it.timestamp }))
            AccuracyComparison.offGaps(ringEvidence, from, coverageEnd) else emptyList()
        val refGaps = if (AccuracyComparison.judgeable(refHr.map { it.at }))
            AccuracyComparison.offGaps(refEvidence, from, coverageEnd) else emptyList()

        /** Which device was off longer within [a, b), and from when; null when both stayed on. */
        fun offNote(prefix: String, a: Long, b: Long, minMs: Long): String? {
            val r = AccuracyComparison.gapsWithin(ringGaps, a, b)
            val f = AccuracyComparison.gapsWithin(refGaps, a, b)
            val rMs = AccuracyComparison.gapMs(r, a, b)
            val fMs = AccuracyComparison.gapMs(f, a, b)
            if (maxOf(rMs, fMs) < minMs) return null
            val (who, gaps) = if (rMs >= fMs) WHO_RING to r else WHO_REF to f
            return "$prefix$who:${gaps.first().first}"
        }

        // ── Daily totals: whole local days inside the window only. The day a check starts on is
        //    partial for whichever device went on later, so its totals aren't comparable. A day
        //    one device spent partly off is compared over the hours both covered, or not at all
        //    when that is under [MIN_SHARED_MS].
        val fullDays = fullDaysIn(from, to, zone)
        val refStepItems = ref.steps.map { AccuracyComparison.IntervalAmount(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.count.toDouble()) }
        val refDistItems = ref.distance.map { AccuracyComparison.IntervalAmount(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.distance.inMeters) }
        val refSteps = AccuracyComparison.dailyTotals(refStepItems, zone)
        val refDist = AccuracyComparison.dailyTotals(refDistItems, zone)
        val refCal = AccuracyComparison.dailyTotals(ref.calories.map { AccuracyComparison.IntervalAmount(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.energy.inKilocalories) }, zone)
        for (day in fullDays) {
            val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val ringDay = db.activityDailyDao().byDay(dayStart)?.takeIf { it.source != "demo" && it.source != "mock" }
            val isDelivered = delivered(dayEnd)
            if (!isDelivered) waiting++
            val refStepsDay = refSteps[day]
            // Worn on both: the reference walked a real day and the ring recorded something.
            val worn = (refStepsDay ?: 0.0) >= MIN_REF_STEPS && (ringDay?.steps ?: 0) > 0
            val off = AccuracyComparison.gapsWithin(ringGaps + refGaps, dayStart, dayEnd)
            val sharedMs = (dayEnd - dayStart) - AccuracyComparison.gapMs(off, dayStart, dayEnd)
            val partial = off.isNotEmpty()
            val tooPartial = partial && sharedMs < MIN_SHARED_MS
            val partlyNote = offNote(NOTE_PARTLY_WORN_PREFIX, dayStart, dayEnd, 1L)
            val note = when {
                !isDelivered -> NOTE_WAITING
                !worn -> NOTE_NOT_WORN
                tooPartial -> partlyNote
                partial -> "$NOTE_SHARED_PREFIX${sharedMs / 60_000L}"
                else -> null
            }
            // Over the shared hours only: the ring's 15-minute buckets and the reference's
            // records, each cut to the time both devices were on.
            val ringItems = ringBuckets.filter { it.startEpoch in dayStart until dayEnd }
            fun ringWithin(value: (com.pulseloop.data.entity.ActivityBucketEntity) -> Double) =
                AccuracyComparison.amountWithin(ringItems.map { AccuracyComparison.IntervalAmount(it.startEpoch, it.startEpoch + BUCKET_MS, value(it)) }, dayStart, dayEnd, off)
            if (ReferenceMetric.STEPS in compared) {
                val ringSteps = if (partial) ringWithin { it.steps.toDouble() } else ringDay?.steps?.toDouble()
                val refStepsCompared = if (partial) AccuracyComparison.amountWithin(refStepItems, dayStart, dayEnd, off) else refStepsDay
                rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.STEPS, day, ringSteps, refStepsCompared, isDelivered && worn && !tooPartial, note, now)
            }
            if (ReferenceMetric.DISTANCE in compared) {
                // Only the ring's buckets can be cut to the shared hours; without bucket distance
                // a partial day can't be compared.
                val bucketDist = if (partial) ringWithin { it.distanceMeters }.takeIf { it > 0 } else null
                val ringDist = if (partial) bucketDist else ringDay?.distanceMeters?.takeIf { it > 0 }
                val refDistCompared = if (partial) AccuracyComparison.amountWithin(refDistItems, dayStart, dayEnd, off) else refDist[day]
                val ok = !tooPartial && (!partial || bucketDist != null)
                rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.DISTANCE, day, ringDist, refDistCompared,
                    isDelivered && (worn || ReferenceMetric.STEPS !in compared) && ok,
                    if (partial && !ok && note?.startsWith(NOTE_SHARED_PREFIX) == true) partlyNote else note, now)
            }
            if (ReferenceMetric.ACTIVE_CALORIES in compared) {
                // The ring's calories are one figure for the whole day, so a partly worn day can't be cut.
                val ringCal = ringDay?.let { DailyCalorieEstimator.effectiveActiveCalories(it) }
                rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.ACTIVE_CALORIES, day, ringCal, refCal[day],
                    isDelivered && (worn || ReferenceMetric.STEPS !in compared) && !partial,
                    if (partial && note?.startsWith(NOTE_SHARED_PREFIX) == true) partlyNote else note, now)
            }
        }

        // ── Sleep, per waking day. The ring's main night is its longest session that day, the
        //    same night Readiness uses. A night either device stopped partway through isn't
        //    compared: a watch flat at 3 am makes the ring look like it over-reports sleep.
        val needsNights = ReferenceMetric.SLEEP in compared || ReferenceMetric.RESTING_HEART_RATE in compared || ReferenceMetric.HRV in compared
        val refNights = ref.sleep.groupBy { AccuracyComparison.epochDay(TimeUtil.wakingDayLocal(it.startTime.toEpochMilli(), zone), zone) }
            .mapValues { (_, s) -> s.maxBy { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() } }
        val ringHrv = if (needsNights) db.measurementDao().rangeReal(MeasurementKind.HRV.name, readFrom, readTo) else emptyList()
        for ((day, night) in ringNights) {
            val dayMidnight = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
            val isDelivered = delivered(night.session.endAt)
            val refRecForSpan = refNights[day]
            val spanStart = minOf(night.session.startAt, refRecForSpan?.startTime?.toEpochMilli() ?: night.session.startAt)
            val spanEnd = maxOf(night.session.endAt, refRecForSpan?.endTime?.toEpochMilli() ?: night.session.endAt)
            val nightOff = offNote(NOTE_NIGHT_OFF_PREFIX, spanStart, spanEnd, NIGHT_OFF_MIN_MS)
            if (ReferenceMetric.SLEEP in compared) {
                val refRec = refNights[day]
                val refNight = refRec?.let(::toNight)
                val note = when {
                    !isDelivered -> NOTE_WAITING
                    refNight == null -> NOTE_NO_REF_NIGHT
                    nightOff != null -> nightOff
                    else -> null
                }
                val valid = isDelivered && refNight != null && nightOff == null
                val ringAsleep = (if (night.blocks.isEmpty()) night.session.totalMinutes else asleepMinutes(night.blocks)).toDouble()
                rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.SLEEP_ASLEEP, day, ringAsleep, refNight?.asleepMinutes?.toDouble(), valid, note, now)
                rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.SLEEP_ONSET, day,
                    minutesFrom(dayMidnight, night.night.onset), refNight?.let { minutesFrom(dayMidnight, it.onset) }, valid, note, now)
                rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.SLEEP_WAKE, day,
                    minutesFrom(dayMidnight, night.night.wake), refNight?.let { minutesFrom(dayMidnight, it.wake) }, valid, note, now)
                val agreement = refNight?.let { AccuracyComparison.stageAgreement(night.night, it) }
                if (agreement != null || refNight != null) {
                    rows += AccuracyCheckDayEntity(
                        checkId = check.id, metric = ComparisonMetric.SLEEP_STAGES.name, day = day,
                        pairs = agreement?.overlapMinutes ?: 0, inBand = agreement?.agreeMinutes ?: 0,
                        valid = valid && agreement != null && agreement.overlapMinutes > 0,
                        note = agreement?.encode() ?: if (refNight != null && !refNight.hasStages) NOTE_NO_REF_STAGES else note,
                        computedAt = now,
                    )
                }
            }
            if (ReferenceMetric.RESTING_HEART_RATE in compared || ReferenceMetric.HRV in compared) {
                val summary = NightlyVitals.summarize(
                    day = LocalDate.ofEpochDay(day), startAt = night.session.startAt, endAt = night.session.endAt,
                    asleepMinutes = asleepMinutes(night.blocks),
                    heartRate = ringHrAll.map { VitalSample(it.timestamp, it.value) },
                    hrv = ringHrv.map { VitalSample(it.timestamp, it.value) },
                )
                if (ReferenceMetric.RESTING_HEART_RATE in compared) {
                    val refRhr = ref.resting.filter { AccuracyComparison.epochDay(it.time.toEpochMilli(), zone) == day }
                        .maxByOrNull { it.time }?.beatsPerMinute?.toDouble()
                    rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.RESTING_HR, day, summary.restingHr, refRhr,
                        isDelivered && nightOff == null, if (!isDelivered) NOTE_WAITING else nightOff, now)
                }
                if (ReferenceMetric.HRV in compared) {
                    val window = (night.session.startAt - HOUR)..(night.session.endAt + HOUR)
                    val refHrvNight = ref.hrv.filter { it.time.toEpochMilli() in window }.map { it.heartRateVariabilityMillis }.sorted()
                    val refMedian = refHrvNight.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
                    rows += AccuracyComparison.dailyRow(check.id, ComparisonMetric.HRV, day, summary.hrv, refMedian,
                        isDelivered && nightOff == null, if (!isDelivered) NOTE_WAITING else nightOff, now)
                }
            }
        }

        // ── Heart rate, paired sample by sample and split rest / active.
        var echo: EchoCheck? = null
        if (ReferenceMetric.HEART_RATE in compared && refHr.isNotEmpty()) {
            echo = ReferenceSurveyBuilder.echoCheck(refHr, ringHr)
            val tol = AccuracyComparison.tolerance(ReferenceSurveyBuilder.medianInterval(refHr.map { it.at }), 60_000L, 150_000L)
            val buckets = ringBuckets.associateBy { it.startEpoch }
            val workouts = db.activitySessionDao().finishedSince(from).filter { it.endedAt != null && it.startedAt < to }
            val sleepWindows = ringNights.values.map { it.session.startAt..it.session.endAt }
            val pairs = AccuracyComparison.pair(ringHr, refHr, tol, zone) { t ->
                when {
                    workouts.any { t >= it.startedAt && t <= it.endedAt!! } -> HrContext.ACTIVE
                    sleepWindows.any { t in it } -> HrContext.REST
                    else -> {
                        val steps = buckets[t - Math.floorMod(t, 15 * 60_000L)]?.steps ?: 0
                        when {
                            steps >= ACTIVE_BUCKET_STEPS -> HrContext.ACTIVE
                            steps < REST_BUCKET_STEPS -> HrContext.REST
                            else -> null
                        }
                    }
                }
            }
            for ((key, sums) in pairs) {
                val (day, ctx) = key
                val dayEnd = LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val metric = if (ctx == HrContext.REST) ComparisonMetric.HR_REST else ComparisonMetric.HR_ACTIVE
                val isDelivered = delivered(minOf(dayEnd, to))
                val enough = sums.n >= if (ctx == HrContext.REST) MIN_HR_PAIRS_PER_DAY else 5
                rows += AccuracyComparison.pairedRow(check.id, metric, day, sums, isDelivered && enough, now,
                    when { !isDelivered -> NOTE_WAITING; !enough -> NOTE_FEW_PAIRS; else -> null })
            }
        }

        // ── SpO₂, paired within five minutes.
        if (ReferenceMetric.OXYGEN_SATURATION in compared && ref.spo2.isNotEmpty()) {
            val ringSpo2 = db.measurementDao().rangeReal(MeasurementKind.SPO2.name, from, to)
                .filter { it.value in 70.0..100.0 }.map { TimedValue(it.timestamp, it.value) }
            val refSpo2 = ref.spo2.map { TimedValue(it.time.toEpochMilli(), it.percentage.value) }.sortedBy { it.at }
            val pairs = AccuracyComparison.pair(ringSpo2, refSpo2, 5 * 60_000L, zone) { Unit }
            for ((key, sums) in pairs) {
                val day = key.first
                val dayEnd = LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val isDelivered = delivered(minOf(dayEnd, to))
                rows += AccuracyComparison.pairedRow(check.id, ComparisonMetric.SPO2, day, sums,
                    isDelivered && sums.n >= MIN_SPO2_PAIRS_PER_DAY, now, if (!isDelivered) NOTE_WAITING else null)
            }
        }

        echo?.let {
            rows += AccuracyCheckDayEntity(checkId = check.id, metric = ECHO_ROW, day = 0, pairs = it.compared, inBand = it.matching, valid = false, computedAt = now)
        }

        dao.clearDays(check.id)
        dao.upsertDays(rows)
        dao.byId(check.id)?.let { dao.upsert(it.copy(scoredAt = now)) }
        versions(context).edit().putInt(check.id, SCORING_VERSION).apply()
        return Result.Scored(rows.size, waiting, echo)
    }

    const val ECHO_ROW = "ECHO"
    const val NOTE_WAITING = "waiting"
    const val NOTE_NOT_WORN = "not_worn"
    const val NOTE_NO_REF_NIGHT = "no_ref_night"
    const val NOTE_NO_REF_STAGES = "no_ref_stages"
    const val NOTE_FEW_PAIRS = "few_pairs"
    /** `partly:<ring|ref>:<epoch ms>`: that device was off from then, for too much of the day. */
    const val NOTE_PARTLY_WORN_PREFIX = "partly:"
    /** `night_off:<ring|ref>:<epoch ms>`: that device stopped partway through the night. */
    const val NOTE_NIGHT_OFF_PREFIX = "night_off:"
    /** `shared:<minutes>`: counted, but only over the minutes both devices were on. */
    const val NOTE_SHARED_PREFIX = "shared:"
    const val WHO_RING = "ring"
    const val WHO_REF = "ref"
    /** A partly worn day still counts with this much time both devices covered. */
    const val MIN_SHARED_MS = 12 * HOUR
    /** A night is dropped when either device was off for this long inside it. */
    const val NIGHT_OFF_MIN_MS = 15 * 60_000L
    private const val BUCKET_MS = 15 * 60_000L

    private data class RefData(
        val steps: List<StepsRecord>,
        val distance: List<DistanceRecord>,
        val calories: List<ActiveCaloriesBurnedRecord>,
        val hr: List<HeartRateRecord>,
        val resting: List<RestingHeartRateRecord>,
        val sleep: List<SleepSessionRecord>,
        val spo2: List<OxygenSaturationRecord>,
        val hrv: List<HeartRateVariabilityRmssdRecord>,
    )

    private data class RingNight(
        val session: com.pulseloop.data.entity.SleepSessionEntity,
        val blocks: List<SleepStageBlockEntity>,
        val night: SleepNight,
    )

    /** Ring nights that started at or after the check began and ended by its end, by waking epoch day. */
    private suspend fun ringNights(db: PulseLoopDatabase, from: Long, to: Long, zone: ZoneId): Map<Long, RingNight> {
        val sessions = db.sleepSessionDao().inRangeReal(TimeUtil.startOfDayLocal(from, zone), to + AccuracyComparison.DAY_MS)
            .filter { it.totalMinutes > 0 && it.startAt >= from && it.endAt <= to }
            .groupBy { it.date }
            .mapValues { (_, s) -> s.maxBy { it.totalMinutes } }
        return sessions.values.associate { s ->
            val blocks = db.sleepStageBlockDao().forSession(s.id)
            AccuracyComparison.epochDay(s.date, zone) to RingNight(s, blocks, SleepNight(s.startAt, s.endAt, blocks.map(::toSpan)))
        }
    }

    internal fun toSpan(b: SleepStageBlockEntity): StageSpan = StageSpan(
        b.startAt, b.startAt + b.durationMinutes * 60_000L,
        when (b.stageRaw) {
            SleepStage.AWAKE.name -> Stage.AWAKE
            SleepStage.LIGHT.name -> Stage.LIGHT
            SleepStage.DEEP.name -> Stage.DEEP
            SleepStage.REM.name -> Stage.REM
            else -> Stage.ASLEEP
        },
    )

    internal fun toNight(r: SleepSessionRecord): SleepNight = SleepNight(
        r.startTime.toEpochMilli(), r.endTime.toEpochMilli(),
        r.stages.map { s ->
            StageSpan(
                s.startTime.toEpochMilli(), s.endTime.toEpochMilli(),
                when (s.stage) {
                    SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> Stage.AWAKE
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> Stage.LIGHT
                    SleepSessionRecord.STAGE_TYPE_DEEP -> Stage.DEEP
                    SleepSessionRecord.STAGE_TYPE_REM -> Stage.REM
                    else -> Stage.ASLEEP
                },
            )
        },
    )

    private fun minutesFrom(origin: Long, t: Long): Double = (t - origin) / 60_000.0

    /** Local days lying wholly inside `[from, to]`. */
    internal fun fullDaysIn(from: Long, to: Long, zone: ZoneId): List<Long> {
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val firstFull = if (first.atStartOfDay(zone).toInstant().toEpochMilli() == from) first else first.plusDays(1)
        val out = mutableListOf<Long>()
        var d = firstFull
        while (d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() <= to) {
            out += d.toEpochDay()
            d = d.plusDays(1)
        }
        return out
    }

    private fun fullDaysOrPartial(from: Long, to: Long, zone: ZoneId): List<Long> {
        val a = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().toEpochDay()
        val b = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().toEpochDay()
        return (a..b).toList()
    }
}
