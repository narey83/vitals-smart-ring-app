package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring never says a workout has started, so the app decides — and deciding wrongly costs
 * something either way. A false positive runs the heart sensor for nothing; a missed one loses
 * the session entirely.
 *
 * These are the cases where a plausible-looking rule gets it wrong: a day of pottering that
 * adds up to thousands of steps, a walk that pauses at a kerb, a link that drops mid-run, and
 * the ring zeroing its own counter at midnight in the middle of a session.
 */
class WorkoutDetectorTest {

    private val start = 1_700_000_000_000L

    /** The ring pushes its running total every couple of seconds; this pushes the same way. */
    private class Ring(private val detector: WorkoutDetector, from: Long) {
        var at = from; private set
        private var counter = 4_000
        private var part = 0.0
        val total get() = counter + part.toInt()
        val events = mutableListOf<WorkoutDetector.Event>()

        /** Walks for [minutes] at [pace] steps a minute, pushing the total as the ring does. */
        fun walk(minutes: Int, pace: Int) {
            repeat(minutes * 30) {
                at += 2_000
                part += pace * 2.0 / 60.0
                detector.step(at, total)?.let { events += it }
            }
        }

        /** Sits still: the pushes keep coming, the total does not move. */
        fun rest(minutes: Int) = walk(minutes, 0)

        /** Time passing with the link down, so nothing is pushed at all. */
        fun away(minutes: Int) {
            at += minutes * 60_000L
            detector.quiet(at)?.let { events += it }
        }

        /** Steps taken while nothing was listening, which arrive in the first push after it. */
        fun carryOver(steps: Int) { part += steps }

        /** Midnight: the ring zeroes its own counter and starts the total again. */
        fun reset(to: Int) { counter = to; part = 0.0 }
    }

    private fun ring(detector: WorkoutDetector = WorkoutDetector()) = Ring(detector, start)

    @Test fun `a steady walk becomes a session`() {
        val detector = WorkoutDetector()
        val ring = ring(detector).apply { walk(8, 110) }
        val started = ring.events.filterIsInstance<WorkoutDetector.Event.Started>().single()
        assertEquals(WorkoutDetector.WALK, started.sport)
        assertTrue(detector.inProgress)
    }

    /** Five minutes is the threshold; a walk to the postbox must not turn the sensor on. */
    @Test fun `a short burst of walking is not a workout`() {
        val ring = ring().apply { walk(3, 120); rest(5) }
        assertEquals(emptyList<WorkoutDetector.Event>(), ring.events)
    }

    /**
     * A day of moving about a house passes ten thousand steps without ever being exercise. The
     * rule has to be cadence rather than volume, or every day would record itself as a workout.
     */
    @Test fun `pottering about all day is never a workout`() {
        val ring = ring()
        repeat(40) { ring.walk(1, 60); ring.rest(2) }
        assertEquals(emptyList<WorkoutDetector.Event>(), ring.events)
    }

    @Test fun `a fast pace is recorded as a run`() {
        val ring = ring().apply { walk(8, 165) }
        assertEquals(WorkoutDetector.RUN, ring.events.filterIsInstance<WorkoutDetector.Event.Started>().single().sport)
    }

    /** The threshold is only believed after five minutes, but the walk began before that. */
    @Test fun `the session is backdated to when the walking began`() {
        val ring = ring().apply { walk(8, 110) }
        val started = ring.events.filterIsInstance<WorkoutDetector.Event.Started>().single()
        val believedAt = start + 5 * 60_000
        assertTrue("started at ${started.at - start}ms", started.at < believedAt)
        // Backdated to the start of the window the pace was measured across, not further.
        assertTrue(started.at >= start)
    }

    @Test fun `stopping ends the session, timed to the last step rather than the silence`() {
        val detector = WorkoutDetector()
        val ring = ring(detector)
        ring.walk(20, 110)
        val walkedUntil = ring.at
        ring.rest(5)
        val ended = ring.events.filterIsInstance<WorkoutDetector.Event.Ended>().single()
        assertEquals(walkedUntil, ended.endedAt)
        assertTrue(ended.steps > 2_000)
        assertTrue(!detector.inProgress)
    }

    /** A kerb, a road crossing, a set of lights: a session survives being interrupted. */
    @Test fun `a short pause does not end the session`() {
        val detector = WorkoutDetector()
        val ring = ring(detector).apply { walk(8, 110); rest(2); walk(8, 110) }
        assertEquals(1, ring.events.size)
        assertTrue(detector.inProgress)
    }

    /** The link drops mid-run: no more pushes arrive, and something has to notice on the clock. */
    @Test fun `a dropped link ends the session at the last step it saw`() {
        val detector = WorkoutDetector()
        val ring = ring(detector)
        ring.walk(20, 150)
        val lastSeen = ring.at
        ring.away(30)
        val ended = ring.events.filterIsInstance<WorkoutDetector.Event.Ended>().single()
        assertEquals(lastSeen, ended.endedAt)
        assertTrue(!detector.inProgress)
    }

    /**
     * The ring zeroes its own counter at midnight, so a total can go backwards mid-session. The
     * steps either side of it cannot be differenced, but the session itself is still happening.
     */
    @Test fun `the counter resetting does not end the session or count backwards`() {
        val detector = WorkoutDetector()
        val ring = ring(detector)
        ring.walk(8, 110)
        ring.reset(0)
        ring.walk(8, 110)
        assertTrue(detector.inProgress)
        assertTrue("steps were ${detector.steps}", detector.steps > 0)
        ring.rest(5)
        assertTrue(ring.events.filterIsInstance<WorkoutDetector.Event.Ended>().single().steps > 0)
    }

    /** Reconnecting after an hour hands over an hour of steps in one push, which is not a sprint. */
    @Test fun `steps taken while the link was down are not a workout`() {
        val detector = WorkoutDetector()
        val ring = ring(detector)
        ring.rest(2)
        ring.away(60)
        ring.carryOver(5_000)
        ring.walk(1, 0)
        // The catch-up push carries an hour of walking; it must not read as a minute of it.
        assertTrue(ring.events.isEmpty())
        assertTrue(!detector.inProgress)
    }

    @Test fun `finishing by hand keeps the session and stops it`() {
        val detector = WorkoutDetector()
        val ring = ring(detector).apply { walk(12, 110) }
        val ended = detector.finishNow()!!
        assertEquals(WorkoutDetector.WALK, ended.sport)
        assertTrue(ended.endedAt <= ring.at)
        assertTrue(!detector.inProgress)
        assertNull(detector.finishNow())
    }

    @Test fun `a walk that turns into a run is recorded as a run`() {
        val detector = WorkoutDetector()
        val ring = ring(detector).apply { walk(8, 110); walk(10, 170); rest(5) }
        assertEquals(WorkoutDetector.RUN, ring.events.filterIsInstance<WorkoutDetector.Event.Ended>().single().sport)
    }
}
