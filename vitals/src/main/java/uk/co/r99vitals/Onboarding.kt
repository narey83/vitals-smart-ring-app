package uk.co.r99vitals

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * First run, and once more for anyone who already had the app when this landed — see
 * `ONBOARDING_VERSION` in [VitalsActivity]. Order matters: it is the order [advanceOnboarding]
 * and [retreatOnboarding] step through, taken straight from [OnboardingStep.entries].
 */
enum class OnboardingStep {
    Splash, Name, Sex, Birthday, Height, Weight, SkinTone, Goals, Sleep, Notifications, Pairing
}

@Composable
fun OnboardingFlow(
    step: OnboardingStep,
    profile: Profile,
    onProfile: (Profile) -> Unit,
    stepGoal: Int,
    onGoal: (Int) -> Unit,
    plan: SleepPlan,
    onPlan: (SleepPlan) -> Unit,
    /** The same status text the Today header shows while connecting, so Pairing has no story of its own to tell. */
    link: String,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onEnableNotifications: () -> Unit,
    onFindRing: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Ink.canvas)) {
        when (step) {
            OnboardingStep.Splash -> SplashPage(onNext)
            OnboardingStep.Name -> NamePage(profile, onProfile, onNext)
            OnboardingStep.Sex -> SexPage(profile, onProfile, onNext, onBack)
            OnboardingStep.Birthday -> BirthdayPage(profile, onProfile, onNext, onBack)
            OnboardingStep.Height -> HeightPage(profile, onProfile, onNext, onBack)
            OnboardingStep.Weight -> WeightPage(profile, onProfile, onNext, onBack)
            OnboardingStep.SkinTone -> SkinTonePage(profile, onProfile, onNext, onBack)
            OnboardingStep.Goals -> GoalsPage(stepGoal, onGoal, onNext, onBack)
            OnboardingStep.Sleep -> SleepGoalPage(plan, onPlan, onNext, onBack)
            OnboardingStep.Notifications -> NotificationsPage(onEnableNotifications, onNext, onBack)
            OnboardingStep.Pairing -> PairingPage(link, onFindRing, onNext, onBack)
        }
    }
}

/** Announces itself and gets out of the way; there is nothing here to decide. */
@Composable
private fun SplashPage(onNext: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { delay(1100); onNext() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "R99",
            modifier = Modifier.size(140.dp)
        )
    }
}

@Composable
private fun NamePage(profile: Profile, onProfile: (Profile) -> Unit, onNext: () -> Unit) {
    // Split for entry only — the profile still keeps one string, same as it always has, so
    // nothing downstream (Settings, the birthday card) needs to know this page has two fields.
    val parts = remember { profile.name.trim().split(' ', limit = 2) }
    var first by remember { mutableStateOf(parts.getOrElse(0) { "" }) }
    var last by remember { mutableStateOf(parts.getOrElse(1) { "" }) }
    fun commit(f: String, l: String) = onProfile(profile.copy(name = "$f $l".trim()))
    OnboardingScaffold(
        step = OnboardingStep.Name,
        title = "What's your name?",
        subtitle = "Your first name is what shows up in your greeting on Today. Leave both blank " +
            "if you'd rather not say.",
        onBack = null,
        onPrimary = onNext
    ) {
        NameField("First name", "Prefer not to say", first) { first = it; commit(it, last) }
        Spacer(Modifier.height(14.dp))
        NameField("Last name", "Optional", last) { last = it; commit(first, it) }
    }
}

@Composable
private fun NameField(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink.text, unfocusedTextColor = Ink.text,
            focusedBorderColor = Ink.motion, unfocusedBorderColor = Ink.muted,
            focusedLabelColor = Ink.motion, unfocusedLabelColor = Ink.muted,
            cursorColor = Ink.motion
        )
    )
}

@Composable
private fun SexPage(profile: Profile, onProfile: (Profile) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Sex,
        title = "Sex",
        subtitle = "Used with your age to turn steps into distance and calories.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        Row {
            Toggle("Male", profile.sex == Sex.Male) { onProfile(profile.copy(sex = Sex.Male)) }
            Spacer(Modifier.width(8.dp))
            Toggle("Female", profile.sex == Sex.Female) { onProfile(profile.copy(sex = Sex.Female)) }
        }
        Spacer(Modifier.height(8.dp))
        Toggle("Prefer not to say", profile.sex == Sex.PreferNotToSay) {
            onProfile(profile.copy(sex = Sex.PreferNotToSay))
        }
    }
}

@Composable
private fun BirthdayPage(profile: Profile, onProfile: (Profile) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Birthday,
        title = "Birthday",
        subtitle = "Works out your age, which the ring uses the same way it uses your sex.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        BirthdayPicker(profile, onProfile)
    }
}

@Composable
private fun HeightPage(profile: Profile, onProfile: (Profile) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Height,
        title = "Height",
        subtitle = "Pick whichever unit you think in — it's always sent to the ring the same way.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        HeightPicker(profile, onProfile)
    }
}

@Composable
private fun WeightPage(profile: Profile, onProfile: (Profile) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Weight,
        title = "Weight",
        subtitle = "Same idea — stones and pounds if that's more natural.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        WeightPicker(profile, onProfile)
    }
}

@Composable
private fun SkinTonePage(profile: Profile, onProfile: (Profile) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.SkinTone,
        title = "Skin tone",
        subtitle = "Calibrates the ring's optical sensor — heart rate, oxygen and blood pressure all " +
            "read less reliably on darker skin without it.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        Row {
            SkinTone.entries.forEachIndexed { i, tone ->
                if (i > 0) Spacer(Modifier.width(10.dp))
                Swatch(tone, profile.skinTone == tone) { onProfile(profile.copy(skinTone = tone)) }
            }
        }
    }
}

@Composable
private fun BirthdayPicker(profile: Profile, onProfile: (Profile) -> Unit) {
    val start = profile.born ?: java.time.LocalDate.now().minusYears(30)
    var day by remember { mutableStateOf(start.dayOfMonth) }
    var month by remember { mutableStateOf(start.monthValue) }
    var year by remember { mutableStateOf(start.year) }
    fun commit(d: Int, m: Int, y: Int) {
        // 31 February is reachable on unlatched wheels and is not a date — see WheelSheet.
        val length = java.time.YearMonth.of(y, m).lengthOfMonth()
        onProfile(profile.copy(birthday = java.time.LocalDate.of(y, m, minOf(d, length)).toEpochDay()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Picker(day, 1..31, { "$it" }, Modifier.width(70.dp)) { day = it; commit(it, month, year) }
        Spacer(Modifier.width(6.dp))
        Picker(month, 1..12, { monthNames[it - 1] }, Modifier.width(90.dp)) { month = it; commit(day, it, year) }
        Spacer(Modifier.width(6.dp))
        Picker(year, 1920..java.time.LocalDate.now().year, { "$it" }, Modifier.width(90.dp)) {
            year = it; commit(day, month, it)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        if (profile.birthday > 0) "${profile.age} years old" else "Pick a date to work out your age",
        color = Ink.muted, fontSize = 13.sp
    )
}

@Composable
private fun HeightPicker(profile: Profile, onProfile: (Profile) -> Unit) {
    var feet by remember { mutableStateOf(Units.cmToFeetInches(profile.heightCm).first) }
    var inches by remember { mutableStateOf(Units.cmToFeetInches(profile.heightCm).second) }
    Row {
        Toggle("Centimetres", profile.metric) { onProfile(profile.copy(metric = true)) }
        Spacer(Modifier.width(8.dp))
        Toggle("Feet & inches", !profile.metric) { onProfile(profile.copy(metric = false)) }
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (profile.metric) {
            Picker(profile.heightCm, 120..220, { "$it cm" }, Modifier.width(160.dp)) {
                onProfile(profile.copy(heightCm = it))
            }
        } else {
            Row {
                Picker(feet, 3..7, { "$it ft" }, Modifier.width(110.dp)) {
                    feet = it; onProfile(profile.copy(heightCm = Units.feetInchesToCm(it, inches)))
                }
                Spacer(Modifier.width(10.dp))
                Picker(inches, 0..11, { "$it in" }, Modifier.width(110.dp)) {
                    inches = it; onProfile(profile.copy(heightCm = Units.feetInchesToCm(feet, it)))
                }
            }
        }
    }
}

@Composable
private fun WeightPicker(profile: Profile, onProfile: (Profile) -> Unit) {
    var stone by remember { mutableStateOf(Units.kgToStones(profile.weightKg).first) }
    var pounds by remember { mutableStateOf(Units.kgToStones(profile.weightKg).second) }
    Row {
        Toggle("Kilos", profile.weightUnit == WeightUnit.Kg) { onProfile(profile.copy(weightUnit = WeightUnit.Kg)) }
        Spacer(Modifier.width(8.dp))
        Toggle("Stones", profile.weightUnit == WeightUnit.Stones) {
            onProfile(profile.copy(weightUnit = WeightUnit.Stones))
        }
        Spacer(Modifier.width(8.dp))
        Toggle("Pounds", profile.weightUnit == WeightUnit.Pounds) {
            onProfile(profile.copy(weightUnit = WeightUnit.Pounds))
        }
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (profile.weightUnit) {
            WeightUnit.Stones -> Row {
                Picker(stone, 4..31, { "$it st" }, Modifier.width(100.dp)) {
                    stone = it; onProfile(profile.copy(weightKg = Units.stonesToKg(it, pounds)))
                }
                Spacer(Modifier.width(10.dp))
                Picker(pounds, 0..13, { "$it lb" }, Modifier.width(100.dp)) {
                    pounds = it; onProfile(profile.copy(weightKg = Units.stonesToKg(stone, it)))
                }
            }
            WeightUnit.Pounds -> Picker(Units.kgToLb(profile.weightKg), 66..440, { "$it lb" }, Modifier.width(160.dp)) {
                onProfile(profile.copy(weightKg = Units.lbToKg(it)))
            }
            WeightUnit.Kg -> Picker(profile.weightKg, 30..200, { "$it kg" }, Modifier.width(160.dp)) {
                onProfile(profile.copy(weightKg = it))
            }
        }
    }
}

@Composable
private fun GoalsPage(stepGoal: Int, onGoal: (Int) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Goals,
        title = "Daily steps",
        subtitle = "Set on the ring as well as here, so both agree about the day.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Picker(stepGoal, 1_000..30_000 step 500, { "%,d".format(it) }, Modifier.width(180.dp)) { onGoal(it) }
        }
    }
}

@Composable
private fun SleepGoalPage(plan: SleepPlan, onPlan: (SleepPlan) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Sleep,
        title = "Bedtime and waking",
        subtitle = "${Sleep.spell(plan.target)} between them — that's what a night gets scored against.",
        onBack = onBack,
        onPrimary = onNext
    ) {
        FieldLabel("Bedtime")
        TimeWheel(plan.bedtime) { onPlan(plan.copy(bedtime = it)) }
        Spacer(Modifier.height(28.dp))
        FieldLabel("Wake time")
        TimeWheel(plan.wake) { onPlan(plan.copy(wake = it)) }
    }
}

@Composable
private fun TimeWheel(minutes: Int, onChange: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row {
            Picker(minutes / 60, 0..23, { "%02d".format(it) }, Modifier.width(100.dp)) {
                onChange(it * 60 + minutes % 60)
            }
            Spacer(Modifier.width(10.dp))
            Picker(minutes % 60, 0..55 step 5, { "%02d".format(it) }, Modifier.width(100.dp)) {
                onChange((minutes / 60) * 60 + it)
            }
        }
    }
}

@Composable
private fun NotificationsPage(onEnable: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(
        step = OnboardingStep.Notifications,
        title = "Stay on schedule",
        subtitle = "A nudge before bedtime, and a report on last night, whenever you turn those on " +
            "in Settings. Nothing else.",
        onBack = onBack,
        primaryLabel = "Enable notifications",
        onPrimary = onEnable,
        secondaryLabel = "Not now",
        onSecondary = onSkip
    ) {}
}

@Composable
private fun PairingPage(link: String, onFindRing: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    val trouble = link.contains("tap to", ignoreCase = true) || link.contains("wasn't a ring", ignoreCase = true)
    OnboardingScaffold(
        step = OnboardingStep.Pairing,
        title = "Find your ring",
        subtitle = "Turn Bluetooth on and keep the ring nearby — it's checked against the ring's own " +
            "command channel before it's called connected, so nothing else nearby gets mistaken for it.",
        onBack = onBack,
        primaryLabel = if (trouble) "Try again" else "Look for my ring",
        onPrimary = onFindRing,
        secondaryLabel = "Skip for now",
        onSecondary = onSkip
    ) {
        Spacer(Modifier.height(4.dp))
        Text(link, color = Ink.muted, fontSize = 15.sp)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Ink.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(10.dp))
}

/**
 * The shape every onboarding page takes: a title, whatever it asks, and a footer — dots then
 * the way forward — that stays put at the bottom of the screen whether or not the middle needs
 * to scroll to fit. A Scaffold rather than a bare Column for the same reason [Shell] and
 * [SettingsPage] use one: without it, content draws straight under the status bar.
 */
@Composable
private fun OnboardingScaffold(
    step: OnboardingStep,
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    primaryLabel: String = "Continue",
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.material3.Scaffold(
        containerColor = Ink.canvas,
        bottomBar = {
            Column(
                Modifier.fillMaxWidth()
                    // The gesture-nav strip sits under this otherwise; a custom footer has to ask
                    // for that room itself, unlike NavigationBar which already does.
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp, top = 8.dp)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { StepDots(step) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(primaryLabel, onPrimary)
                secondaryLabel?.let { label ->
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            label, color = Ink.muted, fontSize = 14.sp,
                            modifier = Modifier.clickableNoRippleShared { onSecondary?.invoke() }
                                .padding(vertical = 8.dp, horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 16.dp)
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Ink.card)
                        .clickableNoRippleShared(onBack),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Ink.text, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.height(20.dp))
            }
            Text(title, color = Ink.text, fontSize = 28.sp, fontWeight = FontWeight.Light)
            subtitle?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Ink.muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(28.dp))
            content()
        }
    }
}

@Composable
private fun StepDots(step: OnboardingStep) {
    val steps = OnboardingStep.entries.filter { it != OnboardingStep.Splash }
    val current = steps.indexOf(step)
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, _ ->
            if (i > 0) Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(if (i == current) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (i <= current) Ink.motion else Ink.muted.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.motion)
            .clickableNoRippleShared(onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Ink.canvas, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
