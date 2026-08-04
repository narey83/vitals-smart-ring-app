package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frames are the part of the ring conversation that can be checked without a ring.
 *
 * Expected bytes are not read back from [Ring]; they come from the reference CRC in PROTOCOL.md,
 * and the two golden cases below are frames the hardware itself accepted, so a builder that
 * reproduces them is building real frames rather than self-consistent ones.
 */
class RingFrameTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    /** Captured from the ring: it acknowledged this and started flashing (PROTOCOL.md). */
    @Test fun `start measuring matches the frame the ring accepted`() {
        assertEquals("03 2F 08 00 01 00 4F 1B", Ring.startMeasuring(Ring.HEART).hex())
    }

    /** A payload-free frame, from the same capture, pinning the length and CRC placement. */
    @Test fun `empty payload frame matches the capture`() {
        assertEquals("05 06 06 00 83 20", Ring.frame(0x05, 0x06).hex())
    }

    @Test fun `automatic monitoring turns on both heart and blood oxygen`() {
        val frames = Ring.automaticMonitoring(true, 15).map { it.hex() }
        assertEquals(
            listOf("01 0C 08 00 01 0F 86 87", "01 26 08 00 01 0F 9C C9"),
            frames
        )
    }

    @Test fun `each interval the app offers is sent as its own minute count`() {
        assertEquals("01 0C 08 00 01 0F 86 87", Ring.automaticMonitoring(true, 15)[0].hex())
        assertEquals("01 0C 08 00 01 1E 96 85", Ring.automaticMonitoring(true, 30)[0].hex())
        // An hour is where a signed byte would go wrong if minutes were ever widened.
        assertEquals("01 0C 08 00 01 3C B6 81", Ring.automaticMonitoring(true, 60)[0].hex())
    }

    @Test fun `turning it off clears the flag on both monitors`() {
        val frames = Ring.automaticMonitoring(false, 15).map { it.hex() }
        assertEquals(
            listOf("01 0C 08 00 00 0F B7 B4", "01 26 08 00 00 0F AD FA"),
            frames
        )
    }

    /**
     * Documents a gap rather than a guarantee: blood pressure has its own monitor command
     * (`01 1C`, settingBloodPressureMonitor) and nothing sends it, so the interval never fills
     * the pressure chart. Change this test when that changes.
     */
    @Test fun `blood pressure is not part of automatic monitoring`() {
        val commands = Ring.automaticMonitoring(true, 15).map { "%02X".format(it[1]) }
        assertEquals(listOf("0C", "26"), commands)
    }
}
