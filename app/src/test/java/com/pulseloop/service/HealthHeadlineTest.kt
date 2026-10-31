package com.pulseloop.service

import com.pulseloop.data.entity.LabReportEntity
import com.pulseloop.data.entity.LabResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The header's health line: what wins when several things want to be said. */
class HealthHeadlineTest {

    private val calm = SymptomRadar.Result(SymptomRadar.Level.NONE, emptyList(), 0.0, baselineNights = 20)
    private val restingUp = SymptomRadar.Result(
        SymptomRadar.Level.MINOR,
        listOf(
            SymptomRadar.Signal(SymptomRadar.Metric.HRV, 30.0, 42.0, 1.6),
            SymptomRadar.Signal(SymptomRadar.Metric.RESTING_HR, 62.0, 56.0, 2.4),
        ),
        2.0, baselineNights = 20,
    )
    private fun area(rating: HealthArea.Rating, title: String = "Sleep health") =
        HealthArea(title, rating, 50, emptyList(), "")

    @Test
    fun labsOutsideTheirRangeComeFirst() {
        val h = HealthHeadline.pick(2, restingUp, listOf(area(HealthArea.Rating.NEEDS_CARE)))
        assertEquals(HealthHeadline.Kind.LABS, h.kind)
        assertEquals("2 lab markers to review", h.text)
        assertEquals("lab_reports", h.route)
        assertEquals("1 lab marker to review", HealthHeadline.pick(1, null, emptyList()).text)
    }

    @Test
    fun aRadarSignNamesTheStrongestSignalAndOpensWhatChanged() {
        val h = HealthHeadline.pick(0, restingUp, emptyList())
        assertEquals(HealthHeadline.Kind.RADAR, h.kind)
        assertEquals("Resting HR above your usual", h.text)
        assertEquals("what_changed", h.route)
        assertTrue(h.attention)
    }

    @Test
    fun beforeTheBaselineItSaysItIsStillLearning() {
        val early = SymptomRadar.Result(SymptomRadar.Level.NONE, emptyList(), 0.0, baselineNights = 4)
        val h = HealthHeadline.pick(0, early, listOf(area(HealthArea.Rating.GOOD)))
        assertEquals(HealthHeadline.Kind.LEARNING, h.kind)
        assertEquals("Learning you · 4 of 14 nights", h.text)
        assertFalse(h.attention)
    }

    @Test
    fun otherwiseTheWeakestAreaSpeaks() {
        assertEquals("All health areas steady", HealthHeadline.pick(0, calm, listOf(area(HealthArea.Rating.GOOD), area(HealthArea.Rating.THRIVING))).text)
        val fair = HealthHeadline.pick(0, calm, listOf(area(HealthArea.Rating.GOOD), area(HealthArea.Rating.FAIR, "Heart health")))
        assertEquals("Heart health · Fair", fair.text)
        assertFalse(fair.attention)
        assertTrue(HealthHeadline.pick(0, calm, listOf(area(HealthArea.Rating.NEEDS_CARE))).attention)
        assertEquals(HealthHeadline.Kind.NONE, HealthHeadline.pick(0, null, emptyList()).kind)
    }

    @Test
    fun onlyTheLatestReportCounts() {
        val reports = listOf(
            LabReportEntity(id = "old", collectedDay = 1_000L, createdAt = 0),
            LabReportEntity(id = "new", collectedDay = 2_000L, createdAt = 0),
        )
        fun r(id: String, report: String, flag: String) =
            LabResultEntity(id = id, reportId = report, panel = "", name = id, markerKey = id, flag = flag)
        val results = listOf(r("a", "old", "HIGH"), r("b", "old", "LOW"), r("c", "new", "HIGH"), r("d", "new", "NORMAL"))
        assertEquals(1, HealthHeadline.labsOutside(reports, results))
        assertEquals(0, HealthHeadline.labsOutside(emptyList(), results))
    }
}
