package com.pulseloop.health.accuracy

import com.pulseloop.health.HealthConnectBodyImport
import com.pulseloop.health.HealthConnectPermissionReconcile
import com.pulseloop.health.HealthConnectPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSurveyTest {

    private val own = "com.pulseloop"
    private val sync = "nl.appyhapps.healthsync"
    private val min = 60_000L

    private fun hr(pkg: String, times: List<Long>, bpm: Double = 60.0, end: Long = times.last(), modified: Long = end) =
        SurveyRecord(pkg, ReferenceMetric.HEART_RATE, times, end, modified, null, heartRate = times.map { TimedValue(it, bpm) })

    @Test
    fun ownRecordsNeverBecomeASource() {
        val b = ReferenceSurveyBuilder(own)
        b.add(hr(own, listOf(0, min)))
        b.add(hr(sync, listOf(0, min)))
        assertEquals(listOf(sync), b.build().map { it.packageName })
    }

    @Test
    fun cadenceIsTheMedianGapAcrossRecordsIgnoringDuplicates() {
        val b = ReferenceSurveyBuilder(own)
        // Two records, 5-minute samples, one instant written twice, one long overnight gap.
        b.add(hr(sync, listOf(0, 5 * min, 10 * min, 10 * min)))
        b.add(hr(sync, listOf(15 * min, 20 * min, 600 * min)))
        val m = b.build().single().metric(ReferenceMetric.HEART_RATE)!!
        assertEquals(7, m.sampleCount)
        assertEquals(5 * min, m.medianIntervalMs)
        assertEquals(0L, m.firstAt)
        assertEquals(600 * min, m.lastAt)
    }

    @Test
    fun aSingleSampleHasNoCadence() {
        assertNull(ReferenceSurveyBuilder.medianInterval(listOf(42L)))
        assertNull(ReferenceSurveyBuilder.medianInterval(listOf(42L, 42L)))
    }

    @Test
    fun deliveryDelayIsLastModifiedMinusEnd_neverNegative() {
        val b = ReferenceSurveyBuilder(own)
        b.add(hr(sync, listOf(0), end = 0, modified = 30 * min))
        b.add(hr(sync, listOf(min), end = min, modified = min + 90 * min))
        b.add(hr(sync, listOf(2 * min), end = 2 * min, modified = 0)) // clock skew: clamp
        assertEquals(30 * min, b.build().single().metric(ReferenceMetric.HEART_RATE)!!.medianDeliveryDelayMs)
    }

    @Test
    fun sourcesAreOrderedByVolumeAndMetricsByDisplayOrder() {
        val b = ReferenceSurveyBuilder(own)
        b.add(SurveyRecord("small", ReferenceMetric.STEPS, listOf(0), min, min, null))
        b.add(SurveyRecord(sync, ReferenceMetric.SLEEP, listOf(0), min, min, null, sleepStageTypes = setOf(4, 5)))
        b.add(hr(sync, listOf(0, min, 2 * min)))
        val out = b.build()
        assertEquals(listOf(sync, "small"), out.map { it.packageName })
        assertEquals(listOf(ReferenceMetric.HEART_RATE, ReferenceMetric.SLEEP), out.first().metrics.map { it.metric })
        assertEquals(setOf(4, 5), out.first().metric(ReferenceMetric.SLEEP)!!.sleepStageTypes)
    }

    @Test
    fun devicesAreCollectedPerSource() {
        val b = ReferenceSurveyBuilder(own)
        val watch = DeviceInfo("HUAWEI", "WATCH GT 4", 1)
        b.add(hr(sync, listOf(0)).copy(device = watch))
        b.add(hr(sync, listOf(min)).copy(device = watch))
        assertEquals(setOf(watch), b.build().single().devices)
    }

    @Test
    fun anEchoOfTheRingsOwnHeartRateIsFlagged() {
        val times = (0 until 100).map { it * 5 * min }
        val ring = times.map { TimedValue(it + 400, 70.0) }
        val b = ReferenceSurveyBuilder(own)
        b.add(hr(sync, times, bpm = 70.0))
        val echo = b.build(ring).single().echo!!
        assertEquals(100, echo.matching)
        assertTrue(echo.looksLikeEcho)
    }

    @Test
    fun anIndependentDeviceIsNotAnEcho() {
        val times = (0 until 100).map { it * 5 * min }
        // Same instants, different readings — two real sensors.
        val ring = times.map { TimedValue(it, 64.0) }
        val b = ReferenceSurveyBuilder(own)
        b.add(hr(sync, times.map { it + 2_000 }, bpm = 64.0)) // and 2 s apart
        b.add(hr("other", times, bpm = 70.0))
        val bySource = b.build(ring).associateBy { it.packageName }
        assertFalse(bySource.getValue(sync).echo!!.looksLikeEcho)
        assertEquals(0, bySource.getValue("other").echo!!.matching)
    }

    @Test
    fun tooFewSamplesCanNeverLookLikeAnEcho() {
        assertFalse(EchoCheck(compared = 10, matching = 10).looksLikeEcho)
    }

    @Test
    fun noRingHeartRateMeansNoEchoCheck() {
        val b = ReferenceSurveyBuilder(own)
        b.add(hr(sync, listOf(0, min)))
        assertNull(b.build().single().echo)
    }

    @Test
    fun summaryTextHoldsNoReadings() {
        val b = ReferenceSurveyBuilder(own)
        b.add(hr(sync, listOf(0, 5 * min, 10 * min), bpm = 137.0).copy(device = DeviceInfo("HUAWEI", "WATCH GT 4", 1)))
        val text = ReferenceSurveyText.render(b.build(), 7, { ReferenceAppLabels.KNOWN[it] ?: it }, setOf(ReferenceMetric.HRV))
        assertTrue(text, text.contains("Health Sync (nl.appyhapps.healthsync)"))
        assertTrue(text, text.contains("HUAWEI WATCH GT 4 (watch)"))
        assertTrue(text, text.contains("every 5 min"))
        assertTrue(text, text.contains("Not readable (permission not granted): HRV"))
        assertFalse(text, text.contains("137"))
    }

    @Test
    fun readPermissionsAreTheirOwnSet() {
        assertEquals(ReferenceMetric.entries.size, AccuracyCheckPermissions.all.size)
        assertTrue(AccuracyCheckPermissions.all.all { it.startsWith("android.permission.health.READ_") })
        assertTrue(AccuracyCheckPermissions.all.none { it in HealthConnectBodyImport.permissions })
        // The export's reconcile must not count an accuracy-check grant as an export grant.
        assertTrue(HealthConnectPermissionReconcile.storedSetOf(AccuracyCheckPermissions.all).isEmpty())
        assertTrue(AccuracyCheckPermissions.all.none { it in HealthConnectPermissions.all })
        assertTrue("android.permission.health.READ_HEART_RATE_VARIABILITY" in AccuracyCheckPermissions.all)
    }
}
