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
fun WorkoutPage(state: VitalsState, onStart: (String) -> Unit, onStop: () -> Unit) {
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

        if (state.workout == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Heart rate is measured continuously for the whole session, rather than once " +
                    "every so often.",
                color = Ink.muted, fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(20.dp))
            SPORTS.forEach { (name, icon) ->
                SportRow(name, icon) { onStart(name) }
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
                    PastSession(session)
                    Spacer(Modifier.height(10.dp))
                }
            }
        } else {
            val minutes = ((System.currentTimeMillis() - state.workoutSince) / 60000).toInt()
            Spacer(Modifier.height(10.dp))
            Text(state.workout, color = Ink.text, fontSize = 30.sp)
            Text("$minutes min", color = Ink.muted, fontSize = 15.sp)

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

/** A finished session, with the curve it recorded rather than only its numbers. */
@Composable
private fun PastSession(session: Workouts.Session) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(session.sport, color = Ink.text, fontSize = 17.sp)
                Text(sessionStamp.format(session.at), color = Ink.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${session.minutes} min · average ${session.average} · peak ${session.high}",
                color = Ink.muted, fontSize = 13.sp
            )
            if (session.beats.size > 1) {
                Spacer(Modifier.height(12.dp))
                TrendChart(session.beats, Ink.motion, Modifier.fillMaxWidth().height(72.dp), showScale = false)
            }
        }
    }
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
