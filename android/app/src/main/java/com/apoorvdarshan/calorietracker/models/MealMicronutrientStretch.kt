package com.apoorvdarshan.calorietracker.models

/** Meal-level micro stretch used when ingredients change but micros are not per-ingredient. */
object MealMicronutrientStretch {
    fun factor(oldGrams: Double, newGrams: Double): Double? {
        if (oldGrams <= 0.0 || newGrams <= 0.0) return null
        return newGrams / oldGrams
    }

    fun scale(value: Double?, factor: Double): Double? = value?.let { it * factor }

    fun scale(values: Map<String, Double>, factor: Double): Map<String, Double> =
        values.mapValues { (_, value) -> value * factor }
}

data class MealMicronutrientSnapshot(
    val sugar: Double? = null,
    val addedSugar: Double? = null,
    val fiber: Double? = null,
    val saturatedFat: Double? = null,
    val monounsaturatedFat: Double? = null,
    val polyunsaturatedFat: Double? = null,
    val cholesterol: Double? = null,
    val caffeine: Double? = null,
    val supplementalNutrients: Map<String, Double> = emptyMap(),
    val sodium: Double? = null,
    val potassium: Double? = null,
    val transFat: Double? = null,
    val calcium: Double? = null,
    val iron: Double? = null,
    val magnesium: Double? = null,
    val zinc: Double? = null,
    val vitaminA: Double? = null,
    val vitaminC: Double? = null,
    val vitaminD: Double? = null,
    val vitaminB12: Double? = null,
    val vitaminE: Double? = null,
    val vitaminK: Double? = null,
    val folate: Double? = null,
    val omega3: Double? = null
) {
    fun stretched(oldGrams: Double, newGrams: Double): MealMicronutrientSnapshot {
        val factor = MealMicronutrientStretch.factor(oldGrams, newGrams) ?: return this
        return copy(
            sugar = MealMicronutrientStretch.scale(sugar, factor),
            addedSugar = MealMicronutrientStretch.scale(addedSugar, factor),
            fiber = MealMicronutrientStretch.scale(fiber, factor),
            saturatedFat = MealMicronutrientStretch.scale(saturatedFat, factor),
            monounsaturatedFat = MealMicronutrientStretch.scale(monounsaturatedFat, factor),
            polyunsaturatedFat = MealMicronutrientStretch.scale(polyunsaturatedFat, factor),
            cholesterol = MealMicronutrientStretch.scale(cholesterol, factor),
            caffeine = MealMicronutrientStretch.scale(caffeine, factor),
            supplementalNutrients = MealMicronutrientStretch.scale(supplementalNutrients, factor),
            sodium = MealMicronutrientStretch.scale(sodium, factor),
            potassium = MealMicronutrientStretch.scale(potassium, factor),
            transFat = MealMicronutrientStretch.scale(transFat, factor),
            calcium = MealMicronutrientStretch.scale(calcium, factor),
            iron = MealMicronutrientStretch.scale(iron, factor),
            magnesium = MealMicronutrientStretch.scale(magnesium, factor),
            zinc = MealMicronutrientStretch.scale(zinc, factor),
            vitaminA = MealMicronutrientStretch.scale(vitaminA, factor),
            vitaminC = MealMicronutrientStretch.scale(vitaminC, factor),
            vitaminD = MealMicronutrientStretch.scale(vitaminD, factor),
            vitaminB12 = MealMicronutrientStretch.scale(vitaminB12, factor),
            vitaminE = MealMicronutrientStretch.scale(vitaminE, factor),
            vitaminK = MealMicronutrientStretch.scale(vitaminK, factor),
            folate = MealMicronutrientStretch.scale(folate, factor),
            omega3 = MealMicronutrientStretch.scale(omega3, factor)
        )
    }

    companion object {
        fun from(entry: FoodEntry): MealMicronutrientSnapshot = MealMicronutrientSnapshot(
            sugar = entry.sugar,
            addedSugar = entry.addedSugar,
            fiber = entry.fiber,
            saturatedFat = entry.saturatedFat,
            monounsaturatedFat = entry.monounsaturatedFat,
            polyunsaturatedFat = entry.polyunsaturatedFat,
            cholesterol = entry.cholesterol,
            caffeine = entry.caffeine,
            supplementalNutrients = entry.supplementalNutrients,
            sodium = entry.sodium,
            potassium = entry.potassium,
            transFat = entry.transFat,
            calcium = entry.calcium,
            iron = entry.iron,
            magnesium = entry.magnesium,
            zinc = entry.zinc,
            vitaminA = entry.vitaminA,
            vitaminC = entry.vitaminC,
            vitaminD = entry.vitaminD,
            vitaminB12 = entry.vitaminB12,
            vitaminE = entry.vitaminE,
            vitaminK = entry.vitaminK,
            folate = entry.folate,
            omega3 = entry.omega3
        )
    }
}

fun FoodEntry.withMicros(snapshot: MealMicronutrientSnapshot): FoodEntry = copy(
    sugar = snapshot.sugar,
    addedSugar = snapshot.addedSugar,
    fiber = snapshot.fiber,
    saturatedFat = snapshot.saturatedFat,
    monounsaturatedFat = snapshot.monounsaturatedFat,
    polyunsaturatedFat = snapshot.polyunsaturatedFat,
    cholesterol = snapshot.cholesterol,
    caffeine = snapshot.caffeine,
    supplementalNutrients = snapshot.supplementalNutrients,
    sodium = snapshot.sodium,
    potassium = snapshot.potassium,
    transFat = snapshot.transFat,
    calcium = snapshot.calcium,
    iron = snapshot.iron,
    magnesium = snapshot.magnesium,
    zinc = snapshot.zinc,
    vitaminA = snapshot.vitaminA,
    vitaminC = snapshot.vitaminC,
    vitaminD = snapshot.vitaminD,
    vitaminB12 = snapshot.vitaminB12,
    vitaminE = snapshot.vitaminE,
    vitaminK = snapshot.vitaminK,
    folate = snapshot.folate,
    omega3 = snapshot.omega3
)

/** Review/Edit Food ingredient edits reset meal scale to 1.0, so micros must be rewritten. */
fun FoodEntry.applyingIngredientChanges(displayedIngredients: List<MealIngredient>): FoodEntry {
    val totals = displayedIngredients.totals()
    val stretched = MealMicronutrientSnapshot.from(this)
        .stretched(ingredients.totals().grams, totals.grams)
    return withMicros(stretched).copy(
        calories = totals.calories,
        protein = totals.protein,
        carbs = totals.carbs,
        fat = totals.fat,
        servingSizeGrams = totals.grams.takeIf { it > 0 } ?: servingSizeGrams,
        servingUnitOptions = emptyList(),
        selectedServingUnit = null,
        selectedServingQuantity = null,
        ingredients = displayedIngredients
    )
}
