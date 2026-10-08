package com.apoorvdarshan.calorietracker.services.hosted

import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.AiErrorKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the AI services need to route a request through Hosted AI. The client-side
 * gate only short-circuits a known non-subscriber; the Worker is the authority
 * and meters every call (including background ones like Adaptive Goals).
 */
class HostedAiAccess(
    val client: HostedAiClient,
    private val entitlement: () -> HostedEntitlement
) {
    /** Throws when RevenueCat has answered and there is no Plus/Pro plan. */
    fun requireEntitlement() {
        val current = entitlement()
        if (current.loaded && !current.isEntitled) {
            throw AiError.Hosted(AiErrorKind.HOSTED_SUBSCRIPTION_REQUIRED, "subscription_required")
        }
    }
}

sealed interface HostedPrompt {
    data object Paywall : HostedPrompt
    data object Credits : HostedPrompt
    data object OutOfQuota : HostedPrompt
}

/**
 * App-wide request for the paywall / credits / out-of-quota UI. One value at a
 * time, rendered by a single host, so two modals are never stacked.
 */
class HostedUiRouter {
    private val _prompt = MutableStateFlow<HostedPrompt?>(null)
    val prompt: StateFlow<HostedPrompt?> = _prompt.asStateFlow()

    fun show(prompt: HostedPrompt) {
        _prompt.value = prompt
    }

    fun dismiss() {
        _prompt.value = null
    }

    /**
     * Routes a Hosted AI paywall error to its UI. Returns true when handled, so the
     * caller can skip its own error dialog.
     */
    fun handle(error: Throwable?): Boolean {
        val kind = (error as? AiError)?.kind ?: return false
        when (kind) {
            AiErrorKind.HOSTED_QUOTA_EXCEEDED -> show(HostedPrompt.OutOfQuota)
            AiErrorKind.HOSTED_SUBSCRIPTION_REQUIRED -> show(HostedPrompt.Paywall)
            else -> return false
        }
        return true
    }
}
