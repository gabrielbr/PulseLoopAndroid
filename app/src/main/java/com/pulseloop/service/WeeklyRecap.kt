package com.pulseloop.service

import com.pulseloop.ui.i18n.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.pulseloop.data.PulseLoopDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * A shareable picture of the last seven days: sleep, steps, resting heart rate and HRV against
 * the week before, plus workouts (OpenStrap Edge's shareable weekly recap). Only the user's own
 * averages, no scores, so nothing in it reads as a medical claim out of context.
 */
object WeeklyRecap {

    data class Row(val label: String, val value: String, val change: String?)

    data class Recap(val title: String, val rows: List<Row>)

    /** The rows, from this week's and last week's summaries. Pure, for testing. */
    fun rows(thisWeek: WeeklyReport.Week, lastWeek: WeeklyReport.Week?, workouts: Int): List<Row> = buildList {
        thisWeek.avgAsleepMinutes?.let { m ->
            val d = lastWeek?.avgAsleepMinutes?.let { m - it }
            add(Row(tr("Sleep a night"), SleepFormat.duration(m), d?.let { if (kotlin.math.abs(it) < 5) "same as last week" else "${if (it > 0) "+" else "−"}${SleepFormat.duration(kotlin.math.abs(it))}" }))
        }
        thisWeek.avgSteps?.let { s ->
            val d = lastWeek?.avgSteps?.takeIf { it > 0 }?.let { ((s - it) * 100.0 / it).roundToInt() }
            add(Row(tr("Steps a day"), "%,d".format(s), d?.let { if (kotlin.math.abs(it) < 3) "same as last week" else "${if (it > 0) "+" else "−"}${kotlin.math.abs(it)}%" }))
        }
        thisWeek.avgRestingHr?.let { r ->
            val d = lastWeek?.avgRestingHr?.let { (r - it).roundToInt() }
            add(Row(tr("Resting heart rate"), "${r.roundToInt()} bpm", d?.let { if (it == 0) "same as last week" else "${if (it > 0) "+" else "−"}${kotlin.math.abs(it)} bpm" }))
        }
        thisWeek.avgHrv?.let { h ->
            val d = lastWeek?.avgHrv?.takeIf { it > 0 }?.let { ((h - it) / it * 100).roundToInt() }
            add(Row("HRV", "${h.roundToInt()} ms", d?.let { if (kotlin.math.abs(it) < 3) "same as last week" else "${if (it > 0) "+" else "−"}${kotlin.math.abs(it)}%" }))
        }
        add(Row(tr("Workouts"), "$workouts", null))
    }

    suspend fun load(db: PulseLoopDatabase, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Recap {
        val inputs = ReadinessRepository.loadInputs(db, now, zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val all = inputs.history + listOfNotNull(inputs.lastNight)
        val thisNights = all.filter { it.day.isAfter(today.minusDays(7)) }
        val lastNights = all.filter { !it.day.isAfter(today.minusDays(7)) && it.day.isAfter(today.minusDays(14)) }
        val days = db.activityDailyDao().recentReal(15)
        fun steps(from: LocalDate, to: LocalDate) = days.filter {
            val d = Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate()
            !d.isBefore(from) && !d.isAfter(to) && it.steps > 0
        }.map { it.steps }
        val thisWeek = WeeklyReport.summarize(thisNights, steps(today.minusDays(6), today))
        val lastWeek = WeeklyReport.summarize(lastNights, steps(today.minusDays(13), today.minusDays(7))).takeIf { it.nights >= 3 }
        val since = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val workouts = db.activitySessionDao().recent(50).count { it.statusRaw == "finished" && it.startedAt >= since }
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        return Recap("${today.minusDays(6).format(fmt)} – ${today.format(fmt)}", rows(thisWeek, lastWeek, workouts))
    }

    /** Draw the recap as a 1080×1350 PNG in the app's colours and return a shareable URI. */
    fun share(context: Context, recap: Recap): Uri {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF121214.toInt())
        val mono = Typeface.MONOSPACE
        fun paint(color: Long, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toInt(); textSize = size; typeface = Typeface.create(mono, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val mint = 0xFF43F3B7; val text = 0xFFF2F2F2; val muted = 0xFF7C7B82; val card = 0xFF1C1B1F
        c.drawText(tr("MY WEEK"), 80f, 150f, paint(mint, 44f, bold = true))
        c.drawText(recap.title, 80f, 220f, paint(text, 64f, bold = true))
        var y = 300f
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card.toInt() }
        recap.rows.forEach { r ->
            c.drawRoundRect(60f, y, (w - 60).toFloat(), y + 170f, 24f, 24f, cardPaint)
            c.drawText(r.label.uppercase(), 100f, y + 55f, paint(muted, 30f))
            c.drawText(r.value, 100f, y + 130f, paint(text, 64f, bold = true))
            r.change?.let { ch ->
                val p = paint(if (ch.startsWith("same")) muted else mint, 34f)
                c.drawText(ch, w - 100f - p.measureText(ch), y + 125f, p)
            }
            y += 190f
        }
        c.drawText(tr("PulseLoop · from my ring, compared with last week"), 80f, h - 70f, paint(muted, 28f))
        val file = File(context.cacheDir, "pulseloop-week.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
