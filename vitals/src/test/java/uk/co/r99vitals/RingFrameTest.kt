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

    @Test fun `automatic monitoring addresses every monitor`() {
        val frames = Ring.automaticMonitoring(Ring.Monitors(), 15).map { it.hex() }
        assertEquals(
            listOf(
                "01 0C 08 00 01 0F 86 87",   // heart rate, on
                "01 26 08 00 01 0F 9C C9",   // blood oxygen, on
                "01 1C 08 00 00 0F ED B0"    // blood pressure, off by default
            ),
            frames
        )
    }

    @Test fun `each interval the app offers is sent as its own minute count`() {
        val on = Ring.Monitors()
        assertEquals("01 0C 08 00 01 0F 86 87", Ring.automaticMonitoring(on, 15)[0].hex())
        assertEquals("01 0C 08 00 01 1E 96 85", Ring.automaticMonitoring(on, 30)[0].hex())
        // An hour is where a signed byte would go wrong if minutes were ever widened.
        assertEquals("01 0C 08 00 01 3C B6 81", Ring.automaticMonitoring(on, 60)[0].hex())
    }

    @Test fun `turning it off clears the flag on every monitor`() {
        val off = Ring.Monitors(heart = false, oxygen = false, pressure = false)
        assertEquals(
            listOf("00", "00", "00"),
            Ring.automaticMonitoring(off, 15).map { "%02X".format(it[4]) }
        )
    }

    /**
     * A monitor that is switched off still gets a frame. The ring keeps this setting itself, so
     * one that simply went unmentioned would stay however it was last left — which is how a
     * switch turned off in the app comes back on by itself.
     */
    @Test fun `every monitor is addressed whichever way it is set`() {
        val one = Ring.Monitors(heart = true, oxygen = false, pressure = false)
        val frames = Ring.automaticMonitoring(one, 15)
        assertEquals(listOf("0C", "26", "1C"), frames.map { "%02X".format(it[1]) })
        assertEquals(listOf("01", "00", "00"), frames.map { "%02X".format(it[4]) })
    }
}
