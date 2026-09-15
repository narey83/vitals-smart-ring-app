package uk.co.r99vitals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic routes with the noise a phone's GPS really has in them. What matters is not the
 * arithmetic on a clean line — any of it gets that right — but that a phone left on a bench does
 * not walk, a reflection off a building does not sprint, and a wait at a crossing does not slow
 * the pace.
 */
class TrackTest {

    private val start = 1_700_000_000_000L
    private val home = Route.Fix(start, 55.8573, -4.4266)

    /** Degrees of latitude in a metre, near enough everywhere. */
    private val degreePerMetre = 1 / 111_195.0

    /**
     * A fix a second heading north at [speed], from [from] metres along, with up to [wobble]
     * metres of GPS error in any direction.
     */
    private fun heading(
        seconds: Int, speed: Double, from: Double = 0.0, startAt: Long = start,
        wobble: Double = 3.0, accuracy: Float = 5f, random: Random = Random(7)
    ) = (0 until seconds).map { s ->
        val along = from + s * speed
        Route.Fix(
            at = startAt + s * 1000L,
            latitude = home.latitude + (along + random.nextDouble(-wobble, wobble)) * degreePerMetre,
            longitude = home.longitude + random.nextDouble(-wobble, wobble) * degreePerMetre * 1.77,
            accuracy = accuracy
        )
    }

    @Test fun `a thousandth of a degree of latitude is 111 metres`() {
        val north = home.copy(latitude = home.latitude + 0.001)
        assertEquals(111.195, Track.metresBetween(home, north), 0.1)
    }

    @Test fun `a phone left still for ten minutes goes nowhere`() {
        val track = Track.of("Walk", heading(seconds = 600, speed = 0.0, wobble = 4.0))
        assertTrue("wandered ${track.metres} m", track.metres < Track.MIN_STEP * 2)
        assertEquals(0L, track.movingMillis)
        assertNull(track.currentSpeed())
    }

    @Test fun `a steady run measures its distance, time and splits`() {
        val track = Track.of("Run", heading(seconds = 1201, speed = 3.0))
        assertEquals(3600.0, track.metres, 3600 * 0.02)
        assertEquals(1_200_000.0, track.movingMillis.toDouble(), 30_000.0)
        assertEquals(3, track.splits.size)
        // A kilometre at 3 m/s is 333 seconds.
        track.splits.forEach { assertEquals(333_000.0, it.toDouble(), 15_000.0) }
        assertEquals(3.0, track.currentSpeed()!!, 0.3)
        assertEquals(3.0, track.averageSpeed()!!, 0.1)
    }

    @Test fun `splits are counted in miles when miles are read`() {
        val track = Track.of("Run", heading(seconds = 1201, speed = 3.0), metric = false)
        assertEquals(2, track.splits.size)
        assertEquals(536_000.0, track.splits.first().toDouble(), 20_000.0)
    }

    @Test fun `the vague fixes while the GPS wakes up are not a place to start from`() {
        val waking = (0 until 20).map { s ->
            Route.Fix(start + s * 1000L, home.latitude + 0.002, home.longitude, accuracy = 48f)
        }
        val track = Track.of("Walk", waking + heading(seconds = 300, speed = 1.4, startAt = start + 20_000))
        assertEquals(20, track.rejected)
        assertEquals(420.0, track.metres, 420 * 0.05)
    }

    @Test fun `a reflection half a kilometre away is not a sprint there and back`() {
        val fixes = heading(seconds = 600, speed = 3.0).toMutableList()
        fixes[300] = fixes[300].copy(latitude = fixes[300].latitude + 500 * degreePerMetre)
        val track = Track.of("Run", fixes)
        assertEquals(1800.0, track.metres, 1800 * 0.02)
        assertEquals(1, track.rejected)
    }

    @Test fun `a wait at a crossing counts in elapsed time but not in moving time`() {
        val walk = heading(seconds = 300, speed = 1.5)
        val wait = heading(seconds = 120, speed = 0.0, from = 450.0, startAt = start + 300_000, random = Random(11))
        val onward = heading(seconds = 300, speed = 1.5, from = 450.0, startAt = start + 420_000, random = Random(13))
        val track = Track.of("Walk", walk + wait + onward)
        assertEquals(900.0, track.metres, 900 * 0.05)
        assertEquals(719_000L, track.elapsedMillis)
        assertEquals(600_000.0, track.movingMillis.toDouble(), 40_000.0)
    }

    @Test fun `fed one fix at a time, it agrees with the whole file`() {
        val fixes = heading(seconds = 400, speed = 2.5)
        val live = Track("Run").also { t -> fixes.forEach(t::add) }
        val read = Track.of("Run", fixes)
        assertEquals(read.metres, live.metres, 0.0)
        assertEquals(read.movingMillis, live.movingMillis)
    }

    @Test fun `a fix older than the last one heard is ignored`() {
        val fixes = heading(seconds = 100, speed = 3.0)
        val track = Track.of("Run", fixes + fixes[10])
        assertEquals(Track.of("Run", fixes).metres, track.metres, 0.0)
    }

    @Test fun `pace reads per kilometre or mile, and a ride reads as speed`() {
        assertEquals("5:33 /km", Track.pace(3.0, metric = true))
        assertEquals("8:56 /mi", Track.pace(3.0, metric = false))
        assertEquals("18.0 km/h", Track.speed(5.0, metric = true))
        assertEquals("11.2 mph", Track.speed(5.0, metric = false))
        assertEquals("18.0 km/h", Track.rate("Ride", 5.0, metric = true))
        assertEquals("3:20 /km", Track.rate("Run", 5.0, metric = true))
    }
}
