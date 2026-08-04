package uk.co.r99vitals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp

enum class Tab(val label: String, val icon: ImageVector, val accent: Color) {
    Today("Today", Icons.Rounded.GridView, Ink.text),
    Heart("Heart", Icons.Rounded.Favorite, Ink.heart),
    Oxygen("SpO₂", Icons.Rounded.Bloodtype, Ink.oxygen),
    Pressure("BP", Icons.Rounded.MonitorHeart, Ink.pressure),
    Steps("Steps", Icons.Rounded.DirectionsWalk, Ink.motion),
    Workout("Workout", Icons.Rounded.FitnessCenter, Ink.motion)
}

/**
 * The tab shell. Today is a glance across everything; the rest are one vital each, with their
 * own chart, statistics and day-by-day history.
 */
@Composable
fun Shell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    state: VitalsState,
    dayOffset: Int,
    onDay: (Int) -> Unit,
    onMeasure: (Int) -> Unit,
    onInterval: () -> Unit,
    onExport: () -> Unit,
    onGoal: () -> Unit,
    onLink: () -> Unit,
    onStartWorkout: (String) -> Unit,
    onStopWorkout: () -> Unit,
    dayFor: (Tab) -> VitalDay
) {
    Scaffold(
        containerColor = Ink.canvas,
        bottomBar = {
            NavigationBar(containerColor = Ink.card) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { onTab(entry) },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink.canvas,
                            selectedTextColor = entry.accent,
                            indicatorColor = entry.accent,
                            unselectedIconColor = Ink.muted,
                            unselectedTextColor = Ink.muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        // Scaffold already accounts for the system bars and the navigation bar, so adding
        // windowInsetsPadding on top counted the status bar twice and left a dead band.
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Today -> VitalsScreen(state, onMeasure, onInterval, onExport, onGoal, onLink)
                Tab.Workout -> WorkoutPage(state, onStartWorkout, onStopWorkout)
                else -> VitalPage(
                    day = dayFor(tab),
                    dayOffset = dayOffset,
                    busy = state.measuring != null,
                    streaming = state.streaming,
                    onDay = onDay,
                    onMeasure = {
                        onMeasure(
                            when (tab) {
                                Tab.Oxygen -> Ring.OXYGEN
                                Tab.Pressure -> Ring.PRESSURE
                                else -> Ring.HEART
                            }
                        )
                    }
                )
            }
        }
    }
}
