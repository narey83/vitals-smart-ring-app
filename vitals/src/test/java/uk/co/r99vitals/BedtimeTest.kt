package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The two nudges: when the bedtime reminder should land, and whether last night is still news.
 *
 * Both are pure arithmetic on a clock, which is exactly the part that goes wrong quietly — a
 * reminder booked for a time that has already passed simply never fires, and a report keyed to
 * the wrong window either repeats all day or never appears.
 */
class BedtimeTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun readable(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }
        .let { "%d %02d:%02d".format(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

    private val plan = SleepPlan(bedtime = 23 * 60, wake = 7 * 60, remind = true, report = true)

    @Test fun `the reminder lands the stated time before bedtime`() {
        assertEquals("6 22:30", readable(Bedtime.nextReminder(plan, at(6, 18))))
    }

    @Test fun `a reminder whose moment has passed is booked for tomorrow`() {
        assertEquals("7 22:30", readable(Bedtime.nextReminder(plan, at(6, 23, 5))))
    }

    /** Bedtime just after midnight puts the reminder before midnight, not at a negative hour. */
    @Test fun `a bedtime past midnight still has a reminder on the clock`() {
        val nightOwl = plan.copy(bedtime = 15)
        assertEquals("6 23:45", readable(Bedtime.nextReminder(nightOwl, at(6, 18))))
    }

    @Test fun `the target is the hours between the two, across midnight`() {
        assertEquals(8 * 3600, plan.target)
        assertEquals(7 * 3600 + 30 * 60, SleepPlan(bedtime = 23 * 60 + 30, wake = 7 * 60).target)
        assertEquals(6 * 3600, SleepPlan(bedtime = 60, wake = 7 * 60).target)
    }

    /** A night the wearer's own hours call short must score lower than one they call enough. */
    @Test fun `the score follows the wearer's target rather than a fixed seven hours`() {
        val night = Sleep.Night(
            at(6, 0),
            listOf(
                Sleep.Stage(at(6, 0), Sleep.LIGHT, 4 * 3600),
                Sleep.Stage(at(6, 4), Sleep.DEEP, 3600),
                Sleep.Stage(at(6, 5), Sleep.REM, 5400)
            )
        )
        val againstSix = SleepInsight.score(night, SleepPlan(bedtime = 60, wake = 7 * 60).target).first
        val againstNine = SleepInsight.score(night, SleepPlan(bedtime = 22 * 60, wake = 7 * 60).target).first
        assertTrue("six-hour target should be kinder: $againstSix vs $againstNine", againstSix > againstNine)
    }

    /** A target nobody could mean is ignored rather than scoring every night at nothing. */
    @Test fun `an absurd target falls back to the usual seven hours`() {
        val night = Sleep.Night(at(6, 0), listOf(Sleep.Stage(at(6, 0), Sleep.LIGHT, 7 * 3600)))
        assertEquals(
            SleepInsight.score(night, SleepInsight.TARGET_ASLEEP).first,
            SleepInsight.score(night, target = 30).first
        )
    }

    private fun night(endedAt: Long, minutes: Int) = Sleep.Night(
        endedAt - minutes * 60_000L,
        listOf(Sleep.Stage(endedAt - minutes * 60_000L, Sleep.LIGHT, minutes * 60))
    )

    @Test fun `this morning's night is worth reporting once`() {
        val slept = night(at(6, 7), 400)
        assertTrue(SleepReport.due(plan, slept, at(6, 7, 20), alreadyReported = 0L))
        assertFalse(
            "a night already reported must not be reported again",
            SleepReport.due(plan, slept, at(6, 9), alreadyReported = slept.startedAt)
        )
    }

    @Test fun `yesterday's night is not this morning's news`() {
        assertFalse(SleepReport.due(plan, night(at(5, 7), 400), at(6, 9), alreadyReported = 0L))
    }

    @Test fun `a scrap of a night is not reported at all`() {
        assertFalse(SleepReport.due(plan, night(at(6, 5), 32), at(6, 8), alreadyReported = 0L))
    }

    @Test fun `nothing is sent when the wearer has not asked for it`() {
        assertFalse(SleepReport.due(plan.copy(report = false), night(at(6, 7), 400), at(6, 8), 0L))
        assertFalse(SleepReport.due(plan, null, at(6, 8), 0L))
    }

    @Test fun `both nudges are off until they are switched on`() {
        assertFalse(SleepPlan().remind)
        assertFalse(SleepPlan().report)
    }
}
