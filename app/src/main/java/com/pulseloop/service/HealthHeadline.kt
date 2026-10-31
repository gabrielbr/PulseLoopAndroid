package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one line under the greeting in every tab's header, which opens My Health.
 *
 * It says the most important thing first: lab markers outside their range, then a sign from the
 * radar (What changed), then "still learning you", then how the health areas stand. It never
 * names a condition: it points at what to look at, and the screen it opens says the rest.
 */
object HealthHeadline {
    enum class Kind { LABS, RADAR, LEARNING, AREAS, NONE }

    /** [route] is the screen the line opens; [attention] paints the dot amber. */
    data class Headline(val kind: Kind, val text: String, val route: String, val attention: Boolean)

    fun pick(labsOutside: Int, radar: SymptomRadar.Result?, areas: List<HealthArea>): Headline {
        if (labsOutside > 0) {
            val text = if (labsOutside == 1) tr("1 lab marker to review") else trf("{0} lab markers to review", labsOutside)
            return Headline(Kind.LABS, text, "lab_reports", attention = true)
        }
        if (radar != null && radar.baselineNights >= SymptomRadar.MIN_BASELINE && radar.level != SymptomRadar.Level.NONE) {
            val top = radar.flagged.maxByOrNull { it.z }
            return Headline(Kind.RADAR, top?.let { signalText(it.metric) } ?: radar.level.label, "what_changed", attention = true)
        }
        if (radar != null && radar.baselineNights < SymptomRadar.MIN_BASELINE) {
            return Headline(
                Kind.LEARNING,
                trf("Learning you · {0} of {1} nights", radar.baselineNights, SymptomRadar.MIN_BASELINE),
                "my_health", attention = false,
            )
        }
        val worst = areas.maxByOrNull { it.rating.ordinal }
        if (worst != null) {
            return when (worst.rating) {
                HealthArea.Rating.THRIVING, HealthArea.Rating.GOOD ->
                    Headline(Kind.AREAS, tr("All health areas steady"), "my_health", attention = false)
                HealthArea.Rating.FAIR ->
                    Headline(Kind.AREAS, trf("{0} · {1}", worst.title, worst.rating.label), "my_health", attention = false)
                HealthArea.Rating.NEEDS_CARE ->
                    Headline(Kind.AREAS, trf("{0} · {1}", worst.title, worst.rating.label), "my_health", attention = true)
            }
        }
        return Headline(Kind.NONE, tr("My Health"), "my_health", attention = false)
    }

    /** What moved, in the strain direction the radar measured. Never what it might mean. */
    fun signalText(metric: SymptomRadar.Metric): String = when (metric) {
        SymptomRadar.Metric.TEMPERATURE, SymptomRadar.Metric.TEMPERATURE_TREND -> tr("Temperature above your usual")
        SymptomRadar.Metric.RESTING_HR -> tr("Resting HR above your usual")
        SymptomRadar.Metric.HRV -> tr("HRV below your usual")
        SymptomRadar.Metric.RESPIRATORY_RATE -> tr("Breathing rate above your usual")
        SymptomRadar.Metric.INACTIVE -> tr("Fewer steps than usual")
    }

    /** Markers outside the lab's range in the latest report only: an older one was superseded. */
    fun labsOutside(reports: List<com.pulseloop.data.entity.LabReportEntity>, results: List<com.pulseloop.data.entity.LabResultEntity>): Int {
        val latest = reports.maxWithOrNull(compareBy({ it.collectedDay }, { it.createdAt })) ?: return 0
        return results.count { it.reportId == latest.id && LabReports.isOutside(it.flag) }
    }

    suspend fun load(db: PulseLoopDatabase): Headline = withContext(Dispatchers.Default) {
        val labs = runCatching { labsOutside(db.labDao().reports(), db.labDao().results()) }.getOrDefault(0)
        val snap = runCatching { HealthInsightsRepository.load(db) }.getOrNull()
        pick(labs, snap?.radar, snap?.areas.orEmpty())
    }
}
