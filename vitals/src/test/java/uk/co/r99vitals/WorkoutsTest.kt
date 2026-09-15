package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sessions outlive the app that wrote them, so the file has to be readable by a version that
 * did not write it. These cover the two directions that matter: rows written before workouts
 * were ever detected, and a detected row corrected afterwards by the wearer.
 */
class WorkoutsTest {

    @get:Rule val folder = TemporaryFolder()

    private fun workouts() = Workouts(folder.newFile("workouts.csv"))

    private val start = 1_700_000_000_000L

    @Test fun `a session comes back as it was written`() {
        val workouts = workouts()
        workouts.save("Run", start, listOf(120, 140, 155), endedAt = start + 30 * 60_000)
        val session = workouts.all().single()
        assertEquals("Run", session.sport)
        assertEquals(30, session.minutes)
        assertEquals(listOf(120, 140, 155), session.beats)
        assertEquals(155, session.high)
        assertEquals(120, session.low)
        assertTrue(!session.detected)
    }

    /** The end is passed in, because a detected one is only noticed once it has been quiet. */
    @Test fun `a session is as long as it was, not as long as the app took to notice`() {
        val workouts = workouts()
        workouts.save("Walk", start, listOf(90), endedAt = start + 22 * 60_000, detected = true, steps = 2_400)
        val session = workouts.all().single()
        assertEquals(22, session.minutes)
        assertEquals(2_400, session.steps)
        assertTrue(session.detected)
    }

    /** A workout with no readings at all is nothing — unless the steps are the record. */
    @Test fun `an empty session is not written down, but a detected one is`() {
        val workouts = workouts()
        workouts.save("Walk", start, emptyList())
        assertEquals(emptyList<Workouts.Session>(), workouts.all())
        workouts.save("Walk", start, emptyList(), detected = true, steps = 1_800)
        assertEquals(1_800, workouts.all().single().steps)
    }

    @Test fun `correcting the sport leaves everything else alone`() {
        val workouts = workouts()
        workouts.save("Walk", start, listOf(100, 110), endedAt = start + 20 * 60_000, detected = true, steps = 2_000)
        workouts.save("Run", start + 60 * 60_000, listOf(150), endedAt = start + 90 * 60_000)
        workouts.relabel(start, "Ride")
        val sessions = workouts.all()
        assertEquals(listOf("Ride", "Run"), sessions.map { it.sport })
        assertEquals(listOf(100, 110), sessions[0].beats)
        assertEquals(2_000, sessions[0].steps)
        // How it came to be recorded stays true whatever it ends up being called.
        assertTrue(sessions[0].detected)
    }

    @Test fun `a session that went somewhere keeps its distance and moving time`() {
        val workouts = workouts()
        workouts.save("Run", start, listOf(140, 150), endedAt = start + 30 * 60_000, metres = 5_120, movingSeconds = 1_710)
        val session = workouts.all().single()
        assertEquals(5_120, session.metres)
        assertEquals(1_710, session.movingSeconds)
        assertEquals(5_120.0 / 1_710, session.speed!!, 1e-9)
    }

    /** A ring off the finger on a ride gives no heart rate, and the ride still happened. */
    @Test fun `a route alone is enough to keep a session`() {
        val workouts = workouts()
        assertTrue(workouts.save("Ride", start, emptyList(), metres = 12_400, movingSeconds = 2_400))
        assertFalse(workouts.save("Yoga", start + 1, emptyList()))
        assertEquals(listOf("Ride"), workouts.all().map { it.sport })
    }

    @Test fun `a session keeps when each reading was taken`() {
        val workouts = workouts()
        workouts.save("Run", start, listOf(120, 140, 150), beatTimes = listOf(start + 5_000, start + 65_000, start + 125_000))
        assertEquals(listOf(5, 65, 125), workouts.all().single().beatSeconds)
    }

    /** Laid out in time or not at all: one reading without a time would shift every one after it. */
    @Test fun `readings not all timed are kept without times`() {
        val workouts = workouts()
        workouts.save("Run", start, listOf(120, 140), beatTimes = listOf(0L, start + 5_000))
        workouts.save("Walk", start + 1, listOf(90, 95), beatTimes = listOf(start + 5_000))
        assertEquals(listOf(emptyList<Int>(), emptyList()), workouts.all().map { it.beatSeconds })
    }

    @Test fun `correcting the sport keeps the distance`() {
        val workouts = workouts()
        workouts.save("Walk", start, listOf(100), metres = 2_000, movingSeconds = 1_500)
        workouts.relabel(start, "Run")
        assertEquals(2_000, workouts.all().single().metres)
    }

    /** Rows written before sessions could be detected have four fields, and still read. */
    @Test fun `sessions written by an older version still open`() {
        val file = folder.newFile("old.csv")
        file.writeText("$start,Yoga,45,70 72 74\n")
        val session = Workouts(file).all().single()
        assertEquals("Yoga", session.sport)
        assertEquals(45, session.minutes)
        assertEquals(listOf(70, 72, 74), session.beats)
        assertTrue(!session.detected)
        assertEquals(0, session.steps)
        assertEquals(0, session.metres)
        assertNull(session.speed)
    }
}
