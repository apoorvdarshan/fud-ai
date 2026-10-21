package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.AIProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A named API-key profile. The secret itself never lives in this record or in
 * plain preferences; it stays in [CredentialSecretStore] (EncryptedSharedPreferences
 * on Android) under [AICredentialRegistry.secretStorageKey].
 *
 * [id] is the stable association used by routing slots, so renaming or replacing a
 * key never changes which routes point at it.
 */
@Serializable
data class AICredential(
    val id: String,
    val provider: AIProvider,
    /**
     * User-defined nickname. Null marks the provider's unnamed "key slot" — the
     * profile the legacy one-key-per-provider API writes to. The UI shows a localized
     * default name for it.
     */
    val label: String? = null,
    @SerialName("createdAt") val createdAtEpochMs: Long = 0,
)

/** The AI request slots whose credential can be chosen independently. */
enum class AIRoute {
    PRIMARY,
    TEXT,
    IMAGE_FALLBACK,
    TEXT_FALLBACK,
}

/** Minimal secure-storage seam so the registry can be tested on the JVM. */
interface CredentialSecretStore {
    fun load(key: String): String?
    fun save(key: String, value: String)
    fun delete(key: String)
}

/**
 * Stores named API-key profiles and the credential bound to each routing slot.
 *
 * Legacy installs kept exactly one key per provider under `apikey_<PROVIDER>`. That key
 * becomes the provider's unnamed profile, and the registry keeps mirroring the
 * provider's effective key into the legacy entry so older call sites that only know a
 * provider keep working without ever seeing another profile's secret.
 *
 * Resolution rules:
 * - An explicit route binding wins when it still exists and belongs to that provider.
 * - Otherwise the provider's default profile is used: its unnamed profile when that one
 *   still holds a key, otherwise its oldest profile with a key. For a migrated install
 *   that is exactly the pre-profile behavior of every existing route.
 * - A binding to a different provider is ignored, so changing a route's provider can
 *   never accidentally send one provider's key to another service.
 */
class AICredentialRegistry(
    private val secrets: CredentialSecretStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // -- Migration --------------------------------------------------------

    @Synchronized
    fun ensureMigrated() {
        val stored = readProfiles()
        if (stored != null && secrets.load(MIGRATION_KEY) == MIGRATION_VERSION.toString()) return

        // Keep every profile that could still be read, then adopt any legacy key that
        // is not represented yet. The legacy secret is never deleted here, so a damaged
        // profile file can still be rebuilt on the next launch.
        val profiles = stored.orEmpty().toMutableList()
        for (provider in AIProvider.entries) {
            if (!provider.requiresApiKey) continue
            val legacy = secrets.load(legacyKey(provider))?.trim().orEmpty()
            if (legacy.isEmpty()) continue
            if (profiles.any { it.provider == provider }) continue
            val credential = AICredential(
                id = newId(),
                provider = provider,
                label = null,
                createdAtEpochMs = nextCreatedAt(profiles),
            )
            secrets.save(secretStorageKey(credential.id), legacy)
            profiles += credential
        }
        writeProfiles(profiles)
        profiles.map { it.provider }.distinct().forEach(::syncLegacyDefault)
        secrets.save(MIGRATION_KEY, MIGRATION_VERSION.toString())
    }

    // -- Reading ----------------------------------------------------------

    @Synchronized
    fun all(): List<AICredential> {
        ensureMigrated()
        return readProfiles().orEmpty().sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
    }

    @Synchronized
    fun forProvider(provider: AIProvider): List<AICredential> =
        all().filter { it.provider == provider }

    /**
     * The provider's default profile: its unnamed profile while that one still holds a
     * key, otherwise its oldest profile with a key. It is the implicit target for
     * routes without an explicit binding.
     */
    @Synchronized
    fun defaultFor(provider: AIProvider): AICredential? =
        defaultIn(readProfiles().orEmpty(), provider)

    @Synchronized
    fun find(id: String?): AICredential? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    /** Secret of the provider's default profile. */
    @Synchronized
    fun defaultSecret(provider: AIProvider): String? = defaultFor(provider)?.let { secret(it.id) }

    /**
     * The provider's effective key: the profile bound to the primary route when that
     * route runs on this provider, otherwise the provider's default profile. Legacy
     * "one key per provider" readers use this, so they follow what the user sees as the
     * primary AI configuration.
     */
    @Synchronized
    fun effectiveSecret(provider: AIProvider): String? =
        (routeCredential(AIRoute.PRIMARY, provider) ?: defaultFor(provider))?.let { secret(it.id) }

    @Synchronized
    fun secret(id: String): String? {
        ensureMigrated()
        return rawSecret(id)
    }

    /** Reads a stored secret without triggering the migration pass. */
    private fun rawSecret(id: String): String? =
        secrets.load(secretStorageKey(id))?.takeIf { it.isNotBlank() }

    /** Stable, non-reversible display fingerprint. Never includes a long key prefix. */
    @Synchronized
    fun maskedSecret(id: String): String = maskSecret(secret(id))

    @Synchronized
    fun routeCredential(route: AIRoute, provider: AIProvider): AICredential? {
        ensureMigrated()
        val bound = find(secrets.load(routeKey(route)))
        if (bound != null && bound.provider == provider) return bound
        return defaultFor(provider)
    }

    @Synchronized
    fun routeSecret(route: AIRoute, provider: AIProvider): String? =
        routeCredential(route, provider)?.let { secret(it.id) }

    @Synchronized
    fun bindings(): Map<AIRoute, String?> =
        AIRoute.entries.associateWith { secrets.load(routeKey(it)) }

    // -- Writing ----------------------------------------------------------

    /** Binds a slot to a profile. A binding to another provider is ignored. */
    @Synchronized
    fun bindRoute(route: AIRoute, credentialId: String?) {
        ensureMigrated()
        if (credentialId == null) {
            secrets.delete(routeKey(route))
            return
        }
        val credential = find(credentialId) ?: return
        secrets.save(routeKey(route), credential.id)
    }

    /** Clears a slot binding after its provider changed, so the new provider's default applies. */
    @Synchronized
    fun clearRoute(route: AIRoute) {
        secrets.delete(routeKey(route))
    }

    /**
     * Adds a named profile. A null [label] creates the provider's unnamed key slot;
     * a blank label is rejected.
     */
    @Synchronized
    fun add(provider: AIProvider, label: String?, secret: String): Result<AICredential> {
        ensureMigrated()
        val normalizedLabel = label?.trim()
        if (normalizedLabel != null) {
            validateLabel(normalizedLabel)?.let { return Result.failure(IllegalArgumentException(it)) }
        }
        val normalizedSecret = secret.trim()
        validateSecret(normalizedSecret)?.let { return Result.failure(IllegalArgumentException(it)) }

        val profiles = readProfiles().orEmpty()
        val credential = AICredential(
            id = newId(),
            provider = provider,
            label = normalizedLabel?.takeIf { it.isNotEmpty() },
            createdAtEpochMs = nextCreatedAt(profiles),
        )
        secrets.save(secretStorageKey(credential.id), normalizedSecret)
        writeProfiles(profiles + credential)
        syncLegacyDefault(provider)
        return Result.success(credential)
    }

    @Synchronized
    fun rename(id: String, label: String): Result<AICredential> {
        ensureMigrated()
        val normalizedLabel = label.trim()
        validateLabel(normalizedLabel)?.let { return Result.failure(IllegalArgumentException(it)) }
        val profiles = readProfiles().orEmpty().toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(NoSuchElementException("credential"))
        val updated = profiles[index].copy(label = normalizedLabel)
        profiles[index] = updated
        writeProfiles(profiles)
        return Result.success(updated)
    }

    /** Replaces the secret of one profile only; other profiles and route bindings stay untouched. */
    @Synchronized
    fun replaceSecret(id: String, secret: String): Result<AICredential> {
        ensureMigrated()
        val normalizedSecret = secret.trim()
        validateSecret(normalizedSecret)?.let { return Result.failure(IllegalArgumentException(it)) }
        val credential = find(id) ?: return Result.failure(NoSuchElementException("credential"))
        secrets.save(secretStorageKey(id), normalizedSecret)
        syncLegacyDefault(credential.provider)
        return Result.success(credential)
    }

    /**
     * Removes the stored key of one profile. A named profile keeps its label and its
     * route binding, so the user can paste a new key into the same profile later. The
     * provider's unnamed slot is removed instead, because it only exists to hold a key.
     */
    @Synchronized
    fun clearSecret(id: String): Result<AICredential> {
        ensureMigrated()
        val credential = find(id) ?: return Result.failure(NoSuchElementException("credential"))
        if (credential.label.isNullOrBlank()) {
            delete(id)
            return Result.success(credential)
        }
        secrets.delete(secretStorageKey(id))
        syncLegacyDefault(credential.provider)
        return Result.success(credential)
    }

    /**
     * Deletes a profile. Explicit route bindings are moved to [replacementId] when it
     * belongs to the same provider, otherwise they are cleared. Unbound routes fall
     * back to the provider's default profile automatically.
     */
    @Synchronized
    fun delete(id: String, replacementId: String? = null): Result<Unit> {
        ensureMigrated()
        val profiles = readProfiles().orEmpty().toMutableList()
        val credential = profiles.firstOrNull { it.id == id }
            ?: return Result.failure(NoSuchElementException("credential"))
        val replacement = find(replacementId)?.takeIf { it.id != id && it.provider == credential.provider }
        profiles.removeAll { it.id == id }
        writeProfiles(profiles)
        secrets.delete(secretStorageKey(id))
        AIRoute.entries.forEach { route ->
            if (secrets.load(routeKey(route)) == id) {
                if (replacement != null) {
                    secrets.save(routeKey(route), replacement.id)
                } else {
                    secrets.delete(routeKey(route))
                }
            }
        }
        syncLegacyDefault(credential.provider)
        return Result.success(Unit)
    }

    /**
     * Compatibility entry point for onboarding and older callers that only know a
     * provider. It only ever writes to the provider's unnamed profile, so a key the
     * user named is never overwritten. A new unnamed profile is created when the
     * provider has none.
     */
    @Synchronized
    fun setDefaultSecret(provider: AIProvider, secret: String?): Result<AICredential?> {
        ensureMigrated()
        val value = secret?.trim().orEmpty()
        val target = forProvider(provider).firstOrNull { it.label.isNullOrBlank() }
        if (value.isEmpty()) {
            // Clearing a key removes the secret; routes fall back to the
            // provider's remaining default.
            if (target != null) clearSecret(target.id)
            return Result.success(null)
        }
        return if (target != null) {
            replaceSecret(target.id, value).map { it }
        } else {
            add(provider, null, value).map { it }
        }
    }

    /** All slots that currently resolve to [id], including implicit defaults. */
    @Synchronized
    fun routesUsing(id: String, routeProviders: Map<AIRoute, AIProvider>): List<AIRoute> {
        val credential = find(id) ?: return emptyList()
        return routeProviders.mapNotNull { (route, provider) ->
            route.takeIf { provider == credential.provider && routeCredential(route, provider)?.id == id }
        }
    }

    @Synchronized
    fun deleteAll() {
        readProfiles().orEmpty().forEach { secrets.delete(secretStorageKey(it.id)) }
        AIProvider.entries.forEach { secrets.delete(legacyKey(it)) }
        AIRoute.entries.forEach { secrets.delete(routeKey(it)) }
        secrets.delete(PROFILES_KEY)
        secrets.delete(MIGRATION_KEY)
    }

    // -- Internals --------------------------------------------------------

    /**
     * The provider's default profile out of [profiles]: its unnamed profile while that
     * one still holds a key, otherwise its oldest profile with a key.
     */
    private fun defaultIn(profiles: List<AICredential>, provider: AIProvider): AICredential? {
        val withKey = profiles
            .filter { it.provider == provider && rawSecret(it.id) != null }
            .sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
        return withKey.firstOrNull { it.label.isNullOrBlank() }
            ?: withKey.firstOrNull()
    }

    /** Mirrors the provider's effective key into `apikey_<PROVIDER>` for legacy readers. */
    private fun syncLegacyDefault(provider: AIProvider) {
        val profiles = readProfiles().orEmpty()
        val primaryBound = profiles.firstOrNull {
            it.id == secrets.load(routeKey(AIRoute.PRIMARY)) && it.provider == provider
        }
        val target = primaryBound ?: defaultIn(profiles, provider)
        val value = target?.let { secrets.load(secretStorageKey(it.id)) }
        if (value.isNullOrBlank()) {
            secrets.delete(legacyKey(provider))
        } else {
            secrets.save(legacyKey(provider), value)
        }
    }

    private fun readProfiles(): List<AICredential>? {
        val raw = secrets.load(PROFILES_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<AICredential>>(raw)
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
        }.getOrElse {
            // Keep the damaged file so a manual recovery is still possible; the
            // migration then rebuilds the profiles from the secure legacy entries.
            secrets.save(PROFILES_BACKUP_KEY, raw)
            null
        }
    }

    private fun writeProfiles(profiles: List<AICredential>) {
        secrets.save(PROFILES_KEY, json.encodeToString(profiles))
    }

    private fun nextCreatedAt(profiles: List<AICredential>): Long =
        maxOf(now(), (profiles.maxOfOrNull { it.createdAtEpochMs } ?: 0L) + 1L)

    private fun validateLabel(label: String): String? = when {
        label.isEmpty() -> "label_required"
        label.length > MAX_LABEL_LENGTH -> "label_too_long"
        else -> null
    }

    private fun validateSecret(secret: String): String? = when {
        secret.isEmpty() -> "key_required"
        secret.length > MAX_SECRET_LENGTH -> "key_too_long"
        secret.any { it.isWhitespace() } -> "key_contains_whitespace"
        else -> null
    }

    companion object {
        const val MAX_LABEL_LENGTH = 60
        const val MAX_SECRET_LENGTH = 8_192
        internal const val PROFILES_KEY = "aiCredentialProfiles_v1"
        internal const val PROFILES_BACKUP_KEY = "aiCredentialProfiles_v1_corrupt_backup"
        internal const val MIGRATION_KEY = "aiCredentialMigrationVersion"
        internal const val MIGRATION_VERSION = 1
        internal const val SECRET_PREFIX = "aiCredentialSecret_"
        internal const val ROUTE_PREFIX = "aiRouteCredential_"

        internal fun secretStorageKey(id: String) = SECRET_PREFIX + id
        internal fun routeKey(route: AIRoute) = ROUTE_PREFIX + route.name
        internal fun legacyKey(provider: AIProvider) = "apikey_" + provider.name

        /** Last four characters only, or a fully masked value for very short secrets. */
        fun maskSecret(secret: String?): String {
            val value = secret?.trim().orEmpty()
            if (value.isEmpty()) return ""
            if (value.length < 8) return "••••"
            return "••••" + value.takeLast(4)
        }
    }
}
