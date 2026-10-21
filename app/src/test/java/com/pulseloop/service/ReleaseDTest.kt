package com.pulseloop.service

import com.pulseloop.data.entity.ActivityGpsPointEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import com.pulseloop.data.entity.MedDoseEntity
import com.pulseloop.data.entity.MedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Detected activity, GPX export, the weekly recap and medications. */
class ReleaseDTest {
    private val min = 60_000L
    private val base = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    // ── Detection ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two brisk quarter hours are a walk, faster ones a run, one alone is nothing`() {
        val walk = ActivityDetection.stepSessions(listOf(base to 1500, base + 15 * min to 1400, base + 30 * min to 300))
        assertEquals(1, walk.size)
        assertEquals(ActivityDetection.Proposal.Kind.WALK, walk[0].kind)
        assertEquals(30, walk[0].minutes)
        val run = ActivityDetection.stepSessions(listOf(base to 2200, base + 15 * min to 2300))
        assertEquals(ActivityDetection.Proposal.Kind.RUN, run.single().kind)
        assertTrue(ActivityDetection.stepSessions(listOf(base to 1500, base + 45 * min to 1500)).isEmpty())
    }

    @Test
    fun `twenty minutes well above the quiet level is cardio`() {
        val hr = (0..5).map { base + it * 5 * min to 130.0 }
        val p = ActivityDetection.heartSessions(hr, quiet = 60.0, hrMax = 180.0)
        assertEquals(ActivityDetection.Proposal.Kind.CARDIO, p.single().kind)
        assertTrue(ActivityDetection.heartSessions(hr.take(3), 60.0, 180.0).isEmpty())
    }

    @Test
    fun `a still afternoon at the quiet level is a possible nap`() {
        val afternoon = base + 13 * 60 * min
        val hr = (0..6).map { afternoon + it * 5 * min to 55.0 }
        val p = ActivityDetection.naps(hr, emptyList(), quiet = 60.0, zone = ZoneOffset.UTC)
        assertEquals(ActivityDetection.Proposal.Kind.NAP, p.single().kind)
        assertTrue(ActivityDetection.naps(hr, listOf(afternoon + 10 * min to 400), 60.0, ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun `recorded or answered stretches are not proposed again`() {
        val p = ActivityDetection.Proposal(ActivityDetection.Proposal.Kind.WALK, base, base + 30 * min, "")
        assertTrue(ActivityDetection.open(listOf(p), listOf(base + 10 * min..base + 20 * min), emptySet()).isEmpty())
        assertTrue(ActivityDetection.open(listOf(p), emptyList(), setOf(p.key)).isEmpty())
        assertEquals(1, ActivityDetection.open(listOf(p), emptyList(), emptySet()).size)
    }

    // ── GPX ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `gpx carries accepted points in order with heart rate`() {
        val s = ActivitySessionEntity(id = "s", type = "run", statusRaw = "finished", startedAt = base, endedAt = base + 10 * min)
        val pts = listOf(
            ActivityGpsPointEntity(sessionId = "s", latitude = -23.5, longitude = -46.6, timestamp = base + 60_000, altitude = 760.0),
            ActivityGpsPointEntity(sessionId = "s", latitude = -23.6, longitude = -46.7, timestamp = base),
            ActivityGpsPointEntity(sessionId = "s", latitude = 0.0, longitude = 0.0, timestamp = base + 30_000, accepted = false),
        )
        val gpx = GpxExport.build(s, pts, "Running & more", listOf(base to 150.0))!!
        assertEquals(2, Regex("<trkpt").findAll(gpx).count())
        assertTrue(gpx.indexOf("-23.6000000") < gpx.indexOf("-23.5000000"))
        assertTrue(gpx.contains("<gpxtpx:hr>150</gpxtpx:hr>"))
        assertTrue(gpx.contains("Running &amp; more"))
        assertTrue(gpx.contains("<ele>760.0</ele>"))
        assertNull(GpxExport.build(s, emptyList(), "x"))
    }

    // ── Weekly recap ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recap rows compare with last week`() {
        val rows = WeeklyRecap.rows(
            WeeklyReport.Week(avgAsleepMinutes = 420, avgRestingHr = 56.0, avgHrv = 44.0, avgSteps = 8800, nights = 7),
            WeeklyReport.Week(avgAsleepMinutes = 390, avgRestingHr = 58.0, avgHrv = 40.0, avgSteps = 8000, nights = 7),
            workouts = 3,
        )
        assertEquals(listOf("Sleep a night", "Steps a day", "Resting heart rate", "HRV", "Workouts"), rows.map { it.label })
        assertEquals("+10%", rows[1].change)
        assertEquals("−2 bpm", rows[2].change)
        assertEquals("+10%", rows[3].change)
        assertNull(rows[4].change)
    }

    // ── Medications ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `only an unanswered past dose is missed`() {
        val day = LocalDate.of(2026, 10, 20)
        val m = MedEntity(id = "m", name = "Vitamin D", timesCsv = "08:00,20:00", createdAt = 0)
        val noon = day.atTime(12, 0)
        val doses = Meds.dosesOn(day, listOf(m), emptyList(), noon)
        assertEquals(listOf(Meds.State.MISSED, Meds.State.LATER), doses.map { it.state })
        val skipped = MedDoseEntity(MedDoseEntity.idFor("m", day.toEpochDay(), "08:00"), "m", day.toEpochDay(), "08:00", "skipped")
        assertEquals(Meds.State.SKIPPED, Meds.dosesOn(day, listOf(m), listOf(skipped), noon).first().state)
        assertEquals(Meds.State.DUE, Meds.dosesOn(day, listOf(m), emptyList(), day.atTime(19, 30)).last().state)
    }

    @Test
    fun `adherence counts taken doses over scheduled ones`() {
        val today = LocalDate.of(2026, 10, 20)
        val m = MedEntity(id = "m", name = "Iron", timesCsv = "08:00", createdAt = 0)
        val taken = (1..5).map { back ->
            val d = today.minusDays(back.toLong()).toEpochDay()
            MedDoseEntity(MedDoseEntity.idFor("m", d, "08:00"), "m", d, "08:00", "taken")
        }
        assertEquals(71, Meds.adherence(m, taken, today)) // 5 of 7
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0).year, 2026)
    }
}
