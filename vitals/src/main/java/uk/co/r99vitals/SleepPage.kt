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
import androidx.compose.ui.text.style.TextAlign
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
fun SleepPage(recorded: List<Sleep.Night>, target: Int = SleepInsight.TARGET_ASLEEP) {
    // The ring splits a broken night into two records; they are one night to the wearer.
    val nights = SleepInsight.merge(recorded)
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
        val (score, parts) = SleepInsight.score(last, target)
        Spacer(Modifier.height(10.dp))
        Text(whenItWas(last), color = Ink.muted, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Sleep.spell(last.asleep), color = Ink.text, fontSize = 56.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(10.dp))
            Text("asleep", color = Ink.muted, fontSize = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        Text(
            "${nightClock.format(Date(last.startedAt))} – ${nightClock.format(Date(last.endedAt))}" +
                " · ${Sleep.spell(last.inBed)} in bed",
            color = Ink.muted, fontSize = 14.sp
        )

        Spacer(Modifier.height(18.dp))
        if (SleepInsight.isFragment(last)) {
            // Saying "Poor" about half an hour the ring happened to catch would be a judgement on
            // sleep that was never recorded.
            Note(
                "Too short to score — the ring recorded only part of this night, so it is left " +
                    "out of your averages."
            )
            Spacer(Modifier.height(14.dp))
        } else {
            ScoreCard(score, parts)
            Spacer(Modifier.height(14.dp))
        }
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

        Spacer(Modifier.height(22.dp))
        WeekCard(nights)

        Spacer(Modifier.height(14.dp))
        MonthCard(nights, target)

        Spacer(Modifier.height(14.dp))
        TipsCard(nights)

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
        Text(Sleep.spell(seconds), color = Ink.text, fontSize = 16.sp)
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
                // The morning it ended, which is the day the rest of the page files it under.
                Text(nightStamp.format(SleepInsight.day(night)), color = Ink.text, fontSize = 16.sp)
                Text(Sleep.spell(night.asleep), color = Ink.text, fontSize = 16.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${nightClock.format(Date(night.startedAt))} – " +
                    "${nightClock.format(Date(night.endedAt))} · " +
                    "deep ${Sleep.spell(night.seconds(Sleep.DEEP))} · " +
                    "REM ${Sleep.spell(night.seconds(Sleep.REM))}",
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
    return if (ago < 30 * 60 * 60 * 1000L) "Last night" else nightStamp.format(SleepInsight.day(night))
}


/** A plain statement in a card, for the times there is nothing to draw. */
@Composable
private fun Note(text: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = Ink.muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(18.dp))
    }
}

/**
 * The night out of a hundred, with the four parts that made it.
 *
 * The parts are not decoration. A score with nothing behind it is a number to be believed or
 * ignored; showing which part was short makes it a number that can be acted on.
 */
@Composable
private fun ScoreCard(score: Int, parts: List<SleepInsight.Part>) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$score", color = Ink.sleep, fontSize = 44.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(8.dp))
                Text(
                    SleepInsight.verdict(score), color = Ink.text, fontSize = 17.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Text("out of 100", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(12.dp))
            parts.forEach { part ->
                PartRow(part)
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}

@Composable
private fun PartRow(part: SleepInsight.Part) {
    val filled = Ink.sleep
    val track = Ink.muted.copy(alpha = 0.22f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(part.label, color = Ink.text, fontSize = 13.sp, modifier = Modifier.width(74.dp))
        Canvas(Modifier.weight(1f).height(6.dp)) {
            drawRoundRect(color = track, size = size, cornerRadius = CornerRadius(3f, 3f))
            drawRoundRect(
                color = filled,
                size = Size(size.width * part.got / part.of, size.height),
                cornerRadius = CornerRadius(3f, 3f)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(part.detail, color = Ink.muted, fontSize = 12.sp, modifier = Modifier.width(84.dp))
    }
}

/** The last seven days, gaps included, each night stacked deep to REM against the same scale. */
@Composable
private fun WeekCard(nights: List<Sleep.Night>) {
    val week = SleepInsight.week(nights)
    // Fragments are drawn — they happened — but kept out of the average, as they are out of the
    // month's. An average that counted half an hour the ring caught would be a lie about the week.
    val slept = week.mapNotNull { day -> day.night?.takeUnless { SleepInsight.isFragment(it) }?.asleep }
    val deep = tint(Sleep.DEEP)
    val light = tint(Sleep.LIGHT)
    val rem = tint(Sleep.REM)
    val rule = Ink.muted.copy(alpha = 0.30f)
    val empty = Ink.muted.copy(alpha = 0.12f)
    // Scaled against nine hours, or a longer night if there is one, so the bars mean the same
    // thing from week to week rather than being rescaled by whatever the best night happened to be.
    val ceiling = maxOf(9 * 3600, slept.maxOrNull() ?: 0).toFloat()
    val average = if (slept.isEmpty()) 0 else slept.average().toInt()

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("THIS WEEK", color = Ink.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(
                    if (slept.isEmpty()) "no nights yet" else "average ${Sleep.spell(average)}",
                    color = Ink.text, fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val slot = size.width / 7f
                val barWidth = slot * 0.52f
                week.forEachIndexed { index, day ->
                    val left = index * slot + (slot - barWidth) / 2f
                    val night = day.night
                    if (night == null) {
                        // An empty day is drawn as an empty day. Skipping it would let a gap read
                        // as a week of solid sleep.
                        drawRoundRect(
                            color = empty,
                            topLeft = Offset(left, size.height - 4f),
                            size = Size(barWidth, 4f),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                        return@forEachIndexed
                    }
                    var bottom = size.height
                    listOf(Sleep.DEEP to deep, Sleep.LIGHT to light, Sleep.REM to rem).forEach { (code, colour) ->
                        val height = (night.seconds(code) / ceiling) * size.height
                        if (height <= 0f) return@forEach
                        drawRect(
                            color = colour,
                            topLeft = Offset(left, bottom - height),
                            size = Size(barWidth, height)
                        )
                        bottom -= height
                    }
                }
                if (average > 0) {
                    val y = size.height - (average / ceiling) * size.height
                    // Dashes rather than a solid line: it is a summary of the bars, not a target.
                    var x = 0f
                    while (x < size.width) {
                        drawRect(color = rule, topLeft = Offset(x, y), size = Size(10f, 1.5f))
                        x += 18f
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Text(
                        weekdayLetter.format(day.at),
                        color = if (day.night == null) Ink.muted.copy(alpha = 0.5f) else Ink.muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val fragments = week.count { it.night != null && SleepInsight.isFragment(it.night) }
            Text(
                "${week.count { it.night != null }} of 7 nights recorded" +
                    if (fragments > 0) " · $fragments too short to count" else "",
                color = Ink.muted, fontSize = 12.sp
            )
        }
    }
}

/** The month so far: what a night usually is, and the two that stood out. */
@Composable
private fun MonthCard(nights: List<Sleep.Night>, target: Int) {
    val month = SleepInsight.month(nights, target = target)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                monthName.format(Date()).uppercase(), color = Ink.muted, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(10.dp))
            if (month.average == 0) {
                Text(
                    "No full night recorded this month yet.",
                    color = Ink.muted, fontSize = 13.sp
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Sleep.spell(month.average), color = Ink.text, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(8.dp))
                    Text("a night, on average", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                month.best?.let { StandoutRow("Best", it, target, Ink.sleep) }
                month.worst?.let {
                    Spacer(Modifier.height(6.dp))
                    StandoutRow("Worst", it, target, Ink.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${month.recorded} of ${month.ofDays} nights recorded",
                color = Ink.muted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun StandoutRow(label: String, night: Sleep.Night, target: Int, accent: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = accent, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Text(shortDay.format(SleepInsight.day(night)), color = Ink.text, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            "${Sleep.spell(night.asleep)} · ${SleepInsight.score(night, target).first}",
            color = Ink.muted, fontSize = 13.sp
        )
    }
}

/**
 * What the nights themselves show, once there are enough of them — and until then, the ordinary
 * advice, labelled as such so the two are never mistaken for each other.
 */
@Composable
private fun TipsCard(nights: List<Sleep.Night>) {
    val found = SleepInsight.patterns(nights)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (found.isEmpty()) "WORTH TRYING" else "WHAT YOUR NIGHTS SHOW",
                color = Ink.sleep, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(12.dp))
            (found.ifEmpty { SleepInsight.ADVICE }).forEach { line ->
                Row {
                    Text("·", color = Ink.sleep, fontSize = 13.sp, modifier = Modifier.width(14.dp))
                    Text(line, color = Ink.muted, fontSize = 13.sp, lineHeight = 19.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (found.isEmpty()) {
                val proper = SleepInsight.merge(nights).count { !SleepInsight.isFragment(it) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$proper of the ${SleepInsight.ENOUGH} nights needed before this can say " +
                        "anything about your own sleep.",
                    color = Ink.muted.copy(alpha = 0.75f), fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }
}

private val weekdayLetter = SimpleDateFormat("EEEEE", Locale.UK)
private val monthName = SimpleDateFormat("MMMM", Locale.UK)
private val shortDay = SimpleDateFormat("EEE d MMM", Locale.UK)
