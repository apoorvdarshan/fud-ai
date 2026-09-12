package com.apoorvdarshan.calorietracker.billing

enum class HostedPlan(val id: String) {
    NONE("none"),
    PLUS("plus"),
    PRO("pro");

    companion object {
        fun fromEntitlements(hasPro: Boolean, hasPlus: Boolean): HostedPlan = when {
            hasPro -> PRO
            hasPlus -> PLUS
            else -> NONE
        }
    }
}

enum class AIMode(val storageValue: String) {
    BYOK("byok"),
    HOSTED("hosted");

    companion object {
        fun fromStorage(value: String?): AIMode =
            entries.firstOrNull { it.storageValue == value } ?: BYOK
    }
}

enum class HostedAIAction(val cost: Int) {
    PHOTO_FOOD(1),
    TEXT_FOOD(1),
    VOICE_FOOD(2),
    INGREDIENT_AI(1),
    REPROCESS_MEAL(1),
    WHAT_IF(1),
    ALLERGENS_LAB(1),
    SIRI_FOOD(1),
    COACH_MESSAGE(1),
    WORKOUT_AI(1),
    HOSTED_STT(1);

    fun manualRecalculateGoals(llmCalls: Int) = llmCalls.coerceAtLeast(1)
}

object HostedAIConstants {
    const val PLUS_ENTITLEMENT = "plus"
    const val PRO_ENTITLEMENT = "pro"

    const val PLUS_MONTHLY = "com.apoorvdarshan.calorietracker.plus.monthly"
    const val PLUS_YEARLY = "com.apoorvdarshan.calorietracker.plus.yearly"
    const val PRO_MONTHLY = "com.apoorvdarshan.calorietracker.pro.monthly"
    const val PRO_YEARLY = "com.apoorvdarshan.calorietracker.pro.yearly"

    const val CREDITS_50 = "com.apoorvdarshan.calorietracker.credits.50"
    const val CREDITS_150 = "com.apoorvdarshan.calorietracker.credits.150"
    const val CREDITS_400 = "com.apoorvdarshan.calorietracker.credits.400"

    const val TIP_SNACK = "com.apoorvdarshan.calorietracker.tip.snack"
    const val TIP_PROTEIN = "com.apoorvdarshan.calorietracker.tip.proteinshake"
    const val TIP_LUNCH = "com.apoorvdarshan.calorietracker.tip.lunch"
    const val TIP_FEAST = "com.apoorvdarshan.calorietracker.tip.feast"

    const val PLUS_DAILY_LIMIT = 30
    const val PRO_DAILY_LIMIT = 60
    const val MAX_HOSTED_IMAGES = 3
    const val HOSTED_AI_BASE_URL = "https://fud-ai.app/api/hosted-ai/v1"
    const val HOSTED_AI_APP_SECRET = "fud-hosted-v1-dev-placeholder"

    val subscriptionProductIds = listOf(PLUS_MONTHLY, PLUS_YEARLY, PRO_MONTHLY, PRO_YEARLY)
    val creditProductIds = listOf(CREDITS_50, CREDITS_150, CREDITS_400)
    val tipProductIds = listOf(TIP_SNACK, TIP_PROTEIN, TIP_LUNCH, TIP_FEAST)

    fun dailyLimit(plan: HostedPlan): Int = when (plan) {
        HostedPlan.PRO -> PRO_DAILY_LIMIT
        HostedPlan.PLUS -> PLUS_DAILY_LIMIT
        HostedPlan.NONE -> 0
    }

    fun creditAmount(productId: String): Int? = when (productId) {
        CREDITS_50 -> 50
        CREDITS_150 -> 150
        CREDITS_400 -> 400
        else -> null
    }
}

sealed class HostedAIQuotaError : Exception() {
    data object NoActiveSubscription : HostedAIQuotaError()
    data class QuotaExceeded(val remainingDaily: Int, val creditBank: Int) : HostedAIQuotaError()
}
