package com.apoorvdarshan.calorietracker.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Man
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Woman
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ActivityLevel
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.ui.components.DateWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.DecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.SplitDecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.FeetInchesWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.NumericWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.UnitToggle
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.time.LocalDate
import java.time.Period
import java.util.Locale

@Composable
fun OnboardingScreen(container: AppContainer, onComplete: () -> Unit) {
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory(container))
    val ui by vm.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // iOS shows a chevron-left back button + a thin Capsule progress bar at
        // the top, only on steps 1..N-2 (hidden on Welcome and Review).
        if (ui.step != OnboardingStep.WELCOME && ui.step != OnboardingStep.REVIEW) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { vm.back() }
                )
                val totalSteps = OnboardingStep.values().size
                val progress = ui.step.ordinal.toFloat() / (totalSteps - 1).toFloat()
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onBackground)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(24.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            when (ui.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.GENDER -> GenderStep(selected = ui.gender, onSelect = vm::setGender)
                OnboardingStep.BIRTHDAY -> BirthdayStep(current = ui.birthday, onChange = vm::setBirthday)
                OnboardingStep.HEIGHT -> HeightStep(
                    cm = ui.heightCm,
                    useMetric = ui.useMetric,
                    onChange = vm::setHeight,
                    onToggle = vm::setUseMetric
                )
                OnboardingStep.WEIGHT -> WeightStep(
                    kg = ui.weightKg,
                    useMetric = ui.useMetric,
                    onChange = vm::setWeight,
                    onToggle = vm::setUseMetric
                )
                OnboardingStep.BODY_FAT -> BodyFatStep(
                    bodyFat = ui.bodyFatPercentage,
                    onChange = vm::setBodyFat,
                    onSkip = {
                        vm.setBodyFat(null)
                        vm.next()
                    }
                )
                OnboardingStep.ACTIVITY -> ActivityStep(selected = ui.activity, onSelect = vm::setActivity)
                OnboardingStep.GOAL -> GoalStep(selected = ui.goal, onSelect = vm::setGoal)
                OnboardingStep.GOAL_WEIGHT -> GoalWeightStep(
                    current = ui.goalWeightKg,
                    goal = ui.goal,
                    useMetric = ui.useMetric,
                    onChange = vm::setGoalWeight,
                    onToggle = vm::setUseMetric
                )
                OnboardingStep.GOAL_SPEED -> GoalSpeedStep(
                    weeklyKg = ui.weeklyChangeKg,
                    goal = ui.goal,
                    useMetric = ui.useMetric,
                    onSelect = vm::setWeeklyChange
                )
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(
                    enabled = ui.notificationsEnabled,
                    onToggle = vm::setNotificationsEnabled
                )
                OnboardingStep.HEALTH_CONNECT -> HealthConnectStep(
                    container = container,
                    enabled = ui.healthConnectEnabled,
                    onToggle = vm::setHealthConnectEnabled
                )
                OnboardingStep.PROVIDER -> ProviderStep(
                    provider = ui.aiProvider,
                    apiKey = ui.apiKey,
                    onProviderChange = vm::setAiProvider,
                    onKeyChange = vm::setApiKey
                )
                OnboardingStep.BUILDING_PLAN -> BuildingPlanStep(state = ui)
                OnboardingStep.REVIEW -> ReviewStep(state = ui)
            }
        }

        if (ui.step == OnboardingStep.WELCOME) {
            // iOS Welcome shows a single full-width pink-gradient "Get Started"
            // capsule with no Back button.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                            )
                        )
                        .clickable { vm.next() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Get Started",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            // iOS continueButton: full-width primary-coloured Capsule, height 54,
            // with inverse text. In dark mode that's a white pill with black text;
            // in light mode it's a black pill with white text.
            Button(
                onClick = { if (ui.isLastStep) vm.complete(onComplete) else vm.next() },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)
                    .height(54.dp)
            ) {
                Text(
                    if (ui.isLastStep) "Finish" else "Continue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    // 1:1 port of iOS OnboardingView.welcomeStep — broccoli logo, two-line
    // "Eat Smart, / Live Better" headline (second line uses the pink gradient),
    // and a centered two-line subheading.
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_logo),
            contentDescription = "Fud AI logo",
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Eat Smart,",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        // Second line of the headline uses the pink gradient as a foreground
        // brush — matches iOS .foregroundStyle(LinearGradient(...)).
        Text(
            "Live Better",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            style = LocalTextStyle.current.copy(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                )
            )
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Just snap, track, and thrive.\nYour nutrition, simplified.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String? = null) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        subtitle?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun GenderStep(selected: Gender, onSelect: (Gender) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepHeader("What's your gender?", subtitle = "This helps us calculate your metabolism")
        Spacer(Modifier.weight(1f))
        for (g in Gender.values()) {
            SelectionCard(
                icon = when (g) {
                    Gender.MALE -> Icons.Outlined.Man
                    Gender.FEMALE -> Icons.Outlined.Woman
                    Gender.OTHER -> Icons.Outlined.Accessibility
                },
                title = g.displayName,
                selected = g == selected
            ) { onSelect(g) }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BirthdayStep(current: LocalDate, onChange: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepHeader("When's your birthday?", subtitle = "Used to calculate your daily needs")
        Spacer(Modifier.weight(1f))
        DateWheelPicker(selected = current, onSelect = onChange)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HeightStep(cm: Int, useMetric: Boolean, onChange: (Int) -> Unit, onToggle: (Boolean) -> Unit) {
    Column {
        StepHeader("Your height?")
        UnitToggle(
            leftLabel = "cm",
            rightLabel = "ft / in",
            isLeft = useMetric,
            onSelect = onToggle,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        if (useMetric) {
            NumericWheelPicker(value = cm, onValueChange = onChange, min = 100, max = 250, unit = "cm")
        } else {
            FeetInchesWheelPicker(cm = cm, onValueChange = onChange)
        }
    }
}

@Composable
private fun WeightStep(kg: Double, useMetric: Boolean, onChange: (Double) -> Unit, onToggle: (Boolean) -> Unit) {
    Column {
        StepHeader("Your current weight?")
        UnitToggle(
            leftLabel = "kg",
            rightLabel = "lbs",
            isLeft = useMetric,
            onSelect = onToggle,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        if (useMetric) {
            SplitDecimalWheelPicker(
                value = kg,
                onValueChange = onChange,
                min = 30,
                max = 250,
                unit = "kg"
            )
        } else {
            SplitDecimalWheelPicker(
                value = kg * 2.20462,
                onValueChange = { lbs -> onChange(lbs / 2.20462) },
                min = 66,
                max = 551,
                unit = "lbs"
            )
        }
    }
}

@Composable
private fun ActivityStep(selected: ActivityLevel, onSelect: (ActivityLevel) -> Unit) {
    Column {
        StepHeader("How active are you?", subtitle = "Drives your TDEE multiplier.")
        for (a in ActivityLevel.values()) {
            ChoiceRow(label = a.displayName, subtitle = a.subtitle, selected = a == selected) { onSelect(a) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun GoalStep(selected: WeightGoal, onSelect: (WeightGoal) -> Unit) {
    Column {
        StepHeader("What's your goal?")
        for (g in WeightGoal.values()) {
            ChoiceRow(label = g.displayName, selected = g == selected) { onSelect(g) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GoalWeightStep(current: Double, goal: WeightGoal, useMetric: Boolean, onChange: (Double) -> Unit, onToggle: (Boolean) -> Unit) {
    Column {
        StepHeader(
            "Your target weight?",
            subtitle = if (goal == WeightGoal.MAINTAIN) "Skip — maintaining current weight." else null
        )
        if (goal != WeightGoal.MAINTAIN) {
            UnitToggle(
                leftLabel = "kg",
                rightLabel = "lbs",
                isLeft = useMetric,
                onSelect = onToggle,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            if (useMetric) {
                SplitDecimalWheelPicker(
                    value = current,
                    onValueChange = onChange,
                    min = 30,
                    max = 250,
                    unit = "kg"
                )
            } else {
                SplitDecimalWheelPicker(
                    value = current * 2.20462,
                    onValueChange = { lbs -> onChange(lbs / 2.20462) },
                    min = 66,
                    max = 551,
                    unit = "lbs"
                )
            }
        }
    }
}

@Composable
private fun BodyFatStep(bodyFat: Double?, onChange: (Double?) -> Unit, onSkip: () -> Unit) {
    Column {
        StepHeader(
            "Body fat % (optional)",
            subtitle = "If set, BMR uses Katch-McArdle instead of Mifflin-St Jeor — a bit more accurate."
        )
        val current = bodyFat ?: 0.20
        DecimalWheelPicker(
            value = current * 100,
            onValueChange = { onChange(it / 100.0) },
            min = 5.0,
            max = 60.0,
            step = 0.5,
            unit = "%"
        )
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.Calorie.copy(alpha = 0.12f))
                .clickable(onClick = onSkip)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Skip — I don't know my body fat %",
                color = AppColors.Calorie,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GoalSpeedStep(weeklyKg: Double, goal: WeightGoal, useMetric: Boolean, onSelect: (Double) -> Unit) {
    Column {
        StepHeader(
            "How fast?",
            subtitle = "Pace of ${if (goal == WeightGoal.GAIN) "gain" else "loss"} determines your calorie adjustment."
        )
        val options = listOf(
            Triple(0.25, "Slow & steady", "Sustainable. Easier to stick with."),
            Triple(0.5, "Moderate", "Recommended for most people."),
            Triple(1.0, "Fast", "Aggressive. Higher risk of muscle loss on cuts.")
        )
        for ((kg, label, subtitle) in options) {
            val display = if (useMetric) String.format(Locale.US, "%.2f kg/week", kg)
                          else String.format(Locale.US, "%.2f lbs/week", kg * 2.20462)
            ChoiceRow(
                label = "$label · $display",
                subtitle = subtitle,
                selected = kotlin.math.abs(weeklyKg - kg) < 0.01
            ) { onSelect(kg) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun NotificationsStep(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onToggle(granted) }

    Column {
        StepHeader(
            "Daily reminders?",
            subtitle = "We'll nudge you to log a meal and keep your streak."
        )
        ToggleCard(
            label = "Enable reminders",
            subtitle = "Streak reminder + daily summary.",
            enabled = enabled,
            onToggle = { wantEnabled ->
                if (wantEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onToggle(true)
                    }
                } else {
                    onToggle(false)
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "You can change this anytime in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun HealthConnectStep(container: AppContainer, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val hcLauncher = rememberLauncherForActivityResult(
        container.health.permissionRequestContract()
    ) { granted ->
        onToggle(granted.containsAll(container.health.permissions))
    }

    Column {
        StepHeader(
            "Sync with Health Connect?",
            subtitle = "Mirrors macros + 9 micronutrients + weight to Android's Health Connect so Samsung Health, Fitbit, Withings, etc. see them."
        )
        ToggleCard(
            label = "Use Health Connect",
            subtitle = if (container.health.isAvailable()) "Grant read/write for Weight + Nutrition."
                       else "Health Connect not available on this device.",
            enabled = enabled,
            onToggle = { wantEnabled ->
                if (wantEnabled) {
                    if (container.health.isAvailable()) {
                        hcLauncher.launch(container.health.permissions)
                    } else {
                        // Can't grant — leave it off.
                        onToggle(false)
                    }
                } else {
                    onToggle(false)
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Everything stays on-device unless you enable Health Connect's own cloud sync.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun ToggleCard(label: String, subtitle: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) AppColors.Calorie.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!enabled) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ProviderStep(
    provider: AIProvider,
    apiKey: String,
    onProviderChange: (AIProvider) -> Unit,
    onKeyChange: (String) -> Unit
) {
    Column {
        StepHeader(
            "AI Provider",
            subtitle = "Bring your own key. 13 providers supported — you can switch anytime in Settings."
        )
        // Provider quick-select — just the top 3 most common, rest in Settings later.
        val quick = listOf(AIProvider.GEMINI, AIProvider.OPENAI, AIProvider.ANTHROPIC)
        for (p in quick) {
            ChoiceRow(
                label = p.displayName,
                subtitle = when (p) {
                    AIProvider.GEMINI -> "Free tier at aistudio.google.com/apikey"
                    AIProvider.OPENAI -> "sk-... from platform.openai.com"
                    AIProvider.ANTHROPIC -> "sk-ant-... from console.anthropic.com"
                    else -> null
                },
                selected = p == provider
            ) { onProviderChange(p) }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste your ${provider.displayName} key",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onKeyChange,
            placeholder = { Text(provider.apiKeyPlaceholder) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Optional — you can skip and paste later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun BuildingPlanStep(state: OnboardingState) {
    val profile = state.buildProfile()
    Column {
        StepHeader("Your plan is ready")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Calorie.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Daily calories",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppColors.Calorie,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${profile.effectiveCalories} kcal",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    MacroTile("Protein", "${profile.effectiveProtein}g")
                    MacroTile("Carbs", "${profile.effectiveCarbs}g")
                    MacroTile("Fat", "${profile.effectiveFat}g")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val bmrFormula = if (profile.bodyFatPercentage != null) "Katch-McArdle (body fat-aware)" else "Mifflin-St Jeor"
        Text(
            "BMR formula: $bmrFormula · TDEE ${profile.tdee.toInt()} kcal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
        if (state.goal != WeightGoal.MAINTAIN) {
            val sign = if (state.goal == WeightGoal.LOSE) "-" else "+"
            val weeklyLabel = if (state.useMetric) String.format(Locale.US, "%s%.2f kg/week", sign, state.weeklyChangeKg)
                              else String.format(Locale.US, "%s%.2f lbs/week", sign, state.weeklyChangeKg * 2.20462)
            Text(
                "Target pace: $weeklyLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun MacroTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ReviewStep(state: OnboardingState) {
    Column {
        StepHeader("Looks good?")
        ReviewRow("Gender", state.gender.displayName)
        ReviewRow("Age", "${Period.between(state.birthday, LocalDate.now()).years}")
        ReviewRow("Height", "${state.heightCm} cm")
        ReviewRow("Weight", String.format(Locale.US, "%.1f kg", state.weightKg))
        ReviewRow("Activity", state.activity.displayName)
        ReviewRow("Goal", state.goal.displayName)
        if (state.goal != WeightGoal.MAINTAIN) {
            ReviewRow("Target", String.format(Locale.US, "%.1f kg", state.goalWeightKg))
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * iOS selectionCard parity — rounded card with leading icon, title, optional
 * subtitle, and a trailing checkmark.circle.fill / circle. Selected state adds
 * a 2pt onBackground stroke; matches AppColors.appCard background.
 */
@Composable
private fun SelectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.onBackground
    val baseModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface)
        .clickable(onClick = onClick)
    val outlined = if (selected)
        baseModifier.border(BorderStroke(2.dp, accent), RoundedCornerShape(16.dp))
    else baseModifier
    Box(outlined.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else accent.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 16.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = accent.copy(alpha = 0.55f)
                    )
                }
            }
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) accent else accent.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ChoiceRow(label: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        Brush.linearGradient(listOf(AppColors.CalorieStart.copy(alpha = 0.18f), AppColors.CalorieEnd.copy(alpha = 0.10f)))
    } else {
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) AppColors.Calorie else Color.Transparent)
                    .padding(3.dp)
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.95f))
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}
