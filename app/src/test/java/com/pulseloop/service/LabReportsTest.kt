package com.pulseloop.service

import com.pulseloop.data.entity.LabReportEntity
import com.pulseloop.data.entity.LabResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind lab reports: flags against the lab's range, marker keys, decode, series. */
class LabReportsTest {

    @Test
    fun aNumericValueIsJudgedAgainstThePrintedRangeNotTheModelsFlag() {
        // The model said normal; 148 is above the printed 130, so it is high.
        assertEquals("HIGH", LabReports.flagFor(148.0, null, 130.0, "normal"))
        assertEquals("LOW", LabReports.flagFor(22.0, 30.0, 100.0, "normal"))
        assertEquals("NORMAL", LabReports.flagFor(52.0, 40.0, null, "high"))
        // On the limit is inside.
        assertEquals("NORMAL", LabReports.flagFor(130.0, null, 130.0, "unknown"))
    }

    @Test
    fun withoutANumberOrARangeTheReportsOwnMarkingStands() {
        assertEquals("ABNORMAL", LabReports.flagFor(null, null, null, "abnormal"))
        assertEquals("NORMAL", LabReports.flagFor(null, null, null, "Normal"))
        assertEquals("UNKNOWN", LabReports.flagFor(5.0, null, null, "whatever"))
        assertEquals("HIGH", LabReports.flagFor(5.0, null, null, "high"))
    }

    @Test
    fun oneKeyPerAnalyteWhateverTheAccentsOrPunctuation() {
        assertEquals("colesterol_ldl", LabReports.normalizeKey("Colesterol  LDL"))
        assertEquals("vitamina_d_25_oh", LabReports.normalizeKey("Vitamina D (25-OH)"))
        assertEquals("hemoglobina_glicada", LabReports.normalizeKey("Hemoglobina glicada "))
        assertEquals("proteinas_urina", LabReports.normalizeKey("Proteínas — urina"))
        assertEquals("marker", LabReports.normalizeKey("  ()  "))
    }

    @Test
    fun valuesShowWithoutTrailingZeros() {
        assertEquals("148", LabReports.formatValue(148.0))
        assertEquals("14.6", LabReports.formatValue(14.6))
        assertEquals("0.85", LabReports.formatValue(0.8500001))
        assertEquals("Negative", LabReports.valueLabel(null, "Negative"))
        assertEquals("—", LabReports.valueLabel(null, " "))
    }

    @Test
    fun decodeToleratesFencesAndProse() {
        val body = """{"collected_on":"2026-10-12","laboratory":"Lab","kinds":["blood"],"results":[
            {"panel":"Lipids","name":"LDL","key":"ldl_cholesterol","value_number":148,"value_text":"148","unit":"mg/dL",
             "ref_low":null,"ref_high":130,"ref_text":"","flag":"high"}]}"""
        val fenced = "Here it is:\n```json\n$body\n```\nLet me know."
        val d = LabReports.decode(fenced)
        assertNotNull(d)
        assertEquals("2026-10-12", d!!.collectedOn)
        assertEquals(148.0, d.results.single().valueNumber!!, 0.0)
        assertNull(d.results.single().refLow)
        assertNull(LabReports.decode("no json here"))
    }

    @Test
    fun aMarkerFollowsItsKeyAcrossReportsOldestFirst() {
        val reports = listOf(
            LabReportEntity(id = "b", collectedDay = 2_000L, createdAt = 0),
            LabReportEntity(id = "a", collectedDay = 1_000L, createdAt = 0),
        )
        fun r(id: String, report: String, v: Double, flag: String, name: String = "LDL") = LabResultEntity(
            id = id, reportId = report, panel = "Lipids", name = name, markerKey = "ldl_cholesterol",
            valueNum = v, refHigh = 130.0, flag = flag,
        )
        // A result whose report is gone is left out rather than plotted on no date.
        val orphan = r("x", "gone", 999.0, "HIGH")
        val markers = LabReports.markers(reports, listOf(r("2", "b", 148.0, "HIGH", "Colesterol LDL"), r("1", "a", 126.0, "NORMAL"), orphan))
        val m = markers.single()
        assertEquals(listOf(126.0, 148.0), m.points.map { it.valueNum })
        // Named as in the latest report.
        assertEquals("Colesterol LDL", m.name)
        assertTrue(LabReports.isOutside(m.latest.flag))
        assertFalse(LabReports.isOutside("NORMAL"))
    }
}
