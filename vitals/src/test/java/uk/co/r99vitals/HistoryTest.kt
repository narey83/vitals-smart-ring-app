package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Recording is read, change, write back, and two things record at once: the activity while it
 * is open, the collector service while it is not, with their callbacks on different threads.
 *
 * These are the cases where getting that wrong costs readings rather than merely misplacing
 * them, which is what happened before the file was locked and replaced atomically.
 */
class HistoryTest {

    @get:Rule val folder = TemporaryFolder()

    private fun history() = History(folder.newFile("readings.csv"))

    /** A burst of 0 means every reading is its own row, rather than settling into the last one. */
    private fun History.add(kind: String, value: Int) = record(kind, value, burst = 0)

    @Test fun `a new reading keeps the ones already written`() {
        val history = history()
        repeat(50) { history.add("steps", it) }
        assertEquals(50, history.all().size)
        history.add("heart", 72)
        assertEquals(51, history.all().size)
    }

    @Test fun `two threads recording at once lose nothing`() {
        val history = history()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        listOf("steps", "heart").forEach { kind ->
            Thread {
                start.await()
                repeat(100) { history.add(kind, it) }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))

        val entries = history.all()
        assertEquals("readings were lost to the other thread", 200, entries.size)
        assertEquals(100, entries.count { it.kind == "steps" })
        assertEquals(100, entries.count { it.kind == "heart" })
    }

    /** The file is replaced by moving one into place, so nothing may be left lying beside it. */
    @Test fun `recording leaves no working file behind`() {
        val history = history()
        history.add("steps", 1)
        val strays = folder.root.listFiles().orEmpty().filter { it.name.endsWith(".writing") }
        assertEquals(emptyList<Any>(), strays)
    }

    @Test fun `a reading the wearer asked for stays theirs as the burst settles`() {
        val history = history()
        history.record("heart", 70, manual = true)
        // Same burst, so it replaces the first rather than adding a row.
        history.record("heart", 74, manual = false)
        val entries = history.all()
        assertEquals(1, entries.size)
        assertEquals(74, entries.first().value)
        assertTrue("the tap was forgotten as the reading settled", entries.first().manual)
    }
}
