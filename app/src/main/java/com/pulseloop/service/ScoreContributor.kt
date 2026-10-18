package com.pulseloop.service

/**
 * One row of an Oura-style score breakdown, shared by Readiness, Sleep and Activity so every score
 * renders its parts the same way (docs/oura-ux-analysis.md §1.2).
 */
data class ScoreContributor(
    val title: String,
    /** 0–100. */
    val score: Int,
    /** Plain-language reading against the user's own normal or goal, e.g. "6h 40m of 7h 30m goal". */
    val detail: String,
    /** What this contributor measures and why it matters, for the tap-to-explain view. */
    val explanation: String,
    /** Share of the parent score; renormalised over the contributors that could be scored. */
    val weight: Double,
) {
    val band: Readiness.Band get() = Readiness.Band.of(score)

    companion object {
        /** Weighted mean of [parts], 0–100, or null when there is nothing to weigh. */
        fun combine(parts: List<ScoreContributor>): Int? {
            val w = parts.sumOf { it.weight }
            if (parts.isEmpty() || w <= 0) return null
            return (parts.sumOf { it.score * it.weight } / w).let { kotlin.math.round(it).toInt() }.coerceIn(0, 100)
        }
    }
}

fun Readiness.Contributor.asScoreContributor() = ScoreContributor(
    title = kind.title,
    score = score,
    detail = detail,
    explanation = ReadinessCopy.explanation(kind),
    weight = kind.weight,
)
