package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * The judgements: what makes one night better than another, which day a night belongs to, and
 * what the run of them is allowed to claim.
 *
 * Nights are built to order here rather than captured, because the point is the arithmetic on top
 * of them — the reading of real ring bytes is pinned in [SleepTest].
 */
class SleepInsightTest {

    /** A night beginning [at], made of the stages given as (code, minutes), back to back. */
    private fun night(at: Long, vararg stages: Pair<Int, Int>): Sleep.Night {
        var running = at
        return Sleep.Night(at, stages.map { (code, minutes) ->
            Sleep.Stage(running, code, minutes * 60).also { running += minutes * 60_000L }
        })
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun onDay(day: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, day, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.time

    /** Seven hours, well-proportioned, unbroken: what the score is measured against. */
    private fun goodNight(day: Int) = night(
        at(day - 1, 23),
        Sleep.LIGHT to 240, Sleep.DEEP to 65, Sleep.REM to 95
    )

    @Test fun `a full well-proportioned night scores near the top`() {
        val (score, parts) = SleepInsight.score(goodNight(5))
        assertTrue("expected a high score, got $score", score >= 90)
        // 6h 40m of the seven hours, so the length marks are nearly but not quite all of them.
        assertEquals(listOf(47, 20, 20, 10), parts.map { it.got })
        assertEquals("Excellent", SleepInsight.verdict(score))
    }

    @Test fun `a short night loses the length marks and only those`() {
        // Half the target, same proportions of deep and REM, still unbroken.
        val (score, parts) = SleepInsight.score(
            night(at(4, 23), Sleep.LIGHT to 120, Sleep.DEEP to 33, Sleep.REM to 47)
        )
        assertEquals(23, parts.first { it.label == "Length" }.got)
        assertEquals(20, parts.first { it.label == "Deep" }.got)
        assertEquals(10, parts.first { it.label == "Unbroken" }.got)
        // Well-proportioned but far too short: this must not read as a good night.
        assertEquals("Fair", SleepInsight.verdict(score))
    }

    @Test fun `waking repeatedly costs the unbroken marks`() {
        val broken = night(
            at(4, 23),
            Sleep.LIGHT to 120, Sleep.AWAKE to 10, Sleep.DEEP to 65,
            Sleep.AWAKE to 10, Sleep.LIGHT to 120, Sleep.AWAKE to 10, Sleep.REM to 95
        )
        assertEquals(2, SleepInsight.score(broken).second.first { it.label == "Unbroken" }.got)
        assertTrue(SleepInsight.score(broken).first < SleepInsight.score(goodNight(5)).first)
    }

    @Test fun `no deep sleep at all scores nothing for deep`() {
        val none = night(at(4, 23), Sleep.LIGHT to 300, Sleep.REM to 90)
        assertEquals(0, SleepInsight.score(none).second.first { it.label == "Deep" }.got)
    }

    @Test fun `a night belongs to the morning it ended`() {
        val overnight = night(at(4, 23), Sleep.LIGHT to 300)   // 23:00 on the 4th to 04:00 on the 5th
        assertEquals(onDay(5).date, SleepInsight.day(overnight).date)
    }

    @Test fun `records twenty minutes apart are one interrupted night`() {
        val first = night(at(4, 23), Sleep.LIGHT to 120)
        val second = night(at(5, 1, 20), Sleep.DEEP to 60, Sleep.REM to 60)
        val merged = SleepInsight.merge(listOf(second, first)).single()
        assertEquals(at(4, 23), merged.startedAt)
        assertEquals(4 * 3600, merged.asleep)
    }

    @Test fun `records a night apart stay two nights`() {
        val monday = night(at(4, 23), Sleep.LIGHT to 300)
        val tuesday = night(at(5, 23), Sleep.LIGHT to 300)
        assertEquals(2, SleepInsight.merge(listOf(monday, tuesday)).size)
    }

    @Test fun `the week keeps a slot for every day including the empty ones`() {
        val week = SleepInsight.week(listOf(goodNight(5), goodNight(3)), onDay(6))
        assertEquals(7, week.size)
        assertEquals(2, week.count { it.day != null })
        assertEquals(onDay(6).date, week.last().at.date)
    }

    @Test fun `a fragment is kept out of the month's figures but still counted`() {
        val scrap = night(at(6, 4, 50), Sleep.LIGHT to 30, Sleep.REM to 2)
        val month = SleepInsight.month(listOf(goodNight(5), scrap), onDay(6))
        assertTrue(SleepInsight.isFragment(scrap))
        assertEquals(goodNight(5).asleep, month.average)
        assertEquals(2, month.recorded)
        assertEquals(at(4, 23), month.best?.from)
    }

    @Test fun `one good night is a best and not also a worst`() {
        val month = SleepInsight.month(listOf(goodNight(5)), onDay(6))
        assertEquals(at(4, 23), month.best?.from)
        assertEquals(null, month.worst)
    }

    @Test fun `nothing is claimed about a handful of nights`() {
        assertEquals(emptyList<String>(), SleepInsight.patterns(listOf(goodNight(5), goodNight(4))))
    }

    @Test fun `an earlier bedtime that gives a longer night is noticed`() {
        val nights = listOf(
            night(at(1, 22, 30), Sleep.LIGHT to 260, Sleep.DEEP to 60, Sleep.REM to 90),
            night(at(2, 22, 40), Sleep.LIGHT to 250, Sleep.DEEP to 60, Sleep.REM to 90),
            night(at(3, 22, 50), Sleep.LIGHT to 255, Sleep.DEEP to 60, Sleep.REM to 90),
            night(at(5, 2, 30), Sleep.LIGHT to 150, Sleep.DEEP to 20, Sleep.REM to 40),
            night(at(6, 2, 40), Sleep.LIGHT to 140, Sleep.DEEP to 18, Sleep.REM to 38)
        )
        val said = SleepInsight.patterns(nights)
        assertTrue("expected something about turning in earlier, got $said",
            said.any { it.contains("earlier") })
        assertTrue("expected at most three", said.size <= 3)
    }

    /** Overlapping records must never report more sleep than the night was long. */
    @Test fun `a night that overlaps another cannot claim more sleep than time in bed`() {
        val long = night(at(4, 23), Sleep.LIGHT to 240, Sleep.DEEP to 60, Sleep.REM to 90)
        val overlapping = night(at(5, 4, 50), Sleep.LIGHT to 30, Sleep.REM to 2)
        val merged = SleepInsight.merge(listOf(long, overlapping)).single()
        assertTrue("asleep ${merged.asleep} exceeds in bed ${merged.inBed}", merged.asleep <= merged.inBed)
        assertEquals(long.endedAt, merged.endedAt)
    }

    @Test fun `the same night handed over twice is not counted twice`() {
        val once = night(at(4, 23), Sleep.LIGHT to 240, Sleep.DEEP to 60, Sleep.REM to 90)
        val merged = SleepInsight.merge(listOf(once, once)).single()
        assertEquals(once.asleep, merged.asleep)
        assertEquals(once.stages.size, merged.stages.size)
    }

    /**
     * The one that started all this: a real night came off the ring in four records, hours apart,
     * and the page showed the last of them as the whole night.
     */
    @Test fun `a night the ring recorded in four pieces is one day's sleep`() {
        val pieces = listOf(
            night(at(6, 21, 1), Sleep.LIGHT to 119, Sleep.DEEP to 53, Sleep.REM to 28),
            night(at(7, 0, 24), Sleep.LIGHT to 27, Sleep.DEEP to 5, Sleep.REM to 6),
            night(at(7, 1, 15), Sleep.LIGHT to 44, Sleep.DEEP to 15, Sleep.REM to 10),
            // Three and a half hours after the previous piece ended: awake, or the ring losing
            // the thread. Either way it is the same night's sleep.
            night(at(7, 5, 54), Sleep.LIGHT to 48, Sleep.DEEP to 29, Sleep.REM to 21, Sleep.AWAKE to 1)
        )
        val days = SleepInsight.days(pieces)
        assertEquals("all four belong to the one day", 1, days.size)
        val friday = days.single()
        assertEquals(onDay(7).date, friday.at.date)
        assertEquals(6 * 3600 + 45 * 60, friday.asleep)
        assertEquals(at(6, 21, 1), friday.from)
        // Every break between pieces counts against the night, as does the ring's own waking.
        assertTrue("expected the breaks to count as wakings", friday.wakings >= 3)
    }

    @Test fun `an afternoon nap belongs to the same day as that morning's sleep`() {
        val morning = night(at(7, 1), Sleep.LIGHT to 240, Sleep.DEEP to 60, Sleep.REM to 60)
        val nap = night(at(7, 15), Sleep.LIGHT to 40, Sleep.DEEP to 10)
        val day = SleepInsight.days(listOf(morning, nap)).single()
        assertEquals(2, day.sessions.size)
        assertEquals(6 * 3600 + 50 * 60, day.asleep)
        // Time in bed is the sessions added up, not the span: the afternoon between is not bed.
        assertEquals(day.sessions.sumOf { it.inBed }, day.inBed)
    }

    @Test fun `there is always something to show when there is nothing to say`() {
        assertTrue(SleepInsight.ADVICE.isNotEmpty())
        assertTrue(SleepInsight.ADVICE.all { it.length in 20..120 })
    }
}
