package com.pulseloop.ui.components

import com.pulseloop.ui.i18n.tr

import androidx.compose.ui.graphics.Color
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ui.theme.MetricColors

/**
 * One zone segment on the threshold bar.
 */
data class ThresholdZone(
    val label: String,        // "Normal", "Elevated", …
    val start: Double,        // inclusive lower bound on the display scale
    val end: Double,          // exclusive upper bound
    val color: Color,
)

/**
 * Threshold table for a single metric — defines display range, zones, and direction.
 */
data class MetricThresholds(
    val displayMin: Double,
    val displayMax: Double,
    val zones: List<ThresholdZone>,
    val higherIsBetter: Boolean? = null,   // null = neutral / personal / relative
    val unitLabel: String = "",
) {
    fun zoneFor(value: Double): ThresholdZone? =
        zones.firstOrNull { value >= it.start && value < it.end } ?: zones.lastOrNull()
}

/**
 * Lookup table that maps every [MeasurementKind] to its [MetricThresholds].
 * All ranges are **general wellness reference ranges, not diagnostic thresholds**.
 */
object MetricThresholdTable {

    fun forKind(kind: MeasurementKind): MetricThresholds? = when (kind) {
        MeasurementKind.HEART_RATE -> heartRate
        MeasurementKind.SPO2 -> spo2
        MeasurementKind.STRESS -> stress
        MeasurementKind.FATIGUE -> fatigue
        MeasurementKind.HRV -> hrv
        MeasurementKind.TEMPERATURE -> skinTemp
        MeasurementKind.BLOOD_PRESSURE_SYSTOLIC -> bloodPressure
        MeasurementKind.BLOOD_PRESSURE_DIASTOLIC -> bloodPressure
        MeasurementKind.BLOOD_SUGAR -> bloodSugar
        // YCBT history-only fields (respiratory rate / VO2max) have no dedicated zone chart yet.
        MeasurementKind.RESPIRATORY_RATE, MeasurementKind.VO2MAX -> null
    }

    /** Returns a synthetic thresholds entry for the combined "bp" detail screen. */
    fun forKey(key: String): MetricThresholds? = when (key) {
        "hr"       -> heartRate
        "spo2"     -> spo2
        "stress"   -> stress
        "fatigue"  -> fatigue
        "hrv"      -> hrv
        "temp"     -> skinTemp
        "bp"       -> bloodPressure
        "glucose"  -> bloodSugar
        else       -> null
    }

    // ── Zone tables ─────────────────────────────────────────────────────

    private val heartRate = MetricThresholds(
        displayMin = 40.0, displayMax = 140.0,
        higherIsBetter = false,
        unitLabel = "bpm",
        zones = listOf(
            ThresholdZone(tr("Low"),      40.0,  50.0, MetricColors.ZoneLow),
            ThresholdZone(tr("Good"),     50.0,  90.0, MetricColors.ZoneGood),
            ThresholdZone(tr("Elevated"), 90.0, 120.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("High"),    120.0, 140.0, MetricColors.ZoneConcern),
        ),
    )

    private val spo2 = MetricThresholds(
        displayMin = 85.0, displayMax = 100.0,
        higherIsBetter = true,
        unitLabel = "%",
        zones = listOf(
            ThresholdZone(tr("Concerning"), 85.0, 90.0, MetricColors.ZoneConcern),
            ThresholdZone(tr("Low"),        90.0, 95.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("Good"),       95.0, 100.0, MetricColors.ZoneGood),
        ),
    )

    private val stress = MetricThresholds(
        displayMin = 0.0, displayMax = 100.0,
        higherIsBetter = false,
        unitLabel = "",
        zones = listOf(
            ThresholdZone(tr("Relaxed"),   0.0, 30.0, MetricColors.ZoneGood),
            ThresholdZone(tr("Normal"),   30.0, 60.0, MetricColors.ZoneNormal),
            ThresholdZone(tr("Moderate"), 60.0, 80.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("High"),     80.0, 100.0, MetricColors.ZoneConcern),
        ),
    )

    private val fatigue = MetricThresholds(
        displayMin = 0.0, displayMax = 100.0,
        higherIsBetter = false,
        unitLabel = "",
        zones = listOf(
            ThresholdZone(tr("Relaxed"),   0.0, 30.0, MetricColors.ZoneGood),
            ThresholdZone(tr("Normal"),   30.0, 60.0, MetricColors.ZoneNormal),
            ThresholdZone(tr("Moderate"), 60.0, 80.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("High"),     80.0, 100.0, MetricColors.ZoneConcern),
        ),
    )

    private val hrv = MetricThresholds(
        displayMin = 0.0, displayMax = 120.0,
        higherIsBetter = true,
        unitLabel = "ms",
        zones = listOf(
            ThresholdZone(tr("Low"),         0.0, 20.0, MetricColors.ZoneConcern),
            ThresholdZone(tr("Below avg"),  20.0, 50.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("Good"),       50.0, 120.0, MetricColors.ZoneGood),
        ),
    )

    private val skinTemp = MetricThresholds(
        displayMin = 28.0, displayMax = 38.0,
        higherIsBetter = null,  // personal / relative
        unitLabel = "°C",
        zones = listOf(
            ThresholdZone(tr("Low"),      28.0, 31.0, MetricColors.ZoneLow),
            ThresholdZone(tr("Normal"),   31.0, 36.0, MetricColors.ZoneGood),
            ThresholdZone(tr("Elevated"), 36.0, 38.0, MetricColors.ZoneBorderline),
        ),
    )

    private val bloodPressure = MetricThresholds(
        displayMin = 80.0, displayMax = 180.0,
        higherIsBetter = false,
        unitLabel = "mmHg",
        zones = listOf(
            ThresholdZone(tr("Normal"),     80.0, 120.0, MetricColors.ZoneGood),
            ThresholdZone(tr("Elevated"),  120.0, 130.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("Stage 1"),   130.0, 140.0, MetricColors.ZoneElevated),
            ThresholdZone(tr("High"),      140.0, 180.0, MetricColors.ZoneConcern),
        ),
    )

    private val bloodSugar = MetricThresholds(
        displayMin = 50.0, displayMax = 200.0,
        higherIsBetter = false,
        unitLabel = "mg/dL",
        zones = listOf(
            ThresholdZone(tr("Low"),       50.0,  70.0, MetricColors.ZoneLow),
            ThresholdZone(tr("Normal"),    70.0, 100.0, MetricColors.ZoneGood),
            ThresholdZone(tr("Elevated"), 100.0, 126.0, MetricColors.ZoneBorderline),
            ThresholdZone(tr("High"),     126.0, 200.0, MetricColors.ZoneConcern),
        ),
    )
}

/**
 * BP helper — returns the worse zone category of systolic vs diastolic.
 * The threshold bar is scaled on systolic, but zone selection uses the
 * worse of the two readings so the user isn't misled by a normal systolic
 * when diastolic is high (or vice versa).
 */
fun bpZone(sys: Double?, dia: Double?): ThresholdZone? {
    if (sys == null && dia == null) return null

    // Diastolic category
    val diaZone = when {
        dia == null -> null
        dia < 80.0  -> tr("Normal")
        dia < 90.0  -> tr("Stage1")
        else        -> tr("High")
    }

    // Systolic category
    val sysZone = when {
        sys == null -> null
        sys < 120.0  -> tr("Normal")
        sys < 130.0  -> tr("Elevated")
        sys < 140.0  -> tr("Stage1")
        else         -> tr("High")
    }

    // Pick the worse category (ordered Normal < Elevated < Stage1 < High)
    val worst = when {
        sysZone == "High"   || diaZone == "High"   -> tr("High")
        sysZone == "Stage1" || diaZone == "Stage1" -> tr("Stage1")
        sysZone == "Elevated"                      -> tr("Elevated")
        sysZone == "Normal"  && diaZone == "Normal"-> tr("Normal")
        diaZone == "Normal"  && sysZone == null    -> tr("Normal")
        sysZone == "Normal"  && diaZone == null    -> tr("Normal")
        else                                        -> null
    }

    return MetricThresholdTable.forKind(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC)
        ?.zones?.firstOrNull { it.label.equals(worst, ignoreCase = true) }
}
