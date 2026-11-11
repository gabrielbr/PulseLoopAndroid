package com.pulseloop.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0x0E device-mode frames, byte for byte against the vendor SDK's `setDeviceMode`
 * (`BluetoothLeService.m(int)` in JRing 2.0.8, `com.jaga.ibraceletplus.jyring`).
 */
class JringDeviceModeTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val pad = "00".repeat(11)

    @Test fun factoryResetIsMode4() {
        assertEquals("0e1234123412341234$pad", hex(RingEncoder.makeDeviceModeCommand(JringDeviceMode.FACTORY_RESET)))
    }

    @Test fun restartIsMode3() {
        assertEquals("0e123456789abcdef0$pad", hex(RingEncoder.makeDeviceModeCommand(JringDeviceMode.RESTART)))
    }

    @Test fun powerOffIsMode2() {
        assertEquals("0efedcba9876543210$pad", hex(RingEncoder.makeDeviceModeCommand(JringDeviceMode.POWER_OFF)))
    }

    @Test fun everyFrameIsTwentyBytes() {
        JringDeviceMode.values().forEach { assertEquals(20, RingEncoder.makeDeviceModeCommand(it).size) }
    }

    @Test fun jringOffersFactoryReset() {
        assertTrue(WearableCapability.FACTORY_RESET in JringCoordinator.capabilities)
    }
}
