package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

@Serializable
data class UserProfile(
    val name: String? = null,
    val gender: Gender = Gender.MALE,
    @Serializable(with = InstantSerializer::class)
    val birthday: Instant = defaultBirthday(),
    val heightCm: Double = 175.0,
    val weightKg: Double = 70.0,
    val activityLevel: ActivityLevel = ActivityLevel.MODERATE,
    val goal: WeightGoal = WeightGoal.MAINTAIN,
    val bodyFatPercentage: Double? = null,
    /** Display-only goal — explicitly NOT used in BMR/TDEE/macro math. */
    val goalBodyFatPercentage: Double? = null,
    /** Nullable so older serialized profiles decode as null = treat as true. When false,
     *  BMR falls back to Mifflin-St Jeor even if bodyFatPercentage is set. */
    val useBodyFatInBMR: Boolean? = null,
    val weeklyChangeKg: Double? = null,
    val goalWeightKg: Double? = null,
    val customCalories: Int? = null,
    val customProtein: Int? = null,
    val customFat: Int? = null,
    val customCarbs: Int? = null,
    val autoBalanceMacro: AutoBalanceMacro? = null
) {
    val displayName: String get() = name?.takeIf { it.isNotEmpty() } ?: "User"

    val initials: String get() {
        val parts = displayName.split(" ")
        return if (parts.size >= 2) {
            (parts[0].take(1) + parts[1].take(1)).uppercase()
        } else {
            displayName.take(1).uppercase()
        }
    }

    val age: Int get() {
        val birthDate = birthday.atZone(ZoneId.systemDefault()).toLocalDate()
        return Period.between(birthDate, LocalDate.now()).years.coerceAtLeast(0)
    }

    /** True when BMR currently uses Katch-McArdle. Centralized accessor — read this everywhere
     *  instead of the raw `useBodyFatInBMR` Bool? so the nil-default-true semantics live in one place. */
    val usesBodyFatForBMR: Boolean get() = bodyFatPercentage != null && (useBodyFatInBMR ?: true)

    val bmr: Double get() = if (usesBodyFatForBMR) {
        // Katch-McArdle
        370.0 + 21.6 * (1.0 - bodyFatPercentage!!) * weightKg
    } else {
        // Mifflin-St Jeor
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age - 161.0
        if (gender == Gender.MALE) base + 166.0 else base
    }

    val tdee: Double get() = bmr * activityLevel.multiplier

    val calorieAdjustment: Int get() = when (goal) {
        WeightGoal.MAINTAIN -> 0
        WeightGoal.LOSE -> {
            val rate = weeklyChangeKg ?: 0.5
            -(rate * 7000 / 7).toInt()
        }
        WeightGoal.GAIN -> {
            val rate = weeklyChangeKg ?: 0.5
            (rate * 7000 / 7).toInt()
        }
    }

    val dailyCalories: Int get() = tdee.toInt() + calorieAdjustment

    val proteinGoal: Int get() {
        // +0.2 g/kg during cutting phase to preserve lean mass (Helms et al 2014).
        val cuttingBoost = if (goal == WeightGoal.LOSE) 0.2 else 0.0
        return ((activityLevel.proteinPerKg + cuttingBoost) * weightKg).toInt()
    }

    val fatGoal: Int get() = (0.6 * weightKg).toInt()

    val carbsGoal: Int get() = maxOf(0, (dailyCalories - proteinGoal * 4 - fatGoal * 9) / 4)

    val effectiveCalories: Int get() = customCalories ?: dailyCalories

    fun isPinned(macro: AutoBalanceMacro): Boolean = customValue(macro) != null

    val pinnedCount: Int get() = AutoBalanceMacro.values().count { isPinned(it) }

    val effectiveProtein: Int get() = customProtein ?: autoMacroValue(AutoBalanceMacro.PROTEIN)
    val effectiveCarbs: Int get() = customCarbs ?: autoMacroValue(AutoBalanceMacro.CARBS)
    val effectiveFat: Int get() = customFat ?: autoMacroValue(AutoBalanceMacro.FAT)

    private fun customValue(macro: AutoBalanceMacro): Int? = when (macro) {
        AutoBalanceMacro.PROTEIN -> customProtein
        AutoBalanceMacro.CARBS -> customCarbs
        AutoBalanceMacro.FAT -> customFat
    }

    private fun formulaValue(macro: AutoBalanceMacro): Int = when (macro) {
        AutoBalanceMacro.PROTEIN -> proteinGoal
        AutoBalanceMacro.CARBS -> carbsGoal
        AutoBalanceMacro.FAT -> fatGoal
    }

    private fun autoMacroValue(macro: AutoBalanceMacro): Int {
        val pinnedKcal = AutoBalanceMacro.values().sumOf { m ->
            customValue(m)?.let { it * m.kcalPerGram } ?: 0
        }
        val remaining = maxOf(0, effectiveCalories - pinnedKcal)
        val autoMacros = AutoBalanceMacro.values().filter { !isPinned(it) }
        if (macro !in autoMacros) return 0

        if (autoMacros.size == 1) {
            return remaining / macro.kcalPerGram
        }

        val totalFormulaKcal = autoMacros.sumOf { formulaValue(it) * it.kcalPerGram }
        if (totalFormulaKcal <= 0) return formulaValue(macro)

        val mySharedKcal = remaining * formulaValue(macro) * macro.kcalPerGram / totalFormulaKcal
        return mySharedKcal / macro.kcalPerGram
    }

    /**
     * Returns a copy with calories recomputed from formulas and all three macros reset to auto.
     * User can pin individual macros afterwards (max 2).
     */
    fun recalculatedFromFormulas(): UserProfile = copy(
        customCalories = dailyCalories,
        customProtein = null,
        customFat = null,
        customCarbs = null,
        autoBalanceMacro = null
    )

    companion object {
        private fun defaultBirthday(): Instant =
            LocalDate.now().minusYears(25).atStartOfDay(ZoneId.systemDefault()).toInstant()

        val Default = UserProfile()
    }
}
