package com.pulseloop.service

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.pulseloop.data.entity.ActivityGpsPointEntity
import com.pulseloop.data.entity.ActivitySessionEntity
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * GPX 1.1 for a workout's route, so it can go to any map or training app, not only Strava.
 * Only accepted points (the recorder's own filter) are written, in time order, with elevation and
 * time where known; heart rate rides in the Garmin TrackPointExtension that most apps read.
 */
object GpxExport {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun build(session: ActivitySessionEntity, points: List<ActivityGpsPointEntity>, name: String, heartRate: List<Pair<Long, Double>> = emptyList()): String? {
        val kept = points.filter { it.accepted }.sortedBy { it.timestamp }
        if (kept.isEmpty()) return null
        val iso = DateTimeFormatter.ISO_INSTANT
        val hr = heartRate.sortedBy { it.first }
        fun hrAt(t: Long): Int? = hr.lastOrNull { it.first <= t && t - it.first <= 30_000L }?.second?.toInt()
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<gpx version="1.1" creator="PulseLoop" xmlns="http://www.topografix.com/GPX/1/1" """)
            append("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">""").append('\n')
            append("  <metadata><name>").append(esc(name)).append("</name><time>")
                .append(iso.format(Instant.ofEpochMilli(session.startedAt))).append("</time></metadata>\n")
            append("  <trk><name>").append(esc(name)).append("</name><type>").append(esc(session.type)).append("</type><trkseg>\n")
            for (p in kept) {
                append("    <trkpt lat=\"").append("%.7f".format(java.util.Locale.US, p.latitude))
                    .append("\" lon=\"").append("%.7f".format(java.util.Locale.US, p.longitude)).append("\">")
                p.altitude?.let { append("<ele>").append("%.1f".format(java.util.Locale.US, it)).append("</ele>") }
                append("<time>").append(iso.format(Instant.ofEpochMilli(p.timestamp))).append("</time>")
                hrAt(p.timestamp)?.let { append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it).append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>") }
                append("</trkpt>\n")
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }
    }

    /** Write the GPX to the cache and return a shareable content URI, or null without a route. */
    fun share(context: Context, session: ActivitySessionEntity, points: List<ActivityGpsPointEntity>, name: String, heartRate: List<Pair<Long, Double>>): Uri? {
        val text = build(session, points, name, heartRate) ?: return null
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date(session.startedAt))
        val file = File(context.cacheDir, "pulseloop-${session.type}-$stamp.gpx")
        file.writeText(text)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
