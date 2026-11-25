package com.apoorvdarshan.calorietracker.models

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R

enum class OpenRouterReasoningEffort(val value: String, @StringRes val labelRes: Int) {
    AUTO("auto", R.string.reasoning_effort_auto),
    NONE("none", R.string.reasoning_effort_none),
    MINIMAL("minimal", R.string.reasoning_effort_minimal),
    LOW("low", R.string.reasoning_effort_low),
    MEDIUM("medium", R.string.reasoning_effort_medium),
    HIGH("high", R.string.reasoning_effort_high),
    XHIGH("xhigh", R.string.reasoning_effort_xhigh),
    MAX("max", R.string.reasoning_effort_max);

    fun requestOptions(compactRetry: Boolean, exclude: Boolean, structuredModel: String? = null): Map<String, Any>? {
        val options = mutableMapOf<String, Any>()
        if (exclude || compactRetry) options["exclude"] = true
        // Excluding reasoning only hides it from the response; it still uses output tokens.
        if (structuredModel?.let(::canDisableAnalysisReasoning) == true && (compactRetry || this == AUTO)) {
            options["effort"] = "none"
        }
        else if (compactRetry) options["effort"] = "low"
        else if (this != AUTO) options["effort"] = value
        return options.takeIf { it.isNotEmpty() }
    }

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: AUTO

        // OpenRouter's public catalog confirms "none" for these families. Do not
        // assume that arbitrary/mandatory reasoning models accept the override.
        private fun canDisableAnalysisReasoning(model: String): Boolean =
            model == "openrouter/free" ||
                model.substringBefore(':') in setOf(
                    "deepseek/deepseek-v4.1-flash",
                    "deepseek/deepseek-v4.1-flash-1017",
                    "nvidia/nemotron-3-ultra-550b-a55b"
                )
    }
}
