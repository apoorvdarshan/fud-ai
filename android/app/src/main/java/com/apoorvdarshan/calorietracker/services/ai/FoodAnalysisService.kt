package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.services.SecureHttpClient
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.BodyMeasurement
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.OptionalNutrientGoals
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.services.GoalEvidence
import com.apoorvdarshan.calorietracker.services.health.HealthEnergySummary
import com.apoorvdarshan.calorietracker.services.ondevice.LocalGemmaRuntime
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.Locale

internal fun multiPhotoAnalysisPrompt(
    progressiveMeal: Boolean,
    description: String? = null
): String {
    val interpretation = if (progressiveMeal) {
        """
            These images are a chronological progressive-meal sequence in the exact order the user captured or selected them.
            - Photo 1 shows the first ingredient on the plate. Each later photo shows the same plate after one or more new ingredients were added.
            - Compare each photo with the previous photo. Return foods already present only once, and add each newly visible food as its own ingredient.
            - When the photos show reliable cumulative scale totals with the same plate and tare, the first ingredient weight is the first reading. Each later added weight is the current scale total minus the previous scale total.
            - If the scale was visibly tared or reset before a photo, use that photo's reading directly for the newly added ingredient.
            - Never subtract unreadable, incompatible, or decreasing readings. In that case estimate only the newly added food from the visual change and user context.
            - The final meal weight should match the latest reliable cumulative reading, and ingredient weights and macros should add up approximately to the meal totals.
        """.trimIndent()
    } else {
        """
            Use every image once. Do not double-count the same food shown from multiple angles. When separate ingredients are shown, combine their nutrition into one meal total. Read visible scale weights and nutrition labels when available; prefer those measurements over visual portion estimates.
            Treat the photos as multiple views of the same item unless there are clearly separate foods.
        """.trimIndent()
    }

    return buildString {
        append(
            """
                Analyze these food images together as one meal logging request. They may show different angles of the same food, separate ingredients, kitchen-scale readings, packaging, or nutrition labels.
                $interpretation
                Respond ONLY with JSON:
                {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"caffeine":0.0,"creatine":0.0,"beta_alanine":0.0,"l_citrulline":0.0,"l_carnitine":0.0,"l_arginine":0.0,"taurine":0.0,"betaine":0.0,"hmb":0.0,"sodium":0.0,"potassium":0.0,"trans_fat":0.0,"calcium":0.0,"iron":0.0,"magnesium":0.0,"zinc":0.0,"vitamin_a":0.0,"vitamin_c":0.0,"vitamin_d":0.0,"vitamin_b12":0.0,"vitamin_e":0.0,"vitamin_k":0.0,"folate":0.0,"omega_3":0.0,"ingredients":[],"unit_options":[]}
                Calories are integers. Protein/carbs/fat are decimal gram values when needed. serving_size_grams is the estimated weight in grams of the serving shown. Nutrients are numbers: sugar/fiber/sat fat/mono fat/poly fat/trans fat/omega-3 in grams; cholesterol/caffeine/sodium/potassium/calcium/iron/magnesium/zinc/vitamin C/vitamin E in milligrams; vitamin A/vitamin D/vitamin B12/vitamin K/folate in micrograms.
                Creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB are grams. Only report them when explicitly present in a label or description; otherwise use 0.
                unit_options is required. It must be [] or a JSON array of complete objects, never an array of strings.
                Every unit_options object must have this exact shape (the values are schema examples only; never copy them): {"unit":"slice","quantity":2.0,"grams_per_unit":60.0}. unit is a non-gram unit name, quantity is the positive number of those units in the whole analyzed amount, and grams_per_unit is the positive gram weight of one unit. All three fields are required.
                For every option, quantity * grams_per_unit must approximately equal serving_size_grams.
                ingredients is required. Return each clearly separate food once using {"name":"...","grams":0.0,"calories":0,"protein":0.0,"carbs":0.0,"fat":0.0}. Ingredient grams and macros must describe the full meal and add up approximately to the meal totals. Return [] only when a reliable breakdown is not possible.
                Only return a count when it is visible in the images, stated in the user context, or strongly implied by an unambiguous single-item portion. Never invent a count from the food name or serving_size_grams alone.
                Use slice/piece for visibly counted foods; use ml/cup/fl oz for visible or labeled liquid volumes; use tbsp/tsp for visible or labeled spoon measures; use can/packet only when visible or labeled. Use [] when no reliable non-gram unit exists. Do not include g/gram/grams in unit_options.
                Use null for any nutrient you cannot estimate.
            """.trimIndent()
        )
        if (!description.isNullOrBlank()) {
            append("\n\nAdditional context from the user about this complete meal: $description\nApply this note to the full image set.")
        }
    }
}

/**
 * Single-shot food / text / nutrition-label analysis. Port of iOS GeminiService.
 * Routes the call to the right per-format client based on the user's selected provider.
 */
class FoodAnalysisService(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    private val okHttp: OkHttpClient = defaultClient,
    private val localGemma: LocalGemmaRuntime? = null
) {

    suspend fun analyzeWorkout(
        description: String,
        date: java.time.LocalDate,
        unit: com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit,
        library: List<com.apoorvdarshan.calorietracker.data.ExerciseItem>
    ): com.apoorvdarshan.calorietracker.models.WorkoutTextDraft {
        require(description.isNotBlank() && description.length <= 4000)
        val prompt = com.apoorvdarshan.calorietracker.models.WorkoutTextDraft.prompt(description, date, unit, library)
        return com.apoorvdarshan.calorietracker.models.WorkoutTextDraft.parse(callAi(prompt, emptyList()), library)
    }

    suspend fun estimateOptionalNutrientGoals(profile: UserProfile?): OptionalNutrientGoals {
        val profileContext = profile?.let {
            """
                Profile:
                - age: ${it.age}
                - gender: ${it.gender.name.lowercase()}
                - height_cm: ${String.format(java.util.Locale.US, "%.1f", it.heightCm)}
                - weight_kg: ${String.format(java.util.Locale.US, "%.1f", it.weightKg)}
                - activity_level: ${it.activityLevel.name.lowercase()}
                - weight_goal: ${it.goal.name.lowercase()}
                - daily_calories: ${it.effectiveCalories}
                - daily_protein_g: ${it.effectiveProtein}
                - daily_carbs_g: ${it.effectiveCarbs}
                - daily_fat_g: ${it.effectiveFat}
            """.trimIndent()
        } ?: "No user profile is available. Use conservative general adult defaults."
        val prompt = """
            Estimate practical daily goals for nutrients outside the app's calorie/protein/carbs/fat calculator.

            $profileContext

            Return ONLY JSON in this exact shape:
            {"sugar":50,"added_sugar":25,"fiber":30,"saturated_fat":20,"cholesterol":300,"caffeine":400,"creatine":0,"beta_alanine":0,"l_citrulline":0,"l_carnitine":0,"l_arginine":0,"taurine":0,"betaine":0,"hmb":0,"sodium":2300,"potassium":3500,"trans_fat":0,"calcium":1000,"iron":18,"magnesium":400,"zinc":11,"vitamin_a":900,"vitamin_c":90,"vitamin_d":20,"vitamin_b12":3,"vitamin_e":15,"vitamin_k":120,"folate":400,"omega_3":2}

            Rules:
            - Do not return calories, protein, carbs, or fat.
            - Keep this independent from macro calculation; only estimate the listed optional nutrient goals.
            - sugar, added_sugar, fiber, saturated_fat, trans_fat, and omega_3 are grams per day.
            - creatine, beta_alanine, l_citrulline, l_carnitine, l_arginine, taurine, betaine, and hmb are optional gram targets; return 0 unless explicitly requested by the user profile.
            - cholesterol, caffeine, sodium, potassium, calcium, iron, magnesium, zinc, vitamin_c, and vitamin_e are milligrams per day.
            - vitamin_a, vitamin_d, vitamin_b12, vitamin_k, and folate are micrograms per day.
            - Use realistic non-medical nutrition targets for an average adult adjusted by profile and calorie target.
            - Keep added_sugar and saturated_fat near or below 10% of calories when possible.
            - Fiber should generally scale around 14g per 1000 kcal, with a practical adult range.
            - Treat sugar, added_sugar, saturated_fat, trans_fat, cholesterol, caffeine, and sodium as daily limits; keep caffeine at a conservative general-adult limit.
            - Sodium should usually stay near general adult guidance unless the profile strongly suggests otherwise.
            - Potassium, calcium, iron, magnesium, zinc, vitamins, folate, and omega-3 should use practical daily targets, not food-log intake.
            - Use integers only.
        """.trimIndent()
        return FoodJsonParser.parseOptionalNutrientGoals(callAi(prompt, imageBytes = null))
    }

    suspend fun suggestHealthEnergyGoals(
        profile: UserProfile,
        energy: HealthEnergySummary,
        heightMetric: Boolean,
        weightMetric: Boolean
    ): HealthEnergyGoalSuggestion {
        val weight = if (weightMetric) {
            String.format(java.util.Locale.US, "%.1f kg", profile.weightKg)
        } else {
            String.format(java.util.Locale.US, "%.1f lb", profile.weightKg * 2.20462)
        }
        val height = if (heightMetric) {
            String.format(java.util.Locale.US, "%.0f cm", profile.heightCm)
        } else {
            String.format(java.util.Locale.US, "%.1f in", profile.heightCm / 2.54)
        }
        val bodyFat = profile.bodyFatPercentage
            ?.let { "${(it * 100).toInt()}%" }
            ?: "not set"
        val goalWeight = profile.goalWeightKg?.let { kg ->
            if (weightMetric) String.format(java.util.Locale.US, "%.1f kg", kg)
            else String.format(java.util.Locale.US, "%.1f lb", kg * 2.20462)
        } ?: "not set"
        val healthTotalLine = energy.totalAverageCalories
            ?.let { "$it kcal/day from active + basal energy" }
            ?: "total energy unavailable; estimate total burn from app BMR + Health Connect active energy"

        val prompt = """
            You are setting a daily calorie target for a food tracking app.
            Return ONLY valid JSON with these exact keys:
            {"calories":2000,"reason":"Short reason under 100 characters"}

            Use Health Connect energy as the primary activity signal, but keep the app's existing formula as a sanity check.
            If Health Connect total energy is unavailable, estimate total daily burn from app BMR plus Health Connect active energy.
            Apply the user's weight goal and weekly change preference to choose the calorie target.
            Keep calories practical for a consumer food tracker: 800-6000 kcal.
            Do not set protein, carbs, or fat; the app keeps macros unlocked on auto-balance unless the user manually locks them.
            Use integers only for calories. Do not include any other keys.

            User profile:
            - Gender: ${profile.gender.name.lowercase()}
            - Age: ${profile.age}
            - Height: $height
            - Weight: $weight
            - Activity level setting: ${profile.activityLevel.name.lowercase()}
            - Weight goal: ${profile.goal.name.lowercase()}
            - Weekly change preference: ${profile.weeklyChangeKg?.let { String.format(java.util.Locale.US, "%.2f kg/week", it) } ?: "maintain"}
            - Goal weight: $goalWeight
            - Body fat: $bodyFat

            Existing app formula:
            - BMR: ${profile.bmr.toInt()} kcal/day
            - TDEE: ${profile.tdee.toInt()} kcal/day
            - Formula calorie target: ${profile.dailyCalories} kcal/day

            Health Connect energy from ${energy.daysUsed} of the last ${energy.requestedDays} completed days:
            - Active energy average: ${energy.activeAverageCalories} kcal/day
            - Basal energy average: ${energy.basalAverageCalories?.let { "$it kcal/day" } ?: "not available"}
            - Health total: $healthTotalLine
        """.trimIndent()
        return FoodJsonParser.parseHealthEnergyGoalSuggestion(callAi(prompt, imageBytes = null))
    }

    /**
     * AI-driven daily target calculation (port of iOS GeminiService.calculateGoals). Sends the
     * app's formulas, the profile, and a completeness-aware privacy-safe evidence pack so the
     * model can use longer-term real signals without treating partial diary days as true intake.
     * A thrown error leaves the caller's existing goals unchanged.
     */
    suspend fun calculateGoals(
        profile: UserProfile,
        heightMetric: Boolean,
        weightMetric: Boolean,
        measuredTdee: Int? = null,
        measurement: BodyMeasurement? = null,
        evidence: GoalEvidence? = null
    ): GoalCalculation {
        val weight = if (weightMetric) String.format(Locale.US, "%.1f kg", profile.weightKg)
            else String.format(Locale.US, "%.1f lb", profile.weightKg * 2.20462)
        val height = if (heightMetric) String.format(Locale.US, "%.0f cm", profile.heightCm)
            else String.format(Locale.US, "%.1f in", profile.heightCm / 2.54)
        val canonicalWeight = String.format(Locale.US, "%.1f kg", profile.weightKg)
        val canonicalHeight = String.format(Locale.US, "%.1f cm", profile.heightCm)
        val bodyFat = profile.bodyFatPercentage?.let {
            String.format(Locale.US, "%.0f%% (fraction %.3f)", it * 100, it)
        } ?: "not set"
        val goalWeight = profile.goalWeightKg?.let { kg ->
            val preferred = if (weightMetric) String.format(Locale.US, "%.1f kg", kg)
                else String.format(Locale.US, "%.1f lb", kg * 2.20462)
            "${String.format(Locale.US, "%.1f kg", kg)} (preferred display: $preferred)"
        } ?: "not set"
        val weekly = profile.weeklyChangeKg?.let { String.format(Locale.US, "%.2f kg/week", it) } ?: "not set (maintain)"
        val bmrMethod = if (profile.usesBodyFatForBMR) "Katch-McArdle (automatic because body fat is known)" else "Mifflin-St Jeor"

        // Energy Burn toggle: when on (and Health Connect has enough data) this measured
        // maintenance replaces the formula TDEE as the calorie anchor.
        val measuredSection = if (measuredTdee != null) {
            "\nENERGY BURN MAINTENANCE ANCHOR — $measuredTdee kcal/day, derived from the recent Health Connect energy window. It uses measured total energy when at least 3 total-energy days exist; otherwise it combines average measured external active energy with formula BMR. Prefer this over formula TDEE, apply the goal/weekly adjustment, and sanity-check against complete-diary and weight trends."
        } else ""

        // Optional tape-measure circumferences + derived metrics. Extra signal only — never overrides
        // the formulas. A shrinking waist alongside flat/declining weight implies recomposition.
        val measurementsSummary = if (evidence == null) {
            measurement?.promptSummary(profile.gender, profile.heightCm)
        } else null
        val measurementsSection = if (measurementsSummary != null) {
            "\nBODY MEASUREMENTS — the user's latest tape-measure circumferences and the metrics derived from them. Use as extra signal: a shrinking waist with steady or falling weight suggests recomposition, so keep protein high and don't over-cut. Treat the US-Navy body-fat figure as a rough estimate, not exact.\n$measurementsSummary"
        } else ""

        val evidenceSection = evidence?.let {
            "\n\n${it.promptSection(profile)}\nUse the confidence and completeness labels explicitly. Prefer likely-complete days and measured trends; never interpret missing or likely-partial days as true low intake."
        } ?: ""

        val prompt = """
            You are the goal calculator for a calorie & macro tracking app. Using the FORMULAS, USER PROFILE, and GOAL EVIDENCE below, compute the user's daily targets.
            Return ONLY valid JSON with these exact keys (integers, plus a short reason):
            {"calories":2000,"protein":150,"carbs":200,"fat":60,"reason":"Short reason under 100 characters"}

            Use the app's formulas as the basis. Use empirical signals only according to the evidence confidence/completeness labels; never infer low intake from partial or missing diary days.
            FORMULAS
            - BMR (Mifflin-St Jeor): base = 10*weightKg + 6.25*heightCm - 5*age - 161; if male add 166; female/other use base.
            - BMR (Katch-McArdle, used automatically when body fat is known): 370 + 21.6 * (1 - bodyFatFraction) * weightKg.
            - TDEE = BMR * activity multiplier. Multipliers: sedentary 1.2, light 1.375, moderate 1.465, active 1.55, very active 1.725, extra active 1.9.
            - Calorie target = TDEE + adjustment. adjustment = 0 for maintain; lose: -(weeklyChangeKg*7700/7); gain: +(weeklyChangeKg*7700/7).
            - Guarded empirical maintenance: for a matching 14/28/90-day window, maintenance ≈ average likely-complete intake − (weightChangeKg × 7700 ÷ weightSpanDays). Use only when evidence confidence is medium/high, at least half the window is likely-complete, there are at least 2 weigh-ins spanning 14+ days, and the implied trend is physiologically plausible. Never use partial/missing intake, never divide by the nominal window when the reported weight span differs, and ignore this estimate when those guards fail. Priority: measured Energy Burn anchor when available; otherwise a well-supported empirical estimate; otherwise formula TDEE.
            - Protein: aim NEAR the formula protein value shown below — the activity rates (sedentary 0.8, light 1.2, moderate 1.6, active 1.8, very active 2.0, extra active 2.2 g/kg; +0.2 if losing) are full-bodyweight equivalents and are applied directly to bodyweight. You may choose a value within about ±15% based on the weight goal and observed history (lean toward the higher end during a calorie deficit to preserve muscle). Do NOT reinterpret these as lean-mass rates or scale protein down just to fit a lower calorie target.
            - Fat: 0.6 g/kg of full bodyweight.
            - Carbs: the calories remaining after protein (4 kcal/g) and fat (9 kcal/g), divided by 4. Keep 4*protein + 4*carbs + 9*fat approximately equal to calories.
            BMR method in effect for this user: $bmrMethod.
            Keep calories within 800-6000. Use integers only. Output no keys other than calories, protein, carbs, fat, reason.

            USER PROFILE
            - Gender: ${profile.gender.name.lowercase()}
            - Age: ${profile.age}
            - Height: $canonicalHeight (preferred display: $height)
            - Weight: $canonicalWeight (preferred display: $weight)
            - Body fat: $bodyFat
            - Activity level: ${profile.activityLevel.name.lowercase()}
            - Weight goal: ${profile.goal.name.lowercase()}
            - Weekly change preference: $weekly
            - Goal weight: $goalWeight

            APP FORMULA REFERENCE (already computed deterministically — use as the anchor)
            - BMR: ${profile.bmr.toInt()} kcal/day
            - TDEE: ${profile.tdee.toInt()} kcal/day
            - Formula calorie target: ${profile.dailyCalories} kcal/day
            - Formula macros: ${profile.proteinGoal} g protein, ${profile.carbsGoal} g carbs, ${profile.fatGoal} g fat

            CURRENT SAVED TARGETS (before this recalculation)
            - Calories: ${profile.effectiveCalories} kcal/day
            - Protein: ${profile.effectiveProtein} g/day
            - Carbs: ${profile.effectiveCarbs} g/day
            - Fat: ${profile.effectiveFat} g/day
            $measuredSection
            $measurementsSection
            $evidenceSection
        """.trimIndent()
        return FoodJsonParser.parseGoalCalculation(callAi(prompt, imageBytes = null), profile)
    }

    suspend fun suggestMealWhatIf(
        entry: FoodEntry,
        dayEntries: List<FoodEntry>,
        profile: UserProfile,
        weightMetric: Boolean
    ): String {
        val beforeCalories = dayEntries.sumOf { it.calories }
        val beforeProtein = dayEntries.sumOf { it.protein }
        val beforeCarbs = dayEntries.sumOf { it.carbs }
        val beforeFat = dayEntries.sumOf { it.fat }
        val afterCalories = beforeCalories + entry.calories
        val afterProtein = beforeProtein + entry.protein
        val afterCarbs = beforeCarbs + entry.carbs
        val afterFat = beforeFat + entry.fat
        val weight = if (weightMetric) {
            String.format(Locale.US, "%.1f kg", profile.weightKg)
        } else {
            String.format(Locale.US, "%.1f lb", profile.weightKg * 2.20462)
        }
        val bodyFat = profile.bodyFatPercentage
            ?.let { "${(it * 100).toInt()}%" }
            ?: "not set"
        fun grams(value: Double) = String.format(Locale.US, "%.1fg", value)

        val prompt = """
            The user tapped "What if?" before logging a meal in a nutrition tracker.
            Return 2-4 short sentences, no markdown, under 90 words.
            Explain how this meal changes today's calorie/protein/carbs/fat totals compared with the user's goals, then give one practical action: log it as-is, reduce portion, replace part of it, or adjust the next meal.
            Stay practical and non-medical.

            User profile:
            - Gender: ${profile.gender.name.lowercase()}
            - Age: ${profile.age}
            - Weight: $weight
            - Activity level: ${profile.activityLevel.name.lowercase()}
            - Weight goal: ${profile.goal.name.lowercase()}
            - Body fat: $bodyFat

            Daily goals:
            - Calories: ${profile.effectiveCalories} kcal
            - Protein: ${profile.effectiveProtein}g
            - Carbs: ${profile.effectiveCarbs}g
            - Fat: ${profile.effectiveFat}g

            Today's totals before this meal:
            - Calories: $beforeCalories kcal
            - Protein: ${grams(beforeProtein)}
            - Carbs: ${grams(beforeCarbs)}
            - Fat: ${grams(beforeFat)}

            Meal being reviewed:
            - Name: ${entry.name}
            - Calories: ${entry.calories} kcal
            - Protein: ${grams(entry.protein)}
            - Carbs: ${grams(entry.carbs)}
            - Fat: ${grams(entry.fat)}

            Today's totals if logged:
            - Calories: $afterCalories kcal
            - Protein: ${grams(afterProtein)}
            - Carbs: ${grams(afterCarbs)}
            - Fat: ${grams(afterFat)}
        """.trimIndent()
        return callAi(prompt, imageBytes = null).trim()
    }

    suspend fun analyzeText(description: String): FoodAnalysis {
        val prompt = """
            Estimate the nutritional content for: $description
            Parse any quantities, brands, and multiple items from the text. If a brand is mentioned, use that brand's known nutritional data. If multiple items are described, sum up the total nutrition.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"serving_size_grams":0.0,"emoji":"<single specific food emoji>","sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"caffeine":0.0,"creatine":0.0,"beta_alanine":0.0,"l_citrulline":0.0,"l_carnitine":0.0,"l_arginine":0.0,"taurine":0.0,"betaine":0.0,"hmb":0.0,"sodium":0.0,"potassium":0.0,"trans_fat":0.0,"calcium":0.0,"iron":0.0,"magnesium":0.0,"zinc":0.0,"vitamin_a":0.0,"vitamin_c":0.0,"vitamin_d":0.0,"vitamin_b12":0.0,"vitamin_e":0.0,"vitamin_k":0.0,"folate":0.0,"omega_3":0.0,"ingredients":[],"unit_options":[]}
            Calories are integers. Protein/carbs/fat are decimal gram values when needed. serving_size_grams is the estimated total weight in grams. Nutrients are numbers: sugar/fiber/sat fat/mono fat/poly fat/trans fat/omega-3 in grams; cholesterol/caffeine/sodium/potassium/calcium/iron/magnesium/zinc/vitamin C/vitamin E in milligrams; vitamin A/vitamin D/vitamin B12/vitamin K/folate in micrograms.
            Creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB are grams. Only report them when explicitly present in a label or description; otherwise use 0.
            unit_options is required. It must be [] or a JSON array of complete objects, never an array of strings.
            Every unit_options object must have this exact shape (the values are schema examples only; never copy them): {"unit":"slice","quantity":2.0,"grams_per_unit":60.0}. unit is a non-gram unit name, quantity is the positive number of those units in the whole analyzed amount, and grams_per_unit is the positive gram weight of one unit. All three fields are required.
            For every option, quantity * grams_per_unit must approximately equal serving_size_grams.
            ingredients is required. For a meal with multiple meaningful foods, return each food once using {"name":"...","grams":0.0,"calories":0,"protein":0.0,"carbs":0.0,"fat":0.0}. Ingredient grams and macros must describe the analyzed amount and add up approximately to the meal totals. Return [] for a single simple food or when a reliable breakdown is not possible.
            Only return a count when it is stated in the text or user context, or strongly implied by an unambiguous described portion. Never invent a count from the food name or serving_size_grams alone.
            Use slice/piece for explicitly counted pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for stated liquid volumes; use tbsp/tsp for stated spoon measures; use can/packet only when stated or strongly implied. Use [] when no reliable non-gram unit exists. Do not include g/gram/grams in unit_options.
            For "emoji" pick the single most specific food emoji that depicts this dish — e.g. 🥚 for eggs, 🍕 for pizza, 🍎 for an apple, 🥗 for a salad, 🍔 for a burger, 🍜 for ramen, 🍰 for cake, 🥑 for avocado, ☕ for coffee, 🍣 for sushi. Only fall back to 🍽️ when the food truly cannot be represented by any specific emoji. Use null for any nutrient you cannot estimate.
        """.trimIndent()
        val parsed = FoodJsonParser.parseFoodResponse(callAi(prompt, null))
        return addingFallbackServingUnits(
            analysis = parsed.analysis,
            imageBytes = null,
            description = description,
            shouldRequestFallback = parsed.shouldRequestServingUnitFallback
        )
    }

    suspend fun analyzeAuto(imageBytes: ByteArray): FoodAnalysis {
        val prompt = """
            Analyze this image. It could be either a photo of food OR a nutrition facts label.

            If it's a food photo: identify the food and estimate nutritional content for the serving shown.
            If it's a nutrition label: read the values and calculate for one serving size as listed on the label.

            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"caffeine":0.0,"creatine":0.0,"beta_alanine":0.0,"l_citrulline":0.0,"l_carnitine":0.0,"l_arginine":0.0,"taurine":0.0,"betaine":0.0,"hmb":0.0,"sodium":0.0,"potassium":0.0,"trans_fat":0.0,"calcium":0.0,"iron":0.0,"magnesium":0.0,"zinc":0.0,"vitamin_a":0.0,"vitamin_c":0.0,"vitamin_d":0.0,"vitamin_b12":0.0,"vitamin_e":0.0,"vitamin_k":0.0,"folate":0.0,"omega_3":0.0,"ingredients":[],"unit_options":[]}
            Calories are integers. Protein/carbs/fat are decimal gram values when needed. serving_size_grams is the estimated weight in grams of the serving. Nutrients are numbers: sugar/fiber/sat fat/mono fat/poly fat/trans fat/omega-3 in grams; cholesterol/caffeine/sodium/potassium/calcium/iron/magnesium/zinc/vitamin C/vitamin E in milligrams; vitamin A/vitamin D/vitamin B12/vitamin K/folate in micrograms.
            Creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB are grams. Only report them when explicitly present in a label or description; otherwise use 0.
            unit_options is required. It must be [] or a JSON array of complete objects, never an array of strings.
            Every unit_options object must have this exact shape (the values are schema examples only; never copy them): {"unit":"slice","quantity":2.0,"grams_per_unit":60.0}. unit is a non-gram unit name, quantity is the positive number of those units in the whole analyzed amount, and grams_per_unit is the positive gram weight of one unit. All three fields are required.
            For every option, quantity * grams_per_unit must approximately equal serving_size_grams.
            ingredients is required. For a food photo with multiple meaningful foods, return each food once using {"name":"...","grams":0.0,"calories":0,"protein":0.0,"carbs":0.0,"fat":0.0}. Ingredient grams and macros must describe the analyzed amount and add up approximately to the meal totals. Return [] for a nutrition label, a single simple food, or when a reliable breakdown is not possible.
            Only return a count when it is visible in the image or label, stated in visible text, or strongly implied by an unambiguous single-item portion. Never invent a count from the food name or serving_size_grams alone.
            Use slice/piece for visibly counted pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for visible or labeled liquid volumes; use tbsp/tsp for visible or labeled spoon measures; use can/packet only when visible or labeled. Use [] when no reliable non-gram unit exists. Do not include g/gram/grams in unit_options.
            Use null for any nutrient you cannot estimate.
        """.trimIndent()
        val parsed = FoodJsonParser.parseFoodResponse(callAi(prompt, imageBytes))
        return addingFallbackServingUnits(
            analysis = parsed.analysis,
            imageBytes = imageBytes,
            description = null,
            shouldRequestFallback = parsed.shouldRequestServingUnitFallback
        )
    }

    suspend fun analyzeFood(imageBytes: ByteArray, description: String? = null): FoodAnalysis {
        var prompt = """
            Analyze this food image. Identify the food and estimate its nutritional content.
            Respond ONLY with JSON:
            {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"caffeine":0.0,"creatine":0.0,"beta_alanine":0.0,"l_citrulline":0.0,"l_carnitine":0.0,"l_arginine":0.0,"taurine":0.0,"betaine":0.0,"hmb":0.0,"sodium":0.0,"potassium":0.0,"trans_fat":0.0,"calcium":0.0,"iron":0.0,"magnesium":0.0,"zinc":0.0,"vitamin_a":0.0,"vitamin_c":0.0,"vitamin_d":0.0,"vitamin_b12":0.0,"vitamin_e":0.0,"vitamin_k":0.0,"folate":0.0,"omega_3":0.0,"ingredients":[],"unit_options":[]}
            Calories are integers. Protein/carbs/fat are decimal gram values when needed. serving_size_grams is the estimated weight in grams of the serving shown. Nutrients are numbers: sugar/fiber/sat fat/mono fat/poly fat/trans fat/omega-3 in grams; cholesterol/caffeine/sodium/potassium/calcium/iron/magnesium/zinc/vitamin C/vitamin E in milligrams; vitamin A/vitamin D/vitamin B12/vitamin K/folate in micrograms.
            Creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB are grams. Only report them when explicitly present in a label or description; otherwise use 0.
            unit_options is required. It must be [] or a JSON array of complete objects, never an array of strings.
            Every unit_options object must have this exact shape (the values are schema examples only; never copy them): {"unit":"slice","quantity":2.0,"grams_per_unit":60.0}. unit is a non-gram unit name, quantity is the positive number of those units in the whole analyzed amount, and grams_per_unit is the positive gram weight of one unit. All three fields are required.
            For every option, quantity * grams_per_unit must approximately equal serving_size_grams.
            ingredients is required. For a meal with multiple meaningful foods, return each food once using {"name":"...","grams":0.0,"calories":0,"protein":0.0,"carbs":0.0,"fat":0.0}. Ingredient grams and macros must describe the visible amount and add up approximately to the meal totals. Return [] for a single simple food or when a reliable breakdown is not possible.
            Only return a count when it is visible in the image, stated in the user context, or strongly implied by an unambiguous single-item portion. Never invent a count from the food name or serving_size_grams alone.
            Use slice/piece for visibly counted pizza, cake, bread, cookies, fruit pieces, etc.; use ml/cup/fl oz for visible liquid volumes; use tbsp/tsp for visible spoon measures; use can/packet only when visible or strongly implied by packaging. Use [] when no reliable non-gram unit exists. Do not include g/gram/grams in unit_options.
            Give your best estimate for the visible food amount shown in the image. For whole/mostly-whole cakes, pizzas, pies, loaves, or similar foods, estimate the total visible item/remaining item weight rather than defaulting to one slice. Use null for any nutrient you cannot estimate.
        """.trimIndent()
        if (!description.isNullOrBlank()) {
            prompt += "\n\nAdditional context from the user about this meal: $description\nUse this context to improve accuracy of identification, portion size, and nutrition estimates."
        }
        val parsed = FoodJsonParser.parseFoodResponse(callAi(prompt, imageBytes))
        return addingFallbackServingUnits(
            analysis = parsed.analysis,
            imageBytes = imageBytes,
            description = description,
            shouldRequestFallback = parsed.shouldRequestServingUnitFallback
        )
    }

    suspend fun analyzeFood(
        imageBytesList: List<ByteArray>,
        description: String? = null,
        progressiveMeal: Boolean = false
    ): FoodAnalysis {
        val prompt = multiPhotoAnalysisPrompt(progressiveMeal, description)
        val images = imageBytesList.filter { it.isNotEmpty() }
        if (images.isEmpty()) throw AiError.InvalidResponse
        val parsed = FoodJsonParser.parseFoodResponse(callAi(prompt, images))
        return addingFallbackServingUnits(
            analysis = parsed.analysis,
            imageBytes = images.first(),
            description = description,
            shouldRequestFallback = parsed.shouldRequestServingUnitFallback
        ).copy(progressiveMeal = progressiveMeal)
    }

    suspend fun analyzeNutritionLabel(imageBytes: ByteArray, servingGrams: Double): FoodAnalysis {
        val prompt = """
            Read this nutrition facts label and extract per-100g values. If the label only shows per-serving, normalize using the serving size listed on the label.
            Respond ONLY with JSON:
            {"name":"...","calories_per_100g":0.0,"protein_per_100g":0.0,"carbs_per_100g":0.0,"fat_per_100g":0.0,"serving_size_grams":0.0,"sugar_per_100g":0.0,"added_sugar_per_100g":0.0,"fiber_per_100g":0.0,"saturated_fat_per_100g":0.0,"monounsaturated_fat_per_100g":0.0,"polyunsaturated_fat_per_100g":0.0,"cholesterol_per_100g":0.0,"caffeine_per_100g":0.0,"creatine_per_100g":0.0,"beta_alanine_per_100g":0.0,"l_citrulline_per_100g":0.0,"l_carnitine_per_100g":0.0,"l_arginine_per_100g":0.0,"taurine_per_100g":0.0,"betaine_per_100g":0.0,"hmb_per_100g":0.0,"sodium_per_100g":0.0,"potassium_per_100g":0.0,"trans_fat_per_100g":0.0,"calcium_per_100g":0.0,"iron_per_100g":0.0,"magnesium_per_100g":0.0,"zinc_per_100g":0.0,"vitamin_a_per_100g":0.0,"vitamin_c_per_100g":0.0,"vitamin_d_per_100g":0.0,"vitamin_b12_per_100g":0.0,"vitamin_e_per_100g":0.0,"vitamin_k_per_100g":0.0,"folate_per_100g":0.0,"omega_3_per_100g":0.0,"unit_options":[]}
            All nutrient and serving values should be numbers. If serving size or any nutrient is not available, use null.
            unit_options is required. It must be [] or a JSON array of complete objects, never an array of strings.
            Every unit_options object must have this exact shape (the values are schema examples only; never copy them): {"unit":"slice","quantity":2.0,"grams_per_unit":60.0}. unit is a non-gram unit name, quantity is the positive number of those units in the whole analyzed amount, and grams_per_unit is the positive gram weight of one unit. All three fields are required.
            For every option, quantity * grams_per_unit must approximately equal serving_size_grams.
            Only return a count or volume when it is printed on the label, visible in the image, or strongly implied by an unambiguous labeled single-item serving. Never invent a count from the product name or serving_size_grams alone.
            Use [] when no reliable non-gram label unit is visible. Do not include g/gram/grams in unit_options.
        """.trimIndent()
        val parsed = FoodJsonParser.parseLabelResponse(callAi(prompt, imageBytes))
        return addingFallbackServingUnits(
            analysis = parsed.analysis,
            imageBytes = imageBytes,
            shouldRequestFallback = parsed.shouldRequestServingUnitFallback
        ).scaled(servingGrams)
    }

    // -- Internal dispatch ------------------------------------------------

    private suspend fun callAi(prompt: String, imageBytes: ByteArray?): String {
        return callAi(prompt, imageBytes?.let { listOf(it) }.orEmpty())
    }

    private suspend fun callAi(prompt: String, imageBytesList: List<ByteArray>): String {
        val context = prefs.userContext.first()
        val finalPrompt = if (context.isNotBlank()) "User context (apply to every analysis): $context\n\n$prompt" else prompt

        val useSeparateTextProvider = imageBytesList.isEmpty() && prefs.separateTextProviderEnabled.first()
        val primary = if (useSeparateTextProvider) {
            prefs.selectedTextAIProvider.first()
        } else {
            prefs.selectedAIProvider.first()
        }
        val primaryModel = if (useSeparateTextProvider) {
            primary.supportedTextModelOrDefault(prefs.selectedTextAIModel.first())
        } else {
            primary.supportedModelOrDefault(prefs.selectedAIModel.first())
        }
        val primaryBaseUrl = prefs.customBaseUrl(primary).first()?.takeIf { it.isNotEmpty() } ?: primary.baseUrl
        val primaryKey = keyStore.apiKey(primary)
        if (primary.requiresApiKey && primaryKey.isNullOrEmpty()) throw AiError.NoApiKey
        val maxTokens = prefs.maxResponseTokens.first()
        val requestTimeoutSeconds = prefs.aiRequestTimeoutSeconds.first()
        val uploadImages = imageBytesList.map(FoodImagePreprocessor::prepareForUpload)

        return try {
            dispatch(primary, primaryModel, primaryBaseUrl, primaryKey, finalPrompt, uploadImages, maxTokens, requestTimeoutSeconds)
        } catch (primaryError: Throwable) {
            if (primaryError is kotlinx.coroutines.CancellationException) throw primaryError
            val fallback = if (imageBytesList.isEmpty()) {
                currentTextFallbackConfig(primary, primaryModel)
            } else {
                currentImageFallbackConfig(primary, primaryModel)
            } ?: throw primaryError
            dispatch(fallback.provider, fallback.model, fallback.baseUrl, fallback.apiKey, finalPrompt, uploadImages, maxTokens, requestTimeoutSeconds)
        }
    }

    private suspend fun addingFallbackServingUnits(
        analysis: FoodAnalysis,
        imageBytes: ByteArray?,
        description: String?,
        shouldRequestFallback: Boolean
    ): FoodAnalysis {
        if (!shouldRequestFallback) return analysis
        val options = runCatching {
            inferServingUnitOptions(
                name = analysis.name,
                servingSizeGrams = analysis.servingSizeGrams,
                imageBytes = imageBytes,
                description = description
            )
        }.getOrDefault(emptyList())
        if (options.isEmpty()) return analysis
        val selected = options.first()
        return analysis.copy(
            servingUnitOptions = options,
            selectedServingUnit = selected.unit,
            selectedServingQuantity = selected.quantityFor(analysis.servingSizeGrams)
        )
    }

    private suspend fun addingFallbackServingUnits(
        analysis: NutritionLabelAnalysis,
        imageBytes: ByteArray,
        shouldRequestFallback: Boolean
    ): NutritionLabelAnalysis {
        if (!shouldRequestFallback) return analysis
        val servingSizeGrams = analysis.servingSizeGrams ?: return analysis
        val options = runCatching {
            inferServingUnitOptions(
                name = analysis.name,
                servingSizeGrams = servingSizeGrams,
                imageBytes = imageBytes,
                description = null
            )
        }.getOrDefault(emptyList())
        if (options.isEmpty()) return analysis
        return analysis.copy(servingUnitOptions = options)
    }

    private suspend fun inferServingUnitOptions(
        name: String,
        servingSizeGrams: Double,
        imageBytes: ByteArray?,
        description: String?
    ): List<com.apoorvdarshan.calorietracker.models.ServingUnitOption> {
        val context = description?.trim()?.takeIf { it.isNotEmpty() }
        val contextLine = context?.let { "\nUser context: $it" }.orEmpty()
        val prompt = """
            The previous food analysis omitted unit_options or returned it in a malformed format. Repair only that field for the same food and analyzed amount.

            Food: $name
            Total grams for the analyzed amount: ${String.format(java.util.Locale.US, "%.1f", servingSizeGrams)}$contextLine

            Return ONLY JSON:
            {"unit_options":[]}

            Rules:
            - unit_options must be [] or an array of complete objects, never strings.
            - Every object requires unit, quantity, and grams_per_unit. quantity and grams_per_unit must be positive numbers.
            - For every option, quantity * grams_per_unit must approximately equal the total grams above.
            - Return a count only when it is stated in User context, visible in the attached image or label, or strongly implied by an unambiguous single-item portion.
            - Never invent a count from the food name or total grams alone.
            - Return {"unit_options":[]} when no reliable non-gram unit exists.
            - Do not include g, gram, or grams as an option.
        """.trimIndent()
        return FoodJsonParser.parseServingUnitOptions(callAi(prompt, imageBytes), servingSizeGrams)
    }

    private suspend fun dispatch(
        provider: AIProvider,
        model: String,
        baseUrl: String,
        apiKey: String?,
        prompt: String,
        imageBytesList: List<ByteArray>,
        maxTokens: Int,
        requestTimeoutSeconds: Int
    ): String {
        if (provider == AIProvider.LOCAL_GEMMA) {
            return localGemma?.generate(
                prompt = prompt,
                images = imageBytesList,
                maxOutputTokens = maxTokens
            ) ?: throw AiError.Api("The on-device Gemma runtime is unavailable.")
        }
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        val requestClient = clientForProvider(okHttp, provider, requestTimeoutSeconds)
        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI ->
                GeminiClient.analyze(requestClient, baseUrl, model, apiKey!!, prompt, imageBytesList)
            AIProvider.ApiFormat.ANTHROPIC ->
                AnthropicClient.analyze(requestClient, baseUrl, model, apiKey!!, prompt, imageBytesList, maxTokens)
            AIProvider.ApiFormat.OPENAI_COMPATIBLE ->
                OpenAICompatibleClient.analyze(requestClient, baseUrl, model, apiKey, prompt, imageBytesList, provider, maxTokens, prefs.openRouterReasoningEffort.first())
            AIProvider.ApiFormat.LOCAL -> error("Local inference must be dispatched before network setup.")
        }
    }

    private suspend fun currentImageFallbackConfig(
        primary: AIProvider,
        primaryModel: String
    ): FallbackConfig? {
        if (!prefs.fallbackEnabled.first()) return null
        val provider = prefs.selectedFallbackProvider.first()
        val model = provider.supportedModelOrDefault(prefs.selectedFallbackModel.first())
        // Fallback identical to primary would be a pointless retry of the same call.
        if (provider == primary && model == primaryModel) return null
        val key = keyStore.apiKey(provider)
        if (provider.requiresApiKey && key.isNullOrEmpty()) return null
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        if (provider != AIProvider.LOCAL_GEMMA && baseUrl.isEmpty()) return null
        return FallbackConfig(provider, model, baseUrl, key)
    }

    private suspend fun currentTextFallbackConfig(
        primary: AIProvider,
        primaryModel: String
    ): FallbackConfig? {
        if (!prefs.textFallbackEnabled.first()) return null
        val provider = prefs.selectedTextFallbackProvider.first()
        val model = provider.supportedTextModelOrDefault(prefs.selectedTextFallbackModel.first())
        if (provider == primary && model == primaryModel) return null
        val key = keyStore.apiKey(provider)
        if (provider.requiresApiKey && key.isNullOrEmpty()) return null
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        if (provider != AIProvider.LOCAL_GEMMA && baseUrl.isEmpty()) return null
        return FallbackConfig(provider, model, baseUrl, key)
    }

    private data class FallbackConfig(
        val provider: AIProvider,
        val model: String,
        val baseUrl: String,
        val apiKey: String?
    )

    companion object {
        internal fun clientForProvider(
            client: OkHttpClient,
            provider: AIProvider,
            requestTimeoutSeconds: Int
        ): OkHttpClient {
            if (!provider.usesConfigurableRequestTimeout) return client
            val seconds = AIProvider.normalizedRequestTimeoutSeconds(requestTimeoutSeconds).toLong()
            return client.newBuilder()
                .readTimeout(seconds, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(seconds, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        internal val defaultClient: OkHttpClient by lazy {
            SecureHttpClient.builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
}
