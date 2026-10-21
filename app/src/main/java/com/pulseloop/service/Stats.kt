package com.pulseloop.service

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The small amount of statistics the insight screens need, without a library: Welch's t-test and
 * the Benjamini-Hochberg false-discovery correction. "What affects you" tests every tag against
 * several outcomes; uncorrected, a user with a dozen tags would be shown a couple of "findings"
 * that are chance by construction (OpenStrap Edge corrects for exactly this).
 */
object Stats {

    /** Two-sided p-value of Welch's unequal-variance t-test; 1.0 when it can't be computed. */
    fun welchP(a: List<Double>, b: List<Double>): Double {
        if (a.size < 2 || b.size < 2) return 1.0
        val ma = a.average(); val mb = b.average()
        val va = a.sumOf { (it - ma) * (it - ma) } / (a.size - 1)
        val vb = b.sumOf { (it - mb) * (it - mb) } / (b.size - 1)
        val se2 = va / a.size + vb / b.size
        if (se2 <= 0.0) return if (ma == mb) 1.0 else 0.0
        val t = (ma - mb) / sqrt(se2)
        val df = se2 * se2 / ((va / a.size) * (va / a.size) / (a.size - 1) + (vb / b.size) * (vb / b.size) / (b.size - 1))
        return twoSidedT(t, df)
    }

    /** P(|T| ≥ |t|) for Student's t with [df] degrees of freedom. */
    fun twoSidedT(t: Double, df: Double): Double {
        if (!df.isFinite() || df <= 0) return 1.0
        return incompleteBeta(df / 2, 0.5, df / (df + t * t)).coerceIn(0.0, 1.0)
    }

    /**
     * Which of [pValues] survive the Benjamini-Hochberg procedure at false-discovery rate [q]:
     * sort ascending, find the largest k with p(k) ≤ k/m·q, and keep the first k.
     */
    fun benjaminiHochberg(pValues: List<Double>, q: Double = 0.10): List<Boolean> {
        val m = pValues.size
        if (m == 0) return emptyList()
        val order = pValues.indices.sortedBy { pValues[it] }
        var cutoff = -1
        order.forEachIndexed { rank, i -> if (pValues[i] <= (rank + 1).toDouble() / m * q) cutoff = rank }
        val keep = BooleanArray(m)
        for (rank in 0..cutoff) keep[order[rank]] = true
        return keep.toList()
    }

    // Regularised incomplete beta I_x(a, b) (Numerical Recipes, continued fraction).
    private fun incompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val bt = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1 - x))
        return if (x < (a + 1) / (a + b + 2)) bt * betaCf(a, b, x) / a else 1 - bt * betaCf(b, a, 1 - x) / b
    }

    private fun betaCf(a: Double, b: Double, x: Double): Double {
        val tiny = 1e-30
        var c = 1.0
        var d = 1 - (a + b) * x / (a + 1)
        if (abs(d) < tiny) d = tiny
        d = 1 / d
        var h = d
        for (m in 1..200) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d
            val del = d * c
            h *= del
            if (abs(del - 1) < 3e-12) break
        }
        return h
    }

    private val LANCZOS = doubleArrayOf(
        76.18009172947146, -86.50532032941677, 24.01409824083091,
        -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5,
    )

    private fun lnGamma(xx: Double): Double {
        var y = xx
        val tmp = xx + 5.5 - (xx + 0.5) * ln(xx + 5.5)
        var ser = 1.000000000190015
        for (c in LANCZOS) { y += 1; ser += c / y }
        return -tmp + ln(2.5066282746310005 * ser / xx)
    }
}
