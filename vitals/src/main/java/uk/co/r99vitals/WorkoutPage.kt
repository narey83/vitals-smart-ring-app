package uk.co.r99vitals

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsGymnastics
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The sports this ring reports as supported; the rest of the SDK's list it does not implement. */
val SPORTS = listOf(
    "Walk" to Icons.Rounded.DirectionsWalk,
    "Run" to Icons.Rounded.DirectionsRun,
    "Ride" to Icons.Rounded.DirectionsBike,
    "Yoga" to Icons.Rounded.SelfImprovement,
    "Other" to Icons.Rounded.SportsGymnastics
)

@Composable
fun WorkoutPage(
    state: VitalsState,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onRelabel: (Long, String) -> Unit = { _, _ -> }
) {
    var pending by remember { mutableStateOf<String?>(null) }
    // Which finished session has its sports opened for correction, by its start time.
    var correcting by remember { mutableStateOf<Long?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp)
    ) {
        Text(
            "WORKOUT", color = Ink.motion, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp
        )

        if (state.workout == null && pending == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Heart rate is measured continuously for the whole session, rather than once " +
                    "every so often. Walks and runs are found on their own after five minutes " +
                    "of steady pace, whether or not the app is open — start one here to name " +
                    "the sport yourself, or for anything the step counter cannot see.",
                color = Ink.muted, fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(20.dp))
            SPORTS.forEach { (name, icon) ->
                SportRow(name, icon) { pending = name }
                Spacer(Modifier.height(10.dp))
            }

            if (state.pastWorkouts.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Text(
                    "EARLIER", color = Ink.muted, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp
                )
                Spacer(Modifier.height(12.dp))
                state.pastWorkouts.asReversed().take(25).forEach { session ->
                    PastSession(
                        session = session,
                        correcting = correcting == session.at.time,
                        onCorrect = {
                            correcting = if (correcting == session.at.time) null else session.at.time
                        },
                        onRelabel = { sport -> onRelabel(session.at.time, sport); correcting = null }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        } else if (state.workout == null) {
            val sport = pending!!
            Spacer(Modifier.height(18.dp))
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Ink.card)) {
                Column(Modifier.padding(22.dp)) {
                    Text("Ready for $sport?", color = Ink.text, fontSize = 26.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.connected) "Ring connected${state.battery?.let { " · $it% battery" } ?: ""}"
                        else "Ring disconnected — reconnect it before starting.",
                        color = if (state.connected) Ink.motion else Ink.heart, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Heart rate will be measured continuously until you finish the workout.", color = Ink.muted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { onStart(sport); pending = null }, enabled = state.connected,
                        shape = CircleShape, modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.motion, contentColor = Ink.canvas)
                    ) { Text("Start workout", fontSize = 16.sp) }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { pending = null }, shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink.canvas, contentColor = Ink.text)
                    ) { Text("Choose another activity") }
                }
            }
        } else {
            val minutes = ((System.currentTimeMillis() - state.workoutSince) / 60000).toInt()
            Spacer(Modifier.height(10.dp))
            Text(state.workout, color = Ink.text, fontSize = 30.sp)
            Text("$minutes min", color = Ink.muted, fontSize = 15.sp)
            if (state.workoutDetected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Found by your pace, and timed from when you set off. Finish it here, or " +
                        "leave it — it ends itself once you stop, and the sport can be " +
                        "corrected afterwards.",
                    color = Ink.muted, fontSize = 13.sp, lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    state.workoutBeats.lastOrNull()?.toString() ?: "––",
                    color = Ink.text, fontSize = 72.sp, fontWeight = FontWeight.Light
                )
                Spacer(Modifier.width(10.dp))
                Text("bpm", color = Ink.muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
            }

            if (state.workoutBeats.size > 1) {
                Spacer(Modifier.height(18.dp))
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Ink.card),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(vertical = 20.dp, horizontal = 16.dp)) {
                        // The session's own curve, built as it happens.
                        TrendChart(state.workoutBeats, Ink.motion, Modifier.fillMaxWidth().height(150.dp))
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Figure("LOW", state.workoutBeats.min().toString())
                            Figure("AVERAGE", state.workoutBeats.average().toInt().toString())
                            Figure("PEAK", state.workoutBeats.max().toString())
                            Figure("READINGS", state.workoutBeats.size.toString())
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(16.dp))
                Text("Waiting for the first reading…", color = Ink.muted, fontSize = 14.sp)
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onStop,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink.heart, contentColor = Ink.canvas
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Finish workout", fontSize = 16.sp) }
        }
    }
}

/**
 * A finished session, with the curve it recorded rather than only its numbers.
 *
 * A detected one says so, and its sport can be corrected: the app worked it out from cadence
 * alone, which tells a walk from a run and nothing else, so the wearer has the last word.
 */
@Composable
private fun PastSession(
    session: Workouts.Session,
    correcting: Boolean = false,
    onCorrect: () -> Unit = {},
    onRelabel: (String) -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(session.sport, color = Ink.text, fontSize = 17.sp)
                    if (session.detected) {
                        Spacer(Modifier.width(8.dp))
                        Badge()
                    }
                }
                Text(sessionStamp.format(session.at), color = Ink.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("${session.minutes} min")
                    if (session.steps > 0) append(" · %,d steps".format(session.steps))
                    if (session.beats.isNotEmpty()) {
                        append(" · average ${session.average} · peak ${session.high}")
                    }
                },
                color = Ink.muted, fontSize = 13.sp
            )
            if (session.beats.size > 1) {
                Spacer(Modifier.height(12.dp))
                TrendChart(session.beats, Ink.motion, Modifier.fillMaxWidth().height(72.dp), showScale = false)
            }
            if (session.detected) {
                Spacer(Modifier.height(12.dp))
                if (correcting) {
                    Text("It was really:", color = Ink.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    // Five of them do not fit across a narrow phone, and a wrapped row of chips
                    // reads worse than one that slides.
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        SPORTS.forEach { (name, _) ->
                            Chip(name, chosen = name == session.sport) { onRelabel(name) }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                } else {
                    Text(
                        "Change sport",
                        color = Ink.motion, fontSize = 13.sp,
                        modifier = Modifier.clickableNoRippleShared(onCorrect)
                    )
                }
            }
        }
    }
}

/** Marks a session the app found rather than one the wearer started. */
@Composable
private fun Badge() {
    Text(
        "FOUND",
        color = Ink.motion, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier
            .background(Ink.motion.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun Chip(label: String, chosen: Boolean, onPick: () -> Unit) {
    Text(
        label,
        color = if (chosen) Ink.canvas else Ink.text, fontSize = 12.sp,
        modifier = Modifier
            .background(if (chosen) Ink.motion else Ink.canvas, CircleShape)
            .clickableNoRippleShared(onPick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

private val sessionStamp = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.UK)

@Composable
private fun SportRow(name: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth().height(64.dp).clickableNoRippleShared(onClick)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Ink.motion, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(name, color = Ink.text, fontSize = 17.sp)
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink.text, fontSize = 19.sp)
        Text(label, color = Ink.motion.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 1.sp)
    }
}
