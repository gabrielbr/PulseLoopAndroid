package com.pulseloop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Oura-style readiness: spike filter, nightly vitals, contributors and the learning state. */
class ReadinessTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 10, 18)

    /** A night from 23:30 the evening before [day] to 07:30, asleep [asleep] minutes. */
    private fun night(day: LocalDate, rhr: Double? = 55.0, hrv: Double? = 40.0, asleep: Int = 450, coverage: Double = 1.0): NightSummary {
        val end = day.atTime(7, 30).atZone(zone).toInstant().toEpochMilli()
        val start = end - 8 * 3600_000L
        return NightSummary(day, start, end, asleep, rhr, hrv, coverage)
    }

    private fun history(n: Int, rhr: Double = 55.0, hrv: Double = 40.0, asleep: Int = 450) =
        (1..n).map { night(today.minusDays(it.toLong()), rhr, hrv, asleep) }

    private fun inputs(
        last: NightSummary? = night(today),
        hist: List<NightSummary> = history(14),
        steps: List<Int> = List(28) { 8000 },
    ) = Readiness.Inputs(last, hist, sleepGoalMinutes = 450, dailySteps = steps, zone = zone)

    // ── ReadingQuality ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an isolated spike is dropped and its neighbours survive`() {
        val values = listOf(55.0, 56.0, 54.0, 140.0, 55.0, 57.0, 56.0)
        val samples = values.mapIndexed { i, v -> VitalSample(i * 300_000L, v) }
        val kept = ReadingQuality.dropSpikes(samples).map { it.value }
        assertEquals(listOf(55.0, 56.0, 54.0, 55.0, 57.0, 56.0), kept)
    }

    @Test
    fun `a genuine sustained change is not treated as a spike`() {
        val values = listOf(55.0, 56.0, 55.0, 70.0, 72.0, 71.0, 73.0, 72.0)
        val samples = values.mapIndexed { i, v -> VitalSample(i * 300_000L, v) }
        assertEquals(values.size, ReadingQuality.dropSpikes(samples).size)
    }

    // ── NightlyVitals ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `resting HR is the lowest sustained value inside the night, not one dropped sample`() {
        val start = 0L
        val end = 8 * 3600_000L
        val hr = listOf(62.0, 58.0, 54.0, 53.0, 55.0, 52.0, 56.0, 60.0)
            .mapIndexed { i, v -> VitalSample(start + i * 3600_000L, v) } +
            VitalSample(end + 3600_000L, 40.0) // after waking: must not count
        val n = NightlyVitals.summarize(today, start, end, 450, hr, emptyList())
        assertEquals(53.3, n.restingHr!!, 0.05)
        assertNull("no HRV samples → no HRV", n.hrv)
    }

    @Test
    fun `a night with too few heart-rate samples has no resting HR and low coverage`() {
        val hr = listOf(VitalSample(0L, 55.0), VitalSample(600_000L, 54.0))
        val n = NightlyVitals.summarize(today, 0L, 8 * 3600_000L, 450, hr, emptyList())
        assertNull(n.restingHr)
        assertTrue(n.coverage < 0.1)
    }

    // ── Readiness ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no night recorded is NoSleep, not a score`() {
        assertEquals(Readiness.Result.NoSleep, Readiness.compute(inputs(last = null)))
    }

    @Test
    fun `fewer than a week of nights is Learning with progress`() {
        val r = Readiness.compute(inputs(hist = history(4)))
        assertEquals(Readiness.Result.Learning(4, Readiness.MIN_BASELINE_NIGHTS), r)
    }

    @Test
    fun `nights without any vitals do not count toward the baseline`() {
        val r = Readiness.compute(inputs(hist = history(14).map { it.copy(restingHr = null, hrv = null) }))
        assertTrue(r is Readiness.Result.Learning)
    }

    @Test
    fun `a usual night scores optimal and is marked derived`() {
        val r = Readiness.compute(inputs()) as Readiness.Result.Score
        assertTrue("got ${r.value}", r.value >= 85)
        assertEquals(Readiness.Band.OPTIMAL, r.band)
        assertTrue(r.derived)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `an elevated resting HR and suppressed HRV pull the score down`() {
        val r = Readiness.compute(inputs(last = night(today, rhr = 62.0, hrv = 28.0))) as Readiness.Result.Score
        val usual = (Readiness.compute(inputs()) as Readiness.Result.Score).value
        assertTrue("got ${r.value} vs usual $usual", r.value < usual - 10)
        val rhr = r.contributors.first { it.kind == Readiness.ContributorKind.RESTING_HR }
        assertEquals("7 bpm above your usual 55", rhr.detail)
        assertTrue(rhr.band == Readiness.Band.PAY_ATTENTION)
    }

    @Test
    fun `contributors that cannot be scored are reported missing and the rest are renormalised`() {
        val r = Readiness.compute(inputs(steps = emptyList())) as Readiness.Result.Score
        assertTrue(Readiness.ContributorKind.PREVIOUS_DAY_ACTIVITY in r.missing)
        assertTrue(Readiness.ContributorKind.ACTIVITY_BALANCE in r.missing)
        assertTrue("still optimal on the remaining contributors", r.value >= 85)
    }

    @Test
    fun `a night without HR or HRV is withheld rather than a sleep-only readiness`() {
        // The baseline exists, so this isn't "learning": last night's readings were too poor.
        val r = Readiness.compute(inputs(last = night(today, rhr = null, hrv = null)))
        assertTrue(r is Readiness.Result.Withheld)
        assertTrue((r as Readiness.Result.Withheld).reasons.isNotEmpty())
    }

    @Test
    fun `hrv that is too sparse, flat or scattered is withheld with a reason`() {
        assertTrue(hrvQuality(listOf(40.0, 42.0))!!.startsWith("Only 2 HRV readings"))
        assertTrue(hrvQuality(listOf(40.0, 40.0, 40.0, 40.0))!!.contains("same value"))
        assertTrue(hrvQuality(listOf(10.0, 90.0, 15.0, 80.0, 40.0))!!.contains("scattered"))
        assertNull(hrvQuality(listOf(38.0, 42.0, 40.0, 45.0, 36.0)))
        assertNull(hrvQuality(emptyList()))
    }

    @Test
    fun `short sleep lowers the sleep contributor`() {
        val c = Readiness.sleep(night(today, asleep = 225), goalMinutes = 450)
        assertNotNull(c)
        assertTrue("half the goal scores ${c!!.score}", c.score in 25..35)
    }

    @Test
    fun `both a sedentary and a very heavy day cost previous-day activity points`() {
        val sedentary = Readiness.previousDayActivity(listOf(1000) + List(27) { 8000 })!!.score
        val heavy = Readiness.previousDayActivity(listOf(24000) + List(27) { 8000 })!!.score
        val usual = Readiness.previousDayActivity(List(28) { 8000 })!!.score
        assertEquals(100, usual)
        assertTrue(sedentary < 70)
        assertTrue(heavy < 80)
    }

    @Test
    fun `irregular bedtimes lower sleep regularity`() {
        val regular = Readiness.sleepRegularity(night(today), history(13), zone)!!.score
        val shifted = history(13).mapIndexed { i, n ->
            val shift = if (i % 2 == 0) 2 * 3600_000L else -2 * 3600_000L
            n.copy(startAt = n.startAt + shift, endAt = n.endAt + shift)
        }
        val irregular = Readiness.sleepRegularity(night(today), shifted, zone)!!.score
        assertEquals(100, regular)
        assertTrue("got $irregular", irregular < 50)
    }

    @Test
    fun `a low score names the weakest contributor and suggests rest`() {
        val r = Readiness.compute(inputs(last = night(today, rhr = 66.0, hrv = 20.0, asleep = 150))) as Readiness.Result.Score
        assertEquals(Readiness.Band.PAY_ATTENTION, r.band)
        val text = ReadinessCopy.headline(r)
        assertTrue(text, text.startsWith("Your body is asking for rest"))
        val weakest = r.contributors.minBy { it.score }.kind.title.lowercase()
        assertTrue(text, text.contains("your $weakest is furthest from your usual"))
    }

    @Test
    fun `learning copy shows progress and low coverage gets a caution`() {
        assertEquals(
            "Learning your baseline: 4 of 7 nights. Keep wearing your ring to bed.",
            ReadinessCopy.headline(Readiness.Result.Learning(4, 7)),
        )
        val full = Readiness.compute(inputs()) as Readiness.Result.Score
        assertNull(ReadinessCopy.coverageNote(full))
        val thin = Readiness.compute(inputs(last = night(today, coverage = 0.4))) as Readiness.Result.Score
        assertEquals(
            "Your ring captured only 40% of last night, so today's score is less certain.",
            ReadinessCopy.coverageNote(thin),
        )
    }

    @Test
    fun `bands follow Oura's published cut-offs`() {
        assertEquals(Readiness.Band.OPTIMAL, Readiness.Band.of(85))
        assertEquals(Readiness.Band.GOOD, Readiness.Band.of(84))
        assertEquals(Readiness.Band.GOOD, Readiness.Band.of(70))
        assertEquals(Readiness.Band.FAIR, Readiness.Band.of(60))
        assertEquals(Readiness.Band.PAY_ATTENTION, Readiness.Band.of(59))
    }
}
