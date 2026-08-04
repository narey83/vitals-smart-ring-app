package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metric is what gets stored and what the ring is told, so the risk in imperial is not the
 * arithmetic but the round trip: typing a height in feet and reading it back changed.
 */
class UnitsTest {

    @Test fun `feet and inches survive the trip back to centimetres`() {
        // Every height a person is likely to enter, converted out and back.
        (140..210).forEach { cm ->
            val (feet, inches) = Units.cmToFeetInches(cm)
            val back = Units.feetInchesToCm(feet, inches)
            assertTrue("$cm cm became $feet'$inches\" and returned as $back", kotlin.math.abs(back - cm) <= 1)
        }
    }

    @Test fun `inches never reach twelve`() {
        (140..210).forEach { cm ->
            val (_, inches) = Units.cmToFeetInches(cm)
            assertTrue("$cm cm produced $inches inches", inches in 0..11)
        }
    }

    @Test fun `known heights convert as expected`() {
        assertEquals(5 to 9, Units.cmToFeetInches(175))
        assertEquals(6 to 0, Units.cmToFeetInches(183))
        assertEquals(175, Units.feetInchesToCm(5, 9))
    }

    @Test fun `weight round trips within a pound`() {
        (40..150).forEach { kg ->
            assertTrue(kotlin.math.abs(Units.lbToKg(Units.kgToLb(kg)) - kg) <= 1)
        }
        assertEquals(165, Units.kgToLb(75))
    }

    @Test fun `distance reads in whichever units are wanted`() {
        assertEquals("850 m", Units.distance(850, metric = true))
        assertEquals("1.02 km", Units.distance(1016, metric = true))
        // Short walks in miles would read 0.00, so they are given in yards.
        assertEquals("109 yd", Units.distance(100, metric = false))
        assertEquals("0.63 mi", Units.distance(1016, metric = false))
    }
}
