package com.apoorvdarshan.calorietracker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.SpeechProvider

/**
 * Encrypted per-provider API key storage — the Android equivalent of iOS Keychain.
 * Backed by EncryptedSharedPreferences (AES-256).
 */
class KeyStore(context: Context) : CredentialSecretStore {
    private val prefs: SharedPreferences = openOrRecover(context)

    /**
     * Named API-key profiles plus the credential bound to each AI routing slot.
     * The registry owns every AI key, so [apiKey] and [setApiKey] stay in sync
     * with the profiles shown in Settings.
     */
    val credentials: AICredentialRegistry = AICredentialRegistry(this)

    override fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun load(key: String): String? = prefs.getString(key, null)

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    // AI providers
    /** The provider's effective key: the primary route's profile, or its default. */
    fun apiKey(provider: AIProvider): String? = credentials.effectiveSecret(provider)

    /** Writes the provider's default profile only; other profiles stay untouched. */
    fun setApiKey(provider: AIProvider, key: String?) {
        credentials.setDefaultSecret(provider, key)
    }

    // Speech providers
    fun speechApiKey(provider: SpeechProvider): String? {
        val dedicated = load(STT_PREFIX + provider.name)
        if (!dedicated.isNullOrEmpty()) return dedicated
        return provider.matchingAIProvider?.let(::apiKey)
    }
    fun setSpeechApiKey(provider: SpeechProvider, key: String?) {
        val storageKey = STT_PREFIX + provider.name
        if (key.isNullOrEmpty()) delete(storageKey) else save(storageKey, key)
    }

    /** The user's own USDA FoodData Central key for barcode lookups (#533); null when blank. */
    fun usdaFoodDataCentralApiKey(): String? = load(USDA_FDC_API_KEY)?.trim()?.takeIf { it.isNotEmpty() }

    fun setUsdaFoodDataCentralApiKey(key: String?) {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) delete(USDA_FDC_API_KEY) else save(USDA_FDC_API_KEY, trimmed)
    }

    fun weeklyChallengeBearerToken(): String? = load(WEEKLY_CHALLENGE_BEARER_TOKEN)

    fun setWeeklyChallengeBearerToken(token: String) {
        save(WEEKLY_CHALLENGE_BEARER_TOKEN, token)
    }

    fun clearWeeklyChallengeBearerToken() {
        delete(WEEKLY_CHALLENGE_BEARER_TOKEN)
    }

    fun cloudBackupAccessToken(): String? = load(CLOUD_BACKUP_ACCESS_TOKEN)

    fun setCloudBackupAccessToken(token: String?) {
        if (token.isNullOrBlank()) delete(CLOUD_BACKUP_ACCESS_TOKEN) else save(CLOUD_BACKUP_ACCESS_TOKEN, token)
    }

    fun clearAll(preserveWeeklyChallengeToken: Boolean = false) {
        val challengeToken = if (preserveWeeklyChallengeToken) weeklyChallengeBearerToken() else null
        prefs.edit().clear().apply {
            if (challengeToken != null) {
                putString(WEEKLY_CHALLENGE_BEARER_TOKEN, challengeToken)
            }
        }.apply()
    }

    companion object {
        private const val TAG = "FudAIKeyStore"
        private const val FILE_NAME = "fudai_keychain"
        private const val STT_PREFIX = "speechApiKey_"
        private const val USDA_FDC_API_KEY = "usdaFoodDataCentralApiKey"
        private const val WEEKLY_CHALLENGE_BEARER_TOKEN = "weekly_challenge_bearer_token_v1"
        private const val CLOUD_BACKUP_ACCESS_TOKEN = "cloud_backup_access_token_v1"

        /**
         * Open EncryptedSharedPreferences. On Android 14/15 (and occasionally
         * older), the AndroidKeystore master-key alias survives `pm uninstall`
         * but the encrypted prefs file does not — so a reinstall (debug build,
         * Play Store update from a deleted install, etc.) hits an
         * `AEADBadTagException` on the first read because the surviving alias
         * can't decrypt a freshly-generated keyset header.
         *
         * Recovery path: catch the failure, wipe both the prefs file AND the
         * AndroidKeystore alias, then rebuild. The user only loses cached API
         * keys, which they'd re-enter on first run anyway. Without this, the
         * app crashes on Application.onCreate before showing any UI.
         */
        private fun openOrRecover(context: Context): SharedPreferences {
            return try {
                build(context)
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences open failed; recovering", e)
                runCatching { context.deleteSharedPreferences(FILE_NAME) }
                runCatching {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
                build(context)
            }
        }

        private fun build(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
