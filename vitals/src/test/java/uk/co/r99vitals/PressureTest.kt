package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band a reading lands in is the only thing on that page anyone acts on, so these are the
 * boundaries where being one out would still look like a sensible answer.
 */
class PressureTest {

    @Test fun `the textbook readings land where the NHS puts them`() {
        assertEquals(BpBand.Ideal, BpBand.of(115, 75))
        assertEquals(BpBand.Raised, BpBand.of(130, 78))
        assertEquals(BpBand.HighOne, BpBand.of(145, 88))
        assertEquals(BpBand.HighTwo, BpBand.of(165, 98))
        assertEquals(BpBand.Crisis, BpBand.of(185, 110))
    }

    /** A band starts at its own number: 140 is high, 139 is not. */
    @Test fun `the thresholds are inclusive`() {
        assertEquals(BpBand.Raised, BpBand.of(139, 89))
        assertEquals(BpBand.HighOne, BpBand.of(140, 85))
        assertEquals(BpBand.Ideal, BpBand.of(119, 79))
        assertEquals(BpBand.Raised, BpBand.of(120, 79))
    }

    /** Either half can carry the reading up; the worse of the two decides. */
    @Test fun `the higher half decides the band`() {
        assertEquals(BpBand.HighOne, BpBand.of(130, 95))
        assertEquals(BpBand.Crisis, BpBand.of(150, 125))
    }

    /** But either half falling short makes it a low reading, however calm the other looks. */
    @Test fun `low is judged on either half`() {
        assertEquals(BpBand.Low, BpBand.of(85, 70))
        assertEquals(BpBand.Low, BpBand.of(110, 55))
        assertEquals(BpBand.Ideal, BpBand.of(90, 60))
    }

    /** Only the bands worth a GP visit shout, or the alert stops meaning anything. */
    @Test fun `nothing below stage one raises an alert`() {
        assertEquals(
            listOf(BpBand.HighOne, BpBand.HighTwo, BpBand.Crisis),
            BpBand.entries.filter { it.alert }
        )
        assertTrue(BpBand.entries.none { it.alert && it.sys < 140 })
    }
}
