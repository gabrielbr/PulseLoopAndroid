package com.pulseloop.health.accuracy

import kotlin.math.abs

/**
 * Phase 0 of the accuracy check (docs/accuracy-check-plan.md): before anyone wears two devices for
 * a week, find out what each app actually writes to Health Connect, how often, and how late. Pure —
 * no Health Connect or Room imports — so the rules are unit-testable; [ReferenceSourceScanner]
 * feeds it.
 *
 * It keeps counts and timings, never values: the only values it holds are heart-rate samples for
 * the echo check, and those don't leave [ReferenceSurveyBuilder.build].
 */

/** `Metadata.device`, flattened. [type] is a `Device.TYPE_*` constant. */
data class DeviceInfo(val manufacturer: String?, val model: String?, val type: Int)

data class TimedValue(val at: Long, val value: Double)

/** One Health Connect record, reduced to what the survey needs. Times are epoch millis. */
data class SurveyRecord(
    val packageName: String,
    val metric: ReferenceMetric,
    /** Sample instants (a series record's samples; an interval record's start). */
    val sampleTimes: List<Long>,
    val endTime: Long,
    val lastModified: Long,
    val device: DeviceInfo?,
    /** `SleepSessionRecord.STAGE_TYPE_*` values present, for sleep records. */
    val sleepStageTypes: Set<Int> = emptySet(),
    /** Heart-rate samples, for the echo check only. */
    val heartRate: List<TimedValue> = emptyList(),
)

data class MetricSurvey(
    val metric: ReferenceMetric,
    val recordCount: Int,
    val sampleCount: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** Median gap between consecutive samples; null with fewer than two distinct instants. */
    val medianIntervalMs: Long?,
    /**
     * Median of `lastModifiedTime - endTime`: how long after the data's own time it reached
     * Health Connect. This is the sync lag of a bridge like Health Sync, and it's what decides
     * when a check day can be scored.
     */
    val medianDeliveryDelayMs: Long,
    val sleepStageTypes: Set<Int>,
)

/**
 * Whether this source's heart rate looks like PulseLoop's own coming back: the share of its
 * samples that match a ring sample to the second and the beat. A bridge syncing both ways
 * (Health Connect → Huawei Health → Health Connect) re-publishes PulseLoop's export under its own
 * package, which the origin filter can't catch. Only catches exact echoes; a bridge that rounds
 * or re-times samples on the way through would slip past.
 */
data class EchoCheck(val compared: Int, val matching: Int) {
    val share: Double get() = if (compared == 0) 0.0 else matching.toDouble() / compared
    val looksLikeEcho: Boolean get() = compared >= MIN_COMPARED && share >= ECHO_SHARE

    companion object {
        const val MIN_COMPARED = 50
        /** Two independent sensors agree to the beat and the second on a few percent of samples
         *  at most; an echo matches nearly all of them. */
        const val ECHO_SHARE = 0.5
    }
}

data class ReferenceSourceSurvey(
    val packageName: String,
    val metrics: List<MetricSurvey>,
    val devices: Set<DeviceInfo>,
    val echo: EchoCheck?,
) {
    val totalSamples: Int get() = metrics.sumOf { it.sampleCount }
    fun metric(m: ReferenceMetric): MetricSurvey? = metrics.firstOrNull { it.metric == m }
}

class ReferenceSurveyBuilder(private val ownPackage: String) {

    private class Acc {
        var records = 0
        val sampleTimes = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        val stageTypes = mutableSetOf<Int>()
    }

    private val bySource = linkedMapOf<String, MutableMap<ReferenceMetric, Acc>>()
    private val devices = mutableMapOf<String, MutableSet<DeviceInfo>>()
    private val heartRate = mutableMapOf<String, MutableList<TimedValue>>()

    /** Adds one record. PulseLoop's own records are dropped here, so no caller can forget to. */
    fun add(r: SurveyRecord) {
        if (r.packageName == ownPackage) return
        val acc = bySource.getOrPut(r.packageName) { linkedMapOf() }.getOrPut(r.metric) { Acc() }
        acc.records++
        acc.sampleTimes += r.sampleTimes
        acc.delays += (r.lastModified - r.endTime).coerceAtLeast(0)
        acc.stageTypes += r.sleepStageTypes
        r.device?.let { devices.getOrPut(r.packageName) { mutableSetOf() } += it }
        if (r.heartRate.isNotEmpty()) heartRate.getOrPut(r.packageName) { mutableListOf() } += r.heartRate
    }

    /** Sources with the most data first. [ringHeartRate] is the ring's own stored HR, for the echo check. */
    fun build(ringHeartRate: List<TimedValue> = emptyList()): List<ReferenceSourceSurvey> {
        val ring = ringHeartRate.sortedBy { it.at }
        return bySource.map { (pkg, metrics) ->
            ReferenceSourceSurvey(
                packageName = pkg,
                metrics = ReferenceMetric.entries.mapNotNull { m -> metrics[m]?.let { summarize(m, it) } },
                devices = devices[pkg].orEmpty(),
                echo = heartRate[pkg]?.takeIf { ring.isNotEmpty() }?.let { echoCheck(it, ring) },
            )
        }.sortedByDescending { it.totalSamples }
    }

    private fun summarize(metric: ReferenceMetric, acc: Acc): MetricSurvey {
        val times = acc.sampleTimes.sorted()
        return MetricSurvey(
            metric = metric,
            recordCount = acc.records,
            sampleCount = times.size,
            firstAt = times.firstOrNull() ?: 0L,
            lastAt = times.lastOrNull() ?: 0L,
            medianIntervalMs = medianInterval(times),
            medianDeliveryDelayMs = median(acc.delays.sorted()) ?: 0L,
            sleepStageTypes = acc.stageTypes.toSet(),
        )
    }

    companion object {
        const val ECHO_TIME_TOLERANCE_MS = 1_000L
        const val ECHO_VALUE_TOLERANCE = 0.5

        /** Median positive gap between sorted instants; duplicates (two records stamped at the
         *  same instant) are not a zero-length cadence. */
        fun medianInterval(sortedTimes: List<Long>): Long? =
            median(sortedTimes.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted())

        fun median(sorted: List<Long>): Long? = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }

        /** [ring] must be sorted by time. */
        fun echoCheck(reference: List<TimedValue>, ring: List<TimedValue>): EchoCheck {
            var matching = 0
            for (s in reference) {
                var i = lowerBound(ring, s.at - ECHO_TIME_TOLERANCE_MS)
                while (i < ring.size && ring[i].at <= s.at + ECHO_TIME_TOLERANCE_MS) {
                    if (abs(ring[i].value - s.value) < ECHO_VALUE_TOLERANCE) { matching++; break }
                    i++
                }
            }
            return EchoCheck(compared = reference.size, matching = matching)
        }

        private fun lowerBound(sorted: List<TimedValue>, at: Long): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid].at < at) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
