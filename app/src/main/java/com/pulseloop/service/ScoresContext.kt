package com.pulseloop.service

import com.pulseloop.data.PulseLoopDatabase
import kotlinx.serialization.Serializable

/**
 * Today's scores as the coach sees them (docs/oura-ux-analysis.md phase 5), so a check-in can
 * say *why* today is a rest day instead of only repeating step counts. Every number here is a
 * PulseLoop estimate; [note] tells the model so it doesn't present them as measurements.
 */
@Serializable
data class ScoresContext(
    /** "score", "learning" or "no_sleep". */
    val readinessStatus: String,
    val readiness: Int? = null,
    val readinessBand: String? = null,
    /** e.g. "Resting heart rate: Pay attention (7 bpm above your usual 55)". */
    val readinessContributors: List<String> = emptyList(),
    val baselineNightsCollected: Int? = null,
    val lastNightCoveragePct: Int? = null,
    val activityScore: Int? = null,
    val activityBand: String? = null,
    val note: String = "Scores are PulseLoop estimates from ring data compared with the user's own baseline, not medical measurements.",
) {
    /** Short stable string for coach-summary regeneration signatures. */
    fun signature(): String = listOf(readinessStatus, readiness, activityScore, baselineNightsCollected).joinToString("|")

    companion object {
        suspend fun build(db: PulseLoopDatabase): ScoresContext? = runCatching {
            val activity = runCatching { ActivityScore.load(db) }.getOrNull()
            when (val r = ReadinessRepository.compute(db)) {
                is Readiness.Result.Score -> ScoresContext(
                    readinessStatus = "score",
                    readiness = r.value,
                    readinessBand = r.band.label,
                    readinessContributors = r.contributors.map { "${it.kind.title}: ${it.band.label} (${it.detail})" },
                    lastNightCoveragePct = r.nightCoverage?.let { (it * 100).toInt() },
                    activityScore = activity?.value,
                    activityBand = activity?.band?.label,
                )
                is Readiness.Result.Learning -> ScoresContext(
                    readinessStatus = "learning",
                    baselineNightsCollected = r.nightsCollected,
                    activityScore = activity?.value,
                    activityBand = activity?.band?.label,
                )
                Readiness.Result.NoSleep -> ScoresContext(
                    readinessStatus = "no_sleep",
                    activityScore = activity?.value,
                    activityBand = activity?.band?.label,
                )
            }
        }.getOrNull()
    }
}
