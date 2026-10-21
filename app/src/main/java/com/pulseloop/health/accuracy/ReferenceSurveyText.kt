package com.pulseloop.health.accuracy

import android.content.Context

/**
 * Wording for the reference-source survey, shared by the screen and its "Copy summary" text.
 * Pure apart from [ReferenceAppLabels.label]'s PackageManager fallback.
 */
object ReferenceSurveyText {

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            ms < 60_000 -> "${ms / 1000} s"
            minutes < 90 -> "$minutes min"
            minutes < 48 * 60 -> "%.1f h".format(minutes / 60.0)
            else -> "${minutes / (24 * 60)} days"
        }
    }

    /** "every 5 min" — or "about daily" for once-a-night records like sleep and resting HR. */
    fun cadence(m: MetricSurvey): String = when (val i = m.medianIntervalMs) {
        null -> "single record"
        in 20L * 3_600_000..30L * 3_600_000 -> "about daily"
        else -> "every ${duration(i)}"
    }

    /** `Device.TYPE_*` → word. Constants as published in connect-client 1.1.0. */
    fun deviceType(type: Int): String? = when (type) {
        1 -> "watch"
        2 -> "phone"
        3 -> "scale"
        4 -> "ring"
        5 -> "head-mounted"
        6 -> "fitness band"
        7 -> "chest strap"
        8 -> "smart display"
        else -> null
    }

    fun device(d: DeviceInfo): String =
        listOfNotNull(d.manufacturer?.takeIf { it.isNotBlank() }, d.model?.takeIf { it.isNotBlank() }, deviceType(d.type)?.let { "($it)" })
            .joinToString(" ").ifEmpty { "unnamed device" }

    /** `SleepSessionRecord.STAGE_TYPE_*` → word. */
    fun sleepStage(type: Int): String = when (type) {
        1 -> "awake"
        2 -> "sleeping (no stages)"
        3 -> "out of bed"
        4 -> "light"
        5 -> "deep"
        6 -> "REM"
        7 -> "awake in bed"
        else -> "unknown"
    }

    /** True when the sleep data has real stages, not just "asleep". */
    fun hasSleepStages(m: MetricSurvey): Boolean = m.sleepStageTypes.any { it in setOf(4, 5, 6) }

    /**
     * Plain-text summary for "Copy summary" — something a user can paste into an issue. Counts,
     * cadences, delays, device names and stage *types* only: no readings, in line with the
     * diagnostics export's rule that health values don't go into text people paste publicly.
     */
    fun render(
        sources: List<ReferenceSourceSurvey>,
        lookbackDays: Long,
        labelFor: (String) -> String,
        unreadable: Set<ReferenceMetric>,
    ): String = buildString {
        appendLine("PulseLoop accuracy check — Health Connect sources, last $lookbackDays days")
        if (sources.isEmpty()) appendLine("No other app wrote readable data.")
        for (s in sources) {
            appendLine()
            appendLine("${labelFor(s.packageName)} (${s.packageName})")
            if (s.devices.isEmpty()) appendLine("  device: not recorded")
            else appendLine("  device: " + s.devices.joinToString("; ") { device(it) })
            for (m in s.metrics) {
                append("  ${m.metric.label}: ${m.recordCount} records, ${m.sampleCount} samples, ${cadence(m)}, ")
                append("arrives ${duration(m.medianDeliveryDelayMs)} late (median)")
                if (m.metric == ReferenceMetric.SLEEP) {
                    append(", stages: " + m.sleepStageTypes.sorted().joinToString(", ") { sleepStage(it) })
                }
                appendLine()
            }
            s.echo?.let {
                appendLine("  echo check: ${it.matching}/${it.compared} heart-rate samples match the ring exactly" +
                    if (it.looksLikeEcho) " — LOOKS LIKE PULSELOOP'S OWN DATA" else "")
            }
        }
        if (unreadable.isNotEmpty()) {
            appendLine()
            appendLine("Not readable (permission not granted): " + unreadable.joinToString(", ") { it.label })
        }
    }.trimEnd()
}

/**
 * Human names for the apps behind Health Connect `DataOrigin`s. PackageManager can't see most of
 * them — Android 11+ package visibility, and the manifest's `<queries>` names only Health Connect
 * itself — so the common health apps are named here and anything else falls back to the package
 * name. Health Sync is listed because it's a bridge: its records are someone else's device.
 */
object ReferenceAppLabels {

    val KNOWN: Map<String, String> = mapOf(
        "nl.appyhapps.healthsync" to "Health Sync",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.huawei.health" to "Huawei Health",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.ouraring.oura" to "Oura",
        "com.withings.wiscale2" to "Withings",
        "com.xiaomi.wearable" to "Mi Fitness",
        "com.mi.health" to "Mi Fitness",
        "com.zepp.health" to "Zepp",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.polar.polarflow" to "Polar Flow",
        "com.suunto.android" to "Suunto",
        "com.whoop.android" to "WHOOP",
        "com.coros.coros" to "COROS",
        "com.amazfit" to "Amazfit",
    )

    /** Bridges re-publish another app's data under their own package. */
    val BRIDGES: Set<String> = setOf("nl.appyhapps.healthsync")

    fun label(context: Context, packageName: String): String =
        KNOWN[packageName] ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != packageName } ?: packageName
}
