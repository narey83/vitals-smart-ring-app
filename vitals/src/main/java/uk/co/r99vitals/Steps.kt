package uk.co.r99vitals

import java.util.Calendar

/**
 * A day of step readings, cut into hours and quarter hours.
 *
 * The ring counts steps as a running total since midnight, not as an amount per period, so
 * every figure here is a difference between two totals rather than a reading in its own right.
 * That is the whole job: turn a climbing number into "how many steps in that hour".
 *
 * Quarter hours are the finest cut worth showing. The ring pushes its counter every couple of
 * seconds, which is far more often than anyone wants to read, and the automatic monitoring
 * interval the app offers is fifteen minutes, so the two line up.
 */
object Steps {

    /** Minutes in one bucket, and how many buckets make up an hour. */
    private const val QUARTER = 15
    private const val PER_HOUR = 60 / QUARTER
    private const val BUCKETS = 24 * PER_HOUR

    /** Steps taken inside one quarter hour, labelled by the minute it starts at. */
    data class Slot(val hour: Int, val minute: Int, val steps: Int)

    /** Steps taken inside one hour, with the quarter hours that made it up. */
    data class Hour(val hour: Int, val steps: Int, val slots: List<Slot>)

    /**
     * Every hour of the day, zeros included, so the chart always has 24 bars to draw.
     *
     * A bucket's total is the highest count seen in it: the counter only climbs, so its peak is
     * where it had reached by the end of that quarter hour. The difference from the last bucket
     * that held a reading is what was walked in between.
     *
     * The first reading of the day carries everything since [baseline], which is the counter's
     * value when today began — the last total the ring reported yesterday, on a ring whose clock
     * actually rolls over at midnight. Not every ring's does: one with a stopped clock never
     * zeroes the counter on its own, so without a baseline its whole leftover total would land
     * in the first bucket of every day it is read in, as if it had all been walked at once.
     */
    fun hours(entries: List<History.Entry>, baseline: Int = 0): List<Hour> {
        // The peak total seen in each quarter hour, or null where the ring said nothing.
        val peaks = arrayOfNulls<Int>(BUCKETS)
        val when_ = Calendar.getInstance()
        entries.filter { it.kind == "steps" }.forEach { entry ->
            when_.time = entry.at
            val bucket = when_.get(Calendar.HOUR_OF_DAY) * PER_HOUR + when_.get(Calendar.MINUTE) / QUARTER
            peaks[bucket] = maxOf(peaks[bucket] ?: 0, entry.value)
        }

        var carried = baseline
        val slots = peaks.mapIndexed { bucket, peak ->
            if (peak == null) null
            else {
                // A total that has gone backwards means the ring reset its day; treat the new
                // total as steps taken rather than reporting a negative hour.
                val taken = if (peak >= carried) peak - carried else peak
                carried = peak
                Slot(bucket / PER_HOUR, (bucket % PER_HOUR) * QUARTER, taken)
            }
        }

        return (0 until 24).map { hour ->
            val inHour = slots.subList(hour * PER_HOUR, hour * PER_HOUR + PER_HOUR).filterNotNull()
            Hour(hour, inHour.sumOf { it.steps }, inHour)
        }
    }

    /** The day's total, which is the last running count seen rather than a sum of differences. */
    fun total(entries: List<History.Entry>, baseline: Int = 0): Int =
        ((entries.filter { it.kind == "steps" }.maxOfOrNull { it.value } ?: baseline) - baseline)
            .coerceAtLeast(0)
}
