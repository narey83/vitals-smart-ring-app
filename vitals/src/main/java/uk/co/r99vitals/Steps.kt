package uk.co.r99vitals

import java.util.Calendar

/**
 * A day of step readings, cut into hours and quarter hours.
 *
 * The ring counts steps as a running total since its own midnight, not as an amount per period,
 * so every figure here is a difference between two totals rather than a reading in its own
 * right. That is the whole job: turn a climbing number into "how many steps in that hour".
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

    /** A day so far, as the tile and the notification show it. */
    data class Today(val steps: Int, val calories: Int)

    /**
     * Every hour of the day, zeros included, so the chart always has 24 bars to draw.
     *
     * Each reading adds what the counter rose by since the one before it, and a quarter hour
     * holds the sum of what its readings added. [baseline] is the counter's last total before
     * the day began, which the first reading of the day is measured from — see [rises].
     */
    fun hours(entries: List<History.Entry>, baseline: Int = 0): List<Hour> {
        val readings = steps(entries)
        val added = rises(readings.map { it.value }, baseline)
        // What was walked in each quarter hour, or null where the ring said nothing.
        val sums = arrayOfNulls<Int>(BUCKETS)
        val when_ = Calendar.getInstance()
        readings.forEachIndexed { i, entry ->
            when_.time = entry.at
            val bucket = when_.get(Calendar.HOUR_OF_DAY) * PER_HOUR + when_.get(Calendar.MINUTE) / QUARTER
            sums[bucket] = (sums[bucket] ?: 0) + added[i]
        }
        val slots = sums.mapIndexed { bucket, sum ->
            sum?.let { Slot(bucket / PER_HOUR, (bucket % PER_HOUR) * QUARTER, it) }
        }

        return (0 until 24).map { hour ->
            val inHour = slots.subList(hour * PER_HOUR, hour * PER_HOUR + PER_HOUR).filterNotNull()
            Hour(hour, inHour.sumOf { it.steps }, inHour)
        }
    }

    /**
     * The day's total: everything the counter rose by across it, resets included.
     *
     * Not the highest count seen minus [baseline]. The ring's midnight is 00:00 UTC, an hour
     * into a British summer day, so the day's first readings are still yesterday's total and
     * would outscore everything walked after the reset until the wearer had matched yesterday.
     */
    fun total(entries: List<History.Entry>, baseline: Int = 0): Int =
        rises(steps(entries).map { it.value }, baseline).sum()

    /** Calories, which the ring keeps as a running total alongside steps and resets with them. */
    fun calories(entries: List<History.Entry>, baseline: Int = 0): Int =
        rises(steps(entries).map { it.extra }, baseline).sum()

    /** Today's steps and calories so far, from what [history] holds. */
    fun today(history: History): Today {
        val start = History.startOfToday()
        val before = history.latestBefore("steps", start)
        val entries = history.between("steps", start)
        return Today(total(entries, before?.value ?: 0), calories(entries, before?.extra ?: 0))
    }

    /**
     * A step count older than this means the ring has not been heard from, not that nobody
     * walked: it pushes its counter every couple of seconds while connected, moving or not.
     */
    const val QUIET = 15 * 60_000L

    /**
     * What the Steps page says when the ring has gone quiet, or null while it has not.
     *
     * A gap otherwise reads as an afternoon spent sitting down, and then as a burst of walking
     * in the quarter hour the ring came back — the ring keeps no step history, so that is where
     * everything walked in between has to land.
     */
    fun silence(lastHeard: Long?, now: Long): String? {
        if (lastHeard == null || now - lastHeard < QUIET) return null
        val heard = Calendar.getInstance().apply { timeInMillis = lastHeard }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = heard.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            heard.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val time = "%02d:%02d".format(heard.get(Calendar.HOUR_OF_DAY), heard.get(Calendar.MINUTE))
        val whenHeard = if (sameDay) time
            else "$time on ${heard.get(Calendar.DAY_OF_MONTH)} ${heard.getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.UK)}"
        return "Nothing from the ring since $whenHeard. Steps taken since are counted once it " +
            "reconnects, all in the quarter hour it comes back."
    }

    private fun steps(entries: List<History.Entry>) = entries.filter { it.kind == "steps" }.sortedBy { it.at }

    /**
     * What a running total rose by at each reading.
     *
     * Measured from [baseline] rather than zero, because the ring does not reset at the phone's
     * midnight: it keeps climbing from yesterday's total until its own, or indefinitely on a ring
     * whose clock has stopped — see PROTOCOL.md. A total that has gone backwards is the ring
     * resetting — its midnight, or a clock write — so the new total is all steps taken since.
     *
     * A reset nobody was connected to see, followed by more steps than the total it wiped, is
     * the one case this misses: it reads as a rise and undercounts by the old total.
     */
    private fun rises(totals: List<Int>, baseline: Int): List<Int> {
        var carried = baseline
        return totals.map { total -> (if (total >= carried) total - carried else total).also { carried = total } }
    }
}
