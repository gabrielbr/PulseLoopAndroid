package com.pulseloop.health

import com.pulseloop.data.entity.BodyMeasurementEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.service.DailyCalorieEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scale readings from Health Connect moving the profile, age from birth year, and Katch-McArdle. */
class HealthConnectBodyImportTest {

    private val t = 1_760_000_000_000L
    private fun m(type: String, value: Double, at: Long) = BodyMeasurementEntity("hc:$type:$at", type, value, at, "com.fitdays.fitdays")

    @Test
    fun `a newer weigh-in moves weight and body composition together`() {
        val p = UserProfileEntity(weightKg = 85.0, heightCm = 180.0)
        val out = HealthConnectBodyImport.merge(
            p,
            weight = m(BodyMeasurementEntity.TYPE_WEIGHT, 82.4, t),
            height = null,
            fat = m(BodyMeasurementEntity.TYPE_BODY_FAT, 21.5, t + 1000),
            lean = null,
        )
        assertEquals(82.4, out.weightKg!!, 0.001)
        assertEquals(21.5, out.bodyFatPct!!, 0.001)
        assertEquals(t, out.bodyUpdatedAt)
        assertEquals(180.0, out.heightCm!!, 0.001)
    }

    @Test
    fun `an old weigh-in does not overwrite a newer one`() {
        val p = UserProfileEntity(weightKg = 80.0, bodyUpdatedAt = t)
        val out = HealthConnectBodyImport.merge(p, m(BodyMeasurementEntity.TYPE_WEIGHT, 90.0, t - 1), null, null, null)
        assertEquals(80.0, out.weightKg!!, 0.001)
    }

    @Test
    fun `body fat from a different day is not paired with today's weight`() {
        val out = HealthConnectBodyImport.merge(
            UserProfileEntity(),
            weight = m(BodyMeasurementEntity.TYPE_WEIGHT, 82.0, t),
            height = null,
            fat = m(BodyMeasurementEntity.TYPE_BODY_FAT, 25.0, t - 86_400_000L),
            lean = null,
        )
        assertNull(out.bodyFatPct)
    }

    @Test
    fun `height in metres becomes centimetres`() {
        val out = HealthConnectBodyImport.merge(UserProfileEntity(), null, m(BodyMeasurementEntity.TYPE_HEIGHT, 1.78, t), null, null)
        assertEquals(178.0, out.heightCm!!, 0.001)
    }

    @Test
    fun `age follows the birth year`() {
        val p = ProfileAging.refreshed(UserProfileEntity(age = 40), year = 2026)
        assertEquals(1986, p.birthYear)
        assertEquals(41, ProfileAging.refreshed(p, year = 2027).age)
    }

    @Test
    fun `lean mass from body fat drives Katch-McArdle`() {
        val profile = DailyCalorieEstimator.profileOf(UserProfileEntity(weightKg = 80.0, bodyFatPct = 20.0))
        assertEquals(64.0, profile.leanMassKg!!, 0.001)
        assertEquals(370.0 + 21.6 * 64.0, DailyCalorieEstimator.katchMcArdle(64.0), 0.001)
    }
}
