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
