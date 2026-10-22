package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import com.pulseloop.data.PulseLoopDatabase
import kotlinx.serialization.Serializable

/**
 * Today's scores as the coach sees them (docs/oura-ux-analysis.md phase 5), so a check-in can
 * say *why* today is a rest day instead of only repeating step counts. Every number here is a
 * PulseLoop estimate; [note] tells the model so it doesn't present them as measurements.
 */
@Serializable
data class ScoresContext(
    /** "score", "learning", "withheld" (readings too poor; contributors hold the reasons) or "no_sleep". */
    val readinessStatus: String,
    val readiness: Int? = null,
    val readinessBand: String? = null,
    /** e.g. "Resting heart rate: Pay attention (7 bpm above your usual 55)". */
    val readinessContributors: List<String> = emptyList(),
    val baselineNightsCollected: Int? = null,
    val lastNightCoveragePct: Int? = null,
    val activityScore: Int? = null,
    val activityBand: String? = null,
    /** Learned nightly sleep need, and the decayed 14-night shortfall against it. */
    val sleepNeedMinutes: Int? = null,
    val sleepDebtMinutes: Int? = null,
    val sleepDebtLevel: String? = null,
    /** Symptom Radar: "No signs", "Minor signs" or "Major signs" of strain, with the flagged signals. */
    val symptomRadar: String? = null,
    val symptomRadarSignals: List<String> = emptyList(),
    /** From the periods the user logged, when they track their cycle; no fertility estimate. */
    val cycleDay: Int? = null,
    val cyclePhase: String? = null,
    /** Today's cardio load against the user's quiet waking heart rate, and its band. */
    val cardioLoad: Int? = null,
    val cardioLoadBand: String? = null,
    /** Today's scheduled medications and supplements with their state ("Vitamin D 08:00: taken"). */
    val medsToday: List<String> = emptyList(),
    val note: String = tr("Scores are PulseLoop estimates from ring data compared with the user's own baseline, not medical measurements."),
) {
    /** Short stable string for coach-summary regeneration signatures. */
    fun signature(): String = listOf(readinessStatus, readiness, activityScore, baselineNightsCollected).joinToString("|") +
        "|$sleepDebtLevel|$symptomRadar"

    companion object {
        suspend fun build(db: PulseLoopDatabase): ScoresContext? =
            buildScores(db)?.let { base ->
                val h = runCatching { HealthInsightsRepository.load(db) }.getOrNull() ?: return@let base
                base.copy(
                    sleepNeedMinutes = h.need.minutes,
                    sleepDebtMinutes = h.debt?.minutes,
                    sleepDebtLevel = h.debt?.level?.label,
                    symptomRadar = h.radar?.level?.label,
                    symptomRadarSignals = h.radar?.signals.orEmpty().filter { it.flagged }.map { it.detail },
                    cardioLoad = h.cardioLoad?.load,
                    cardioLoadBand = h.cardioLoad?.band?.label,
                ).let { withLoad ->
                    val cycle = runCatching {
                        Cycle.status(db.periodDao().all().map {
                            Cycle.Period(java.time.LocalDate.ofEpochDay(it.startDay), it.endDay?.let { e -> java.time.LocalDate.ofEpochDay(e) })
                        }, java.time.LocalDate.now())
                    }.getOrNull()
                    val meds = runCatching {
                        val today = java.time.LocalDate.now()
                        Meds.dosesOn(today, db.medDao().active(), db.medDao().dosesSince(today.toEpochDay()), java.time.LocalDateTime.now())
                            .map { "${it.med.name} ${it.time}: ${it.state.label.lowercase()}" }
                    }.getOrDefault(emptyList())
                    withLoad.copy(cycleDay = cycle?.cycleDay, cyclePhase = cycle?.phase?.label, medsToday = meds)
                }
            }

        private suspend fun buildScores(db: PulseLoopDatabase): ScoresContext? = runCatching {
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
                is Readiness.Result.Withheld -> ScoresContext(
                    readinessStatus = "withheld",
                    readinessContributors = r.reasons,
                    lastNightCoveragePct = r.nightCoverage?.let { (it * 100).toInt() },
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
