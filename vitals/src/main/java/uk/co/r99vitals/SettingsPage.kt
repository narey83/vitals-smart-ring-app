package uk.co.r99vitals

import android.content.SharedPreferences
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Who is wearing the ring, and what they want from it.
 *
 * Height, weight, age and sex are not decoration: the ring uses them to turn a step count into
 * a distance and a calorie figure, so getting them wrong makes those two numbers wrong. They
 * are kept here and pushed to the ring, which is why this is a settings page rather than a
 * profile the app merely remembers.
 */
data class Profile(
    val name: String = "",
    val male: Boolean = true,
    val age: Int = 30,
    val heightCm: Int = 175,
    val weightKg: Int = 75,
    /** Which units to show. Height and weight are stored in metric either way. */
    val metric: Boolean = true
) {
    companion object {
        fun read(prefs: SharedPreferences) = Profile(
            name = prefs.getString("name", "") ?: "",
            male = prefs.getBoolean("male", true),
            age = prefs.getInt("age", 30),
            heightCm = prefs.getInt("height", 175),
            weightKg = prefs.getInt("weight", 75),
            metric = prefs.getBoolean("metric", true)
        )
    }

    fun write(prefs: SharedPreferences) = prefs.edit()
        .putString("name", name)
        .putBoolean("male", male)
        .putInt("age", age)
        .putInt("height", heightCm)
        .putInt("weight", weightKg)
        .putBoolean("metric", metric)
        .apply()
}

@Composable
fun SettingsPage(
    profile: Profile,
    state: VitalsState,
    firmware: String?,
    ringAddress: String?,
    nightMode: Int,
    onProfile: (Profile) -> Unit,
    onNightMode: (Int) -> Unit,
    onGoal: (Int) -> Unit,
    onInterval: (Int) -> Unit,
    onRepair: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    // A Scaffold for the same reason Shell has one: it is what keeps content clear of the status
    // and navigation bars. This page is shown instead of Shell rather than inside it, so without
    // its own it would draw underneath both.
    Scaffold(containerColor = Ink.canvas) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 40.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Ink.card)
                    .clickableNoRippleShared(onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = Ink.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text("Settings", color = Ink.text, fontSize = 26.sp, fontWeight = FontWeight.Light)
        }

        Section("YOU")
        Panel {
            Field("Name", profile.name, KeyboardType.Text) { onProfile(profile.copy(name = it)) }
            Divider()
            // Two buttons rather than a dropdown: there are two values the ring accepts.
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("Sex", color = Ink.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Toggle("Male", profile.male) { onProfile(profile.copy(male = true)) }
                Spacer(Modifier.width(8.dp))
                Toggle("Female", !profile.male) { onProfile(profile.copy(male = false)) }
            }
            Divider()
            Number("Age", profile.age, "years") { onProfile(profile.copy(age = it)) }
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("Units", color = Ink.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Toggle("Metric", profile.metric) { onProfile(profile.copy(metric = true)) }
                Spacer(Modifier.width(8.dp))
                Toggle("Imperial", !profile.metric) { onProfile(profile.copy(metric = false)) }
            }
            Divider()
            if (profile.metric) {
                Number("Height", profile.heightCm, "cm") { onProfile(profile.copy(heightCm = it)) }
            } else {
                val (feet, inches) = Units.cmToFeetInches(profile.heightCm)
                FeetInches(feet, inches) { f, i ->
                    onProfile(profile.copy(heightCm = Units.feetInchesToCm(f, i)))
                }
            }
            Divider()
            if (profile.metric) {
                Number("Weight", profile.weightKg, "kg") { onProfile(profile.copy(weightKg = it)) }
            } else {
                Number("Weight", Units.kgToLb(profile.weightKg), "lb") {
                    onProfile(profile.copy(weightKg = Units.lbToKg(it)))
                }
            }
        }
        Note(
            "The ring works out distance and calories from these, so they change what it reports. " +
                "It is always told metric; imperial is only how they are shown here."
        )

        Section("GOALS")
        Panel {
            Number("Daily steps", state.stepGoal, "steps") { onGoal(it) }
        }
        Note("Set on the ring as well as here, so both agree about the day.")

        Section("THE RING")
        Panel {
            listOf(0 to "Off", 15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour")
                .forEachIndexed { i, (minutes, label) ->
                    if (i > 0) Divider()
                    Pick(label, state.interval == minutes) { onInterval(minutes) }
                }
        }
        Note("How often the ring measures on its own, whether or not this app is open.")

        // Deliberately no "set the ring's clock" action here. Writing the clock wipes the
        // ring's stored records, and the ring sets its own clock at midnight anyway, so the
        // button would only ever cost you a day's history.
        Panel {
            Action("Forget this ring and pick another") { onRepair() }
        }

        Section("APPEARANCE")
        Panel {
            listOf(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to "Follow the system",
                AppCompatDelegate.MODE_NIGHT_NO to "Light",
                AppCompatDelegate.MODE_NIGHT_YES to "Dark"
            ).forEachIndexed { i, (mode, label) ->
                if (i > 0) Divider()
                Pick(label, nightMode == mode) { onNightMode(mode) }
            }
        }

        Section("YOUR DATA")
        Panel {
            Action("Export readings") { onExport() }
        }
        Note(
            "Everything stays on this phone. This app has no internet permission, so it cannot " +
                "send your readings anywhere even if it wanted to."
        )

        Section("ABOUT")
        Panel {
            Detail("Ring", ringAddress ?: "not paired")
            Divider()
            Detail("Firmware", firmware ?: "unknown")
            Divider()
            Detail("Battery", state.battery?.let { "$it%" } ?: "unknown")
            Divider()
            Detail("App", BuildConfig.VERSION_NAME)
        }
            Note("Not a medical device. Blood pressure is estimated from the pulse, not measured.")
        }
    }
}

@Composable
private fun Section(text: String) {
    Spacer(Modifier.height(26.dp))
    Text(text, color = Ink.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth()
    ) { Column { content() } }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.canvas.copy(alpha = 0.6f)))
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = Ink.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ink.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink.text, fontSize = 16.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink.motion,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Ink.motion
            ),
            modifier = Modifier.width(170.dp)
        )
    }
}

/** A number with its unit, kept as text while being typed so the field can be emptied. */
@Composable
private fun Number(label: String, value: Int, unit: String, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ink.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = if (value == 0) "" else value.toString(),
            // Ignore anything that is not a number rather than rejecting the whole edit, so a
            // stray character cannot wedge the field.
            onValueChange = { typed -> onChange(typed.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = androidx.compose.ui.text.TextStyle(color = Ink.text, fontSize = 16.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink.motion,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Ink.motion
            ),
            suffix = { Text(unit, color = Ink.muted, fontSize = 13.sp) },
            modifier = Modifier.width(170.dp)
        )
    }
}

@Composable
private fun FeetInches(feet: Int, inches: Int, onChange: (Int, Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Height", color = Ink.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        SmallNumber(feet, "ft") { onChange(it, inches) }
        Spacer(Modifier.width(8.dp))
        SmallNumber(inches.coerceIn(0, 11), "in") { onChange(feet, it.coerceIn(0, 11)) }
    }
}

@Composable
private fun SmallNumber(value: Int, unit: String, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0 && unit == "ft") "" else value.toString(),
        onValueChange = { typed -> onChange(typed.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = androidx.compose.ui.text.TextStyle(color = Ink.text, fontSize = 16.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ink.motion,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Ink.motion
        ),
        suffix = { Text(unit, color = Ink.muted, fontSize = 13.sp) },
        modifier = Modifier.width(105.dp)
    )
}

@Composable
private fun Toggle(label: String, chosen: Boolean, onPick: () -> Unit) {
    Text(
        label,
        color = if (chosen) Ink.canvas else Ink.text,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (chosen) Ink.motion else Ink.canvas)
            .clickableNoRippleShared(onPick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun Pick(label: String, chosen: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableNoRippleShared(onPick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (chosen) Ink.motion else Ink.text, fontSize = 16.sp)
        if (chosen) Icon(Icons.Rounded.Check, null, tint = Ink.motion, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label, color = Ink.text, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickableNoRippleShared(onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Ink.muted, fontSize = 15.sp)
        Text(value, color = Ink.text, fontSize = 15.sp)
    }
}
