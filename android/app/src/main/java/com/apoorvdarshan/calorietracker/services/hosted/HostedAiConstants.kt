package com.apoorvdarshan.calorietracker.services.hosted

/**
 * Hosted AI (Plus/Pro) constants. Mirrors iOS `HostedAIConstants.swift`; the
 * store ids are the Google Play ones from `store/catalog/products.json`
 * (Play caps product ids at 40 chars, so they differ from the App Store ids).
 */
object HostedAiConstants {
    const val BASE_URL = "https://fud-ai.app/api/hosted-ai/v1"
    const val USER_ID_HEADER = "X-Fud-User-Id"

    /** The Worker rejects more than this many images per request. */
    const val MAX_IMAGES = 3

    const val PLUS_ENTITLEMENT = "plus"
    const val PRO_ENTITLEMENT = "pro"
    const val PLUS_OFFERING = "plus"
    const val PRO_OFFERING = "pro"

    /** Consumable credit packs, in display order. Spent after the daily pool, only while a plan is active. */
    val CREDIT_PACKS: List<CreditPack> = listOf(
        CreditPack("credits_50", 50),
        CreditPack("credits_150", 150),
        CreditPack("credits_400", 400)
    )

    const val TERMS_URL = "https://fud-ai.app/terms.html"
    const val PRIVACY_URL = "https://fud-ai.app/privacy.html"
    private const val PACKAGE_NAME = "com.apoorvdarshan.calorietracker"

    /** Google Play's subscription center, deep-linked to the active subscription when known. */
    fun manageSubscriptionsUrl(subscriptionId: String?): String =
        if (subscriptionId.isNullOrBlank()) {
            "https://play.google.com/store/account/subscriptions"
        } else {
            "https://play.google.com/store/account/subscriptions?sku=$subscriptionId&package=$PACKAGE_NAME"
        }
}

data class CreditPack(val productId: String, val credits: Int)

/** How AI requests are powered. Stored as `aiAccessMode` (same key and values as iOS). */
enum class AiAccessMode(val storageValue: String) {
    BYOK("byok"),
    HOSTED("hosted");

    companion object {
        fun fromStorage(raw: String?): AiAccessMode = entries.firstOrNull { it.storageValue == raw } ?: BYOK
    }
}

enum class HostedPlan(val wireValue: String, val dailyLimit: Int) {
    NONE("none", 0),
    PLUS("plus", 30),
    PRO("pro", 60);

    companion object {
        fun fromWire(raw: String?): HostedPlan? = entries.firstOrNull { it.wireValue == raw }
    }
}

enum class BillingPeriod { MONTHLY, YEARLY }
