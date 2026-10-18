package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis

/**
 * Map a logged FoodEntry back into a FoodAnalysis so the FoodResultSheet
 * (which only knows how to render a FoodAnalysis) can review a saved meal or
 * a remembered barcode label before re-logging. A missing original mass is
 * represented as one logged serving, never as a fabricated 100g amount.
 * Mirrors iOS FoodEntry.reviewAnalysis.
 */
fun FoodEntry.toReviewAnalysis(): FoodAnalysis = FoodAnalysis(
    name = name,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    servingSizeGrams = reviewServingReference,
    emoji = emoji,
    sugar = sugar,
    addedSugar = addedSugar,
    fiber = fiber,
    saturatedFat = saturatedFat,
    monounsaturatedFat = monounsaturatedFat,
    polyunsaturatedFat = polyunsaturatedFat,
    cholesterol = cholesterol,
    caffeine = caffeine,
    supplementalNutrients = supplementalNutrients,
    sodium = sodium,
    potassium = potassium,
    transFat = transFat,
    calcium = calcium,
    iron = iron,
    magnesium = magnesium,
    zinc = zinc,
    vitaminA = vitaminA,
    vitaminC = vitaminC,
    vitaminD = vitaminD,
    vitaminB12 = vitaminB12,
    vitaminE = vitaminE,
    vitaminK = vitaminK,
    folate = folate,
    omega3 = omega3,
    servingUnitOptions = reviewServingUnitOptions,
    selectedServingUnit = reviewSelectedServingUnit,
    selectedServingQuantity = reviewSelectedServingQuantity,
    servingSizeIsKnown = hasKnownServingSize,
    customNote = customNote,
    progressiveMeal = progressiveMeal,
    ingredients = ingredients,
    productMetadata = productMetadata
)
