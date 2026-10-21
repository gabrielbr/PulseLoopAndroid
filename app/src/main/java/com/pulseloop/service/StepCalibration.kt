package com.pulseloop.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Ring steps against the phone's own step counter (OpenStrap Edge calibrates ring steps per
 * device family). Ring step counts are noisy, and a phone carried all day is a fair reference.
 * The phone's hardware counter is read whenever the app or its hourly workers run; per-day phone
 * totals are kept for 60 days. Shown as a comparison; applied only to Today's tile, and only when
 * the user switches it on.
 */
object StepCalibration {
    private const val PREFS = "step_calibration"
    const val MIN_DAYS = 5
    const val MIN_STEPS = 3000

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isApplied(c: Context) = prefs(c).getBoolean("apply", false)
    fun setApplied(c: Context, on: Boolean) = prefs(c).edit().putBoolean("apply", on).apply()

    fun hasPermission(c: Context) = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Phone steps per day, as recorded so far. */
    fun phoneDays(c: Context): Map<LocalDate, Int> = runCatching {
        val o = JSONObject(prefs(c).getString("days", "{}") ?: "{}")
        o.keys().asSequence().associate { LocalDate.parse(it) to o.getInt(it) }
    }.getOrDefault(emptyMap())

    /**
     * Add one counter reading. The counter counts since boot: a reading lower than the last one
     * means a reboot, so the new reading itself is the steps since then.
     */
    internal fun accumulate(days: Map<LocalDate, Int>, lastCounter: Long?, counter: Long, day: LocalDate): Map<LocalDate, Int> {
        val delta = when {
            lastCounter == null -> 0L
            counter >= lastCounter -> counter - lastCounter
            else -> counter
        }.coerceIn(0L, 60_000L)
        val out = days.toMutableMap()
        out[day] = (out[day] ?: 0) + delta.toInt()
        return out.filterKeys { it.isAfter(day.minusDays(60)) }
    }

    /** Read the phone's step counter once and fold it into the day totals. Quietly does nothing without permission. */
    suspend fun sample(c: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (!hasPermission(c)) return
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val value = withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<Long> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        } ?: return
        val p = prefs(c)
        val last = p.getLong("lastCounter", -1L).takeIf { it >= 0 }
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = accumulate(phoneDays(c), last, value, day)
        val o = JSONObject(); days.forEach { (d, s) -> o.put(d.toString(), s) }
        p.edit().putLong("lastCounter", value).putString("days", o.toString()).apply()
    }

    data class Result(val factor: Double, val days: Int)

    /**
     * Phone ÷ ring over completed days where both counted at least [MIN_STEPS]; the median of
     * those ratios once there are [MIN_DAYS]. Null when there aren't, or when the ratio is
     * implausible (under 0.6 or over 1.6: the phone wasn't carried, or the ring wasn't worn).
     */
    fun factor(phone: Map<LocalDate, Int>, ring: Map<LocalDate, Int>, today: LocalDate): Result? {
        val ratios = phone.filter { (d, p) -> d.isBefore(today) && p >= MIN_STEPS }
            .mapNotNull { (d, p) -> ring[d]?.takeIf { it >= MIN_STEPS }?.let { p.toDouble() / it } }
            .sorted()
        if (ratios.size < MIN_DAYS) return null
        val m = ratios[ratios.size / 2]
        if (m !in 0.6..1.6) return null
        return Result(m, ratios.size)
    }
}
