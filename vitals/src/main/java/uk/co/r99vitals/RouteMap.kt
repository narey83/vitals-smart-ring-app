package uk.co.r99vitals

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max

/**
 * The shape of a route, with nothing behind it.
 *
 * No map: tiles would have to be fetched from somebody's server, which would tell them where the
 * wearer had been — the one thing a route must not do. The shape alone is enough to recognise a
 * loop of the park.
 */
@Composable
fun RouteMap(fixes: List<Route.Fix>, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val points = drawable(fixes)
        if (points.size < 2) return@Canvas

        // Longitude degrees shrink towards the poles, so they are scaled by the latitude, or a
        // route in Scotland would be drawn nearly twice as wide as it is.
        val squash = cos(Math.toRadians(points.map { it.latitude }.average()))
        val xs = points.map { it.longitude * squash }
        val ys = points.map { it.latitude }
        val left = xs.min(); val right = xs.max()
        val bottom = ys.min(); val top = ys.max()
        val pad = 14.dp.toPx()
        // One scale for both directions, so a route keeps its shape instead of filling the box.
        val scale = minOf(
            (size.width - pad * 2) / max(right - left, 1e-9),
            (size.height - pad * 2) / max(top - bottom, 1e-9)
        )
        val offsetX = (size.width - (right - left) * scale) / 2
        val offsetY = (size.height - (top - bottom) * scale) / 2
        fun at(i: Int) = Offset(
            (offsetX + (xs[i] - left) * scale).toFloat(),
            (size.height - offsetY - (ys[i] - bottom) * scale).toFloat()
        )

        val line = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(line, accent, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // The finish first and the start over it, so a loop that ends where it began still shows
        // where it began.
        drawCircle(accent, radius = 6.dp.toPx(), center = at(points.lastIndex))
        drawCircle(accent, radius = 6.dp.toPx(), center = at(0))
        drawCircle(Color.White, radius = 3.dp.toPx(), center = at(0))
    }
}

/**
 * The fixes worth drawing: vague ones left out and each averaged with its neighbours, as [Track]
 * treats them for the distance, so the line follows the road rather than the GPS's wobble; then
 * thinned to a few hundred, since a two-hour ride is seven thousand and a card is not that wide.
 */
private fun drawable(fixes: List<Route.Fix>): List<Route.Fix> {
    val vetted = fixes.filter { (it.accuracy ?: 0f) <= Track.WORST_ACCURACY }
    val half = Track.SMOOTHING / 2
    val good = vetted.indices.map { i ->
        val around = vetted.subList((i - half).coerceAtLeast(0), (i + half + 1).coerceAtMost(vetted.size))
        vetted[i].copy(latitude = around.sumOf { it.latitude } / around.size, longitude = around.sumOf { it.longitude } / around.size)
    }
    if (good.size <= MOST_POINTS) return good
    val every = good.size / MOST_POINTS + 1
    return good.filterIndexed { i, _ -> i % every == 0 } + good.last()
}

private const val MOST_POINTS = 600

/**
 * Heart rate and speed on one clock, so a climb in one can be read against the other: the hill
 * where the pace dropped and the heart rate went up anyway.
 *
 * Each line has its own scale, top to bottom of the box, since beats and metres a second share
 * no unit; what is compared is when they move, not their heights. Faster is drawn higher. The
 * speed line breaks where the wearer stood still rather than falling to the floor.
 */
@Composable
fun PaceHeartChart(
    beats: List<Int>,
    beatSeconds: List<Int>,
    speeds: List<Pair<Int, Double?>>,
    heart: Color,
    pace: Color,
    modifier: Modifier
) {
    Canvas(modifier) {
        val end = maxOf(beatSeconds.lastOrNull() ?: 0, speeds.lastOrNull()?.first ?: 0)
        if (end <= 0) return@Canvas
        val pad = 6.dp.toPx()
        val tall = size.height - pad * 2
        fun x(second: Int) = size.width * second / end

        // Faint quarter lines: enough to see where half-way was without becoming graph paper.
        for (q in 1..3) {
            val at = size.width * q / 4
            drawLine(pace.copy(alpha = 0.12f), Offset(at, 0f), Offset(at, size.height), strokeWidth = 1.dp.toPx())
        }

        val moving = speeds.mapNotNull { it.second }
        if (moving.size >= 2) {
            val low = moving.min(); val span = (moving.max() - low).coerceAtLeast(0.3)
            fun y(v: Double) = (pad + tall - (v - low) / span * tall).toFloat()
            var path: Path? = null
            val lines = mutableListOf<Path>()
            speeds.forEach { (second, speed) ->
                if (speed == null) { path = null; return@forEach }
                val p = path
                if (p == null) path = Path().apply { moveTo(x(second), y(speed)) }.also { lines += it }
                else p.lineTo(x(second), y(speed))
            }
            lines.forEach { drawPath(it, pace, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }

        if (beats.size >= 2 && beatSeconds.size == beats.size) {
            // The same short running mean the heart curve uses elsewhere, for the one-beat staircase.
            val smooth = beats.indices.map { i -> beats.subList((i - 2).coerceAtLeast(0), (i + 3).coerceAtMost(beats.size)).average() }
            val low = smooth.min(); val span = (smooth.max() - low).coerceAtLeast(6.0)
            fun y(v: Double) = (pad + tall - (v - low) / span * tall).toFloat()
            val line = Path().apply {
                moveTo(x(beatSeconds[0]), y(smooth[0]))
                for (i in 1 until smooth.size) lineTo(x(beatSeconds[i]), y(smooth[i]))
            }
            drawPath(line, heart, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
