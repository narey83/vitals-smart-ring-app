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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/**
 * The palette, in both of its moods.
 *
 * Every colour is read through a composable getter, so the whole app follows the system theme
 * without a single call site changing: `Ink.text` means "the text colour right now".
 *
 * The accents are not the same values in both. A colour with enough punch against near-black is
 * washed out to nothing against white — the dark green in particular fails contrast badly as
 * small text — so each accent has a darker sibling for light mode rather than being reused.
 */
private data class Palette(
    val canvas: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val heart: Color,
    val oxygen: Color,
    val pressure: Color,
    val motion: Color,
    val sleep: Color
)

private val darkInk = Palette(
    canvas = Color(0xFF07090C),
    card = Color(0xFF12161C),
    text = Color(0xFFF4F7FA),
    muted = Color(0xFF7D8794),
    heart = Color(0xFFFF3D71),
    oxygen = Color(0xFF00D1FF),
    pressure = Color(0xFFB57BFF),
    motion = Color(0xFF2DE59B),
    sleep = Color(0xFF6E8BFF)
)

private val lightInk = Palette(
    canvas = Color(0xFFF2F5F9),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF0B0F14),
    muted = Color(0xFF5A6472),
    heart = Color(0xFFD11E4E),
    oxygen = Color(0xFF00718F),
    pressure = Color(0xFF6D3BC7),
    motion = Color(0xFF067A4E),
    sleep = Color(0xFF3D4FB8)
)

object Ink {
    private val now: Palette
        @Composable get() = if (isSystemInDarkTheme()) darkInk else lightInk

    val canvas: Color @Composable get() = now.canvas
    val card: Color @Composable get() = now.card
    val text: Color @Composable get() = now.text
    val muted: Color @Composable get() = now.muted
    val heart: Color @Composable get() = now.heart
    val oxygen: Color @Composable get() = now.oxygen
    val pressure: Color @Composable get() = now.pressure
    val motion: Color @Composable get() = now.motion
    val sleep: Color @Composable get() = now.sleep
}

data class VitalsState(
    val link: String = "Looking for your ring",
    val connected: Boolean = false,
    val battery: Int? = null,
    val charging: Boolean = false,
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
    val workoutBeats: List<Int> = emptyList(),
    val pastWorkouts: List<Workouts.Session> = emptyList(),
    val nights: List<Sleep.Night> = emptyList(),
    val stepGoal: Int = 10_000,
    val firmware: String? = null,
    val metric: Boolean = true,
    val monitors: Ring.Monitors = Ring.Monitors(),
    /** Set only on the wearer's birthday, and already worded. */
    val celebrate: String? = null
)

@Composable
fun VitalsScreen(
    state: VitalsState,
    onMeasure: (Int) -> Unit,
    onSettings: () -> Unit,
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
        Header(state, onLink, onSettings)
        state.celebrate?.let {
            Spacer(Modifier.height(18.dp))
            BirthdayCard(it)
        }
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
        // Settings is the gear in the header now. What is worth saying here is only what the
        // ring is currently doing, which is a fact rather than a button.
        Text(
            if (state.interval == 0) "Automatic readings are off"
            else "Measuring on its own every ${state.interval} min",
            color = Ink.muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Readings stay on this phone. This app has no internet permission, so it cannot " +
                "send them anywhere. Not a medical device.",
            color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp
        )
    }
}

@Composable
private fun Header(state: VitalsState, onLink: () -> Unit, onSettings: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TODAY", color = Ink.muted, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Ink.card)
                    .clickableNoRipple(onSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Settings, "Settings",
                    tint = Ink.text, modifier = Modifier.size(20.dp)
                )
            }
        }
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
                // Charging is worth showing plainly: it is the one battery state you act on.
                val tone = when {
                    state.charging -> Ink.motion
                    level > 20 -> Ink.muted
                    else -> Ink.heart
                }
                Icon(
                    when {
                        state.charging -> Icons.Rounded.BatteryChargingFull
                        level > 80 -> Icons.Rounded.BatteryFull
                        level > 50 -> Icons.Rounded.Battery5Bar
                        level > 20 -> Icons.Rounded.Battery3Bar
                        else -> Icons.Rounded.Battery1Bar
                    },
                    contentDescription = if (state.charging) "Ring charging" else "Ring battery",
                    tint = tone,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("$level%", color = tone, fontSize = 15.sp)
                if (state.charging) {
                    Spacer(Modifier.width(6.dp))
                    Text("charging", color = Ink.motion, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Once a year, and gone the next day. Worth a moment rather than a whole feature. */
@Composable
private fun BirthdayCard(message: String) {
    val rise by animateFloatAsState(1f, tween(700), label = "birthday")
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.motion.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎉", fontSize = (30 * rise).sp)
            Spacer(Modifier.width(14.dp))
            Text(message, color = Ink.text, fontSize = 17.sp, lineHeight = 23.sp)
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
                    Modifier.fillMaxWidth().height(120.dp), showScale = false
                )
            }
            if (state.trendCaption.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(state.trendCaption, color = Ink.muted, fontSize = 12.sp)
            }
        }
    }
}

/** Room on the right for the scale labels, shared with whatever draws an axis underneath. */
val SCALE_GUTTER = 26.dp

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
fun TrendChart(
    values: List<Int>,
    accent: Color,
    modifier: Modifier,
    /** Drawn only where there is room; the small card on Today has none. */
    showScale: Boolean = true,
    /**
     * Where each reading sits in the day, from 0 at midnight to 1 at the end of it.
     *
     * Without these the readings are spaced evenly, which draws eight readings taken between
     * three and six o'clock as though they were spread across the whole day. Given them, the
     * line sits under the hours it actually happened in.
     */
    positions: List<Float> = emptyList()
) {
    val grow by animateFloatAsState(1f, tween(700), label = "grow")
    Canvas(modifier) {
        val raw = if (values.size > 90) values.takeLast(90) else values
        if (raw.size < 2) return@Canvas
        // Heart rate arrives as whole numbers, so a calm stretch is a staircase of one-beat
        // steps. A short running mean turns that back into the curve it represents.
        val points = raw.indices.map { i ->
            val from = (i - 2).coerceAtLeast(0)
            val to = (i + 2).coerceAtMost(raw.lastIndex)
            raw.subList(from, to + 1).average().toFloat()
        }

        // A domain drawn from the data alone puts the lowest reading on the floor, which hides
        // where the values actually sit. Pad it out to round numbers so the line floats inside
        // a range you can read.
        val seen = points.min() to points.max()
        val margin = ((seen.second - seen.first) * 0.35f).coerceAtLeast(4f)
        val low = kotlin.math.floor((seen.first - margin) / 5f) * 5f
        val high = kotlin.math.ceil((seen.second + margin) / 5f) * 5f
        val span = (high - low).coerceAtLeast(10f)

        val gutter = if (showScale) SCALE_GUTTER.toPx() else 0f
        val plot = size.width - gutter
        val stepX = plot / (points.size - 1)
        // Sliced the same way the values were, so a reading keeps its own time.
        val when_ = if (values.size > 90) positions.takeLast(90) else positions
        val timed = when_.size == raw.size
        fun px(i: Int) = if (timed) when_[i] * plot else i * stepX
        fun py(v: Float) = size.height - ((v - low) / span) * size.height * grow

        if (showScale) {
            val label = android.graphics.Paint().apply {
                color = accent.copy(alpha = 0.55f).toArgb()
                textSize = 26f
                isAntiAlias = true
            }
            // Three lines is enough to read a range without becoming graph paper.
            listOf(high, (high + low) / 2f, low).forEach { mark ->
                val y = py(mark)
                drawLine(
                    accent.copy(alpha = 0.13f),
                    Offset(0f, y), Offset(plot, y), strokeWidth = 2f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    mark.toInt().toString(), plot + 14f, y + 9f, label
                )
            }
        }

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
        drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0f))))
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
                else "steps today · ${Units.distance(state.distance, state.metric)} · ${state.calories} kcal",
                color = Ink.muted, fontSize = 13.sp
            )
            state.steps?.let { walked ->
                Spacer(Modifier.height(12.dp))
                val share = (walked.toFloat() / state.stepGoal).coerceIn(0f, 1f)
                val filled by animateFloatAsState(share, tween(700), label = "goal")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Ink.motion.copy(alpha = 0.18f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(filled)
                            .clip(CircleShape)
                            .background(Ink.motion)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (walked >= state.stepGoal) "Goal reached"
                    else "%,d to go of %,d".format(state.stepGoal - walked, state.stepGoal),
                    color = Ink.muted, fontSize = 12.sp
                )
            }
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
