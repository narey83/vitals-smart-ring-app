package uk.co.r99vitals

import android.content.SharedPreferences
import android.os.Build
import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** What is shown and asked; the ring itself only ever gets [Profile.maleForRing]. */
enum class Sex { Male, Female, PreferNotToSay }

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
    val sex: Sex = Sex.Male,
    /**
     * The day itself, as an epoch day, or 0 when it has never been set.
     *
     * A birthday is a fact; an age is what that fact means today, and it changes without anyone
     * editing it. Storing the age instead means it silently goes stale — which is why [storedAge]
     * exists only to keep profiles that predate this field working, and is never written again.
     */
    val birthday: Long = 0L,
    val storedAge: Int = 30,
    val heightCm: Int = 175,
    val weightKg: Int = 75,
    /** Which units to show. Height and weight are stored in metric either way. */
    val metric: Boolean = true,
    /** Read separately from [metric]: feet and inches alongside kilos is a real preference. */
    val weightUnit: WeightUnit = WeightUnit.Stones,
    /** Unset until chosen — see [Ring.setSkinTone]. Nothing is sent to the ring until it is. */
    val skinTone: SkinTone? = null
) {
    companion object {
        fun read(prefs: SharedPreferences) = Profile(
            name = prefs.getString("name", "") ?: "",
            sex = prefs.getString("sex", null)
                ?.let { name -> runCatching { Sex.valueOf(name) }.getOrNull() }
            // Profiles written before sex had its own setting only ever recorded the two the
            // ring accepts.
                ?: if (prefs.getBoolean("male", true)) Sex.Male else Sex.Female,
            birthday = prefs.getLong("birthday", 0L),
            storedAge = prefs.getInt("age", 30),
            heightCm = prefs.getInt("height", 175),
            weightKg = prefs.getInt("weight", 75),
            metric = prefs.getBoolean("metric", true),
            weightUnit = prefs.getString("weightUnit", null)
                ?.let { name -> runCatching { WeightUnit.valueOf(name) }.getOrNull() }
            // Profiles written before weight had its own setting are carried across from what
            // the metric and stones switches meant between them.
                ?: when {
                    prefs.getBoolean("metric", true) -> WeightUnit.Kg
                    prefs.getBoolean("stones", true) -> WeightUnit.Stones
                    else -> WeightUnit.Pounds
                },
            skinTone = prefs.getString("skinTone", null)
                ?.let { name -> runCatching { SkinTone.valueOf(name) }.getOrNull() }
        )
    }

    /** The birthday as a date, or null when it has not been set. */
    val born: java.time.LocalDate? get() = birthday.takeIf { it > 0 }?.let { java.time.LocalDate.ofEpochDay(it) }

    /** Age today, worked out from the birthday, falling back to whatever was stored before. */
    val age: Int
        get() = born?.let { java.time.Period.between(it, java.time.LocalDate.now()).years } ?: storedAge

    /** True on the day itself, which is a thing worth noticing. */
    val birthdayToday: Boolean
        get() = born?.let {
            val today = java.time.LocalDate.now()
            it.monthValue == today.monthValue && it.dayOfMonth == today.dayOfMonth
        } ?: false

    /** The ring's setUserInfo only has the one bit; asked not to say defaults to what it always did. */
    val maleForRing: Boolean get() = sex != Sex.Female

    /** What the Today greeting says — a surname in "Good morning" reads like a form letter. */
    val firstName: String get() = name.trim().substringBefore(' ')

    fun write(prefs: SharedPreferences) = prefs.edit()
        .putString("name", name)
        .putString("sex", sex.name)
        .putLong("birthday", birthday)
        .putInt("height", heightCm)
        .putInt("weight", weightKg)
        .putBoolean("metric", metric)
        .putString("weightUnit", weightUnit.name)
        .putString("skinTone", skinTone?.name)
        .apply()
}

@Composable
fun SettingsPage(
    profile: Profile,
    state: VitalsState,
    firmware: String?,
    ringAddress: String?,
    nightMode: Int,
    plan: SleepPlan,
    onPlan: (SleepPlan) -> Unit,
    onProfile: (Profile) -> Unit,
    onNightMode: (Int) -> Unit,
    onGoal: (Int) -> Unit,
    onInterval: (Int) -> Unit,
    onMonitors: (Ring.Monitors) -> Unit,
    onRepair: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    // A Scaffold for the same reason Shell has one: it is what keeps content clear of the status
    // and navigation bars. This page is shown instead of Shell rather than inside it, so without
    // its own it would draw underneath both.
    var editing by remember { mutableStateOf(Editing.None) }
    WheelSheet(
        editing = editing,
        profile = profile,
        stepGoal = state.stepGoal,
        plan = plan,
        onPlan = onPlan,
        onProfile = onProfile,
        onGoal = onGoal,
        onDismiss = { editing = Editing.None }
    )
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
            Field("Name", profile.name) { onProfile(profile.copy(name = it)) }
            Divider()
            SettingRow("Sex") {
                Toggle("Male", profile.sex == Sex.Male) { onProfile(profile.copy(sex = Sex.Male)) }
                Spacer(Modifier.width(8.dp))
                Toggle("Female", profile.sex == Sex.Female) { onProfile(profile.copy(sex = Sex.Female)) }
                Spacer(Modifier.width(8.dp))
                // Short label here: three chips in a row this narrow have no room for the full
                // "Prefer not to say" that the onboarding screen can afford.
                Toggle("Skip", profile.sex == Sex.PreferNotToSay) {
                    onProfile(profile.copy(sex = Sex.PreferNotToSay))
                }
            }
            Divider()
            Value(
                "Birthday",
                profile.born?.format(birthdayFormat) ?: "Not set"
            ) { editing = Editing.Birthday }
            Divider()
            // Read only: it follows from the birthday rather than being another thing to keep true.
            SettingRow("Age") {
                Text("${profile.age} years", color = Ink.muted, fontSize = 16.sp)
            }
            Divider()
            SettingRow("Units") {
                Toggle("Metric", profile.metric) { onProfile(profile.copy(metric = true)) }
                Spacer(Modifier.width(8.dp))
                Toggle("Imperial", !profile.metric) { onProfile(profile.copy(metric = false)) }
            }
            Divider()
            Value("Height", Units.height(profile.heightCm, profile.metric)) {
                editing = Editing.Height
            }
            Divider()
            Value("Weight", Units.weight(profile.weightKg, profile.weightUnit)) {
                editing = Editing.Weight
            }
            Divider()
            SettingRow("Skin tone") {
                Row {
                    SkinTone.entries.forEachIndexed { i, tone ->
                        if (i > 0) Spacer(Modifier.width(6.dp))
                        Swatch(tone, profile.skinTone == tone) { onProfile(profile.copy(skinTone = tone)) }
                    }
                }
            }
        }
        Note(
            "The ring works out distance and calories from height and weight, so they change " +
                "what it reports. Skin tone calibrates its optical sensor instead — heart rate, " +
                "oxygen and blood pressure all read less reliably on darker skin without it. " +
                "Everything here is always told metric; imperial is only how it is shown."
        )

        Section("GOALS")
        Panel {
            Value("Daily steps", "%,d".format(state.stepGoal)) { editing = Editing.Goal }
        }
        Note("Set on the ring as well as here, so both agree about the day.")

        Section("SLEEP")
        Panel {
            Value("Bedtime", clockOf(plan.bedtime)) { editing = Editing.Bedtime }
            Divider()
            Value("Wake time", clockOf(plan.wake)) { editing = Editing.WakeTime }
            Divider()
            Pick("Remind me ${plan.lead} minutes before bedtime", plan.remind) {
                onPlan(plan.copy(remind = !plan.remind))
            }
            Divider()
            Pick("Sleep report when I unlock in the morning", plan.report) {
                onPlan(plan.copy(report = !plan.report))
            }
        }
        Note(
            "Those hours are what a night is scored against — ${Sleep.spell(plan.target)} between " +
                "them — so the score follows your schedule rather than a number this app picked. " +
                "The report waits until you pick the phone up, since a notification at " +
                "${clockOf(plan.wake)} would arrive while you were still asleep."
        )

        Section("THE RING")
        Panel {
            listOf(0 to "Off", 15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour")
                .forEachIndexed { i, (minutes, label) ->
                    if (i > 0) Divider()
                    Pick(label, state.interval == minutes) { onInterval(minutes) }
                }
        }
        Note("How often the ring measures on its own, whether or not this app is open.")

        Panel {
            Pick("Heart rate", state.monitors.heart) {
                onMonitors(state.monitors.copy(heart = !state.monitors.heart))
            }
            Divider()
            Pick("Blood oxygen", state.monitors.oxygen) {
                onMonitors(state.monitors.copy(oxygen = !state.monitors.oxygen))
            }
            Divider()
            Pick("Blood pressure", state.monitors.pressure) {
                onMonitors(state.monitors.copy(pressure = !state.monitors.pressure))
            }
        }
        Note(
            "Which of them it takes at that interval. Blood pressure comes from the vendor's " +
                "command table and has never been confirmed on this ring — turn it on and see " +
                "whether readings actually arrive."
        )

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
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    // Tapping the label, or the gap after it, puts the cursor in the field. Aiming at the text
    // itself is a smaller target than the row it sits in.
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    SettingRow(label, onClick = { focus.requestFocus() }) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink.text, fontSize = 16.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Ink.motion),
            // Done finishes the edit and puts the cursor away; without it the field keeps focus
            // and carries on blinking long after the name has been typed.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.widthIn(min = 40.dp).focusRequester(focus),
            decorationBox = { field ->
                if (value.isEmpty()) {
                    Text("Not set", color = Ink.muted, fontSize = 16.sp, textAlign = TextAlign.End)
                }
                field()
            }
        )
    }
}

/** Which value a wheel is currently being shown for, if any. */
private enum class Editing { None, Birthday, Height, Weight, Goal, Bedtime, WakeTime }

internal val birthdayFormat = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")
internal val monthNames = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/**
 * The shape every settings row takes.
 *
 * One height, one padding, one place the label sits and one the value does. Rows that each set
 * their own spacing look approximately aligned, which reads worse than being plainly wrong.
 */
@Composable
private fun SettingRow(
    label: String,
    labelColour: Color = Ink.muted,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(if (onClick != null) Modifier.clickableNoRippleShared(onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = labelColour, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** A row that states its value and opens a wheel when tapped, anywhere along it. */
@Composable
private fun Value(label: String, value: String, onOpen: () -> Unit) {
    SettingRow(label, onClick = onOpen) {
        Text(value, color = Ink.text, fontSize = 16.sp)
    }
}

/**
 * The wheel itself, in a sheet rather than in the row.
 *
 * A settings list is for seeing what everything is set to at a glance, which a column of
 * wheels is not: they are tall, they compete for the same drag as the page, and they bury the
 * value being read. Tapping a row brings the wheel up over it and it goes away again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WheelSheet(
    editing: Editing,
    profile: Profile,
    stepGoal: Int,
    plan: SleepPlan,
    onPlan: (SleepPlan) -> Unit,
    onProfile: (Profile) -> Unit,
    onGoal: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (editing == Editing.None) return

    // Each wheel holds its own number while the sheet is open. Driving them all from one date
    // meant setting the month rewrote the day and the year underneath the finger, which is the
    // jumping: the wheels were latched together. They are assembled into a date on the way out.
    val start = profile.born ?: java.time.LocalDate.now().minusYears(30)
    var day by remember(editing) { mutableStateOf(start.dayOfMonth) }
    var month by remember(editing) { mutableStateOf(start.monthValue) }
    var year by remember(editing) { mutableStateOf(start.year) }

    // Feet and inches, and stones and pounds, have the same problem the date had: both halves
    // are worked out from one stored number, so moving either recomputes the other and it
    // shifts under the finger. Each half keeps its own value and writes through to the profile.
    var feet by remember(editing) { mutableStateOf(Units.cmToFeetInches(profile.heightCm).first) }
    var inches by remember(editing) { mutableStateOf(Units.cmToFeetInches(profile.heightCm).second) }
    var stone by remember(editing) { mutableStateOf(Units.kgToStones(profile.weightKg).first) }
    var pounds by remember(editing) { mutableStateOf(Units.kgToStones(profile.weightKg).second) }

    fun finish() {
        if (editing == Editing.Birthday) {
            // 31 February is reachable on unlatched wheels and is not a date, so the day is
            // pulled back to the end of whatever month was chosen.
            val length = java.time.YearMonth.of(year, month).lengthOfMonth()
            val date = java.time.LocalDate.of(year, month, minOf(day, length))
            onProfile(profile.copy(birthday = date.toEpochDay()))
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { finish() },
        containerColor = Ink.card,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(36.dp).height(4.dp).clip(CircleShape)
                        .background(Ink.muted.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                when (editing) {
                    Editing.Birthday -> "Birthday"
                    Editing.Height -> "Height"
                    Editing.Weight -> "Weight"
                    Editing.Bedtime -> "Bedtime"
                    Editing.WakeTime -> "Wake time"
                    else -> "Daily step goal"
                },
                color = Ink.text, fontSize = 22.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(18.dp))
            when (editing) {
                Editing.Birthday -> {
                    Row(horizontalArrangement = Arrangement.Center) {
                        // A full 1 to 31 whatever the month: a wheel that resizes under the
                        // finger is the thing being complained about.
                        Picker(day, 1..31, { "$it" }, Modifier.width(80.dp)) { day = it }
                        Spacer(Modifier.width(8.dp))
                        Picker(month, 1..12, { monthNames[it - 1] }, Modifier.width(100.dp)) { month = it }
                        Spacer(Modifier.width(8.dp))
                        Picker(year, 1920..java.time.LocalDate.now().year, { "$it" }, Modifier.width(100.dp)) {
                            year = it
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Says what will actually be saved, including the day being pulled back.
                    val length = java.time.YearMonth.of(year, month).lengthOfMonth()
                    Text(
                        java.time.LocalDate.of(year, month, minOf(day, length)).format(birthdayFormat),
                        color = Ink.muted, fontSize = 14.sp
                    )
                }
                Editing.Height -> if (profile.metric) {
                    Picker(profile.heightCm, 120..220, { "$it cm" }, Modifier.width(160.dp)) {
                        onProfile(profile.copy(heightCm = it))
                    }
                } else {
                    // Feet and inches are two wheels, because they are two numbers.
                    Row(horizontalArrangement = Arrangement.Center) {
                        Picker(feet, 3..7, { "$it ft" }, Modifier.width(120.dp)) {
                            feet = it
                            onProfile(profile.copy(heightCm = Units.feetInchesToCm(it, inches)))
                        }
                        Spacer(Modifier.width(12.dp))
                        Picker(inches, 0..11, { "$it in" }, Modifier.width(120.dp)) {
                            inches = it
                            onProfile(profile.copy(heightCm = Units.feetInchesToCm(feet, it)))
                        }
                    }
                }
                Editing.Weight -> {
                    // All three offered together, whatever Units says: weight is the one people
                    // are most particular about, and it does not follow from height.
                    Row {
                        Toggle("Kilos", profile.weightUnit == WeightUnit.Kg) {
                            onProfile(profile.copy(weightUnit = WeightUnit.Kg))
                        }
                        Spacer(Modifier.width(8.dp))
                        Toggle("Stones", profile.weightUnit == WeightUnit.Stones) {
                            onProfile(profile.copy(weightUnit = WeightUnit.Stones))
                        }
                        Spacer(Modifier.width(8.dp))
                        Toggle("Pounds", profile.weightUnit == WeightUnit.Pounds) {
                            onProfile(profile.copy(weightUnit = WeightUnit.Pounds))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (profile.weightUnit == WeightUnit.Stones) {
                        Row(horizontalArrangement = Arrangement.Center) {
                            Picker(stone, 4..31, { "$it st" }, Modifier.width(110.dp)) {
                                stone = it
                                onProfile(profile.copy(weightKg = Units.stonesToKg(it, pounds)))
                            }
                            Spacer(Modifier.width(12.dp))
                            Picker(pounds, 0..13, { "$it lb" }, Modifier.width(110.dp)) {
                                pounds = it
                                onProfile(profile.copy(weightKg = Units.stonesToKg(stone, it)))
                            }
                        }
                    } else if (profile.weightUnit == WeightUnit.Pounds) {
                        Picker(Units.kgToLb(profile.weightKg), 66..440, { "$it lb" }, Modifier.width(160.dp)) {
                            onProfile(profile.copy(weightKg = Units.lbToKg(it)))
                        }
                    } else {
                        Picker(profile.weightKg, 30..200, { "$it kg" }, Modifier.width(160.dp)) {
                            onProfile(profile.copy(weightKg = it))
                        }
                    }
                }
                // Hours and minutes as two wheels, the same as feet and inches: two numbers.
                // Five-minute steps, because nobody means twenty-three minutes past eleven.
                Editing.Bedtime, Editing.WakeTime -> {
                    val minutes = if (editing == Editing.Bedtime) plan.bedtime else plan.wake
                    fun set(value: Int) = onPlan(
                        if (editing == Editing.Bedtime) plan.copy(bedtime = value)
                        else plan.copy(wake = value)
                    )
                    Row(horizontalArrangement = Arrangement.Center) {
                        Picker(minutes / 60, 0..23, { "%02d".format(it) }, Modifier.width(110.dp)) {
                            set(it * 60 + minutes % 60)
                        }
                        Spacer(Modifier.width(12.dp))
                        Picker(minutes % 60, 0..55 step 5, { "%02d".format(it) }, Modifier.width(110.dp)) {
                            set((minutes / 60) * 60 + it)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "${clockOf(plan.bedtime)} to ${clockOf(plan.wake)} · ${Sleep.spell(plan.target)}",
                        color = Ink.muted, fontSize = 14.sp
                    )
                }
                // Whole hundreds: nobody sets a goal of 10,137.
                else -> Picker(stepGoal, 1_000..30_000 step 500, { "%,d".format(it) }, Modifier.width(180.dp)) {
                    onGoal(it)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Done",
                color = Ink.motion, fontSize = 17.sp,
                modifier = Modifier.clip(CircleShape).clickableNoRippleShared { finish() }
                    .padding(horizontal = 40.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * A scroll wheel, which is Android's own NumberPicker rather than something rebuilt in Compose.
 *
 * A wheel suits these values better than a keyboard: they sit in a known range, they are
 * adjusted rather than composed, and there is no way to type a number the ring would reject.
 * The platform already has one that scrolls and reads correctly, so it is used as it is.
 */
@Composable
internal fun Picker(
    value: Int,
    range: IntProgression,
    format: (Int) -> String,
    modifier: Modifier,
    onChange: (Int) -> Unit
) {
    val choices = remember(range) { range.toList() }
    val labels = remember(choices) { choices.map(format).toTypedArray() }
    val ink = Ink.text.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            NumberPicker(context).apply {
                // Order matters: the displayed labels are rejected until the range fits them.
                minValue = 0
                maxValue = choices.lastIndex
                displayedValues = labels
                wrapSelectorWheel = false
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                setOnValueChangedListener { _, _, index -> onChange(choices[index]) }
            }
        },
        update = { picker ->
            // The factory runs once, so a range that changed since then has to be applied here
            // or the wheel keeps showing the old one.
            if (picker.maxValue != choices.lastIndex) {
                picker.displayedValues = null
                picker.maxValue = choices.lastIndex
                picker.displayedValues = labels
            }
            // The palette is ours rather than the platform's, so the wheel is told about it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) picker.textColor = ink
            // Nearest match, so a weight converted from pounds still lands on the wheel.
            val index = choices.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: choices.lastIndex
            if (picker.value != index) picker.value = index
        }
    )
}

/** Fitzpatrick-scale swatches, the same reference points as the ring's own six-level setting. */
private val skinSwatches = mapOf(
    SkinTone.Lightest to Color(0xFFFFDBAC),
    SkinTone.Light to Color(0xFFF1C27D),
    SkinTone.Medium to Color(0xFFE0AC69),
    SkinTone.Tan to Color(0xFFC68642),
    SkinTone.Brown to Color(0xFF8D5524),
    SkinTone.Deepest to Color(0xFF4A2C17)
)

@Composable
internal fun Swatch(tone: SkinTone, chosen: Boolean, onPick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (chosen) Ink.motion else Color.Transparent)
            .padding(3.dp)
            .clip(CircleShape)
            .background(skinSwatches.getValue(tone))
            .clickableNoRippleShared(onPick)
    )
}

@Composable
internal fun Toggle(label: String, chosen: Boolean, onPick: () -> Unit) {
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
    SettingRow(label, labelColour = if (chosen) Ink.motion else Ink.text, onClick = onPick) {
        if (chosen) Icon(Icons.Rounded.Check, null, tint = Ink.motion, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    SettingRow(label, labelColour = Ink.text, onClick = onClick) {}
}

@Composable
private fun Detail(label: String, value: String) {
    SettingRow(label) { Text(value, color = Ink.text, fontSize = 15.sp) }
}
