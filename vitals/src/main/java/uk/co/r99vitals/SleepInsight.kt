package uk.co.r99vitals

import java.util.Calendar
import java.util.Date

/**
 * What the nights add up to: a score for one night, a week, a month, and what the pattern of them
 * suggests.
 *
 * All of it is worked out from the stored stages every time it is shown and none of it is written
 * down. The file holds only what the ring actually reported, so the judgements here — the score in
 * particular — can be changed later and the whole history re-reads under the new one. A score
 * saved into the file would freeze today's opinion into yesterday's data.
 */
object SleepInsight {

    /** Under this, a record is a scrap the ring dropped rather than a night's sleep. */
    const val FRAGMENT = 3600

    /** Records this close together are the same night, interrupted, not two nights. */
    private const val SAME_NIGHT = 3_600_000L

    /** What a night is measured against. Not medical targets — the usual advice, no more. */
    private const val TARGET_ASLEEP = 7 * 3600
    private val DEEP_BAND = 0.13f..0.23f
    private val REM_BAND = 0.20f..0.25f

    /**
     * The ring hands back scraps as well as nights — a wake at 4am can end one record and open
     * another twenty minutes later. Records separated by less than an hour are one night with a
     * gap in it, which is what a person would call it.
     */
    fun merge(nights: List<Sleep.Night>): List<Sleep.Night> {
        if (nights.isEmpty()) return emptyList()
        val sorted = nights.sortedBy { it.startedAt }
        val merged = mutableListOf(sorted.first())
        for (night in sorted.drop(1)) {
            val last = merged.last()
            if (night.startedAt - last.endedAt < SAME_NIGHT) {
                // Two records covering the same minutes are one set of minutes. Stages are put in
                // order and any that starts before the one before it has finished is dropped —
                // without that, a night handed over twice reports more sleep than time in bed.
                val stages = (last.stages + night.stages)
                    .sortedBy { it.startedAt }
                    .fold(mutableListOf<Sleep.Stage>()) { kept, stage ->
                        val busyUntil = kept.lastOrNull()?.let { it.startedAt + it.seconds * 1000L }
                        if (busyUntil == null || stage.startedAt >= busyUntil) kept += stage
                        kept
                    }
                merged[merged.lastIndex] = Sleep.Night(last.startedAt, stages)
            } else merged += night
        }
        return merged
    }

    /** A night belongs to the morning it ended, the way a night is spoken about. */
    fun day(night: Sleep.Night): Date = Calendar.getInstance().apply {
        timeInMillis = night.endedAt
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.time

    /** A scrap is shown, but never averaged: one 32-minute record would sink a month. */
    fun isFragment(night: Sleep.Night) = night.asleep < FRAGMENT

    data class Part(val label: String, val detail: String, val got: Int, val of: Int)

    /**
     * One night out of a hundred, and the four parts it came from.
     *
     * Length carries half of it because it is the half that is actually actionable; the two stage
     * shares are scored against the bands sleep is usually described in, and a broken night loses
     * the rest. Every part is shown in the app beside the total, so the number can be argued with
     * rather than believed.
     */
    fun score(night: Sleep.Night): Pair<Int, List<Part>> {
        val asleep = night.asleep
        val length = (50f * (asleep.toFloat() / TARGET_ASLEEP)).coerceIn(0f, 50f)
        val deep = band(night.seconds(Sleep.DEEP), asleep, DEEP_BAND, 20f)
        val rem = band(night.seconds(Sleep.REM), asleep, REM_BAND, 20f)
        val wakings = night.stages.count { it.code == Sleep.AWAKE }
        val unbroken = (10f - wakings * 2.5f).coerceIn(0f, 10f)
        val parts = listOf(
            Part("Length", Sleep.spell(asleep), length.toInt(), 50),
            Part("Deep", "${share(night.seconds(Sleep.DEEP), asleep)}%", deep.toInt(), 20),
            Part("REM", "${share(night.seconds(Sleep.REM), asleep)}%", rem.toInt(), 20),
            Part("Unbroken", if (wakings == 0) "no wakings" else "$wakings waking${if (wakings == 1) "" else "s"}", unbroken.toInt(), 10)
        )
        return parts.sumOf { it.got } to parts
    }

    /**
     * Where the bands sit is set by length, because length dominates the score. A night with
     * ideal proportions but only three and a half hours in it scores in the low seventies, and
     * calling that "Good" would be flattery — so "Good" starts above it.
     */
    fun verdict(score: Int) = when {
        score >= 85 -> "Excellent"
        score >= 75 -> "Good"
        score >= 60 -> "Fair"
        else -> "Poor"
    }

    /** Full marks inside the band, tailing off outside it rather than falling off a cliff. */
    private fun band(seconds: Int, asleep: Int, want: ClosedFloatingPointRange<Float>, worth: Float): Float {
        if (asleep <= 0) return 0f
        val got = seconds.toFloat() / asleep
        if (got in want) return worth
        val miss = if (got < want.start) want.start - got else got - want.endInclusive
        // A tenth of the night away from the band is worth nothing; nearer than that scales.
        return (worth * (1f - miss / 0.10f)).coerceIn(0f, worth)
    }

    private fun share(seconds: Int, asleep: Int) = if (asleep <= 0) 0 else seconds * 100 / asleep

    /** One day of the week strip: the night that ended on it, if any. */
    data class Day(val at: Date, val night: Sleep.Night?)

    /** The last seven days ending today, so the strip always has seven slots and visible gaps. */
    fun week(nights: List<Sleep.Night>, today: Date = Date()): List<Day> {
        val byDay = merge(nights).associateBy { day(it).time }
        val midnight = Calendar.getInstance().apply {
            time = today
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return (6 downTo 0).map { back ->
            val at = (midnight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -back) }.time
            Day(at, byDay[at.time])
        }
    }

    data class Month(
        val average: Int,
        val best: Sleep.Night?,
        val worst: Sleep.Night?,
        val recorded: Int,
        val ofDays: Int
    )

    /** The calendar month [within] falls in, fragments left out of everything but the count. */
    fun month(nights: List<Sleep.Night>, within: Date = Date()): Month {
        val edge = Calendar.getInstance().apply {
            time = within
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val from = edge.timeInMillis
        val to = (edge.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
        val days = Calendar.getInstance().apply { time = within }.getActualMaximum(Calendar.DAY_OF_MONTH)
        val inMonth = merge(nights).filter { day(it).time in from until to }
        val proper = inMonth.filterNot { isFragment(it) }
        return Month(
            average = if (proper.isEmpty()) 0 else proper.sumOf { it.asleep } / proper.size,
            best = proper.maxByOrNull { score(it).first },
            worst = if (proper.size > 1) proper.minByOrNull { score(it).first } else null,
            recorded = inMonth.size,
            ofDays = days
        )
    }

    /**
     * What the nights themselves suggest, and only once there are enough of them to mean anything.
     *
     * These are comparisons within the wearer's own record — earlier bedtimes against later ones,
     * and so on — not advice about their health. Below [ENOUGH] nights it says nothing at all and
     * the app shows general advice instead, which is honest about which is which.
     */
    const val ENOUGH = 5

    fun patterns(nights: List<Sleep.Night>): List<String> {
        val proper = merge(nights).filterNot { isFragment(it) }
        if (proper.size < ENOUGH) return emptyList()
        val found = mutableListOf<String>()

        // Bedtime against how long the night lasted, split at the wearer's own median rather than
        // at some hour decided here: "late" only means late for them.
        val bedtimes = proper.map { minutesPastNoon(it.startedAt) }.sorted()
        val median = bedtimes[bedtimes.size / 2]
        val early = proper.filter { minutesPastNoon(it.startedAt) <= median }
        val late = proper.filter { minutesPastNoon(it.startedAt) > median }
        if (early.isNotEmpty() && late.isNotEmpty()) {
            val gap = early.sumOf { it.asleep } / early.size - late.sumOf { it.asleep } / late.size
            if (gap >= 20 * 60) {
                found += "You sleep ${Sleep.spell(gap)} longer on the nights you turn in earlier " +
                    "(${early.size} of ${proper.size} nights)."
            }
            val deepEarly = early.sumOf { share(it.seconds(Sleep.DEEP), it.asleep) } / early.size
            val deepLate = late.sumOf { share(it.seconds(Sleep.DEEP), it.asleep) } / late.size
            if (deepEarly - deepLate >= 3) {
                found += "Your deep sleep is ${deepEarly - deepLate} points higher on those earlier nights, too."
            }
        }

        // A wandering bedtime is the thing most worth naming, and it needs no comparison group.
        val spread = bedtimes.last() - bedtimes.first()
        if (spread >= 120) {
            found += "Your bedtime moves by ${Sleep.spell(spread * 60)} across these nights. " +
                "A steadier one is the single change most likely to help."
        }

        val wakings = proper.sumOf { night -> night.stages.count { it.code == Sleep.AWAKE } } / proper.size
        if (wakings >= 2) found += "You wake about $wakings times a night on average."

        val short = proper.count { it.asleep < 6 * 3600 }
        if (short * 2 >= proper.size) {
            found += "$short of your last ${proper.size} nights came in under six hours."
        }
        return found.take(3)
    }

    /** Shown until the nights can speak for themselves. Ordinary sleep hygiene, nothing clinical. */
    val ADVICE = listOf(
        "Keep the same wake time every day, weekends included — it steadies everything else.",
        "Daylight early in the day, dim light in the evening.",
        "Caffeine has a long tail; stop by mid-afternoon.",
        "A cool, dark, quiet room beats almost any gadget."
    )

    /** Minutes since midday, so a 23:40 bedtime and a 01:10 one sort in the order they happened. */
    private fun minutesPastNoon(at: Long): Int {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (minutes >= 12 * 60) minutes - 12 * 60 else minutes + 12 * 60
    }

}
