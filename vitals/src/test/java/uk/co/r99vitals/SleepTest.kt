package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The night below is not made up: it is the first sleep record this ring ever handed over, copied
 * from the debugger's log on 5 August 2026, in the two frames it arrived in. A parser that reads
 * it back correctly is reading the ring's own format rather than one invented to suit the parser.
 *
 * The check that matters is the ring's own arithmetic: its header says deep 2633 s, light 11104 s
 * and REM 4810 s, and the stage entries are a separate list. If summing the stages gives those
 * three numbers, both the entry layout and the stage codes are right.
 */
class SleepTest {

    private fun frame(hex: String) = hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val first = frame(
        "05 13 B6 00 AF FA DC 00 24 62 05 32 9F AA 05 32 FF FF CA 12 49 0A 60 2B F2 24 62 05 32 " +
            "7A 05 00 F1 9F 67 05 32 A0 01 00 F3 40 69 05 32 BC 01 00 F2 FD 6A 05 32 24 04 00 F1 " +
            "22 6F 05 32 2D 03 00 F3 4F 72 05 32 BD 02 00 F2 0C 75 05 32 74 02 00 F1 80 77 05 32 " +
            "B3 01 00 F3 33 79 05 32 61 00 00 F2 94 79 05 32 FF 04 00 F1 94 7E 05 32 6E 01 00 F3 " +
            "03 80 05 32 8C 01 00 F2 90 81 05 32 15 03 00 F1 A6 84 05 32 5B 02 00 F2 01 87 05 32 " +
            "0A 02 00 F3 0B 89 05 32 D8 02 00 F2 E3 8B 05 32 81 01 00 F3 64 8D 05 32 87 01 00 F2 " +
            "EB 8E 05 32 A1 03 00 F3 8C 92 05 7D 75"
    )

    private val second = frame(
        "05 13 32 00 32 E8 00 00 F2 74 93 05 32 B5 06 00 F3 29 9A 05 32 DE 06 00 F2 07 A1 05 32 " +
            "F7 08 00 F3 FE A9 05 32 3F 00 00 F2 3D AA 05 32 62 00 00 78 F9"
    )

    private fun theNight(): Sleep.Night {
        val reader = SleepReader()
        assertEquals("a half-read record is not a night", emptyList<Sleep.Night>(), reader.accept(first))
        return reader.accept(second).single()
    }

    @Test fun `a night split across two frames is read back whole`() {
        val night = theNight()
        assertEquals(25, night.stages.size)
        // 2026-08-05 02:53:24 UTC to 08:02:39 UTC, which is the span in the record's own header.
        assertEquals(1_785_898_404_000L, night.startedAt)
        assertEquals(1_785_916_959_000L, night.endedAt)
        assertEquals(5 * 3600 + 9 * 60 + 15, night.inBed)
    }

    @Test fun `the stages add up to the totals the ring worked out itself`() {
        val night = theNight()
        assertEquals(2633, night.seconds(Sleep.DEEP))
        assertEquals(11104, night.seconds(Sleep.LIGHT))
        assertEquals(4810, night.seconds(Sleep.REM))
        assertEquals(18547, night.asleep)
    }

    /** Frames for anything else must not be swallowed into the middle of a night. */
    @Test fun `another push is ignored rather than buffered`() {
        val reader = SleepReader()
        reader.accept(frame("05 15 0C 00 80 E5 03 32 00 54 DB C8"))
        assertEquals(25, reader.accept(first).plus(reader.accept(second)).single().stages.size)
    }

    @Test fun `a night already written down is not written twice`() {
        val file = File.createTempFile("sleep", ".csv").apply { delete() }
        val nights = Nights(file)
        nights.save(listOf(theNight()))
        val afterFirst = file.readLines().size
        nights.save(listOf(theNight()))
        assertEquals(afterFirst, file.readLines().size)
        assertEquals(25, nights.all().single().stages.size)
        assertEquals(2633, nights.all().single().seconds(Sleep.DEEP))
        file.delete()
    }

    @Test fun `nights read back in the order they happened`() {
        val file = File.createTempFile("sleep", ".csv").apply { delete() }
        val night = theNight()
        val earlier = Sleep.Night(
            night.startedAt - 86_400_000,
            listOf(Sleep.Stage(night.startedAt - 86_400_000, Sleep.LIGHT, 3600))
        )
        Nights(file).save(listOf(night, earlier))
        val all = Nights(file).all()
        assertEquals(listOf(earlier.startedAt, night.startedAt), all.map { it.startedAt })
        assertTrue(all.first().stages.single().code == Sleep.LIGHT)
        file.delete()
    }
}
