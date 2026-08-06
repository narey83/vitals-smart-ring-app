package uk.co.r99vitals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The nights, read the way a night is actually read: how long asleep, then where the stages fell.
 *
 * The chart is a hypnogram — one lane per stage, time running left to right — because sleep is
 * not a number that goes up and down like a heart rate. A total of four hours deep says nothing
 * about whether it came in one block or in twenty broken minutes, and the shape is the point.
 */
private val nightStamp = SimpleDateFormat("EEEE d MMMM", Locale.UK)
private val nightClock = SimpleDateFormat("HH:mm", Locale.UK)

/** Top to bottom, lightest sleep first, so the line falls as sleep deepens. */
private val LANES = listOf(Sleep.AWAKE, Sleep.REM, Sleep.LIGHT, Sleep.DEEP)

@Composable
private fun tint(code: Int) = when (code) {
    Sleep.DEEP -> Ink.sleep
    Sleep.LIGHT -> Ink.oxygen
    Sleep.REM -> Ink.pressure
    else -> Ink.muted
}

@Composable
fun SleepPage(nights: List<Sleep.Night>) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp)
    ) {
        Text(
            "SLEEP", color = Ink.sleep, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp
        )

        if (nights.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Ink.card),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("No nights yet", color = Ink.text, fontSize = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The ring works out its own sleep stages overnight and keeps them to " +
                            "itself. Wear it to bed, then open this app while it is connected — " +
                            "the night is collected then, not while you are asleep.",
                        color = Ink.muted, fontSize = 13.sp, lineHeight = 19.sp
                    )
                }
            }
            return@Column
        }

        val last = nights.last()
        Spacer(Modifier.height(10.dp))
        Text(whenItWas(last), color = Ink.muted, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(spell(last.asleep), color = Ink.text, fontSize = 56.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(10.dp))
            Text("asleep", color = Ink.muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        Text(
            "${nightClock.format(Date(last.startedAt))} – ${nightClock.format(Date(last.endedAt))}" +
                " · ${spell(last.inBed)} in bed",
            color = Ink.muted, fontSize = 14.sp
        )

        Spacer(Modifier.height(18.dp))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Ink.card),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 20.dp, horizontal = 16.dp)) {
                Hypnogram(last, Modifier.fillMaxWidth().height(132.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(nightClock.format(Date(last.startedAt)), color = Ink.muted, fontSize = 11.sp)
                    Text(nightClock.format(Date(last.endedAt)), color = Ink.muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LANES.reversed().forEach { code -> Portion(last, code) }
                }
            }
        }

        if (nights.size > 1) {
            Spacer(Modifier.height(22.dp))
            Text(
                "EARLIER", color = Ink.muted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp
            )
            Spacer(Modifier.height(12.dp))
            nights.dropLast(1).asReversed().take(25).forEach { night ->
                EarlierNight(night)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** One stage's share of the night, named and timed, under the colour it is drawn in. */
@Composable
private fun Portion(night: Sleep.Night, code: Int) {
    val seconds = night.seconds(code)
    val share = if (night.inBed > 0) seconds * 100 / night.inBed else 0
    val colour = tint(code)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(8.dp)) { drawCircle(color = colour) }
            Spacer(Modifier.width(6.dp))
            Text(Sleep.name(code), color = Ink.muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(spell(seconds), color = Ink.text, fontSize = 16.sp)
        Text("$share%", color = Ink.muted, fontSize = 11.sp)
    }
}

/**
 * The night as a shape: one lane per stage, each block as wide as the time it lasted.
 *
 * Gaps are left as gaps. The ring sometimes stages a night with a minute missing between two
 * entries, and drawing a continuous line across it would invent sleep it never recorded.
 */
@Composable
private fun Hypnogram(night: Sleep.Night, modifier: Modifier = Modifier) {
    val colours = LANES.map { it to tint(it) }
    val span = (night.endedAt - night.startedAt).toFloat().coerceAtLeast(1f)
    Canvas(modifier) {
        val laneHeight = size.height / LANES.size
        val barHeight = laneHeight * 0.62f
        colours.forEachIndexed { lane, (code, colour) ->
            val top = lane * laneHeight + (laneHeight - barHeight) / 2f
            // The lane's own faint rule, so an empty stage still reads as a stage with nothing in
            // it rather than as blank card.
            drawRoundRect(
                color = colour.copy(alpha = 0.10f),
                topLeft = Offset(0f, top + barHeight / 2f - 0.75f),
                size = Size(size.width, 1.5f),
                cornerRadius = CornerRadius(1f, 1f)
            )
            night.stages.filter { it.code == code }.forEach { stage ->
                val from = ((stage.startedAt - night.startedAt) / span) * size.width
                val width = ((stage.seconds * 1000f) / span) * size.width
                drawRoundRect(
                    color = colour,
                    topLeft = Offset(from, top),
                    size = Size(width.coerceAtLeast(2f), barHeight),
                    cornerRadius = CornerRadius(barHeight / 3f, barHeight / 3f)
                )
            }
        }
    }
}

/** A finished night, small: the date, the total, and the shape it had. */
@Composable
private fun EarlierNight(night: Sleep.Night) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(nightStamp.format(Date(night.startedAt)), color = Ink.text, fontSize = 16.sp)
                Text(spell(night.asleep), color = Ink.text, fontSize = 16.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${nightClock.format(Date(night.startedAt))} – " +
                    "${nightClock.format(Date(night.endedAt))} · " +
                    "deep ${spell(night.seconds(Sleep.DEEP))} · " +
                    "REM ${spell(night.seconds(Sleep.REM))}",
                color = Ink.muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Hypnogram(night, Modifier.fillMaxWidth().height(56.dp))
        }
    }
}

/** "Last night" while it still is one; after that the day it was. */
private fun whenItWas(night: Sleep.Night): String {
    val ago = System.currentTimeMillis() - night.endedAt
    return if (ago < 30 * 60 * 60 * 1000L) "Last night" else nightStamp.format(Date(night.startedAt))
}

/** Hours and minutes, or minutes alone below the hour, which is how a night is spoken. */
private fun spell(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${"%02d".format(minutes % 60)}m" else "${minutes}m"
}
