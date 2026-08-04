package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Steps arrive as a running total, so every figure the page shows is a subtraction. These are
 * the cases where getting that subtraction wrong would still look plausible on screen.
 */
class StepsTest {

    /** A reading of [total] steps at a wall-clock time today. */
    private fun at(hour: Int, minute: Int, total: Int) = History.Entry(
        Date(
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        ),
        "steps", total, 0
    )

    @Test fun `a day always has 24 hours to draw`() {
        assertEquals(24, Steps.hours(emptyList()).size)
        assertEquals(0, Steps.hours(emptyList()).sumOf { it.steps })
    }

    @Test fun `an hour holds the steps taken in it, not the total so far`() {
        val hours = Steps.hours(listOf(at(9, 5, 100), at(10, 5, 250), at(11, 5, 300)))
        assertEquals(100, hours[9].steps)
        assertEquals(150, hours[10].steps)
        assertEquals(50, hours[11].steps)
    }

    @Test fun `quarter hours split an hour and add back up to it`() {
        val hours = Steps.hours(
            listOf(at(9, 0, 100), at(9, 20, 180), at(9, 35, 200), at(9, 50, 260))
        )
        assertEquals(listOf(0, 15, 30, 45), hours[9].slots.map { it.minute })
        assertEquals(listOf(100, 80, 20, 60), hours[9].slots.map { it.steps })
        assertEquals(260, hours[9].steps)
        assertEquals(hours[9].steps, hours[9].slots.sumOf { it.steps })
    }

    /** The ring pushes its counter every couple of seconds; only the peak matters. */
    @Test fun `several readings in one quarter hour count once`() {
        val hours = Steps.hours(listOf(at(9, 1, 100), at(9, 5, 140), at(9, 12, 175)))
        assertEquals(175, hours[9].steps)
        assertEquals(1, hours[9].slots.size)
    }

    /** Hours the ring never reported on are drawn as zero but carry no rows. */
    @Test fun `a silent hour is zero and empty`() {
        val hours = Steps.hours(listOf(at(9, 5, 100), at(14, 5, 300)))
        assertEquals(0, hours[10].steps)
        assertEquals(emptyList<Steps.Slot>(), hours[10].slots)
        // The gap belongs to the hour that reported, not to the silence before it.
        assertEquals(200, hours[14].steps)
    }

    /** Midnight zeroes the ring's counter; that is a new day, not minus a thousand steps. */
    @Test fun `a counter that resets never reports a negative hour`() {
        val hours = Steps.hours(listOf(at(9, 5, 1000), at(10, 5, 40)))
        assertEquals(40, hours[10].steps)
        assertEquals(0, hours.count { it.steps < 0 })
    }

    /** The headline figure is the counter itself, not the sum of the differences. */
    @Test fun `the day's total is the highest count seen`() {
        val entries = listOf(at(9, 5, 100), at(12, 5, 900), at(18, 5, 2400))
        assertEquals(2400, Steps.total(entries))
        assertEquals(Steps.total(entries), Steps.hours(entries).sumOf { it.steps })
    }
}
