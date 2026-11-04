package com.pulseloop.service

import com.pulseloop.service.ActivityClassifier.Example
import com.pulseloop.service.ActivityClassifier.Features
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guessing what a stretch of movement was, from cadence and heart-rate shape. */
class ActivityClassifierTest {
    private fun f(minutes: Double = 40.0, cadence: Double = 0.0, burst: Double = 0.0, mean: Double = 0.0, peak: Double = 0.0, cv: Double = 0.0) =
        Features(minutes, cadence, burst, mean, peak, cv)

    @Test
    fun cadenceSeparatesRunsFromWalks() {
        assertEquals("run", ActivityClassifier.rank(f(cadence = 155.0, mean = 0.6, peak = 0.75, cv = 0.05)).first())
        assertEquals("walk", ActivityClassifier.rank(f(cadence = 95.0, mean = 0.2, peak = 0.3, cv = 0.05)).first())
    }

    @Test
    fun aSteadyRaisedHeartWithFewStepsIsARide() {
        assertEquals("cycle", ActivityClassifier.rank(f(minutes = 60.0, cadence = 4.0, mean = 0.55, peak = 0.65, cv = 0.05)).first())
    }

    @Test
    fun anUnevenHeartWithBurstsOfStepsIsStrength() {
        assertEquals("gym", ActivityClassifier.rank(f(minutes = 45.0, cadence = 12.0, burst = 1.3, mean = 0.3, peak = 0.5, cv = 0.16)).first())
    }

    @Test
    fun highPeaksWithDeepDropsAreIntervalsOrAGame() {
        assertEquals("sport", ActivityClassifier.rank(f(minutes = 50.0, cadence = 45.0, mean = 0.5, peak = 0.9, cv = 0.22)).first())
    }

    @Test
    fun everyTypeIsOfferedOnce() {
        val ranked = ActivityClassifier.rank(f(cadence = 20.0))
        assertEquals(ActivityClassifier.TYPES.toSet(), ranked.toSet())
        assertEquals(ActivityClassifier.TYPES.size, ranked.size)
    }

    @Test
    fun theUsersOwnAnswersOutweighTheRules() {
        // The rules call this a ride; this user has said "dance" three times for stretches like it.
        val stretch = f(minutes = 60.0, cadence = 4.0, mean = 0.55, peak = 0.65, cv = 0.05)
        val learned = List(3) { Example(stretch.copy(minutes = 55.0 + it), "dance") }
        assertEquals("dance", ActivityClassifier.rank(stretch, learned).first())
        // Answers for very different stretches don't move the guess.
        val far = List(3) { Example(f(cadence = 160.0, mean = 0.8), "dance") }
        assertEquals("cycle", ActivityClassifier.rank(stretch, far).first())
    }

    @Test
    fun featuresReadCadenceBurstinessAndHeartShape() {
        val t0 = 0L
        val steps = listOf(0 to 300, 5 to 0, 10 to 300, 15 to 0).map { (m, s) -> (t0 + m * 60_000L) to s }
        val hr = listOf(100.0, 140.0, 100.0, 140.0).mapIndexed { i, b -> (t0 + i * 5 * 60_000L) to b }
        val x = ActivityClassifier.features(t0, t0 + 20 * 60_000L, steps, hr, quiet = 60.0, hrMax = 180.0)
        assertEquals(30.0, x.cadence, 0.01)
        assertEquals(1.0, x.stepBurst, 0.01)
        assertEquals((120.0 - 60.0) / 120.0, x.hrMeanPct, 0.01)
        assertEquals(20.0 / 120.0, x.hrCv, 0.01)
    }

    @Test
    fun touchingStretchesAreOneActivity() {
        val m = 60_000L
        val merged = ActivityDetection.mergeActive(listOf(0L..30 * m, 35 * m..60 * m, 90 * m..120 * m))
        assertEquals(listOf(0L..60 * m, 90 * m..120 * m), merged)
    }

    @Test
    fun aDismissedStretchStaysDismissedWhenItsBoundsShift() {
        val m = 60_000L
        val first = ActivityDetection.Proposal(ActivityDetection.Proposal.Kind.CARDIO, 0, 40 * m, "")
        val redetected = ActivityDetection.Proposal(ActivityDetection.Proposal.Kind.WALK, 5 * m, 50 * m, "")
        assertEquals(0, ActivityDetection.open(listOf(redetected), emptyList(), setOf(first.key)).size)
    }

    @Test
    fun learnedAnswersRoundTrip() {
        val ex = listOf(Example(f(minutes = 45.0, cadence = 12.0, burst = 1.3, mean = 0.3, peak = 0.5, cv = 0.16), "gym"))
        assertEquals(ex, ActivityDetection.decodeLearned(ActivityDetection.encodeLearned(ex)))
        assertEquals(emptyList<Example>(), ActivityDetection.decodeLearned("garbage\ngym,1,2"))
    }
}
