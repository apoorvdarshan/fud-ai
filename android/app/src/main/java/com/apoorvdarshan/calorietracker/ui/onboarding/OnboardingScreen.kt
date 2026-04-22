package com.apoorvdarshan.calorietracker.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ActivityLevel
import com.apoorvdarshan.calorietracker.models.Gender
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.ui.components.DateWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.DecimalWheelPicker
import com.apoorvdarshan.calorietracker.ui.components.NumericWheelPicker
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
        Spacer(Modifier.height(24.dp))
        val progress = (ui.step.ordinal + 1).toFloat() / OnboardingStep.values().size.toFloat()
        LinearProgressIndicator(
            progress = { progress },
            color = AppColors.Calorie,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.height(32.dp))

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            when (ui.step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.GENDER -> GenderStep(selected = ui.gender, onSelect = vm::setGender)
                OnboardingStep.BIRTHDAY -> BirthdayStep(current = ui.birthday, onChange = vm::setBirthday)
                OnboardingStep.HEIGHT -> HeightStep(cm = ui.heightCm, onChange = vm::setHeight)
                OnboardingStep.WEIGHT -> WeightStep(kg = ui.weightKg, onChange = vm::setWeight)
                OnboardingStep.ACTIVITY -> ActivityStep(selected = ui.activity, onSelect = vm::setActivity)
                OnboardingStep.GOAL -> GoalStep(selected = ui.goal, onSelect = vm::setGoal)
                OnboardingStep.GOAL_WEIGHT -> GoalWeightStep(current = ui.goalWeightKg, goal = ui.goal, onChange = vm::setGoalWeight)
                OnboardingStep.PROVIDER -> ProviderStep()
                OnboardingStep.REVIEW -> ReviewStep(state = ui)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ui.step != OnboardingStep.WELCOME) {
                TextButton(onClick = { vm.back() }) {
                    Text("Back", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (ui.isLastStep) vm.complete(onComplete) else vm.next() },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Calorie),
                modifier = Modifier.height(52.dp).padding(horizontal = 8.dp)
            ) {
                Text(
                    if (ui.isLastStep) "Finish" else "Next",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.ic_logo),
            contentDescription = "Fud AI logo",
            modifier = Modifier.size(140.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text("Fud AI", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Snap a photo, speak, or type.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Text(
            "AI handles the rest.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Free · Open source · Bring your own API key",
            style = MaterialTheme.typography.labelLarge,
            color = AppColors.Calorie,
            fontWeight = FontWeight.SemiBold
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
    Column {
        StepHeader("How do you identify?")
        for (g in Gender.values()) {
            ChoiceRow(label = g.displayName, selected = g == selected) { onSelect(g) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BirthdayStep(current: LocalDate, onChange: (LocalDate) -> Unit) {
    Column {
        StepHeader("Your birthday?", subtitle = "We use this for BMR math.")
        Spacer(Modifier.height(8.dp))
        DateWheelPicker(selected = current, onSelect = onChange)
        Spacer(Modifier.height(20.dp))
        Text(
            "Age: ${Period.between(current, LocalDate.now()).years}",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun HeightStep(cm: Int, onChange: (Int) -> Unit) {
    Column {
        StepHeader("Your height?")
        NumericWheelPicker(value = cm, onValueChange = onChange, min = 100, max = 250, unit = "cm")
    }
}

@Composable
private fun WeightStep(kg: Double, onChange: (Double) -> Unit) {
    Column {
        StepHeader("Your current weight?")
        DecimalWheelPicker(
            value = kg,
            onValueChange = onChange,
            min = 30.0,
            max = 250.0,
            step = 0.1,
            unit = "kg"
        )
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
private fun GoalWeightStep(current: Double, goal: WeightGoal, onChange: (Double) -> Unit) {
    Column {
        StepHeader(
            "Your target weight?",
            subtitle = if (goal == WeightGoal.MAINTAIN) "Skip — maintaining current weight." else null
        )
        if (goal != WeightGoal.MAINTAIN) {
            DecimalWheelPicker(
                value = current,
                onValueChange = onChange,
                min = 30.0,
                max = 250.0,
                step = 0.1,
                unit = "kg"
            )
        }
    }
}

@Composable
private fun ProviderStep() {
    Column {
        StepHeader("AI Provider")
        Text(
            "Fud AI uses your own API key — no subscription, nothing transmitted through us.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(16.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Calorie.copy(alpha = 0.12f))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Default: Google Gemini", fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Free tier at aistudio.google.com/apikey. You can switch to OpenAI / Claude / any of 13 providers in Settings after onboarding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
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
