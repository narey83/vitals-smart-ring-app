package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two things can record at once: the activity while it is open and the collector service while
 * it is not, with their callbacks on different threads.
 *
 * These are the cases where getting that wrong costs readings rather than merely misplacing
 * them, which is what happened before the file was locked and replaced atomically.
 */
@RunWith(RobolectricTestRunner::class)
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

    /** SQLite recording no longer needs the CSV implementation's temporary rewrite file. */
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

    /**
     * The ring hands over its whole store on every connection, so the same records arrive again
     * and again. Backfilling twice must leave one day, not two.
     */
    @Test fun `the same stored records are only written down once`() {
        val history = history()
        val readings = listOf(Triple(1_700_000_000_000L, 71, 0), Triple(1_700_000_900_000L, 68, 0))
        history.backfill("heart", readings)
        history.backfill("heart", readings)
        assertEquals(2, history.all().size)
        assertEquals(listOf(71, 68), history.all().map { it.value })
    }

    /** Backfilled readings are dated when they were taken, so they arrive out of order. */
    @Test fun `a backfill leaves the day in order`() {
        val history = history()
        history.record("heart", 80)
        history.backfill("heart", listOf(Triple(1_700_000_000_000L, 71, 0)))
        val times = history.all().map { it.at.time }
        assertEquals(times.sorted(), times)
    }

    /**
     * A measurement streams a reading a second for about half a minute. What is wanted is the
     * one number the measurement settled on, not thirty rows across one minute of the chart.
     */
    @Test fun `a whole measurement is one reading`() {
        val history = history()
        listOf(93, 93, 92, 93, 94, 95, 94, 92, 91, 90, 91).forEach { history.record("heart", it) }
        val entries = history.all()
        assertEquals(1, entries.size)
        assertEquals(91, entries.single().value)
    }

    /**
     * The ring's clock can be stopped or wrong, so a backfilled record can be dated ahead of
     * now. Such a row must not swallow the readings that follow it.
     */
    @Test fun `a reading dated in the future does not absorb later ones`() {
        val history = history()
        val anHourAway = System.currentTimeMillis() + 60 * 60 * 1000
        history.backfill("heart", listOf(Triple(anHourAway, 89, 0)))
        history.record("heart", 91)
        val entries = history.all()
        assertEquals(2, entries.size)
        assertEquals(listOf(89, 91), entries.map { it.value }.sorted())
    }

    /** What happened on 15 September: a future row turned a workout into a row a reading. */
    @Test fun `readings after a future-dated one still settle into one another`() {
        val history = history()
        history.backfill("heart", listOf(Triple(System.currentTimeMillis() + 40 * 60 * 1000, 79, 0)))
        history.record("heart", 72, burst = 10_000L)
        history.record("heart", 73, burst = 10_000L)
        history.record("heart", 74, burst = 10_000L)
        assertEquals(listOf(74, 79), history.all().map { it.value }.sorted())
    }

    @Test fun `the latest reading is not one from the future`() {
        val history = history()
        history.record("heart", 70)
        history.backfill("heart", listOf(Triple(System.currentTimeMillis() + 60 * 60 * 1000, 88, 0)))
        assertEquals(70, history.latest("heart")?.value)
    }

    /** A run's readings are the run's. Stored ones from inside it stay out of the day's. */
    @Test fun `a backfill drops what the ring stored during a workout`() {
        val history = history()
        val run = 1_700_000_000_000L..(1_700_000_000_000L + 30 * 60_000L)
        history.backfill("heart", listOf(
            Triple(run.first - 60_000L, 62, 0),
            Triple(run.first + 10 * 60_000L, 151, 0),
            Triple(run.last + 60_000L, 88, 0)
        ), outside = listOf(run))
        assertEquals(listOf(62, 88), history.all().filter { it.kind == "heart" }.map { it.value })
    }

    /** Blood pressure is two numbers. A backfill that kept only the systolic would lose half. */
    @Test fun `a backfilled blood pressure keeps both halves`() {
        val history = history()
        history.backfill("pressure", listOf(Triple(1_700_000_000_000L, 116, 76)))
        val entry = history.all().single()
        assertEquals(116, entry.value)
        assertEquals(76, entry.extra)
    }

    @Test fun `no steps ever recorded has no flat run to speak of`() {
        assertEquals(null, history().stepsFlatSince())
    }

    /** A value that keeps changing is not stuck, whatever else is going on. */
    @Test fun `a climbing count has no flat run`() {
        val history = history()
        listOf(100, 140, 175).forEach { history.add("steps", it) }
        assertEquals(history.all().last().at, history.stepsFlatSince())
    }

    /** The flat run starts where the value stopped changing, not at the most recent reading. */
    @Test fun `a stuck count is flat back to where it stopped climbing`() {
        val history = history()
        listOf(100, 140, 175).forEach { history.add("steps", it) }
        val stuckFrom = history.all().last().at
        repeat(3) { history.add("steps", 175) }
        assertEquals(stuckFrom, history.stepsFlatSince())
    }

    /** The collector and the open app both hear the same spell; it is one row, not two. */
    @Test fun `a charging spell is written down once however often it is heard`() {
        val history = history()
        assertTrue(history.charging(true, at = 1_000L))
        assertFalse(history.charging(true, at = 2_000L))
        assertTrue(history.charging(false, at = 3_000L))
        assertEquals(listOf(1, 0), history.all().filter { it.kind == "charging" }.map { it.value })
    }

    @Test fun `a ring is on the charger only between going on and coming off`() {
        val history = history()
        history.charging(true, at = 10_000L)
        history.charging(false, at = 20_000L)
        assertFalse(history.chargingAt(5_000L))
        assertTrue(history.chargingAt(10_000L))
        assertTrue(history.chargingAt(19_999L))
        assertFalse(history.chargingAt(20_000L))
    }

    /** The ring goes on measuring in its case; what it stored there is not the wearer's. */
    @Test fun `a backfill drops what the ring stored while it was charging`() {
        val history = history()
        val on = 1_700_000_000_000L
        val off = on + 60 * 60 * 1000L
        history.charging(true, at = on)
        history.charging(false, at = off)
        history.backfill("heart", listOf(
            Triple(on - 10 * 60 * 1000L, 64, 0),
            Triple(on + 30 * 60 * 1000L, 118, 0),
            Triple(off + 10 * 60 * 1000L, 70, 0)
        ))
        assertEquals(listOf(64, 70), history.all().filter { it.kind == "heart" }.map { it.value })
    }
}
