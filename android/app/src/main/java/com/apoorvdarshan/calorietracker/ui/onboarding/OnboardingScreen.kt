package com.apoorvdarshan.calorietracker.ui.onboarding

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingFlat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Man
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SportsKabaddi
import androidx.compose.material.icons.outlined.Woman
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
        if (ui.step != OnboardingStep.WELCOME && ui.step != OnboardingStep.BUILDING_PLAN && ui.step != OnboardingStep.REVIEW) {
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
                    contentDescription = stringResource(R.string.onboarding_back),
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
                OnboardingStep.HEIGHT_WEIGHT -> HeightWeightStep(
                    cm = ui.heightCm,
                    kg = ui.weightKg,
                    useMetric = ui.useMetric,
                    onHeightChange = vm::setHeight,
                    onWeightChange = vm::setWeight,
                    onToggle = vm::setUseMetric
                )
                OnboardingStep.BODY_FAT -> BodyFatStep(
                    bodyFat = ui.bodyFatPercentage,
                    onChange = vm::setBodyFat
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
                    currentKg = ui.weightKg,
                    targetKg = ui.goalWeightKg,
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
                OnboardingStep.BUILDING_PLAN -> BuildingPlanStep(onComplete = vm::next)
                OnboardingStep.PLAN_READY -> PlanReadyStep(state = ui, vm = vm)
                OnboardingStep.REVIEW -> ReviewStep()
            }
        }

        when (ui.step) {
            OnboardingStep.WELCOME -> {
                // iOS Welcome: full-width pink-gradient "Get Started" capsule.
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
                                Brush.horizontalGradient(
                                    listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                                )
                            )
                            .clickable { vm.next() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.action_get_started),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            OnboardingStep.BUILDING_PLAN -> {
                // Auto-advancing animation; no CTA. Reserve the same footer
                // height so layout doesn't jump when we land on this step.
                Spacer(Modifier.height(54.dp + 36.dp + 24.dp))
            }
            OnboardingStep.PLAN_READY -> {
                // Plan Ready advances to the Review/Rate step instead of finishing.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 36.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                            )
                        )
                        .clickable { vm.next() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.action_continue),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            OnboardingStep.REVIEW -> {
                // iOS review step: pink-gradient "Rate fud" primary + "Maybe Later"
                // secondary text button. "Rate fud" opens the Play Store listing
                // (market://details?id=<pkg> with web fallback) and then completes
                // onboarding so the user lands on Home regardless of whether they
                // actually rate.
                val ctx = LocalContext.current
                fun openPlayStore() {
                    val market = Uri.parse("market://details?id=${ctx.packageName}")
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, market)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure {
                        ctx.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 24.dp, bottom = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                                )
                            )
                            .clickable {
                                openPlayStore()
                                vm.complete(onComplete)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.onboarding_review_rate),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { vm.complete(onComplete) }) {
                        Text(
                            stringResource(R.string.onboarding_review_maybe_later),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                        )
                    }
                }
            }
            else -> {
                // iOS continueButton: full-width inverse-coloured capsule.
                Button(
                    onClick = { vm.next() },
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
                        stringResource(R.string.action_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
            contentDescription = stringResource(R.string.onboarding_logo_description),
            modifier = Modifier.size(120.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.onboarding_welcome_line1),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        // Second line of the headline uses the pink gradient as a foreground
        // brush — matches iOS .foregroundStyle(LinearGradient(...)).
        Text(
            stringResource(R.string.onboarding_welcome_line2),
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
            stringResource(R.string.onboarding_welcome_subtitle),
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
        StepHeader(
            stringResource(R.string.onboarding_gender_title),
            subtitle = stringResource(R.string.onboarding_gender_subtitle)
        )
        Spacer(Modifier.weight(1f))
        for (g in Gender.values()) {
            SelectionCard(
                icon = when (g) {
                    Gender.MALE -> Icons.Outlined.Man
                    Gender.FEMALE -> Icons.Outlined.Woman
                    Gender.OTHER -> Icons.Outlined.Accessibility
                },
                title = stringResource(g.displayNameRes),
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
        StepHeader(
            stringResource(R.string.onboarding_birthday_title),
            subtitle = stringResource(R.string.onboarding_birthday_subtitle)
        )
        Spacer(Modifier.weight(1f))
        DateWheelPicker(selected = current, onSelect = onChange)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HeightWeightStep(
    cm: Int,
    kg: Double,
    useMetric: Boolean,
    onHeightChange: (Int) -> Unit,
    onWeightChange: (Double) -> Unit,
    onToggle: (Boolean) -> Unit
) {
    // iOS combines height + weight onto a single onboarding step. The
    // Imperial layout shows three columns (Feet | Inches | Weight) and the
    // Metric layout shows two (Height | Weight). Match that.
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(R.string.onboarding_height_weight_title),
            subtitle = stringResource(R.string.onboarding_height_weight_subtitle)
        )
        UnitToggle(
            leftLabel = stringResource(R.string.onboarding_imperial),
            rightLabel = stringResource(R.string.onboarding_metric),
            // useMetric=false → Imperial selected (left segment).
            isLeft = !useMetric,
            onSelect = { isLeftSel -> onToggle(!isLeftSel) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.weight(1f))
        if (useMetric) {
            HeightWeightMetricWheels(
                cm = cm,
                kg = kg,
                onHeightChange = onHeightChange,
                onWeightChange = onWeightChange
            )
        } else {
            HeightWeightImperialWheels(
                cm = cm,
                kg = kg,
                onHeightChange = onHeightChange,
                onWeightChange = onWeightChange
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HeightWeightMetricWheels(
    cm: Int,
    kg: Double,
    onHeightChange: (Int) -> Unit,
    onWeightChange: (Double) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        WheeledColumn(label = stringResource(R.string.onboarding_height), modifier = Modifier.weight(1f)) {
            NumericWheelPicker(value = cm, onValueChange = onHeightChange, min = 100, max = 250, unit = stringResource(R.string.unit_cm))
        }
        WheeledColumn(label = stringResource(R.string.onboarding_weight), modifier = Modifier.weight(1f)) {
            NumericWheelPicker(
                value = kg.toInt().coerceIn(30, 250),
                onValueChange = { onWeightChange(it.toDouble()) },
                min = 30,
                max = 250,
                unit = stringResource(R.string.unit_kg)
            )
        }
    }
}

@Composable
private fun HeightWeightImperialWheels(
    cm: Int,
    kg: Double,
    onHeightChange: (Int) -> Unit,
    onWeightChange: (Double) -> Unit
) {
    val totalInches = (cm / 2.54).toInt().coerceIn(36, 96)
    val feet = (totalInches / 12).coerceIn(3, 8)
    val inches = (totalInches % 12).coerceIn(0, 11)
    val lbs = (kg * 2.20462).toInt().coerceIn(60, 500)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        WheeledColumn(label = stringResource(R.string.onboarding_feet), modifier = Modifier.weight(1f)) {
            NumericWheelPicker(
                value = feet,
                onValueChange = { newFt ->
                    val newCm = ((newFt * 12 + inches) * 2.54).toInt()
                    onHeightChange(newCm)
                },
                min = 3,
                max = 8,
                unit = stringResource(R.string.unit_ft)
            )
        }
        WheeledColumn(label = stringResource(R.string.onboarding_inches), modifier = Modifier.weight(1f)) {
            NumericWheelPicker(
                value = inches,
                onValueChange = { newIn ->
                    val newCm = ((feet * 12 + newIn) * 2.54).toInt()
                    onHeightChange(newCm)
                },
                min = 0,
                max = 11,
                unit = stringResource(R.string.unit_in)
            )
        }
        WheeledColumn(label = stringResource(R.string.onboarding_weight), modifier = Modifier.weight(1f)) {
            NumericWheelPicker(
                value = lbs,
                onValueChange = { newLbs -> onWeightChange(newLbs / 2.20462) },
                min = 60,
                max = 500,
                unit = stringResource(R.string.unit_lbs)
            )
        }
    }
}

@Composable
private fun WheeledColumn(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ActivityStep(selected: ActivityLevel, onSelect: (ActivityLevel) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(R.string.onboarding_activity_title),
            subtitle = stringResource(R.string.onboarding_activity_subtitle)
        )
        for (a in ActivityLevel.values()) {
            SelectionCard(
                icon = activityIcon(a),
                title = stringResource(a.displayNameRes),
                subtitle = stringResource(a.subtitleRes),
                selected = a == selected
            ) { onSelect(a) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun activityIcon(level: ActivityLevel): ImageVector = when (level) {
    ActivityLevel.SEDENTARY -> Icons.Outlined.Chair
    ActivityLevel.LIGHT -> Icons.AutoMirrored.Outlined.DirectionsWalk
    ActivityLevel.MODERATE -> Icons.AutoMirrored.Outlined.DirectionsRun
    ActivityLevel.ACTIVE -> Icons.Outlined.LocalFireDepartment
    ActivityLevel.VERY_ACTIVE -> Icons.Outlined.FitnessCenter
    ActivityLevel.EXTRA_ACTIVE -> Icons.Outlined.SportsKabaddi
}

@Composable
private fun GoalStep(selected: WeightGoal, onSelect: (WeightGoal) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(R.string.onboarding_goal_title),
            subtitle = stringResource(R.string.onboarding_goal_subtitle)
        )
        Spacer(Modifier.weight(1f))
        for (g in WeightGoal.values()) {
            SelectionCard(
                icon = goalIcon(g),
                title = stringResource(g.displayNameRes),
                selected = g == selected
            ) { onSelect(g) }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun goalIcon(goal: WeightGoal): ImageVector = when (goal) {
    WeightGoal.LOSE -> Icons.AutoMirrored.Outlined.TrendingDown
    WeightGoal.MAINTAIN -> Icons.AutoMirrored.Outlined.TrendingFlat
    WeightGoal.GAIN -> Icons.AutoMirrored.Outlined.TrendingUp
}

@Composable
private fun GoalWeightStep(current: Double, goal: WeightGoal, useMetric: Boolean, onChange: (Double) -> Unit, onToggle: (Boolean) -> Unit) {
    // Same Imperial/Metric toggle as HeightWeightStep so the user can switch
    // units without backing out to change Settings first.
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(R.string.onboarding_desired_weight_title),
            subtitle = stringResource(goal.displayNameRes)
        )
        UnitToggle(
            leftLabel = stringResource(R.string.onboarding_imperial),
            rightLabel = stringResource(R.string.onboarding_metric),
            isLeft = !useMetric,
            onSelect = { isLeftSel -> onToggle(!isLeftSel) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.weight(1f))
        if (useMetric) {
            NumericWheelPicker(
                value = current.toInt().coerceIn(30, 250),
                onValueChange = { onChange(it.toDouble()) },
                min = 30,
                max = 250,
                unit = stringResource(R.string.unit_kg)
            )
        } else {
            val lbs = (current * 2.20462).toInt().coerceIn(60, 500)
            NumericWheelPicker(
                value = lbs,
                onValueChange = { newLbs -> onChange(newLbs / 2.20462) },
                min = 60,
                max = 500,
                unit = stringResource(R.string.unit_lbs)
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BodyFatStep(bodyFat: Double?, onChange: (Double?) -> Unit) {
    // Mirrors iOS: Yes/No SelectionCards. "No" reveals a small explanatory
    // ƒ(x) message; "Yes" reveals a body-fat % wheel picker.
    val knows = bodyFat != null
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(R.string.onboarding_body_fat_title),
            subtitle = stringResource(R.string.onboarding_body_fat_subtitle)
        )
        SelectionCard(
            icon = Icons.Outlined.CheckCircle,
            title = stringResource(R.string.onboarding_yes),
            selected = knows,
            onClick = { if (!knows) onChange(0.20) }
        )
        Spacer(Modifier.height(12.dp))
        SelectionCard(
            icon = Icons.Outlined.Cancel,
            title = stringResource(R.string.onboarding_no),
            selected = !knows,
            onClick = { if (knows) onChange(null) }
        )
        Spacer(Modifier.height(20.dp))
        if (knows) {
            DecimalWheelPicker(
                value = (bodyFat ?: 0.20) * 100,
                onValueChange = { onChange(it / 100.0) },
                min = 3.0,
                max = 60.0,
                step = 0.5,
                unit = stringResource(R.string.unit_percent)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_body_fat_ranges),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "ƒ(x)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_body_fat_no_worries),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun GoalSpeedStep(
    weeklyKg: Double,
    goal: WeightGoal,
    useMetric: Boolean,
    currentKg: Double,
    targetKg: Double,
    onSelect: (Double) -> Unit
) {
    // iOS goalSpeedStep: MAINTAIN shows a centered "Balanced pace set" card; LOSE/GAIN
    // show a big weekly-change readout, a tortoise/hare/bolt row, a 3-stop slider
    // (0.25/0.5/1.0 kg/wk), and an estimated-days card.
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            title = if (goal == WeightGoal.MAINTAIN) stringResource(R.string.onboarding_pace_title_maintain)
                    else stringResource(R.string.onboarding_pace_title_change),
            subtitle = when {
                goal == WeightGoal.MAINTAIN -> stringResource(R.string.onboarding_pace_subtitle_maintain)
                goal == WeightGoal.LOSE -> stringResource(R.string.onboarding_pace_subtitle_lose)
                else -> stringResource(R.string.onboarding_pace_subtitle_gain)
            }
        )
        if (goal == WeightGoal.MAINTAIN) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = AppColors.Protein,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.onboarding_pace_balanced_set),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.onboarding_pace_balanced_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.weight(1f))
        } else {
            val idx = when {
                kotlin.math.abs(weeklyKg - 0.25) < 0.01 -> 0
                kotlin.math.abs(weeklyKg - 1.0) < 0.01 -> 2
                else -> 1
            }
            val unit = if (useMetric) stringResource(R.string.unit_kg) else stringResource(R.string.unit_lbs)
            val display = if (useMetric) String.format(Locale.US, "%.1f", weeklyKg)
                          else String.format(Locale.US, "%.1f", weeklyKg * 2.20462)
            val diffKg = kotlin.math.abs(targetKg - currentKg)
            val estimatedDays = if (weeklyKg > 0) (diffKg / weeklyKg * 7).toInt() else 0
            Spacer(Modifier.weight(1f))
            // Weekly change readout
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$display $unit",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.onboarding_pace_per_week),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }
            Spacer(Modifier.height(20.dp))
            // tortoise / hare / bolt icons with labels
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PaceIcon(Icons.AutoMirrored.Outlined.DirectionsWalk, stringResource(R.string.onboarding_pace_slow), idx == 0)
                PaceIcon(Icons.AutoMirrored.Outlined.DirectionsRun, stringResource(R.string.onboarding_pace_recommended), idx == 1)
                PaceIcon(Icons.Outlined.Bolt, stringResource(R.string.onboarding_pace_fast), idx == 2)
            }
            Spacer(Modifier.height(12.dp))
            // Slider with 3 stops
            androidx.compose.material3.Slider(
                value = idx.toFloat(),
                onValueChange = { v ->
                    val newIdx = v.toInt().coerceIn(0, 2)
                    val kg = when (newIdx) { 0 -> 0.25; 2 -> 1.0; else -> 0.5 }
                    onSelect(kg)
                },
                valueRange = 0f..2f,
                steps = 1,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = AppColors.Calorie,
                    activeTrackColor = AppColors.Calorie,
                    inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(16.dp))
            // Estimated days card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Text(
                            stringResource(R.string.onboarding_pace_reach_prefix),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.onboarding_pace_days_format, estimatedDays),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.Calorie
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (idx) {
                            0 -> stringResource(R.string.onboarding_pace_caption_slow)
                            2 -> stringResource(R.string.onboarding_pace_caption_fast)
                            else -> stringResource(R.string.onboarding_pace_caption_recommended)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PaceIcon(icon: ImageVector, label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AppColors.Calorie
                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (selected) AppColors.Calorie
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun NotificationsStep(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    // iOS notificationsStep: centered bell.badge.fill in pink + big headline
    // "Be reminded to\nlog meals" + subtitle + pink CTA.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onToggle(granted) }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.NotificationsActive,
            contentDescription = null,
            tint = AppColors.Calorie,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.onboarding_notifications_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_notifications_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
                )
                .clickable {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onToggle(true)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (enabled) stringResource(R.string.onboarding_notifications_enabled) else stringResource(R.string.onboarding_notifications_allow),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_notifications_change_anytime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun HealthConnectStep(container: AppContainer, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    // iOS appleHealthStep: heart.fill in pink circle, title "Connect to\nApple Health",
    // feature row list, pink CTA "Connect". Android maps Apple Health → Health Connect.
    val hcLauncher = rememberLauncherForActivityResult(
        container.health.permissionRequestContract()
    ) { granted ->
        onToggle(granted.containsAll(container.health.permissions))
    }
    val available = remember { container.health.isAvailable() }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = AppColors.Calorie
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.onboarding_health_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_health_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.padding(horizontal = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HealthFeatureRow(icon = Icons.Outlined.Restaurant, label = stringResource(R.string.onboarding_health_feature_nutrition))
            HealthFeatureRow(icon = Icons.Outlined.MonitorWeight, label = stringResource(R.string.onboarding_health_feature_weight))
            HealthFeatureRow(icon = Icons.Outlined.Accessibility, label = stringResource(R.string.onboarding_health_feature_body))
        }
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (available)
                        Brush.horizontalGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
                    else
                        Brush.horizontalGradient(listOf(
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f),
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                        ))
                )
                .clickable(enabled = available) {
                    hcLauncher.launch(container.health.permissions)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    !available -> stringResource(R.string.onboarding_health_unavailable)
                    enabled -> stringResource(R.string.onboarding_health_connected)
                    else -> stringResource(R.string.onboarding_health_connect)
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun HealthFeatureRow(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
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
    // iOS aiProviderStep: sparkles icon in circle, "Bring Your Own AI" title,
    // recommended-provider Gemini card with star icon, 3-step setup guide, footer.
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = AppColors.Calorie
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.onboarding_provider_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_provider_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        // Recommended provider card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, AppColors.Calorie.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.onboarding_provider_recommended_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.onboarding_provider_recommended_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Steps card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AiSetupRow("1", stringResource(R.string.onboarding_provider_step_1))
                AiSetupRow("2", stringResource(R.string.onboarding_provider_step_2))
                AiSetupRow("3", stringResource(R.string.onboarding_provider_step_3))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.onboarding_provider_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AiSetupRow(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(AppColors.Calorie),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * iOS BuildingPlanStepView: animated percentage counter, gradient progress bar,
 * and a five-item checklist that ticks off over ~4 seconds, then auto-advances.
 */
@Composable
private fun BuildingPlanStep(onComplete: () -> Unit) {
    val items = listOf(
        stringResource(R.string.onboarding_building_calories) to Icons.Outlined.LocalFireDepartment,
        stringResource(R.string.onboarding_building_carbs) to Icons.Outlined.Restaurant,
        stringResource(R.string.onboarding_building_protein) to Icons.Outlined.FitnessCenter,
        stringResource(R.string.onboarding_building_fats) to Icons.Outlined.Bolt,
        stringResource(R.string.onboarding_building_health_score) to Icons.Filled.Favorite
    )
    var checkedCount by remember { mutableIntStateOf(0) }
    var percent by remember { mutableIntStateOf(0) }
    val targetProgress = checkedCount / items.size.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 400),
        label = "plan_progress"
    )

    LaunchedEffect(Unit) {
        val percentSteps = listOf(20, 40, 60, 80, 100)
        for (i in 0 until items.size) {
            kotlinx.coroutines.delay(700)
            checkedCount = i + 1
            percent = percentSteps[i]
        }
        kotlinx.coroutines.delay(400)
        onComplete()
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.onboarding_building_percent_format, percent),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_building_setting_up),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(AppColors.CalorieStart, AppColors.CalorieEnd, Color(0xFF4A90E2))
                        )
                    )
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.onboarding_building_finalizing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.onboarding_building_recommendation),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            items.forEachIndexed { index, (label, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.onboarding_building_bullet),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    if (index < checkedCount) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * iOS planReadyStep: large gradient-filled calorie number with "daily calories"
 * caption, and three macro cards (Protein, Carbs, Fat) below. Each value is tappable —
 * opens an edit dialog whose value lands in customCalories/customProtein/customCarbs/customFat
 * on the profile that ProfileRepository persists at the end of onboarding.
 */
@Composable
private fun PlanReadyStep(state: OnboardingState, vm: OnboardingViewModel) {
    val profile = state.buildProfile()
    var editing by remember { mutableStateOf<PlanField?>(null) }
    Column(Modifier.fillMaxSize()) {
        StepHeader(
            stringResource(R.string.onboarding_plan_title),
            subtitle = stringResource(R.string.onboarding_plan_subtitle)
        )
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { editing = PlanField.CALORIES }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${profile.effectiveCalories}",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                style = LocalTextStyle.current.copy(
                    brush = Brush.linearGradient(
                        listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
                    )
                )
            )
            Text(
                stringResource(R.string.onboarding_plan_daily_calories),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val macroGradient = listOf(AppColors.CalorieStart, AppColors.CalorieEnd)
            MacroCard(
                label = stringResource(R.string.macro_protein),
                value = profile.effectiveProtein,
                gradient = macroGradient,
                modifier = Modifier.weight(1f).clickable { editing = PlanField.PROTEIN }
            )
            MacroCard(
                label = stringResource(R.string.macro_carbs),
                value = profile.effectiveCarbs,
                gradient = macroGradient,
                modifier = Modifier.weight(1f).clickable { editing = PlanField.CARBS }
            )
            MacroCard(
                label = stringResource(R.string.macro_fat),
                value = profile.effectiveFat,
                gradient = macroGradient,
                modifier = Modifier.weight(1f).clickable { editing = PlanField.FAT }
            )
        }
        editing?.let { field ->
            PlanEditDialog(
                field = field,
                currentValue = when (field) {
                    PlanField.CALORIES -> profile.effectiveCalories
                    PlanField.PROTEIN -> profile.effectiveProtein
                    PlanField.CARBS -> profile.effectiveCarbs
                    PlanField.FAT -> profile.effectiveFat
                },
                onDismiss = { editing = null },
                onSave = { newValue ->
                    when (field) {
                        PlanField.CALORIES -> vm.setCustomCalories(newValue)
                        PlanField.PROTEIN -> vm.setCustomProtein(newValue)
                        PlanField.CARBS -> vm.setCustomCarbs(newValue)
                        PlanField.FAT -> vm.setCustomFat(newValue)
                    }
                    editing = null
                },
                onReset = if (when (field) {
                        PlanField.CALORIES -> state.customCalories != null
                        PlanField.PROTEIN -> state.customProtein != null
                        PlanField.CARBS -> state.customCarbs != null
                        PlanField.FAT -> state.customFat != null
                    }
                ) {
                    {
                        when (field) {
                            PlanField.CALORIES -> vm.setCustomCalories(null)
                            PlanField.PROTEIN -> vm.setCustomProtein(null)
                            PlanField.CARBS -> vm.setCustomCarbs(null)
                            PlanField.FAT -> vm.setCustomFat(null)
                        }
                        editing = null
                    }
                } else null
            )
        }
        if (profile.effectiveCalories < 1200) {
            Spacer(Modifier.height(20.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFF9500).copy(alpha = 0.12f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFFF9500),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            stringResource(R.string.onboarding_plan_doctor_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.onboarding_plan_doctor_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MacroCard(
    label: String,
    value: Int,
    gradient: List<Color>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$value",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(
                        brush = Brush.linearGradient(gradient)
                    )
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    "g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * iOS reviewStep: pink-tinted star inside a circle, "Enjoying fud so far?" hero
 * title, and a two-line subtitle. The Rate fud / Maybe Later CTA is rendered
 * by the screen's footer — same as iOS where the buttons sit outside the body.
 */
@Composable
private fun ReviewStep() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            AppColors.CalorieStart.copy(alpha = 0.10f),
                            Color(0xFFFFCC00).copy(alpha = 0.10f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(64.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_review_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_review_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

private enum class PlanField(@androidx.annotation.StringRes val titleRes: Int, @androidx.annotation.StringRes val unitRes: Int) {
    CALORIES(R.string.onboarding_plan_field_calories, R.string.unit_kcal),
    PROTEIN(R.string.onboarding_plan_field_protein, R.string.unit_g),
    CARBS(R.string.onboarding_plan_field_carbs, R.string.unit_g),
    FAT(R.string.onboarding_plan_field_fat, R.string.unit_g)
}

@Composable
private fun PlanEditDialog(
    field: PlanField,
    currentValue: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onReset: (() -> Unit)?
) {
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.titleRes)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter(Char::isDigit).take(5) },
                singleLine = true,
                suffix = { Text(stringResource(field.unitRes)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let { onSave(it) } },
                enabled = text.toIntOrNull() != null && (text.toIntOrNull() ?: 0) > 0
            ) {
                Text(stringResource(R.string.action_save), color = AppColors.Calorie)
            }
        },
        dismissButton = {
            Row {
                if (onReset != null) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.action_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}
