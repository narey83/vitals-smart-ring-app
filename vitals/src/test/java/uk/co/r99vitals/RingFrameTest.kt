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

    @Test fun `phone side monitor policy rejects optical results the firmware still sends`() {
        val heartOnly = Ring.Monitors(heart = true, oxygen = false, pressure = false)
        assertEquals(true, heartOnly.allows(Ring.Reading.Heart(72)))
        assertEquals(false, heartOnly.allows(Ring.Reading.Oxygen(98)))
        assertEquals(false, heartOnly.allows(Ring.Reading.Pressure(120, 80)))
        assertEquals(true, heartOnly.allows(Ring.Reading.Motion(1, 1, 1)))
    }

    @Test fun `asking for stored heart rates is the frame the ring answered`() {
        assertEquals("05 06 06 00 83 20", Ring.storedHeart().hex())
    }

    /**
     * Timestamps are little endian seconds counted from 2000, not from the epoch: a record read
     * as epoch seconds would land thirty years early and never appear on the day it belongs to.
     */
    @Test fun `a stored record decodes to the moment it was taken`() {
        val frame = Ring.frame(
            0x05, 0x15,
            byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x00, 0x52)
        )
        assertEquals(listOf(Triple(963_593_860_000L, 82, 0)), Ring.readStoredHeart(frame))
    }

    /** The ring's store is fixed size, so unused slots come back as zero rather than absent. */
    @Test fun `empty slots in the ring's store are not readings`() {
        val frame = Ring.frame(
            0x05, 0x15,
            byteArrayOf(
                0x04, 0x03, 0x02, 0x01, 0x00, 0x52,
                0x05, 0x03, 0x02, 0x01, 0x00, 0x00
            )
        )
        assertEquals(listOf(Triple(963_593_860_000L, 82, 0)), Ring.readStoredHeart(frame))
    }

    /** Live readings share the group. Decoding one as history would date it to the year 2000. */
    @Test fun `a live heart frame is not mistaken for stored history`() {
        assertEquals(emptyList<Triple<Long, Int, Int>>(), Ring.readStoredHeart(Ring.frame(0x06, 0x01, byteArrayOf(0x52))))
    }

    /**
     * The two `04 0E` completions seen on hardware: `00 01` on a finger, `00 02` off it. The
     * result byte is the ring's only on-finger signal over BLE — see PROTOCOL.md — so a reader
     * that dropped it, as this one once did, would lose the one thing that tells them apart.
     */
    @Test fun `a measurement that ran reads as worn`() {
        val finished = Ring.read(Ring.frame(0x04, 0x0E, byteArrayOf(0x00, Ring.MEASURE_OK.toByte())))
        assertEquals(Ring.Reading.Finished(type = 0x00, result = Ring.MEASURE_OK), finished)
        assertEquals(false, (finished as Ring.Reading.Finished).notWorn)
    }

    @Test fun `a measurement refused for want of a finger reads as not worn`() {
        val finished = Ring.read(Ring.frame(0x04, 0x0E, byteArrayOf(0x00, Ring.MEASURE_NOT_WORN.toByte())))
        assertEquals(true, (finished as Ring.Reading.Finished).notWorn)
    }

    /**
     * Captured from the ring, and decoded here to what the debugger displayed for the same
     * bytes: `2026-08-04 00:49:20  116/76` and `04:36:32  117/78`. Both halves come from one
     * record, so a shifted offset would read a plausible pressure with the wrong diastolic.
     */
    @Test fun `stored blood pressure matches the capture`() {
        val frame = Ring.frame(
            0x05, 0x17,
            byteArrayOf(
                0x80.toByte(), 0xE5.toByte(), 0x03, 0x32, 0x00, 0x74, 0x4C, 0x4E,
                0xC0.toByte(), 0x1A, 0x04, 0x32, 0x00, 0x75, 0x4E, 0x56
            )
        )
        assertEquals(
            listOf(Triple(1_785_800_960_000L, 116, 76), Triple(1_785_814_592_000L, 117, 78)),
            Ring.readStoredPressure(frame)
        )
    }

    /**
     * Also captured. Blood oxygen arrives inside the twenty-byte comprehensive record, where
     * every other field is a feature this ring does not have and reads as zero — so the tenth
     * byte is the whole reading, and finding it is the entire trick.
     */
    @Test fun `stored blood oxygen matches the capture`() {
        val frame = Ring.frame(
            0x05, 0x18,
            byteArrayOf(
                0x80.toByte(), 0xE5.toByte(), 0x03, 0x32, 0x00, 0x00, 0x00, 0x00, 0x00, 0x63,
                0x00, 0x00, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x55, 0x32
            )
        )
        assertEquals(listOf(Triple(1_785_800_960_000L, 99, 0)), Ring.readStoredOxygen(frame))
    }

    /** Three replies on one channel, each carrying a different record width. */
    @Test fun `each stored reply is read only by its own reader`() {
        val pressure = Ring.frame(0x05, 0x17, ByteArray(8) { 0x40 })
        assertEquals(emptyList<Triple<Long, Int, Int>>(), Ring.readStoredHeart(pressure))
        assertEquals(emptyList<Triple<Long, Int, Int>>(), Ring.readStoredOxygen(pressure))
        assertEquals(1, Ring.readStoredPressure(pressure).size)
    }

    /** SettingTime, and the UTC moment it carries — see PROTOCOL.md's "Send UTC, not local time". */
    @Test fun `setClock frames as SettingTime with the current UTC moment`() {
        val now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val frame = Ring.setClock()
        assertEquals(0x01, frame[0].toInt() and 0xFF)
        assertEquals(0x00, frame[1].toInt() and 0xFF)
        val payload = frame.copyOfRange(4, frame.size - 2)
        val year = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        assertEquals(now.get(java.util.Calendar.YEAR), year)
        assertEquals(now.get(java.util.Calendar.MONTH) + 1, payload[2].toInt() and 0xFF)
        assertEquals(now.get(java.util.Calendar.DAY_OF_MONTH), payload[3].toInt() and 0xFF)
    }

    /** A few minutes either side is normal radio-and-scheduling slop, not a stopped clock. */
    @Test fun `clockLooksStopped ignores ordinary drift`() {
        assertEquals(false, Ring.clockLooksStopped(listOf(System.currentTimeMillis() - 60_000)))
    }

    /** This is the actual failure mode: a factory reset left the RTC days behind reality. */
    @Test fun `clockLooksStopped catches a ring stuck days behind the phone`() {
        assertEquals(true, Ring.clockLooksStopped(listOf(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)))
    }

    @Test fun `clockLooksStopped catches a ring clock in the future`() {
        assertEquals(true, Ring.clockLooksStopped(listOf(System.currentTimeMillis() + 60 * 60 * 1000L)))
    }

    @Test fun `clockLooksStopped has nothing to judge with no timestamps`() {
        assertEquals(false, Ring.clockLooksStopped(emptyList()))
    }
}
