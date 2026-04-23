package com.apoorvdarshan.calorietracker.services

import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Dev-only helper that swaps the user's real data for a year of synthetic food + weight
 * entries so the Progress tab can be eyeballed end-to-end. Triggered by launch flags from
 * MainActivity:
 *   adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez seed_test_data true
 *   adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez restore_real_data true
 *
 * `seed` snapshots the live state into a single backup blob, disables Health Connect so the
 * synthetic entries can't sync upstream, then writes 365 days of food + 53 weeks of weights.
 * `restore` puts everything back exactly as it was.
 */
class TestDataSeeder(private val container: AppContainer) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedYear() {
        val backup = SeedBackup(
            entriesJson = json.encodeToString(
                ListSerializer(FoodEntry.serializer()),
                container.foodRepository.entries.first()
            ),
            weightsJson = json.encodeToString(
                ListSerializer(WeightEntry.serializer()),
                container.weightRepository.entries.first()
            ),
            profileJson = container.profileRepository.profile.first()?.let {
                json.encodeToString(UserProfile.serializer(), it)
            },
            healthConnectEnabled = container.prefs.healthConnectEnabled.first(),
            onboarded = container.prefs.hasCompletedOnboarding.first()
        )
        container.prefs.setTestSeedBackupJson(json.encodeToString(SeedBackup.serializer(), backup))

        container.prefs.setHealthConnectEnabled(false)

        val baseProfile = container.profileRepository.profile.first()
            ?: UserProfile(weightKg = 75.0, goalWeightKg = 70.0)
        container.profileRepository.save(baseProfile.copy(weightKg = 73.5, goalWeightKg = 70.0))
        container.prefs.setOnboardingCompleted(true)

        container.foodRepository.replaceAll(generateFood())
        container.weightRepository.replaceAll(generateWeights())
    }

    suspend fun restore() {
        val raw = container.prefs.testSeedBackupJson.first() ?: return
        val backup = runCatching {
            json.decodeFromString(SeedBackup.serializer(), raw)
        }.getOrNull() ?: return

        container.foodRepository.replaceAll(
            json.decodeFromString(ListSerializer(FoodEntry.serializer()), backup.entriesJson)
        )
        container.weightRepository.replaceAll(
            json.decodeFromString(ListSerializer(WeightEntry.serializer()), backup.weightsJson)
        )
        backup.profileJson?.let {
            container.profileRepository.save(json.decodeFromString(UserProfile.serializer(), it))
        }
        container.prefs.setHealthConnectEnabled(backup.healthConnectEnabled)
        container.prefs.setOnboardingCompleted(backup.onboarded)
        container.prefs.clearTestSeedBackup()
    }

    private fun generateFood(): List<FoodEntry> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val rng = Random(seed = 0xF0D)

        val breakfast = listOf(
            Quad("Greek yogurt with berries", 280, 22, 32, 6, "🥣"),
            Quad("Oatmeal with banana", 340, 12, 60, 8, "🥣"),
            Quad("Avocado toast", 380, 14, 38, 18, "🥑"),
            Quad("Protein smoothie", 310, 30, 28, 7, "🥤"),
            Quad("Eggs and toast", 420, 22, 30, 22, "🍳")
        )
        val lunch = listOf(
            Quad("Chicken caesar salad", 540, 38, 22, 32, "🥗"),
            Quad("Turkey sandwich", 480, 32, 48, 16, "🥪"),
            Quad("Sushi rolls", 620, 28, 84, 14, "🍣"),
            Quad("Burrito bowl", 720, 36, 78, 24, "🌯"),
            Quad("Pasta primavera", 560, 18, 82, 18, "🍝")
        )
        val dinner = listOf(
            Quad("Grilled salmon and rice", 640, 42, 58, 22, "🐟"),
            Quad("Steak and broccoli", 720, 50, 18, 46, "🥩"),
            Quad("Chicken stir-fry", 580, 40, 52, 20, "🍛"),
            Quad("Veggie curry", 510, 18, 72, 18, "🍛"),
            Quad("Margherita pizza", 780, 28, 92, 28, "🍕")
        )
        val snacks = listOf(
            Quad("Apple", 95, 0, 25, 0, "🍎"),
            Quad("Almonds", 170, 6, 6, 14, "🥜"),
            Quad("Protein bar", 210, 20, 22, 6, "🍫"),
            Quad("Banana", 105, 1, 27, 0, "🍌"),
            Quad("Greek yogurt", 130, 18, 8, 4, "🥛")
        )

        val out = mutableListOf<FoodEntry>()
        for (daysAgo in 365 downTo 0) {
            val day = today.minusDays(daysAgo.toLong())
            val skipDay = rng.nextInt(20) == 0
            if (skipDay) continue

            fun add(template: Quad, hour: Int, meal: MealType) {
                val jitter = rng.nextDouble(0.85, 1.15)
                val ts = day.atTime(LocalTime.of(hour, rng.nextInt(0, 50)))
                    .atZone(zone).toInstant()
                out.add(
                    FoodEntry(
                        name = template.name,
                        calories = (template.cal * jitter).toInt(),
                        protein = (template.p * jitter).toInt(),
                        carbs = (template.c * jitter).toInt(),
                        fat = (template.f * jitter).toInt(),
                        timestamp = ts,
                        emoji = template.emoji,
                        source = FoodSource.TEXT_INPUT,
                        mealType = meal
                    )
                )
            }

            add(breakfast.random(rng), hour = 8, meal = MealType.BREAKFAST)
            add(lunch.random(rng), hour = 13, meal = MealType.LUNCH)
            add(dinner.random(rng), hour = 19, meal = MealType.DINNER)
            if (rng.nextBoolean()) add(snacks.random(rng), hour = 16, meal = MealType.SNACK)
        }
        return out
    }

    private fun generateWeights(): List<WeightEntry> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val rng = Random(seed = 0xC0FFEE)
        val startKg = 78.0
        val endKg = 73.5
        val totalWeeks = 53

        val out = mutableListOf<WeightEntry>()
        for (weeksAgo in (totalWeeks - 1) downTo 0) {
            val day = today.minusWeeks(weeksAgo.toLong())
            val progress = (totalWeeks - 1 - weeksAgo).toDouble() / (totalWeeks - 1)
            val baseline = startKg - (startKg - endKg) * progress
            val noise = rng.nextDouble(-0.4, 0.4)
            val ts = day.atTime(8, 0).atZone(zone).toInstant()
            out.add(WeightEntry(date = ts, weightKg = baseline + noise))
        }
        return out
    }
}

@Serializable
private data class SeedBackup(
    val entriesJson: String,
    val weightsJson: String,
    val profileJson: String?,
    val healthConnectEnabled: Boolean,
    val onboarded: Boolean
)

private data class Quad(
    val name: String,
    val cal: Int,
    val p: Int,
    val c: Int,
    val f: Int,
    val emoji: String
)
