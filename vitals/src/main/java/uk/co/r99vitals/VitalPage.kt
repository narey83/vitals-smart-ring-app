package uk.co.r99vitals

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
    /** The lower half of a blood pressure reading; empty for every vital that has only one number. */
    val diastolic: List<Int> = emptyList(),
    val note: String? = null,
    val canMeasure: Boolean = true,
    /** Steps accumulate, so they read as hourly bars rather than a climbing line. */
    val asBars: Boolean = false,
    /** Where each reading sits in the day, 0 to 1, so the line can be drawn against a clock. */
    val positions: List<Float> = emptyList(),
    /** The day's readings in the order they arrived; the page shows them newest first. */
    val rows: List<Reading> = emptyList()
)

/**
 * One reading, already worded.
 *
 * What a reading says differs by vital — steps add up, a pressure has two halves — so it
 * arrives formatted. This is a shape for showing, not for calculating in. [manual] marks a
 * reading the wearer asked for rather than one the ring took on its own schedule.
 */
data class Reading(val at: String, val value: String, val manual: Boolean = false)

private val dayLabel = SimpleDateFormat("EEEE d MMMM", Locale.UK)

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

        // Blood pressure is the one vital where the number alone is not the answer.
        if (day.diastolic.isNotEmpty() && day.readings.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            PressureVerdict(day.readings.last(), day.diastolic.last())
        }

        if (day.readings.size > 1) {
            Spacer(Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Ink.card),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 20.dp, horizontal = 16.dp)) {
                    if (day.diastolic.isNotEmpty() && day.diastolic.size == day.readings.size) {
                        PressureChart(
                            day.readings, day.diastolic, day.positions,
                            Modifier.fillMaxWidth().height(190.dp)
                        )
                        if (day.positions.size == day.readings.size) HourAxis(SCALE_GUTTER)
                        Spacer(Modifier.height(10.dp))
                        PressureKey()
                    } else if (day.asBars) {
                        BarChart(day.readings, day.accent, Modifier.fillMaxWidth().height(150.dp))
                        HourAxis()
                    } else {
                        TrendChart(
                            day.readings, day.accent,
                            Modifier.fillMaxWidth().height(150.dp),
                            positions = day.positions
                        )
                        // Only honest when the line is drawn against time rather than evenly.
                        if (day.positions.size == day.readings.size) HourAxis(SCALE_GUTTER)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (day.diastolic.size == day.readings.size && day.diastolic.isNotEmpty()) {
                            // Each half is summarised on its own: the day's highest systolic and
                            // its highest diastolic rarely belong to the same reading.
                            fun pair(pick: List<Int>.() -> Int) =
                                "${day.readings.pick()}/${day.diastolic.pick()}"
                            Stat("LOW", pair { min() }, day.accent)
                            Stat("AVERAGE", pair { average().toInt() }, day.accent)
                            Stat("HIGH", pair { max() }, day.accent)
                            Stat("READINGS", day.readings.size.toString(), day.accent)
                        } else if (day.asBars) {
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

        if (day.rows.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            // Shut to begin with, and shut again when the day changes. A day of automatic
            // readings is dozens of rows, and the chart above already says what they say — the
            // list is for looking something up, not for scrolling past every time.
            var open by remember(day.title, dayOffset) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickableNoRippleShared { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "READINGS · ${day.rows.size}", color = Ink.muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp
                )
                Icon(
                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (open) "Hide the readings" else "Show all ${day.rows.size} readings",
                    tint = Ink.muted, modifier = Modifier.size(20.dp)
                )
            }
            if (open) {
                Spacer(Modifier.height(4.dp))
                // Newest first: the recent ones are the ones being looked for.
                day.rows.asReversed().forEach { ReadingRow(it, day.accent) }
            }
        }
    }
}

/** Every sixth hour, so the bars above can be read against a time of day. */
@Composable
private fun HourAxis(endInset: Dp = 0.dp) {
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().padding(end = endInset),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("00", "06", "12", "18", "23").forEach {
            Text(it, color = Ink.muted, fontSize = 10.sp)
        }
    }
}

/** One reading: where it came from, when it happened, what it said. */
@Composable
private fun ReadingRow(row: Reading, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Where a reading came from belongs next to when it happened: a reading the wearer
        // stood still for is worth telling apart from one the ring took on its own schedule
        // while they were doing something else.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (row.manual) Icons.Rounded.TouchApp else Icons.Rounded.Timer,
                if (row.manual) "Measured by you" else "Measured on the ring's interval",
                tint = if (row.manual) accent else Ink.muted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(row.at, color = Ink.text, fontSize = 14.sp)
        }
        Text(
            row.value,
            color = if (row.manual) accent else Ink.muted,
            fontSize = 14.sp
        )
    }
}

@Composable
internal fun DayPicker(offset: Int, onDay: (Int) -> Unit) {
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
