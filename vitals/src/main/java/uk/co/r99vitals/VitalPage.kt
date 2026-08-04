package uk.co.r99vitals

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What one vital's own page needs, so the page itself stays declarative. */
data class VitalDay(
    val title: String,
    val unit: String,
    val accent: Color,
    val icon: ImageVector,
    val value: String?,
    val readings: List<Int>,
    val entries: List<History.Entry>,
    val note: String? = null,
    val canMeasure: Boolean = true,
    /** Steps accumulate, so they read as hourly bars rather than a climbing line. */
    val asBars: Boolean = false,
    /** Steps only: the day cut into hours, each holding its own quarter hours. */
    val hours: List<Steps.Hour> = emptyList()
)

private val dayLabel = SimpleDateFormat("EEEE d MMMM", Locale.UK)
private val timeLabel = SimpleDateFormat("HH:mm", Locale.UK)

@Composable
fun VitalPage(
    day: VitalDay,
    dayOffset: Int,
    busy: Boolean,
    streaming: Boolean = false,
    onDay: (Int) -> Unit,
    onMeasure: () -> Unit,
    onStream: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(day.icon, null, tint = day.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                day.title.uppercase(), color = day.accent, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        DayPicker(dayOffset, onDay)

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                day.value ?: "––", color = Ink.text, fontSize = 72.sp, fontWeight = FontWeight.Light
            )
            Spacer(Modifier.width(10.dp))
            Text(day.unit, color = Ink.muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
        }
        day.note?.let { Text(it, color = Ink.muted, fontSize = 13.sp) }

        if (day.readings.size > 1) {
            Spacer(Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Ink.card),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 20.dp, horizontal = 16.dp)) {
                    if (day.asBars) {
                        BarChart(day.readings, day.accent, Modifier.fillMaxWidth().height(150.dp))
                        HourAxis()
                    } else {
                        TrendChart(day.readings, day.accent, Modifier.fillMaxWidth().height(150.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (day.asBars) {
                            Stat("TOTAL", "%,d".format(day.readings.sum()), day.accent)
                            Stat("BUSIEST", day.readings.max().toString(), day.accent)
                            Stat("ACTIVE HOURS", day.readings.count { it > 0 }.toString(), day.accent)
                        } else {
                            Stat("LOW", day.readings.min().toString(), day.accent)
                            Stat("AVERAGE", day.readings.average().toInt().toString(), day.accent)
                            Stat("HIGH", day.readings.max().toString(), day.accent)
                            Stat("READINGS", day.readings.size.toString(), day.accent)
                        }
                    }
                }
            }
        } else if (day.readings.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                if (dayOffset == 0) "No readings yet today." else "Nothing recorded that day.",
                color = Ink.muted, fontSize = 14.sp
            )
        } else {
            // One reading is a reading, not an empty day; it just cannot be drawn as a trend.
            Spacer(Modifier.height(16.dp))
            Text("One reading so far today.", color = Ink.muted, fontSize = 14.sp)
        }

        if (day.canMeasure && dayOffset == 0) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onMeasure,
                enabled = !busy,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = day.accent,
                    contentColor = Ink.canvas,
                    disabledContainerColor = day.accent.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(if (busy) "Measuring…" else "Measure now", fontSize = 16.sp) }

            // Continuous streaming, for a walk or a workout: readings keep arriving until it is
            // turned off, rather than one measurement at a time.
            onStream?.let { toggle ->
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = toggle,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (streaming) day.accent.copy(alpha = 0.22f) else Color.Transparent,
                        contentColor = day.accent
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(
                        if (streaming) "Stop continuous tracking" else "Track continuously",
                        fontSize = 15.sp
                    )
                }
            }
        }

        if (day.hours.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text(
                "BY THE HOUR", color = Ink.muted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp
            )
            Spacer(Modifier.height(4.dp))
            // An hour the ring never reported on is not the same as an hour spent still, so
            // hours with no readings at all are left out rather than shown as zero.
            day.hours.filter { it.slots.isNotEmpty() }.asReversed().forEach { hour ->
                HourRow(hour, day.accent)
            }
        } else if (day.entries.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text(
                "READINGS", color = Ink.muted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp
            )
            Spacer(Modifier.height(10.dp))
            // Newest first: the recent ones are the ones being looked for.
            day.entries.asReversed().take(60).forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(timeLabel.format(entry.at), color = Ink.muted, fontSize = 14.sp)
                    Text(
                        if (entry.kind == "pressure") "${entry.value}/${entry.extra}"
                        else entry.value.toString(),
                        color = Ink.text, fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/** Every sixth hour, so the bars above can be read against a time of day. */
@Composable
private fun HourAxis() {
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00", "06", "12", "18", "23").forEach {
            Text(it, color = Ink.muted, fontSize = 10.sp)
        }
    }
}

/**
 * One hour, which opens to show the quarter hours that made it up.
 *
 * The hour is the figure worth reading, so a day is a short list of hours rather than a wall of
 * ninety-six rows. The quarters answer "when in that hour", which is a question you only
 * sometimes have, so they stay folded away until asked for.
 */
@Composable
private fun HourRow(hour: Steps.Hour, accent: Color) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(
        Modifier
            .fillMaxWidth()
            .clickableNoRippleShared { open = !open }
            .animateContentSize()
            .padding(top = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand",
                    tint = Ink.muted,
                    modifier = Modifier.size(16.dp).rotate(turn)
                )
                Spacer(Modifier.width(8.dp))
                Text("%02d:00".format(hour.hour), color = Ink.text, fontSize = 15.sp)
            }
            Text(
                if (hour.steps > 0) "%,d".format(hour.steps) else "—",
                color = if (hour.steps > 0) accent else Ink.muted,
                fontSize = 15.sp,
                fontWeight = if (hour.steps > 0) FontWeight.Medium else FontWeight.Normal
            )
        }
        if (open) {
            hour.slots.forEach { slot ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, top = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("%02d:%02d".format(slot.hour, slot.minute), color = Ink.muted, fontSize = 13.sp)
                    Text(
                        if (slot.steps > 0) "%,d".format(slot.steps) else "0",
                        color = Ink.muted, fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DayPicker(offset: Int, onDay: (Int) -> Unit) {
    val when_ = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Arrow(Icons.Rounded.ChevronLeft, true) { onDay(offset - 1) }
        Text(
            when (offset) {
                0 -> "Today"
                -1 -> "Yesterday"
                else -> dayLabel.format(when_.time)
            },
            color = Ink.text, fontSize = 17.sp
        )
        // Nothing to see in the future.
        Arrow(Icons.Rounded.ChevronRight, offset < 0) { if (offset < 0) onDay(offset + 1) }
    }
}

@Composable
private fun Arrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Ink.card)
            .then(if (enabled) Modifier.clickableNoRippleShared(onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, null,
            tint = if (enabled) Ink.text else Ink.muted.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun Stat(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink.text, fontSize = 19.sp)
        Text(label, color = accent.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 1.sp)
    }
}
