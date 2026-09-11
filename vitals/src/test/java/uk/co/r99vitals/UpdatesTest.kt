package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for org.json, which the plain JVM only has as stubs. */
@RunWith(RobolectricTestRunner::class)
class UpdatesTest {

    @Test fun `a later release is newer, the same one or an older one is not`() {
        assertTrue(Updates.newer("0.3.0", "0.2.0"))
        assertTrue(Updates.newer("v1.0.0", "0.9.9"))
        assertFalse(Updates.newer("0.2.0", "0.2.0"))
        assertFalse(Updates.newer("v0.2.0", "0.2.0"))
        assertFalse(Updates.newer("0.1.9", "0.2.0"))
    }

    /** Compared as numbers, not text, where "0.10.0" sorts before "0.9.0". */
    @Test fun `versions compare number by number`() {
        assertTrue(Updates.newer("0.10.0", "0.9.0"))
        assertTrue(Updates.newer("0.2.1", "0.2"))
        assertFalse(Updates.newer("0.2", "0.2.0"))
        assertFalse(Updates.newer("0.3.0-rc1", "0.3.0"))
    }

    @Test fun `a release is read for its tag and its page`() {
        val json = """{"tag_name":"v0.3.0","html_url":"https://github.com/narey83/vitals-smart-ring-app/releases/tag/v0.3.0","draft":false}"""
        val release = Updates.parse(json, "narey83/vitals-smart-ring-app")!!
        assertEquals("0.3.0", release.version)
        assertEquals("https://github.com/narey83/vitals-smart-ring-app/releases/tag/v0.3.0", release.page)
    }

    @Test fun `a release without a tag is not one`() {
        assertNull(Updates.parse("""{"html_url":"https://github.com/x/y"}""", "x/y"))
    }
}
