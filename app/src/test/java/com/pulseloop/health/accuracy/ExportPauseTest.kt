package com.pulseloop.health.accuracy

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import com.pulseloop.data.entity.AccuracyCheckEntity
import com.pulseloop.data.entity.AccuracyPausedUnitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ExportPauseTest {

    private val h = 3_600_000L
    private val meta = Metadata.autoRecorded(Device(type = Device.TYPE_RING, manufacturer = "t", model = "t"))

    private fun hr(start: Long, end: Long = start + h) = HeartRateRecord(
        startTime = Instant.ofEpochMilli(start), startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochMilli(end), endZoneOffset = ZoneOffset.UTC,
        samples = listOf(HeartRateRecord.Sample(Instant.ofEpochMilli(start), 60)), metadata = meta,
    )

    private fun steps(start: Long, end: Long) = StepsRecord(
        startTime = Instant.ofEpochMilli(start), startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochMilli(end), endZoneOffset = ZoneOffset.UTC, count = 100, metadata = meta,
    )

    private fun spo2(at: Long) = OxygenSaturationRecord(
        time = Instant.ofEpochMilli(at), zoneOffset = ZoneOffset.UTC, percentage = Percentage(97.0), metadata = meta,
    )

    private fun workout(start: Long) = ExerciseSessionRecord(
        startTime = Instant.ofEpochMilli(start), startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochMilli(start + h), endZoneOffset = ZoneOffset.UTC,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING, metadata = meta,
    )

    private fun check(state: String, resume: String? = null, endedAt: Long? = null, id: String = "c") = AccuracyCheckEntity(
        id = id, deviceKey = "AA", deviceLabel = "R11", referencePackage = "nl.appyhapps.healthsync",
        referenceLabel = "Health Sync", startedAt = 10 * h, plannedEndAt = 100 * h, endedAt = endedAt,
        state = state, resumeChoice = resume,
    )

    private fun paused(unit: ExportUnit, from: Long = 10 * h, id: String = "c") = AccuracyPausedUnitEntity(id, unit.name, from)

    @Test
    fun anOpenCheckHoldsBackFromItsStartWithNoEnd() {
        val ex = ExportPause.exclusions(listOf(check(AccuracyCheckEntity.STATE_RUNNING)), listOf(paused(ExportUnit.HEART_RATE)))
        assertEquals(listOf(ExportExclusion(ExportUnit.HEART_RATE, 10 * h, null)), ex)
        assertEquals(setOf(ExportUnit.HEART_RATE), ExportPause.pausedNow(ex))
        // Awaiting the resume choice still holds back: nothing is decided yet.
        val waiting = ExportPause.exclusions(listOf(check(AccuracyCheckEntity.STATE_AWAITING_RESUME, endedAt = 50 * h)), listOf(paused(ExportUnit.HEART_RATE)))
        assertNull(waiting.single().until)
    }

    @Test
    fun skipClosesTheWindowAtTheEndAndExportRemovesIt() {
        val skip = ExportPause.exclusions(
            listOf(check(AccuracyCheckEntity.STATE_DONE, AccuracyCheckEntity.RESUME_SKIP, 50 * h)),
            listOf(paused(ExportUnit.STEPS)),
        )
        assertEquals(listOf(ExportExclusion(ExportUnit.STEPS, 10 * h, 50 * h)), skip)
        assertTrue(ExportPause.pausedNow(skip).isEmpty())
        val export = ExportPause.exclusions(
            listOf(check(AccuracyCheckEntity.STATE_DONE, AccuracyCheckEntity.RESUME_EXPORT, 50 * h)),
            listOf(paused(ExportUnit.STEPS)),
        )
        assertTrue(export.isEmpty())
    }

    @Test
    fun aUnitThatJoinedLateIsHeldBackOnlyFromWhenItJoined() {
        val ex = ExportPause.exclusions(
            listOf(check(AccuracyCheckEntity.STATE_RUNNING)),
            listOf(paused(ExportUnit.HEART_RATE), paused(ExportUnit.OXYGEN_SATURATION, from = 30 * h)),
        )
        val f = ExportPause.filter(listOf(spo2(20 * h), spo2(31 * h)), listOf(1, 2), ex)
        assertEquals(1, f.records.size)
        assertEquals(20 * h, (f.records.single() as OxygenSaturationRecord).time.toEpochMilli())
    }

    @Test
    fun onlyThePausedTypeIsHeldBackAndTheWatermarkCanStillPassIt() {
        val ex = listOf(ExportExclusion(ExportUnit.HEART_RATE, 10 * h, null))
        val records: List<Record> = listOf(hr(5 * h), hr(12 * h), spo2(12 * h), hr(13 * h), workout(12 * h))
        val f = ExportPause.filter(records, listOf(1, 2, 3, 4, 5), ex)
        assertEquals(3, f.records.size)
        assertEquals(listOf(1L, 3L, 5L), f.highWaters)
        assertEquals(2, f.heldBack)
        assertEquals(4L, f.heldBackHighWater)
        // The workout session itself is never held back.
        assertTrue(f.records.any { it is ExerciseSessionRecord })
    }

    @Test
    fun aRecordOverlappingTheWindowEdgeIsHeldBack() {
        val ex = listOf(ExportExclusion(ExportUnit.STEPS, 10 * h, 50 * h))
        // The whole day the check started on: its total includes in-window steps.
        assertTrue(ExportPause.isHeldBack(steps(0, 24 * h), ex))
        // The day it ended on, likewise.
        assertTrue(ExportPause.isHeldBack(steps(48 * h, 72 * h), ex))
        // Entirely before, entirely after.
        assertFalse(ExportPause.isHeldBack(steps(0, 9 * h), ex))
        assertFalse(ExportPause.isHeldBack(steps(50 * h, 60 * h), ex))
    }

    @Test
    fun noExclusionsIsAPassThrough() {
        val records: List<Record> = listOf(hr(0), spo2(0))
        val f = ExportPause.filter(records, listOf(7, 8), emptyList())
        assertEquals(records, f.records)
        assertNull(f.heldBackHighWater)
    }

    @Test
    fun everyExportUnitMapsBackToItsRecordTypeAndMetric() {
        ReferenceMetric.entries.forEach { assertEquals(it, ExportUnit.of(it).metric) }
        assertEquals(ExportUnit.STEPS, ExportUnit.ofRecord(steps(0, h)))
        assertNull(ExportUnit.ofRecord(workout(0)))
    }
}
