package com.pulseloop.health.accuracy

import com.pulseloop.data.entity.AccuracyCheckDayEntity
import com.pulseloop.health.accuracy.AccuracyComparison.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class AccuracyComparisonTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val min = 60_000L
    private val h = 60 * min
    private val day0 = LocalDate.of(2026, 10, 1).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun daily(metric: ComparisonMetric, day: Long, ring: Double, ref: Double, valid: Boolean = true) =
        AccuracyComparison.dailyRow("c", metric, day, ring, ref, valid, null, 0)

    // ── pairing ──

    @Test
    fun eachReferenceSampleIsUsedOnceAndOnlyWithinTolerance() {
        val ring = listOf(TimedValue(0, 60.0), TimedValue(10_000, 62.0), TimedValue(200_000, 70.0))
        val ref = listOf(TimedValue(5_000, 58.0), TimedValue(400_000, 90.0))
        val pairs = AccuracyComparison.pair(ring, ref, 60_000, utc) { Unit }
        val sums = pairs.values.single()
        // Only the first ring sample gets the 5 s reference sample; the second finds it used, the
        // third has nothing within a minute.
        assertEquals(1, sums.n)
        assertEquals(2.0, sums.sumDiff, 1e-9)
    }

    @Test
    fun pairsSplitByDayAndContext_andANullContextIsSkipped() {
        val ring = listOf(TimedValue(day0 + h, 50.0), TimedValue(day0 + 2 * h, 100.0), TimedValue(day0 + 25 * h, 52.0), TimedValue(day0 + 3 * h, 80.0))
            .sortedBy { it.at }
        val ref = ring.map { TimedValue(it.at + 1_000, it.value - 2) }
        val pairs = AccuracyComparison.pair(ring, ref, 60_000, utc) { t ->
            when (t) {
                day0 + 3 * h -> null
                day0 + 2 * h -> "active"
                else -> "rest"
            }
        }
        val d0 = LocalDate.of(2026, 10, 1).toEpochDay()
        assertEquals(1, pairs[d0 to "rest"]!!.n)
        assertEquals(1, pairs[d0 to "active"]!!.n)
        assertEquals(1, pairs[d0 + 1 to "rest"]!!.n)
        assertEquals(3, pairs.values.sumOf { it.n })
    }

    @Test
    fun aSparseReferenceWidensThePairingWindowUpToItsCap() {
        assertEquals(60_000L, AccuracyComparison.tolerance(null, 60_000, 150_000))
        assertEquals(60_000L, AccuracyComparison.tolerance(30_000, 60_000, 150_000))
        assertEquals(120_000L, AccuracyComparison.tolerance(240_000, 60_000, 150_000))
        assertEquals(150_000L, AccuracyComparison.tolerance(600_000, 60_000, 150_000))
    }

    @Test
    fun pairSumsCombineExactly() {
        val a = PairSums() + 1.0 + 3.0
        val b = PairSums() + -2.0
        val all = a + b
        assertEquals(3, all.n)
        assertEquals(2.0 / 3, all.mean, 1e-9)
        assertEquals(2.0, all.mae, 1e-9)
    }

    // ── reference totals ──

    @Test
    fun anIntervalCrossingMidnightIsSplitByOverlap() {
        val totals = AccuracyComparison.dailyTotals(
            listOf(AccuracyComparison.IntervalAmount(day0 + 23 * h, day0 + 25 * h, 200.0)), utc,
        )
        val d0 = LocalDate.of(2026, 10, 1).toEpochDay()
        assertEquals(100.0, totals[d0]!!, 1e-9)
        assertEquals(100.0, totals[d0 + 1]!!, 1e-9)
    }

    // ── sleep ──

    @Test
    fun aNightWithoutStagesCountsItsWholeSpanAsAsleep() {
        val n = SleepNight(0, 8 * h, emptyList())
        assertEquals(480, n.asleepMinutes)
        assertEquals(0L, n.onset)
        assertEquals(8 * h, n.wake)
        assertFalse(n.hasStages)
    }

    @Test
    fun onsetAndWakeIgnoreAwakeAtTheEdges() {
        val n = SleepNight(0, 8 * h, listOf(
            StageSpan(0, 20 * min, Stage.AWAKE),
            StageSpan(20 * min, 7 * h, Stage.LIGHT),
            StageSpan(7 * h, 8 * h, Stage.AWAKE),
        ))
        assertEquals(20 * min, n.onset)
        assertEquals(7 * h, n.wake)
        assertEquals(400, n.asleepMinutes)
    }

    @Test
    fun stageAgreementCountsOnlyMinutesBothSidesStage() {
        val ring = SleepNight(0, 4 * h, listOf(StageSpan(0, 2 * h, Stage.LIGHT), StageSpan(2 * h, 4 * h, Stage.DEEP)))
        val ref = SleepNight(h, 5 * h, listOf(
            StageSpan(h, 3 * h, Stage.LIGHT),
            StageSpan(3 * h, 3 * h + 30 * min, Stage.ASLEEP), // unstaged: neither agrees nor disagrees
            StageSpan(3 * h + 30 * min, 5 * h, Stage.DEEP),
        ))
        val a = AccuracyComparison.stageAgreement(ring, ref)!!
        // Overlap 1h–4h is 180 min, minus 30 unstaged.
        assertEquals(150, a.overlapMinutes)
        // light/light 60 (1h–2h) + deep/deep 30 (3h30–4h); deep/light 60 (2h–3h).
        assertEquals(90, a.agreeMinutes)
        assertEquals(60, a.confusion[Stage.DEEP to Stage.LIGHT])
        assertEquals(a.confusion, AccuracyComparison.StageAgreement.decode(a.encode()))
    }

    @Test
    fun noStagesOnEitherSideMeansNoStageAgreement() {
        val staged = SleepNight(0, h, listOf(StageSpan(0, h, Stage.LIGHT)))
        assertNull(AccuracyComparison.stageAgreement(staged, SleepNight(0, h, emptyList())))
    }

    // ── verdicts ──

    @Test
    fun aSteadyUndercountIsConsistentlyLowWithAScaleSuggestion() {
        val days = (0 until 6).map { daily(ComparisonMetric.STEPS, it.toLong(), 8_000.0 + it * 100, (8_000.0 + it * 100) / 0.8) }
        val s = AccuracyComparison.summarize(ComparisonMetric.STEPS, days)
        assertEquals(Verdict.CONSISTENTLY_LOW, s.verdict)
        assertEquals(-20.0, s.bias, 0.01)
        assertEquals(1.25, s.suggestedCorrection!!, 0.001)
        assertEquals(1.0, s.sameSignShare, 1e-9)
    }

    @Test
    fun anErraticRingIsInconsistentAndGetsNoCorrection() {
        val ring = listOf(10_000.0, 6_000.0, 12_500.0, 7_000.0, 11_000.0, 6_500.0)
        val days = ring.mapIndexed { i, r -> daily(ComparisonMetric.STEPS, i.toLong(), r, 9_000.0) }
        val s = AccuracyComparison.summarize(ComparisonMetric.STEPS, days)
        assertEquals(Verdict.INCONSISTENT, s.verdict)
        assertNull(s.suggestedCorrection)
    }

    @Test
    fun aSmallDifferenceAgrees() {
        val days = (0 until 4).map { daily(ComparisonMetric.STEPS, it.toLong(), 9_300.0, 9_000.0) }
        assertEquals(Verdict.AGREES, AccuracyComparison.summarize(ComparisonMetric.STEPS, days).verdict)
    }

    @Test
    fun tooFewValidDaysIsNotEnoughData_andInvalidDaysDontCount() {
        val days = listOf(
            daily(ComparisonMetric.STEPS, 0, 5_000.0, 9_000.0),
            daily(ComparisonMetric.STEPS, 1, 5_000.0, 9_000.0),
            daily(ComparisonMetric.STEPS, 2, 100.0, 9_000.0, valid = false),
        )
        val s = AccuracyComparison.summarize(ComparisonMetric.STEPS, days)
        assertEquals(Verdict.NOT_ENOUGH_DATA, s.verdict)
        assertEquals(2, s.validDays)
    }

    @Test
    fun pairedHeartRateWithASteadyOffsetGetsAnOffsetSuggestion() {
        val days = (0 until 4).map { d ->
            val sums = (0 until 200).fold(PairSums()) { acc, i -> acc + (6.0 + (i % 5 - 2) * 0.5) }
            AccuracyComparison.pairedRow("c", ComparisonMetric.HR_REST, d.toLong(), sums, true, 0)
        }
        val s = AccuracyComparison.summarize(ComparisonMetric.HR_REST, days)
        assertEquals(Verdict.CONSISTENTLY_HIGH, s.verdict)
        assertEquals(800, s.pairs)
        assertEquals(-6.0, s.suggestedCorrection!!, 0.01)
    }

    @Test
    fun pairedButTooFewReadingsIsNotEnough() {
        val days = (0 until 4).map { AccuracyComparison.pairedRow("c", ComparisonMetric.HR_REST, it.toLong(), PairSums() + 8.0, true, 0) }
        assertEquals(Verdict.NOT_ENOUGH_DATA, AccuracyComparison.summarize(ComparisonMetric.HR_REST, days).verdict)
    }

    @Test
    fun aWiderBandTurnsABiasIntoAgreement() {
        val days = (0 until 4).map { daily(ComparisonMetric.SLEEP_ASLEEP, it.toLong(), 420.0, 380.0) }
        assertEquals(Verdict.CONSISTENTLY_HIGH, AccuracyComparison.summarize(ComparisonMetric.SLEEP_ASLEEP, days).verdict)
        assertEquals(Verdict.AGREES, AccuracyComparison.summarize(ComparisonMetric.SLEEP_ASLEEP, days, band = 45.0).verdict)
    }

    @Test
    fun reportOnlyMetricsNeverSuggestACorrection() {
        val days = (0 until 5).map { daily(ComparisonMetric.HRV, it.toLong(), 60.0, 30.0) }
        val s = AccuracyComparison.summarize(ComparisonMetric.HRV, days)
        assertEquals(Verdict.CONSISTENTLY_HIGH, s.verdict)
        assertNull(s.suggestedCorrection)
    }

    @Test
    fun stageAgreementSummarizesMinutes() {
        val days = (0 until 3).map {
            AccuracyCheckDayEntity("c", ComparisonMetric.SLEEP_STAGES.name, it.toLong(), pairs = 400, inBand = 300, computedAt = 0)
        }
        assertEquals(0.75, AccuracyComparison.summarize(ComparisonMetric.SLEEP_STAGES, days).stageAgreement!!, 1e-9)
    }

    @Test
    fun aPercentDiffAgainstAZeroReferenceIsNoDiff() {
        assertNull(AccuracyComparison.diff(ComparisonMetric.STEPS, 100.0, 0.0))
        assertFalse(daily(ComparisonMetric.STEPS, 0, 100.0, 0.0).valid)
    }

    @Test
    fun bandsRoundTripAndDefaultsAreNotStored() {
        val raw = AccuracyComparison.encodeBands(mapOf(ComparisonMetric.STEPS to 12.0, ComparisonMetric.HR_REST to ComparisonMetric.HR_REST.band))
        assertEquals("STEPS=12.0", raw)
        assertEquals(mapOf(ComparisonMetric.STEPS to 12.0), AccuracyComparison.parseBands(raw))
        assertNull(AccuracyComparison.encodeBands(emptyMap()))
    }

    // ── scorer helpers ──

    @Test
    fun onlyWholeDaysInsideTheWindowAreDailyDays() {
        val start = day0 + 14 * h
        val end = day0 + 3 * 24 * h + 9 * h
        val d0 = LocalDate.of(2026, 10, 1).toEpochDay()
        assertEquals(listOf(d0 + 1, d0 + 2), AccuracyScorer.fullDaysIn(start, end, utc))
        assertEquals(listOf(d0), AccuracyScorer.fullDaysIn(day0, day0 + 24 * h, utc))
    }

    // ── report text ──

    @Test
    fun theReadingSaysWhichWayAndHowSteady() {
        val days = (0 until 6).map { daily(ComparisonMetric.STEPS, it.toLong(), 8_200.0, 10_000.0) }
        val s = AccuracyComparison.summarize(ComparisonMetric.STEPS, days)
        val text = AccuracyReportText.reading(s, "Health Sync")
        assertTrue(text, text.contains("18 % fewer steps than Health Sync"))
        assertTrue(text, text.contains("on 6 of 6 days"))
        assertTrue(text, text.contains("A correction would help"))
    }

    @Test
    fun anInconsistentReadingRefusesACorrection() {
        val ring = listOf(10_000.0, 6_000.0, 12_500.0, 7_000.0, 11_000.0, 6_500.0)
        val s = AccuracyComparison.summarize(ComparisonMetric.STEPS, ring.mapIndexed { i, r -> daily(ComparisonMetric.STEPS, i.toLong(), r, 9_000.0) })
        assertTrue(AccuracyReportText.reading(s, "Fitbit").contains("A single correction can't fix that"))
    }

    @Test
    fun worstFirstOrdersByDistanceOutsideTheBand() {
        val steps = AccuracyComparison.summarize(ComparisonMetric.STEPS, (0 until 4).map { daily(ComparisonMetric.STEPS, it.toLong(), 7_000.0, 10_000.0) })
        val sleep = AccuracyComparison.summarize(ComparisonMetric.SLEEP_ASLEEP, (0 until 4).map { daily(ComparisonMetric.SLEEP_ASLEEP, it.toLong(), 440.0, 400.0) })
        val ok = AccuracyComparison.summarize(ComparisonMetric.DISTANCE, (0 until 4).map { daily(ComparisonMetric.DISTANCE, it.toLong(), 5_000.0, 5_100.0) })
        assertEquals(listOf(ComparisonMetric.STEPS, ComparisonMetric.SLEEP_ASLEEP), AccuracyReportText.worstFirst(listOf(sleep, ok, steps)).map { it.metric })
    }

    @Test
    fun clockTimesWrapAcrossMidnight() {
        assertEquals("23:30", AccuracyReportText.clock(-30.0))
        assertEquals("07:05", AccuracyReportText.clock(425.0))
    }

    @Test
    fun theShareableSummaryHoldsVerdictsButNoDayValues() {
        val steps = AccuracyComparison.summarize(ComparisonMetric.STEPS, (0 until 4).map { daily(ComparisonMetric.STEPS, it.toLong(), 7_123.0, 8_765.0) })
        val text = AccuracyReportText.share("SR08", "jring-sr08", "V138", "Health Sync (Huawei)", "21 Oct – 28 Oct", listOf(steps))
        assertTrue(text, text.contains("SR08 vs Health Sync (Huawei)"))
        assertTrue(text, text.contains("firmware V138"))
        assertTrue(text, text.contains("Steps: −19 % on average, consistently low (4 days)"))
        assertFalse(text, text.contains("7123") || text.contains("7,123") || text.contains("8765") || text.contains("8,765"))
    }

    // ── wear coverage ──

    @Test
    fun aDeviceThatStopsAtThreeIsOffFromThenToTheEndOfTheDay() {
        // Heart rate every 5 minutes from midnight until 15:00, then nothing.
        val hr = (0 until 15 * 12).map { day0 + it * 5 * min }
        val gaps = AccuracyComparison.offGaps(hr, day0, day0 + 24 * h)
        assertEquals(1, gaps.size)
        assertEquals(day0 + 15 * h - 5 * min, gaps[0].first)
        assertEquals(day0 + 24 * h, gaps[0].last)
    }

    @Test
    fun anHourlyMonitorIsQuietNotOff() {
        val hr = (0..24).map { day0 + it * h }
        assertTrue(AccuracyComparison.offGaps(hr, day0, day0 + 24 * h).isEmpty())
        assertTrue(AccuracyComparison.judgeable(hr))
    }

    @Test
    fun sparseOrMissingHeartRateCannotBeJudged() {
        assertFalse(AccuracyComparison.judgeable(emptyList()))
        // Every three hours: too sparse to tell a flat battery from a quiet afternoon.
        assertFalse(AccuracyComparison.judgeable((0..20).map { day0 + it * 3 * h }))
    }

    @Test
    fun totalsAreCutToTheTimeBothDevicesWereOn() {
        // A watch that counted 600 steps an hour all day; the ring was off from 15:00.
        val items = (0 until 24).map { AccuracyComparison.IntervalAmount(day0 + it * h, day0 + (it + 1) * h, 600.0) }
        val off = listOf((day0 + 15 * h)..(day0 + 24 * h))
        assertEquals(15 * 600.0, AccuracyComparison.amountWithin(items, day0, day0 + 24 * h, off), 1e-6)
        // Half an interval inside a gap counts half.
        val half = listOf((day0 + 30 * min)..(day0 + h))
        assertEquals(300.0, AccuracyComparison.amountWithin(items.take(1), day0, day0 + 24 * h, half), 1e-6)
    }

    @Test
    fun overlappingGapsFromBothDevicesAreCountedOnce() {
        val gaps = listOf((day0 + h)..(day0 + 3 * h), (day0 + 2 * h)..(day0 + 4 * h))
        assertEquals(3 * h, AccuracyComparison.gapMs(gaps, day0, day0 + 24 * h))
        assertEquals(listOf((day0 + h)..(day0 + 4 * h)), AccuracyComparison.merge(gaps))
    }
}
