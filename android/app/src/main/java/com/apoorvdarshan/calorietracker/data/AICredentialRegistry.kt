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
 * provider's effective key into the legacy entry, so older call sites that only know a
 * provider keep working without ever seeing another profile's secret.
 *
 * Resolution rules:
 * - A route that is bound to a profile always uses that profile — even after its key
 *   was cleared, so a route never silently switches to a different key.
 * - A route without a binding uses the provider's default profile: its unnamed profile
 *   when it has one, otherwise its oldest profile.
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
     * The provider's default profile: its unnamed profile when that one still holds a
     * key, otherwise the oldest profile with a key. When no profile has a key, the
     * oldest one is returned so the UI can still show which profile is empty.
     */
    @Synchronized
    fun defaultFor(provider: AIProvider): AICredential? {
        ensureMigrated()
        return defaultIn(readProfiles().orEmpty(), provider)
    }

    @Synchronized
    fun find(id: String?): AICredential? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    /** Secret of the provider's default profile. */
    @Synchronized
    fun defaultSecret(provider: AIProvider): String? = defaultFor(provider)?.let { rawSecret(it.id) }

    /**
     * The provider's effective key: the profile bound to the primary route when that
     * route runs on this provider, otherwise the provider's default profile. Legacy
     * "one key per provider" readers use this, so they follow what the user sees as the
     * primary AI configuration.
     */
    @Synchronized
    fun effectiveSecret(provider: AIProvider): String? {
        ensureMigrated()
        return effectiveSecretForMirror(provider)
    }

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

    /**
     * The profile a route uses: its own binding when that binding is valid for the
     * route's provider, otherwise the provider's default profile.
     */
    @Synchronized
    fun routeCredential(route: AIRoute, provider: AIProvider): AICredential? {
        ensureMigrated()
        return boundProfile(route, provider) ?: defaultFor(provider)
    }

    /**
     * The secret a route uses. A route that is bound to a profile without a key gets no
     * secret — it never falls back to another profile's key.
     */
    @Synchronized
    fun routeSecret(route: AIRoute, provider: AIProvider): String? =
        routeCredential(route, provider)?.let { rawSecret(it.id) }

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
        // The legacy mirror follows the primary route, so a rebuild from a damaged
        // profile file recovers the key the user actually uses.
        syncLegacyDefault(credential.provider)
    }

    /** Clears a slot binding after its provider changed, so the new provider's default applies. */
    @Synchronized
    fun clearRoute(route: AIRoute) {
        val previous = find(secrets.load(routeKey(route)))
        secrets.delete(routeKey(route))
        // Clearing the primary binding changes the effective key the legacy mirror
        // should expose, exactly like rebinding it does.
        previous?.let { syncLegacyDefault(it.provider) }
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

    /**
     * Renames a profile and optionally replaces its key, as one edit.
     *
     * Both values are validated before anything is written, so a rejected key does not
     * leave the profile half-renamed.
     */
    @Synchronized
    fun update(id: String, label: String, secret: String? = null): Result<AICredential> {
        ensureMigrated()
        val normalizedLabel = label.trim()
        validateLabel(normalizedLabel)?.let { return Result.failure(IllegalArgumentException(it)) }
        val normalizedSecret = secret?.trim()
        if (normalizedSecret != null) {
            validateSecret(normalizedSecret)?.let { return Result.failure(IllegalArgumentException(it)) }
        }
        val profiles = readProfiles().orEmpty().toMutableList()
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(NoSuchElementException("credential"))
        val updated = profiles[index].copy(label = normalizedLabel)
        profiles[index] = updated
        if (normalizedSecret != null) {
            secrets.save(secretStorageKey(id), normalizedSecret)
        }
        writeProfiles(profiles)
        syncLegacyDefault(updated.provider)
        return Result.success(updated)
    }

    @Synchronized
    fun rename(id: String, label: String): Result<AICredential> = update(id, label)

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
     * Removes the stored key of one profile but keeps the profile itself, so a named
     * profile keeps its label and its route binding, and the user can paste a new key
     * into the same profile later.
     */
    @Synchronized
    fun clearSecret(id: String): Result<AICredential> {
        ensureMigrated()
        val credential = find(id) ?: return Result.failure(NoSuchElementException("credential"))
        secrets.delete(secretStorageKey(id))
        syncLegacyDefault(credential.provider)
        return Result.success(credential)
    }

    /**
     * Deletes a profile.
     *
     * Every route that would resolve to the deleted profile is captured first — both
     * explicit bindings and routes that used it as the provider default — and moved to
     * [replacementId] when it belongs to the same provider, or left unconfigured
     * otherwise. Routes that never used the profile keep their own credential.
     */
    @Synchronized
    fun delete(
        id: String,
        replacementId: String? = null,
        routeProviders: Map<AIRoute, AIProvider> = emptyMap()
    ): Result<Unit> {
        ensureMigrated()
        val profiles = readProfiles().orEmpty().toMutableList()
        val credential = profiles.firstOrNull { it.id == id }
            ?: return Result.failure(NoSuchElementException("credential"))
        val replacement = find(replacementId)?.takeIf { it.id != id && it.provider == credential.provider }

        // Capture the routes that use this profile before it disappears, including the
        // routes that only used it because it was the provider default. Explicit
        // bindings always count, even when the caller does not pass the route map.
        val affectedRoutes = AIRoute.entries.filter { route ->
            if (secrets.load(routeKey(route)) == id) return@filter true
            val provider = routeProviders[route] ?: return@filter false
            routeCredential(route, provider)?.id == id
        }

        profiles.removeAll { it.id == id }
        writeProfiles(profiles)
        secrets.delete(secretStorageKey(id))
        affectedRoutes.forEach { route ->
            if (replacement != null) {
                secrets.save(routeKey(route), replacement.id)
            } else {
                secrets.delete(routeKey(route))
            }
        }
        syncLegacyDefault(credential.provider)
        return Result.success(Unit)
    }

    /**
     * Compatibility entry point for onboarding and older callers that only know a
     * provider. It writes to the provider's default profile, so a key the user named
     * for a *different* profile is never overwritten.
     */
    @Synchronized
    fun setDefaultSecret(provider: AIProvider, secret: String?): Result<AICredential?> {
        ensureMigrated()
        val value = secret?.trim().orEmpty()
        // The provider-wide API only ever writes the unnamed key slot, so a key the
        // user named is never overwritten.
        val target = forProvider(provider).firstOrNull { it.label.isNullOrBlank() }
        if (value.isEmpty()) {
            // Clearing a key removes the secret; the profile and its bindings stay, so
            // the route reports "no key" instead of silently using another one.
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

    /** The profile a route is explicitly bound to, when that binding is usable. */
    private fun boundProfile(route: AIRoute, provider: AIProvider): AICredential? {
        val boundId = secrets.load(routeKey(route)) ?: return null
        val bound = readProfiles().orEmpty().firstOrNull { it.id == boundId } ?: return null
        return bound.takeIf { it.provider == provider }
    }

    /**
     * The provider's default profile out of [profiles]: its unnamed profile when that one
     * still holds a key, otherwise the oldest profile with a key. Without a key anywhere,
     * the oldest profile is returned so the UI can show which profile is empty.
     */
    private fun defaultIn(profiles: List<AICredential>, provider: AIProvider): AICredential? {
        val candidates = profiles
            .filter { it.provider == provider }
            .sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
        val withKey = candidates.filter { rawSecret(it.id) != null }
        return withKey.firstOrNull { it.label.isNullOrBlank() }
            ?: withKey.firstOrNull()
            ?: candidates.firstOrNull { it.label.isNullOrBlank() }
            ?: candidates.firstOrNull()
    }

    /**
     * The effective key without running the migration. The migration calls the legacy
     * mirror before it writes its marker, so this variant prevents a recursive pass.
     */
    private fun effectiveSecretForMirror(provider: AIProvider): String? {
        val bound = boundProfile(AIRoute.PRIMARY, provider)
            ?: defaultIn(readProfiles().orEmpty(), provider)
        return bound?.let { rawSecret(it.id) }
    }

    /** Mirrors the provider's effective key into `apikey_<PROVIDER>` for legacy readers. */
    private fun syncLegacyDefault(provider: AIProvider) {
        val value = effectiveSecretForMirror(provider)
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
