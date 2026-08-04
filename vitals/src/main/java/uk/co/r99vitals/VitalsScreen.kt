package uk.co.r99vitals

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Battery1Bar
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/** The palette, kept here so the screen reads as one piece rather than chasing resources. */
object Ink {
    val canvas = Color(0xFF07090C)
    val card = Color(0xFF12161C)
    val text = Color(0xFFF4F7FA)
    val muted = Color(0xFF7D8794)
    val heart = Color(0xFFFF3D71)
    val oxygen = Color(0xFF00D1FF)
    val pressure = Color(0xFFB57BFF)
    val motion = Color(0xFF2DE59B)
}

data class VitalsState(
    val link: String = "Looking for your ring",
    val connected: Boolean = false,
    val battery: Int? = null,
    val heart: Int? = null,
    val oxygen: Int? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val steps: Int? = null,
    val distance: Int = 0,
    val calories: Int = 0,
    val measuring: String? = null,
    val trend: List<Int> = emptyList(),
    val trendCaption: String = "",
    val interval: Int = 15,
    val streaming: Boolean = false,
    val workout: String? = null,
    val workoutSince: Long = 0L,
    val workoutBeats: List<Int> = emptyList()
)

@Composable
fun VitalsScreen(
    state: VitalsState,
    onMeasure: (Int) -> Unit,
    onInterval: () -> Unit,
    onHistory: () -> Unit,
    onLink: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp)
    ) {
        Header(state, onLink)
        Spacer(Modifier.height(22.dp))
        HeartCard(state)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            SmallCard(
                "BLOOD OXYGEN", state.oxygen?.let { "$it%" }, Ink.oxygen,
                Modifier.weight(1f), icon = Icons.Rounded.Bloodtype
            )
            Spacer(Modifier.width(12.dp))
            SmallCard(
                "PRESSURE",
                state.systolic?.let { "$it/${state.diastolic}" }, Ink.pressure,
                Modifier.weight(1f), footnote = "estimated", icon = Icons.Rounded.MonitorHeart
            )
        }
        Spacer(Modifier.height(12.dp))
        MovementCard(state)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            Pill("Heart", Ink.heart, state.measuring == null, Modifier.weight(1f), Icons.Rounded.Favorite) { onMeasure(Ring.HEART) }
            Spacer(Modifier.width(10.dp))
            Pill("SpO₂", Ink.oxygen, state.measuring == null, Modifier.weight(1f), Icons.Rounded.Bloodtype) { onMeasure(Ring.OXYGEN) }
            Spacer(Modifier.width(10.dp))
            Pill("BP", Ink.pressure, state.measuring == null, Modifier.weight(1f), Icons.Rounded.MonitorHeart) { onMeasure(Ring.PRESSURE) }
        }
        Spacer(Modifier.height(10.dp))
        Quiet(if (state.interval == 0) "Automatic readings off" else "Automatic readings every ${state.interval} min", onInterval)
        Spacer(Modifier.height(8.dp))
        Quiet("Export readings", onHistory)
        Spacer(Modifier.height(20.dp))
        Text(
            "Readings stay on this phone. This app has no internet permission, so it cannot " +
                "send them anywhere. Not a medical device.",
            color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}

@Composable
private fun Header(state: VitalsState, onLink: () -> Unit) {
    Column {
        Text("TODAY", color = Ink.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A quiet dot beats a sentence about connection state.
            val pulse by animateFloatAsState(
                if (state.connected) 1f else 0.35f, tween(600), label = "link"
            )
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background((if (state.connected) Ink.motion else Ink.muted).copy(alpha = pulse))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                state.link, color = Ink.text, fontSize = 26.sp,
                modifier = Modifier.clickableNoRipple(onLink)
            )
            state.battery?.let { level ->
                Spacer(Modifier.width(12.dp))
                // The icon carries the state at a glance; the number is for when you care.
                Icon(
                    when {
                        level > 80 -> Icons.Rounded.BatteryFull
                        level > 50 -> Icons.Rounded.Battery5Bar
                        level > 20 -> Icons.Rounded.Battery3Bar
                        else -> Icons.Rounded.Battery1Bar
                    },
                    contentDescription = "Ring battery",
                    tint = if (level > 20) Ink.muted else Ink.heart,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("$level%", color = if (level > 20) Ink.muted else Ink.heart, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun HeartCard(state: VitalsState) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp)) {
            Label("HEART RATE", Ink.heart, Icons.Rounded.Favorite)
            Row(verticalAlignment = Alignment.Bottom) {
                // Numbers slide rather than snap, so a change reads as a change.
                AnimatedContent(
                    targetState = state.heart,
                    transitionSpec = {
                        (slideInVertically { it / 3 } + fadeIn()) togetherWith
                            (slideOutVertically { -it / 3 } + fadeOut())
                    },
                    label = "bpm"
                ) { value ->
                    Text(
                        value?.toString() ?: "––",
                        color = Ink.text, fontSize = 66.sp, fontWeight = FontWeight.Light
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("bpm", color = Ink.muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 14.dp))
            }
            Text(
                state.measuring ?: if (state.heart == null) "Not measured yet" else "Latest reading",
                color = Ink.muted, fontSize = 13.sp
            )
            if (state.trend.size > 1) {
                Spacer(Modifier.height(18.dp))
                // Full bleed to the card edges: a plot inset on both sides reads as a thumbnail.
                TrendChart(
                    state.trend, Ink.heart,
                    Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 0.dp)
                )
            }
            if (state.trendCaption.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(state.trendCaption, color = Ink.muted, fontSize = 12.sp)
            }
        }
    }
}

/** A trend drawn as a shape, because at this size the shape is the information. */
@Composable
fun BarChart(values: List<Int>, accent: Color, modifier: Modifier) {
    val grow by animateFloatAsState(1f, tween(700), label = "bars")
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val peak = values.max().coerceAtLeast(1)
        val gap = size.width / values.size * 0.25f
        val barWidth = size.width / values.size - gap
        values.forEachIndexed { i, value ->
            val height = (value.toFloat() / peak) * size.height * grow
            drawRoundRect(
                color = if (value == 0) accent.copy(alpha = 0.12f) else accent,
                topLeft = Offset(i * (barWidth + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2.5f)
            )
        }
    }
}

@Composable
fun TrendChart(values: List<Int>, accent: Color, modifier: Modifier) {
    val grow by animateFloatAsState(1f, tween(700), label = "grow")
    Canvas(modifier) {
        val raw = if (values.size > 90) values.takeLast(90) else values
        // Heart rate arrives as whole numbers, so a calm stretch is a staircase of one-beat
        // steps. A short running mean turns that back into the curve it actually represents.
        val points = raw.indices.map { i ->
            val from = (i - 2).coerceAtLeast(0)
            val to = (i + 2).coerceAtMost(raw.lastIndex)
            raw.subList(from, to + 1).average().toFloat()
        }
        val low = points.min()
        // A steady run should read as steady, not be amplified into noise.
        val span = (points.max() - low).coerceAtLeast(6f)
        val inset = 10f
        val usable = size.height - inset * 2
        val stepX = size.width / (points.size - 1)
        fun px(i: Int) = i * stepX
        fun py(v: Float) = inset + usable - ((v - low) / span) * usable * grow

        // Quadratic segments through the midpoints: the standard way to draw a sparkline that
        // curves rather than corners.
        val line = Path().apply {
            moveTo(px(0), py(points[0]))
            for (i in 0 until points.size - 1) {
                val midX = (px(i) + px(i + 1)) / 2
                val midY = (py(points[i]) + py(points[i + 1])) / 2
                quadraticTo(px(i), py(points[i]), midX, midY)
            }
            lineTo(px(points.size - 1), py(points.last()))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(px(points.size - 1), size.height)
            lineTo(px(0), size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)))
        )
        drawPath(line, accent, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(accent, radius = 8f, center = Offset(px(points.size - 1), py(points.last())))
    }
}

@Composable
private fun SmallCard(
    label: String,
    value: String?,
    accent: Color,
    modifier: Modifier,
    footnote: String? = null,
    icon: ImageVector? = null
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = modifier
    ) {
        Column(Modifier.padding(18.dp)) {
            Label(label, accent, icon)
            Text(value ?: "––", color = Ink.text, fontSize = 34.sp, fontWeight = FontWeight.Light)
            footnote?.let { Text(it, color = Ink.muted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun MovementCard(state: VitalsState) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp)) {
            Label("MOVEMENT", Ink.motion, Icons.Rounded.DirectionsWalk)
            Text(
                state.steps?.let { "%,d".format(it) } ?: "––",
                color = Ink.text, fontSize = 42.sp, fontWeight = FontWeight.Light
            )
            Text(
                if (state.steps == null) "steps today"
                else "steps today · ${state.distance} m · ${state.calories} kcal",
                color = Ink.muted, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun Label(text: String, accent: Color, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Icon(it, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
    }
}

@Composable
private fun Pill(
    text: String,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Ink.canvas,
            disabledContainerColor = accent.copy(alpha = 0.3f),
            disabledContentColor = Ink.canvas.copy(alpha = 0.6f)
        ),
        modifier = modifier.height(54.dp)
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Quiet(text: String, onClick: () -> Unit) {
    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickableNoRipple(onClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = Ink.text, fontSize = 15.sp)
        }
    }
}

fun Modifier.clickableNoRippleShared(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = onClick)

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = onClick)
