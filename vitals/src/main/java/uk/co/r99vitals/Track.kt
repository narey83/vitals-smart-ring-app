package uk.co.r99vitals

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance, moving time, splits and pace, from a route's fixes as they arrive.
 *
 * A GPS does not stand still when its owner does. Put down, a phone's position wanders a few
 * metres every second, and adding up those wanderings reports a half-mile walk for a coffee
 * break. So positions are averaged over a few seconds, and a movement counts only once that
 * average has left the circle the GPS itself says it could be anywhere within, by a margin: the
 * track holds an anchor, and distance is added only when the position lands clear of it. On the
 * move that happens every few seconds and the chords follow the road; standing still, it very
 * seldom does.
 *
 * Fed one fix at a time so the collector can show the distance live, and fed a whole file to
 * read back a finished route — the same arithmetic either way, so the two can never disagree.
 */
class Track(
    private val sport: String,
    /** A kilometre or a mile: what [splits] are counted in. */
    private val splitEvery: Double = KILOMETRE
) {

    /** A place the track is known to have reached, and how far along it that was. */
    private data class Anchor(val fix: Route.Fix, val metres: Double, val moving: Long)

    private var anchor: Anchor? = null
    private var first: Route.Fix? = null
    private var last: Route.Fix? = null
    private val window = ArrayDeque<Route.Fix>()

    /** Recent anchors, for a pace that says how fast things are going now rather than on average. */
    private val recent = ArrayDeque<Anchor>()

    var metres = 0.0
        private set

    /** Time spent actually going somewhere. A wait at a crossing is not in it. */
    var movingMillis = 0L
        private set

    /** From the first fix believed to the last one heard. */
    val elapsedMillis: Long get() = first?.let { f -> last?.let { it.at - f.at } } ?: 0L

    /** Fixes too vague to place, or that would need a speed nobody on foot or bike has. */
    var rejected = 0
        private set

    /** Moving time at each whole kilometre or mile; see [splits]. */
    private val crossings = mutableListOf<Long>()

    /** Whether the track has anything to show yet. */
    val started get() = anchor != null

    fun add(fix: Route.Fix) {
        val accuracy = fix.accuracy
        // The first few fixes after the GPS wakes are often a street or more out, and saying
        // "±48 m" is the GPS admitting it. Those are not a place to measure from.
        if (accuracy != null && accuracy > WORST_ACCURACY) { rejected++; return }
        val before = last
        if (before != null) {
            if (fix.at <= before.at) return
            // A jump further than anyone could go, even allowing each fix its own error, is the
            // GPS and not the wearer: a reflection off a building. Dropped before it reaches the
            // average, where it would drag the next few positions after it.
            val seconds = (fix.at - before.at) / 1000.0
            val unexplained = metresBetween(before, fix) - (before.accuracy ?: 0f) - (accuracy ?: 0f)
            if (unexplained > fastest() * seconds) { rejected++; return }
        }
        last = fix
        if (first == null) first = fix

        // Positions are measured averaged over the last few fixes. Each fix is out by its own few
        // metres in its own direction, and differencing raw fixes adds all of that up as
        // zigzags: a tenth again on a run, and a walk out of a phone lying on a bench.
        window.addLast(fix)
        while (window.size > SMOOTHING) window.removeFirst()
        val here = Route.Fix(
            at = fix.at,
            latitude = window.sumOf { it.latitude } / window.size,
            longitude = window.sumOf { it.longitude } / window.size,
            accuracy = window.mapNotNull { it.accuracy }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        )

        val previous = anchor
        if (previous == null) {
            anchor = Anchor(here, 0.0, 0L).also { recent.addLast(it) }
            return
        }
        val step = metresBetween(previous.fix, here)
        if (step < max(MIN_STEP, (here.accuracy ?: 0f) * CERTAINTY)) return

        val seconds = (here.at - previous.fix.at) / 1000.0
        val moving = if (seconds > 0 && step / seconds >= SLOWEST_MOVING) {
            (here.at - previous.fix.at)
        } else 0L
        crossSplits(previous, step, moving)
        metres += step
        movingMillis += moving
        anchor = Anchor(here, metres, movingMillis).also { recent.addLast(it) }
        while (recent.size > 1 && here.at - recent.first().fix.at > PACE_WINDOW) recent.removeFirst()
    }

    /**
     * How long each whole kilometre or mile took, in moving time — the thing a runner
     * compares one with the next. A part-finished last one is not a split yet.
     */
    val splits: List<Long> get() = crossings.mapIndexed { i, at -> at - (crossings.getOrNull(i - 1) ?: 0L) }

    /**
     * Metres a second over the last half minute or so, or null while that is too short to mean
     * anything, or when nothing has moved in it — standing still has no pace.
     */
    fun currentSpeed(): Double? {
        val newest = recent.lastOrNull() ?: return null
        val oldest = recent.first()
        val span = newest.fix.at - oldest.fix.at
        if (span < SHORTEST_PACE || newest.moving == oldest.moving) return null
        // Over moving time, so a pause inside the window does not drag the pace down.
        return (newest.metres - oldest.metres) / ((newest.moving - oldest.moving) / 1000.0)
    }

    /** Average over moving time, or null before anything has moved. */
    fun averageSpeed(): Double? = if (movingMillis == 0L) null else metres / (movingMillis / 1000.0)

    private fun crossSplits(from: Anchor, step: Double, moving: Long) {
        var next = (crossings.size + 1) * splitEvery
        while (from.metres + step >= next) {
            // Interpolated within the step, since a split does not happen on a fix.
            val fraction = (next - from.metres) / step
            crossings += from.moving + (moving * fraction).toLong()
            next += splitEvery
        }
    }

    private fun fastest() = when (sport) {
        "Ride" -> 30.0  // 108 km/h: a descent, not a GPS glitch, has to fit.
        "Run" -> 12.0   // Faster than any sprint.
        else -> 7.0     // A walk that breaks into a run for a bus.
    }

    companion object {
        const val KILOMETRE = 1000.0
        const val MILE = 1609.344

        /** Fixes vaguer than this are ignored. Open sky gives 3 to 5 m; a city street 10 to 20. */
        const val WORST_ACCURACY = 25f

        /** Fixes averaged into each position: five seconds, short enough to keep a corner a corner. */
        const val SMOOTHING = 5

        /**
         * How far past its stated accuracy a position must move to count. Accuracy is a 68% radius,
         * so a still phone strays past it one second in three; half as far again it seldom does.
         */
        const val CERTAINTY = 1.5

        /** The least a position must move to count, however sure the GPS is. */
        const val MIN_STEP = 5.0

        /** Under this is standing about, not going anywhere: 1.8 km/h. */
        const val SLOWEST_MOVING = 0.5

        const val PACE_WINDOW = 30_000L
        const val SHORTEST_PACE = 10_000L

        private const val EARTH = 6_371_008.8

        /** Great-circle distance. Flat-earth arithmetic is out by more than a pace is worth over a run. */
        fun metresBetween(a: Route.Fix, b: Route.Fix): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
            return 2 * EARTH * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        fun of(sport: String, fixes: List<Route.Fix>, metric: Boolean = true) =
            Track(sport, if (metric) KILOMETRE else MILE).also { t -> fixes.forEach(t::add) }

        /** "5:32 /km", for going on foot. */
        fun pace(metresPerSecond: Double, metric: Boolean): String {
            val unit = if (metric) KILOMETRE else MILE
            val seconds = (unit / metresPerSecond).roundToInt()
            return "%d:%02d /%s".format(seconds / 60, seconds % 60, if (metric) "km" else "mi")
        }

        /** "18.4 km/h", for a ride, where pace per kilometre is not how anyone thinks. */
        fun speed(metresPerSecond: Double, metric: Boolean): String =
            if (metric) "%.1f km/h".format(metresPerSecond * 3.6) else "%.1f mph".format(metresPerSecond * 3600 / MILE)

        /** How a sport is read: a ride by speed, anything on foot by pace. */
        fun rate(sport: String, metresPerSecond: Double, metric: Boolean) =
            if (sport == "Ride") speed(metresPerSecond, metric) else pace(metresPerSecond, metric)
    }
}
