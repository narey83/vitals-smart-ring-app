package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** A GPX file is only any use if the app on the other end can read it. */
class GpxTest {

    private val start = 1_789_400_000_000L
    private val fixes = listOf(
        Route.Fix(start, 55.849, -4.237, 20.5, 5f),
        Route.Fix(start + 1_000, 55.8491, -4.2371, null, 6f),
        Route.Fix(start + 2_000, 55.9, -4.3, 20.0, 48f)
    )

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())

    @Test fun `the file is well-formed GPX with a point for each good fix`() {
        val doc = parse(Gpx.of("Run", start, fixes))
        assertEquals("gpx", doc.documentElement.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", doc.documentElement.namespaceURI)
        val points = doc.getElementsByTagNameNS("*", "trkpt")
        // The third is ±48 m, which the distance ignores and so does the file.
        assertEquals(2, points.length)
        assertEquals("55.849", points.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("-4.237", points.item(0).attributes.getNamedItem("lon").nodeValue)
        assertEquals("running", doc.getElementsByTagNameNS("*", "type").item(0).textContent)
    }

    @Test fun `a point without altitude has no elevation, and every point has its time`() {
        val xml = Gpx.of("Walk", start, fixes)
        assertTrue(xml.contains("<ele>20.5</ele><time>2026-09-14T15:33:20Z</time>"))
        assertTrue(xml.contains("lon=\"-4.2371\"><time>2026-09-14T15:33:21Z</time>"))
    }

    @Test fun `a comma decimal locale still writes points`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val xml = Gpx.of("Ride", start, fixes)
            assertFalse(xml.contains("55,849"))
            assertEquals(2, parse(xml).getElementsByTagNameNS("*", "trkpt").length)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `the file is named for the sport and the day`() {
        assertTrue(Gpx.fileName("Run", start).matches(Regex("vitals-run-2026-09-1[45]\\.gpx")))
    }
}
