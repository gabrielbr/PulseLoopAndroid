package com.pulseloop.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One lab report the user added as a PDF (docs: AGENTS.md, lab reports). [collectedDay] is the
 * local start of the day the sample was collected, which is what a marker's chart is plotted by;
 * [pdfFile] names the copy kept in `filesDir/lab_reports/`.
 */
@Entity(tableName = "lab_reports", indices = [Index("collectedDay")])
data class LabReportEntity(
    @PrimaryKey val id: String,
    val collectedDay: Long,
    val lab: String? = null,
    /** Kinds found in the report, comma-separated: blood, urine, hormones, stool, other. */
    val kindsCsv: String = "",
    /** The name the file had when the user picked it, for display. */
    val fileName: String? = null,
    val pdfFile: String? = null,
    val pageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One marker read from a report, as the user checked it. [markerKey] is the same for one marker
 * across labs and languages ("ldl_cholesterol"), so a chart can follow it from report to report.
 * The range is the lab's own, carried with each result because labs and methods differ.
 */
@Entity(
    tableName = "lab_results",
    foreignKeys = [ForeignKey(
        entity = LabReportEntity::class,
        parentColumns = ["id"],
        childColumns = ["reportId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("reportId"), Index("markerKey")],
)
data class LabResultEntity(
    @PrimaryKey val id: String,
    val reportId: String,
    /** The group the lab printed it under ("Lipids", "Blood count", "Urine"…). */
    val panel: String,
    /** The marker's name as printed on the report. */
    val name: String,
    val markerKey: String,
    val valueNum: Double? = null,
    /** For a result that isn't a number ("Negative", "Clear"), or the printed form of one. */
    val valueText: String? = null,
    val unit: String? = null,
    val refLow: Double? = null,
    val refHigh: Double? = null,
    /** The reference as printed, when it isn't a plain low–high pair. */
    val refText: String? = null,
    /** HIGH, LOW, NORMAL, ABNORMAL (outside a non-numeric reference) or UNKNOWN. */
    val flag: String = "UNKNOWN",
    val position: Int = 0,
)
