package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinkLogTest {

    @get:Rule val folder = TemporaryFolder()

    @Test fun `each note is one stamped line`() {
        val file = File(folder.root, "link-log.txt")
        val log = LinkLog(file)
        log.note("connected")
        log.note("subscribed fea1: ok")
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].matches(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d  subscribed fea1: ok""")))
    }

    /** A log that outgrows its limit moves aside once, rather than growing for ever or vanishing. */
    @Test fun `a full log moves aside and a fresh one starts`() {
        val file = File(folder.root, "link-log.txt")
        val log = LinkLog(file, limit = 100)
        repeat(10) { log.note("line $it") }
        val old = File(folder.root, "link-log.old.txt")
        assertTrue(old.exists())
        assertTrue(file.length() <= 100 + 40)
        // Nothing is lost between the two: the old file ends where the new one begins.
        val all = old.readLines() + file.readLines()
        assertEquals("line 9", all.last().substringAfter("  "))
    }
}
