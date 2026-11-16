package com.pulseloop.ui.calm

import com.pulseloop.ui.calm.StateTimelineModel.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class StateTimelineModelTest {
    @Test
    fun `days at the same level join into one run, and an empty day breaks it`() {
        val runs = StateTimelineModel.runs(listOf(0, 0, 1, 1, null, 1, 2))
        assertEquals(listOf(Run(0, 0, 1), Run(1, 2, 3), Run(1, 5, 5), Run(2, 6, 6)), runs)
    }

    @Test
    fun `a step joins only runs that touch`() {
        val runs = StateTimelineModel.runs(listOf(0, 0, 1, 1, null, 1, 2))
        // 0→1 touch (day 1 to day 2), 1→1 across a gap is no step, 1→2 touch.
        assertEquals(listOf(Run(0, 0, 1) to Run(1, 2, 3), Run(1, 5, 5) to Run(2, 6, 6)), StateTimelineModel.steps(runs))
    }

    @Test
    fun `the selection starts on the last day with a value`() {
        assertEquals(2, StateTimelineModel.lastIndex(listOf(0, null, 1, null)))
        assertEquals(-1, StateTimelineModel.lastIndex(listOf(null, null)))
        assertEquals(emptyList<Run>(), StateTimelineModel.runs(emptyList()))
    }
}
