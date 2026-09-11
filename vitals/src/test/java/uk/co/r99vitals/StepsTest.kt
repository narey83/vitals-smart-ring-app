package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** On a day without a reset the headline figure is simply the counter, and the bars add up to it. */
    @Test fun `the day's total is the highest count seen`() {
        val entries = listOf(at(9, 5, 100), at(12, 5, 900), at(18, 5, 2400))
        assertEquals(2400, Steps.total(entries))
        assertEquals(Steps.total(entries), Steps.hours(entries).sumOf { it.steps })
    }

    /**
     * A ring whose clock never rolls over never zeroes the counter either, so today's first
     * reading is still yesterday's leftover total, not a burst of steps taken at 09:05. Only the
     * amount past the baseline is today's.
     */
    @Test fun `a counter carried over from before today only counts what is past the baseline`() {
        val entries = listOf(at(9, 5, 1451), at(12, 5, 1451), at(18, 5, 1500))
        assertEquals(49, Steps.total(entries, baseline = 1451))
        val hours = Steps.hours(entries, baseline = 1451)
        assertEquals(0, hours[9].steps)
        assertEquals(49, hours[18].steps)
        assertEquals(49, hours.sumOf { it.steps })
    }

    /** A real reset during the day still zeroes out, even with a baseline from before today. */
    @Test fun `a genuine reset still starts today from zero, baseline or not`() {
        val entries = listOf(at(9, 5, 1451), at(12, 5, 40))
        assertEquals(40, Steps.hours(entries, baseline = 1451)[12].steps)
        assertEquals(0, Steps.hours(entries, baseline = 1451).count { it.steps < 0 })
        assertEquals(40, Steps.total(entries, baseline = 1451))
    }

    /**
     * The ring's midnight is 00:00 UTC, so in British summer time it resets at 01:00 and the
     * day's first hour is still yesterday's total. That early reading must not outscore the
     * day's walking: measured as highest-minus-baseline, this read 7 steps all day, and the tile
     * showed nothing until the wearer had out-walked yesterday. The shape is from a real phone:
     * 6,593 at bedtime, reset between 00:57 and 00:59.
     */
    @Test fun `a day the ring resets an hour into still counts what was walked after it`() {
        val entries = listOf(at(0, 30, 6593), at(0, 57, 6600), at(0, 59, 0), at(9, 0, 1200), at(18, 0, 3000))
        assertEquals(3007, Steps.total(entries, baseline = 6593))
        val hours = Steps.hours(entries, baseline = 6593)
        assertEquals(7, hours[0].steps)
        assertEquals(1200, hours[9].steps)
        assertEquals(1800, hours[18].steps)
        assertEquals(Steps.total(entries, baseline = 6593), hours.sumOf { it.steps })
    }

    /**
     * A reset inside a quarter hour is seen reading by reading. Taking only each quarter's peak
     * hid it behind the pre-reset total, and everything up to that total went uncounted.
     */
    @Test fun `a reset in the middle of a quarter hour is still seen`() {
        val entries = listOf(at(0, 50, 873), at(0, 58, 5), at(9, 0, 2000))
        assertEquals(2003, Steps.total(entries, baseline = 870))
        assertEquals(1995, Steps.hours(entries, baseline = 870)[9].steps)
    }

    /** A gap in the counter is said out loud, rather than passing for an afternoon sat down. */
    @Test fun `the page says when the ring went quiet, and only then`() {
        val now = at(14, 0, 0).at.time
        assertEquals(null, Steps.silence(at(13, 50, 0).at.time, now))
        assertEquals(null, Steps.silence(null, now))
        val note = Steps.silence(at(12, 30, 0).at.time, now)!!
        assertTrue(note, note.startsWith("Nothing from the ring since 12:30."))
        val yesterday = at(23, 42, 0).at.time - 24 * 60 * 60 * 1000L
        assertTrue(Steps.silence(yesterday, now)!!.startsWith("Nothing from the ring since 23:42 on "))
    }

    /** Calories are a running total too, kept alongside steps and reset with them. */
    @Test fun `calories are counted across a reset the same way`() {
        fun cal(hour: Int, minute: Int, total: Int, kcal: Int) = at(hour, minute, total).copy(extra = kcal)
        val entries = listOf(cal(0, 30, 6593, 267), cal(0, 59, 0, 0), cal(18, 0, 3000, 120))
        assertEquals(120, Steps.calories(entries, baseline = 267))
    }
}
