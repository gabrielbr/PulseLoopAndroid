package com.pulseloop.ui.components

import com.pulseloop.service.BedtimeGuidance
import com.pulseloop.service.SleepFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * The clock helpers follow the 12/24-hour flag they are given. Off a device the platform's
 * pattern lookup answers null, so these pin the fallback patterns ("HH:mm" / "h:mm a").
 */
class PhoneFormatClockTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() = Locale.setDefault(saved)

    @Test
    fun `bedtime times follow the 12-24 hour flag`() {
        assertEquals("22:45", BedtimeGuidance.fmt(LocalTime.of(22, 45)))
        assertEquals("22:45", BedtimeGuidance.fmt(LocalTime.of(22, 45), is24Hour = true))
        assertEquals("10:45 PM", BedtimeGuidance.fmt(LocalTime.of(22, 45), is24Hour = false))
        assertEquals("7:05 AM", BedtimeGuidance.fmt(LocalTime.of(7, 5), is24Hour = false))
        assertEquals("12:00 AM", BedtimeGuidance.fmt(LocalTime.MIDNIGHT, is24Hour = false))
        assertEquals("00:00", BedtimeGuidance.fmt(LocalTime.MIDNIGHT, is24Hour = true))
    }

    @Test
    fun `sleep clock times follow the 12-24 hour flag`() {
        val ts = LocalDate.of(2026, 11, 4).atTime(23, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("23:30", SleepFormat.clockTime(ts, is24Hour = true))
        assertEquals("11:30 PM", SleepFormat.clockTime(ts, is24Hour = false))
        assertEquals("23:30", PhoneFormat.clock(true, ts))
    }
}
