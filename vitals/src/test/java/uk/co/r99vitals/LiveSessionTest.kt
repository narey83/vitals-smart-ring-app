package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The session in flight is read by a screen that did not write it, and by a collector that may
 * have been restarted since it began, so it has to come back exactly as it was put down.
 */
class LiveSessionTest {

    @get:Rule val folder = TemporaryFolder()

    private fun session(file: File = File(folder.root, "session.txt")) = LiveSession(file)

    private val start = 1_700_000_000_000L

    @Test fun `nothing is running until a session begins`() {
        assertNull(session().read())
        assertEquals(emptyList<Int>(), session().beats())
    }

    @Test fun `a session and its curve come back as written`() {
        val file = File(folder.root, "session.txt")
        session(file).apply {
            begin("Ride", start, detected = false)
            beat(110); beat(124); beat(131)
        }
        // A fresh reader, as a restarted collector or the screen would be.
        val again = session(file)
        assertEquals(LiveSession.Now("Ride", start, detected = false), again.read())
        assertEquals(listOf(110, 124, 131), again.beats())
    }

    @Test fun `each reading keeps when it was taken, through a rename`() {
        val live = session()
        live.begin("Walk", start, detected = true)
        live.beat(100, start + 5_000); live.beat(104, start + 10_000)
        live.rename("Run")
        assertEquals(listOf(100, 104), live.beats())
        assertEquals(listOf(start + 5_000, start + 10_000), live.beatTimes())
    }

    /** A session in flight when the app updated holds readings written without their time. */
    @Test fun `readings written before they carried a time still read`() {
        val file = File(folder.root, "session.txt")
        file.writeText("Run,$start,0\n120\n125,${start + 5_000}\n")
        val live = session(file)
        assertEquals(listOf(120, 125), live.beats())
        assertEquals(listOf(0L, start + 5_000), live.beatTimes())
    }

    @Test fun `beginning again forgets the last session's readings`() {
        val live = session()
        live.begin("Walk", start, detected = true)
        live.beat(95)
        live.begin("Run", start + 60_000, detected = false)
        assertEquals(LiveSession.Now("Run", start + 60_000, detected = false), live.read())
        assertEquals(emptyList<Int>(), live.beats())
    }

    @Test fun `a walk that becomes a run keeps its readings`() {
        val live = session()
        live.begin("Walk", start, detected = true)
        live.beat(100); live.beat(138)
        live.rename("Run")
        assertEquals(LiveSession.Now("Run", start, detected = true), live.read())
        assertEquals(listOf(100, 138), live.beats())
    }

    @Test fun `a reading with no session to belong to is dropped`() {
        val live = session()
        live.beat(120)
        assertNull(live.read())
        assertEquals(emptyList<Int>(), live.beats())
    }

    @Test fun `clearing ends the session`() {
        val live = session()
        live.begin("Yoga", start, detected = false)
        live.beat(70)
        live.clear()
        assertNull(live.read())
        assertEquals(emptyList<Int>(), live.beats())
    }
}
