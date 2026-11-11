package com.pulseloop.ring

import com.pulseloop.service.RingWorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Workouts the ring recorded in sport mode (0x25), rebuilt from the vendor SDK's "25" branch
 * (`onGetMultipleSportData(1, time, type, value)`), and grouped into sessions by [RingWorkoutLog].
 */
class JringRingWorkoutTest {
    private val clock = JringClock(TimeZone.getTimeZone("UTC"), Instant.parse("2026-11-11T12:00:00Z"))
    private val decoder = RingDecoder(clock)

    /** Encode six (type, value) slots the way the ring does. */
    private fun frame(epoch: Long, slots: List<Pair<Int, Int>>): ByteArray = ByteArray(20).also { f ->
        f[0] = 0x25
        for (b in 0 until 4) f[1 + b] = ((epoch shr (8 * b)) and 0xFF).toByte()
        val packed = IntArray(3)
        slots.forEachIndexed { i, (type, value) ->
            f[5 + i * 2] = (((value and 0x0F) shl 4) or (type and 0x0F)).toByte()
            f[6 + i * 2] = ((value shr 4) and 0xFF).toByte()
            val high = type and 0xF0
            packed[i / 2] = packed[i / 2] or (if (i % 2 == 0) high else high shr 4)
        }
        for (k in 0 until 3) f[17 + k] = packed[k].toByte()
    }

    private fun minutes(events: List<RingDecodedEvent>) = events.filterIsInstance<RingDecodedEvent.RingWorkoutMinute>()

    @Test fun decodesTypeAcrossBothNibblesAndTwelveBitValue() {
        val t = 1_794_394_800L
        // 113 = 0x71: low nibble in the slot, high nibble packed in byte 17-19. Value 1234 = 0x4D2.
        val out = minutes(decoder.decode(frame(t, listOf(113 to 1234, 2 to 95, 0 to 0, 0 to 0, 0 to 0, 6 to 7))))
        assertEquals(listOf(113, 2, 6), out.map { it.sportType })
        assertEquals(listOf(1234, 95, 7), out.map { it.value })
        assertEquals(listOf(0L, 60L, 300L), out.map { it._timestamp.epochSecond - t })
    }

    @Test fun anIdleFrameIsNotUnknown() {
        val events = decoder.decode(frame(1_794_394_800L, List(6) { 0 to 0 }))
        assertTrue(events.none { it is RingDecodedEvent.Unknown })
    }

    @Test fun minutesGroupIntoSessionsAndShortPausesStayInside() {
        val m = 60_000L
        val t0 = 1_794_394_800_000L
        val mins = (0 until 10).map { Triple(t0 + it * m, 2, 120) } +            // 10 min run
            (12 until 20).map { Triple(t0 + it * m, 2, 120) } +                      // 2-min pause: same run
            (30 until 40).map { Triple(t0 + it * m, 6, 5) } +                        // badminton later
            listOf(Triple(t0 + 50 * m, 1, 30))                                       // one stray minute
        val s = RingWorkoutLog.sessions(mins)
        assertEquals(2, s.size)
        assertEquals(2, s[0].sportType); assertEquals(18 * 120, s[0].total)
        assertEquals(t0 + 20 * m, s[0].endAt)
        assertEquals("run", RingWorkoutLog.typeFor(2))
        assertEquals("sport", RingWorkoutLog.typeFor(s[1].sportType))
        assertTrue(RingWorkoutLog.countsSteps(2) && !RingWorkoutLog.countsSteps(6))
    }
}
