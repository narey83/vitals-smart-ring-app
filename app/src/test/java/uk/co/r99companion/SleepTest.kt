package uk.co.r99companion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The night below is the first sleep record this ring handed over, on 5 August 2026, in the two
 * frames it arrived in — payloads only, as [SleepFrames] sees them.
 *
 * The record carries its totals twice: once as the ring's own header figures, once as the stage
 * entries. They are parsed by separate code here, so agreeing is what proves both the entry
 * layout and the stage codes rather than a parser agreeing with itself.
 */
class SleepTest {

    private fun payload(hex: String) = hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val first = payload(
        "AF FA DC 00 24 62 05 32 9F AA 05 32 FF FF CA 12 49 0A 60 2B F2 24 62 05 32 " +
            "7A 05 00 F1 9F 67 05 32 A0 01 00 F3 40 69 05 32 BC 01 00 F2 FD 6A 05 32 24 04 00 F1 " +
            "22 6F 05 32 2D 03 00 F3 4F 72 05 32 BD 02 00 F2 0C 75 05 32 74 02 00 F1 80 77 05 32 " +
            "B3 01 00 F3 33 79 05 32 61 00 00 F2 94 79 05 32 FF 04 00 F1 94 7E 05 32 6E 01 00 F3 " +
            "03 80 05 32 8C 01 00 F2 90 81 05 32 15 03 00 F1 A6 84 05 32 5B 02 00 F2 01 87 05 32 " +
            "0A 02 00 F3 0B 89 05 32 D8 02 00 F2 E3 8B 05 32 81 01 00 F3 64 8D 05 32 87 01 00 F2 " +
            "EB 8E 05 32 A1 03 00 F3 8C 92 05"
    )

    private val second = payload(
        "32 E8 00 00 F2 74 93 05 32 B5 06 00 F3 29 9A 05 32 DE 06 00 F2 07 A1 05 32 " +
            "F7 08 00 F3 FE A9 05 32 3F 00 00 F2 3D AA 05 32 62 00 00"
    )

    private fun theNight(): SleepNight {
        val frames = SleepFrames()
        assertEquals("half a record is not a night", emptyList<SleepNight>(), frames.accept(first))
        return frames.accept(second).single()
    }

    @Test fun `a night split across two frames is read back whole`() {
        val night = theNight()
        assertEquals(25, night.stages.size)
        // 2026-08-05 02:53:24 UTC to 08:02:39 UTC, the span in the record's own header.
        assertEquals(1_785_898_404_000L, night.startedAt)
        assertEquals(1_785_916_959_000L, night.endedAt)
    }

    @Test fun `the stage entries add up to the header the ring wrote`() {
        val night = theNight()
        fun summed(code: Int) = night.stages.filter { it.code == code }.sumOf { it.seconds }
        assertEquals(night.deepSeconds, summed(0xF1))
        assertEquals(night.lightSeconds, summed(0xF2))
        assertEquals(night.remSeconds, summed(0xF3))
        assertEquals(2633, night.deepSeconds)
        assertEquals(11104, night.lightSeconds)
        assertEquals(4810, night.remSeconds)
    }

    @Test fun `the names follow the totals`() {
        assertEquals(listOf("deep", "light", "REM", "awake"), listOf(0xF1, 0xF2, 0xF3, 0xF4).map { sleepStageName(it) })
    }

    /** Rubbish must not be read as a night: a size that is not header plus whole entries stops it. */
    @Test fun `a payload that is not a record reads as nothing`() {
        assertEquals(emptyList<SleepNight>(), SleepFrames().accept(ByteArray(40) { 0x55 }))
    }
}
