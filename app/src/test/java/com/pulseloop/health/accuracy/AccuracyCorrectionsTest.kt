package com.pulseloop.health.accuracy

import com.pulseloop.data.entity.AccuracyCorrectionEntity
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.service.DailyCalorieEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyCorrectionsTest {

    private val ring = DeviceEntity(id = "row", bleAddressHint = "aa:bb", firmwareVersion = "fw1")

    private fun c(metric: ComparisonMetric, value: Double, key: String = "AA:BB", enabled: Boolean = true, firmware: String? = "fw1", createdAt: Long = 0) =
        AccuracyCorrectionEntity(
            deviceKey = key, metric = metric.name,
            kind = if (metric.correction == CorrectionKind.SCALE) AccuracyCorrectionEntity.KIND_SCALE else AccuracyCorrectionEntity.KIND_OFFSET,
            value = value, fittedValue = value, fromCheckId = "c", enabled = enabled, firmware = firmware, createdAt = createdAt,
        )

    @Test
    fun onlyThePairedRingsEnabledCorrectionsApply() {
        val all = listOf(
            c(ComparisonMetric.STEPS, 1.2),
            c(ComparisonMetric.SPO2, 2.0, enabled = false),
            c(ComparisonMetric.HR_REST, -3.0, key = "CC:DD"),
        )
        val corr = AccuracyCorrections.of(ring, all, 40)
        assertEquals(setOf(ComparisonMetric.STEPS), corr.items.keys)
        assertEquals("AA:BB", corr.deviceKey)
    }

    @Test
    fun theDemoDeviceAndNoRingGetNothing() {
        val all = listOf(c(ComparisonMetric.STEPS, 1.2, key = RingIdentity.DEMO_DEVICE_ID.uppercase()))
        assertSame(Corrections.NONE, AccuracyCorrections.of(DeviceEntity(id = RingIdentity.DEMO_DEVICE_ID), all, null))
        assertSame(Corrections.NONE, AccuracyCorrections.of(null, all, null))
    }

    @Test
    fun noCorrectionsChangesNothing() {
        val n = Corrections.NONE
        assertEquals(7_412, n.steps(7_412))
        assertEquals(64.0, n.heartRate(64.0), 0.0)
        assertEquals(97.0, n.spo2(97.0), 0.0)
        assertEquals(420, n.asleepMinutes(420))
        val day = ActivityDailyEntity(date = 0, steps = 100, distanceMeters = 80.0)
        assertSame(day, n.day(day))
        assertNull(n.describe())
    }

    @Test
    fun stepsAndDistanceScale() {
        val corr = Corrections("K", mapOf(
            ComparisonMetric.STEPS to c(ComparisonMetric.STEPS, 1.18),
            ComparisonMetric.DISTANCE to c(ComparisonMetric.DISTANCE, 1.1),
        ))
        val day = corr.day(ActivityDailyEntity(date = 0, steps = 7_412, distanceMeters = 5_000.0, calories = 300.0))
        assertEquals(8_746, day.steps)
        assertEquals(5_500.0, day.distanceMeters, 1e-9)
        assertEquals(300.0, day.calories, 0.0) // not a corrected field
    }

    @Test
    fun heartRateUsesTheRestOrActiveOffsetBySplittingAtFlexHr() {
        // Age 40: Flex-HR = 0.6 × 180 = 108.
        val corr = Corrections("K", mapOf(
            ComparisonMetric.HR_REST to c(ComparisonMetric.HR_REST, -3.0),
            ComparisonMetric.HR_ACTIVE to c(ComparisonMetric.HR_ACTIVE, 8.0),
        ), age = 40)
        assertEquals(57.0, corr.heartRate(60.0), 1e-9)
        assertEquals(128.0, corr.heartRate(120.0), 1e-9)
    }

    @Test
    fun withOnlyOneHeartRateOffsetItAppliesAcrossTheBoard() {
        val corr = Corrections("K", mapOf(ComparisonMetric.HR_REST to c(ComparisonMetric.HR_REST, -3.0)), age = 40)
        assertEquals(117.0, corr.heartRate(120.0), 1e-9)
    }

    @Test
    fun theBaselineMovesByTheRestingOffset_preferringAFittedRestingHr() {
        val rest = Corrections("K", mapOf(ComparisonMetric.HR_REST to c(ComparisonMetric.HR_REST, -3.0)))
        assertEquals(55.0, rest.baseline(58.0), 1e-9)
        val both = Corrections("K", mapOf(
            ComparisonMetric.HR_REST to c(ComparisonMetric.HR_REST, -3.0),
            ComparisonMetric.RESTING_HR to c(ComparisonMetric.RESTING_HR, 2.0),
        ))
        assertEquals(60.0, both.baseline(58.0), 1e-9)
    }

    @Test
    fun spo2IsClampedAndSleepCorrectsOnlyTheTotal() {
        val corr = Corrections("K", mapOf(
            ComparisonMetric.SPO2 to c(ComparisonMetric.SPO2, 3.0),
            ComparisonMetric.SLEEP_ASLEEP to c(ComparisonMetric.SLEEP_ASLEEP, -25.0),
        ))
        assertEquals(100.0, corr.spo2(99.0), 0.0)
        val night = SleepSessionEntity(date = 0, startAt = 0, endAt = 1, totalMinutes = 420)
        assertEquals(395, corr.sleep(night).totalMinutes)
        assertEquals(0, corr.asleepMinutes(10))
    }

    @Test
    fun theCaptionNamesWhatChanged() {
        val corr = Corrections("K", mapOf(
            ComparisonMetric.HR_REST to c(ComparisonMetric.HR_REST, -3.0),
            ComparisonMetric.STEPS to c(ComparisonMetric.STEPS, 1.18),
        ))
        assertEquals("steps ×1.18 · heart rate at rest −3.0 bpm", corr.describe())
        assertEquals("steps ×1.18", corr.describe(setOf(ComparisonMetric.STEPS)))
        assertNull(corr.describe(setOf(ComparisonMetric.SPO2)))
    }

    @Test
    fun aCorrectionGoesStaleWithAgeOrAFirmwareChange() {
        val now = 200L * 86_400_000L
        assertFalse(AccuracyCorrections.isStale(c(ComparisonMetric.STEPS, 1.2, createdAt = now - 10 * 86_400_000L), ring, now))
        assertTrue(AccuracyCorrections.isStale(c(ComparisonMetric.STEPS, 1.2, createdAt = now - 100 * 86_400_000L), ring, now))
        assertTrue(AccuracyCorrections.isStale(c(ComparisonMetric.STEPS, 1.2, firmware = "fw0", createdAt = now), ring, now))
    }

    @Test
    fun theAppShowsTheAdjustedCaloriesButFallsBackToTheRawOnes() {
        val raw = ActivityDailyEntity(date = 0, source = DailyCalorieEstimator.RING_HISTORY_SOURCE, estimatedActiveCalories = 300.0)
        assertEquals(300.0, DailyCalorieEstimator.displayActiveCalories(raw)!!, 0.0)
        val adjusted = raw.copy(estimatedActiveCaloriesAdjusted = 340.0)
        assertEquals(340.0, DailyCalorieEstimator.displayActiveCalories(adjusted)!!, 0.0)
        // The export-facing figure ignores the adjusted copy entirely.
        assertEquals(300.0, DailyCalorieEstimator.effectiveActiveCalories(adjusted)!!, 0.0)
        // A device-reported figure wins over both, as before.
        val device = adjusted.copy(source = "colmi", calories = 500.0)
        assertEquals(500.0, DailyCalorieEstimator.displayActiveCalories(device)!!, 0.0)
    }

    @Test
    fun formattedValuesMatchTheirKind() {
        assertEquals("×1.25", AccuracyCorrections.formatValue(ComparisonMetric.STEPS, 1.25))
        assertEquals("+25 min", AccuracyCorrections.formatValue(ComparisonMetric.SLEEP_ASLEEP, 25.0))
        assertEquals("−2.0 points", AccuracyCorrections.formatValue(ComparisonMetric.SPO2, -2.0))
    }

    @Test
    fun storedReadingsAreCorrectedByKindOnly() {
        val corr = Corrections("K", mapOf(
            ComparisonMetric.HR_REST to c(ComparisonMetric.HR_REST, -3.0),
            ComparisonMetric.SPO2 to c(ComparisonMetric.SPO2, 1.0),
        ), age = 40)
        fun m(kind: com.pulseloop.ring.MeasurementKind, v: Double) =
            com.pulseloop.data.entity.MeasurementEntity(kindRaw = kind.name, value = v, unit = "", timestamp = 0, sourceRaw = "history")
        assertEquals(57.0, corr.measurement(m(com.pulseloop.ring.MeasurementKind.HEART_RATE, 60.0)).value, 1e-9)
        assertEquals(97.0, corr.measurement(m(com.pulseloop.ring.MeasurementKind.SPO2, 96.0)).value, 1e-9)
        assertEquals(45.0, corr.measurement(m(com.pulseloop.ring.MeasurementKind.HRV, 45.0)).value, 1e-9)
    }

    @Test
    fun theCoachIsToldOnlyWhenSomethingIsAdjusted() {
        assertNull(Corrections.NONE.coachNote())
        val note = Corrections("K", mapOf(ComparisonMetric.STEPS to c(ComparisonMetric.STEPS, 1.2))).coachNote()!!
        assertTrue(note, note.contains("steps ×1.20"))
        assertTrue(note, note.contains("stored readings are unchanged"))
    }

    @Test
    fun aWidgetSnapshotFromBeforeTheAdjustedFlagStillDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"generatedAt":1,"dayStart":0,"activity":{"steps":5.0},"sleep":{"durationText":"7h","score":80}}"""
        val snap = json.decodeFromString(com.pulseloop.widgets.WidgetSnapshot.serializer(), old)
        assertFalse(snap.activity!!.adjusted)
        assertFalse(snap.sleep!!.adjusted)
    }
}
