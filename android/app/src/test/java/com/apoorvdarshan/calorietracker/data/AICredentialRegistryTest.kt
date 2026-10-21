package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.AIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AICredentialRegistryTest {
    private class FakeSecrets(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : CredentialSecretStore {
        override fun load(key: String): String? = values[key]
        override fun save(key: String, value: String) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
    }

    private fun registry(store: FakeSecrets = FakeSecrets()): AICredentialRegistry {
        var id = 0
        var clock = 1_000L
        return AICredentialRegistry(store, now = { clock++ }, newId = { "id-${++id}" })
    }

    @Test
    fun migrationCopiesLegacyKeysOnceAndKeepsThemUsable() {
        val store = FakeSecrets(
            mutableMapOf(
                "apikey_OPENROUTER" to "sk-or-v1-personal-1234",
                "apikey_OPENAI" to "sk-openai-5678",
            )
        )
        val creds = registry(store)

        val migrated = creds.all()
        assertEquals(2, migrated.size)
        val openRouter = migrated.single { it.provider == AIProvider.OPENROUTER }
        assertNull(openRouter.label)
        assertEquals("sk-or-v1-personal-1234", creds.secret(openRouter.id))
        assertEquals("sk-or-v1-personal-1234", store.load("apikey_OPENROUTER"))

        // Second launch must not duplicate profiles or change their IDs.
        creds.ensureMigrated()
        assertEquals(migrated.map { it.id }, creds.all().map { it.id })
    }

    @Test
    fun twoProfilesForOneProviderStayIsolated() {
        val store = FakeSecrets()
        val creds = registry(store)
        val first = creds.add(AIProvider.OPENROUTER, "Personal", "sk-or-personal-1111").getOrThrow()
        val second = creds.add(AIProvider.OPENROUTER, "Work", "sk-or-work-2222").getOrThrow()

        assertNotEquals(first.id, second.id)
        assertEquals(2, creds.forProvider(AIProvider.OPENROUTER).size)
        assertEquals("sk-or-personal-1111", creds.secret(first.id))
        assertEquals("sk-or-work-2222", creds.secret(second.id))
        assertEquals("••••1111", creds.maskedSecret(first.id))
        assertEquals("••••2222", creds.maskedSecret(second.id))
        assertFalse(creds.maskedSecret(first.id).contains("personal"))

        creds.replaceSecret(second.id, "sk-or-work-replaced-3333").getOrThrow()
        assertEquals("sk-or-personal-1111", creds.secret(first.id))
        assertEquals("sk-or-work-replaced-3333", creds.secret(second.id))
        assertEquals("sk-or-personal-1111", store.load("apikey_OPENROUTER"))
    }

    @Test
    fun routesBindIndependentlyAndIgnoreForeignProviders() {
        val creds = registry()
        val personal = creds.add(AIProvider.OPENROUTER, "Personal", "sk-or-personal-1111").getOrThrow()
        val work = creds.add(AIProvider.OPENROUTER, "Work", "sk-or-work-2222").getOrThrow()

        creds.bindRoute(AIRoute.PRIMARY, personal.id)
        creds.bindRoute(AIRoute.IMAGE_FALLBACK, work.id)

        assertEquals(personal.id, creds.routeCredential(AIRoute.PRIMARY, AIProvider.OPENROUTER)?.id)
        assertEquals(work.id, creds.routeCredential(AIRoute.IMAGE_FALLBACK, AIProvider.OPENROUTER)?.id)
        assertEquals("sk-or-personal-1111", creds.routeSecret(AIRoute.PRIMARY, AIProvider.OPENROUTER))
        assertEquals("sk-or-work-2222", creds.routeSecret(AIRoute.IMAGE_FALLBACK, AIProvider.OPENROUTER))

        // A binding for OpenRouter must never be handed to another provider.
        val openAI = creds.add(AIProvider.OPENAI, "Main", "sk-openai-3333").getOrThrow()
        assertEquals(openAI.id, creds.routeCredential(AIRoute.IMAGE_FALLBACK, AIProvider.OPENAI)?.id)
        assertEquals("sk-openai-3333", creds.routeSecret(AIRoute.IMAGE_FALLBACK, AIProvider.OPENAI))
    }

    @Test
    fun renamingKeepsTheRouteAndSecretStable() {
        val creds = registry()
        val profile = creds.add(AIProvider.OPENROUTER, "Old name", "sk-or-secret-9999").getOrThrow()
        creds.bindRoute(AIRoute.TEXT, profile.id)

        val renamed = creds.rename(profile.id, "New name").getOrThrow()

        assertEquals(profile.id, renamed.id)
        assertEquals("New name", creds.find(profile.id)?.label)
        assertEquals("sk-or-secret-9999", creds.routeSecret(AIRoute.TEXT, AIProvider.OPENROUTER))
    }

    @Test
    fun deletingAReferencedProfileReassignsOrClearsTheRoute() {
        val creds = registry()
        val first = creds.add(AIProvider.OPENROUTER, "First", "sk-or-first-1111").getOrThrow()
        val second = creds.add(AIProvider.OPENROUTER, "Second", "sk-or-second-2222").getOrThrow()
        creds.bindRoute(AIRoute.PRIMARY, first.id)
        creds.bindRoute(AIRoute.TEXT, first.id)

        creds.delete(first.id, replacementId = second.id).getOrThrow()

        assertEquals(second.id, creds.routeCredential(AIRoute.PRIMARY, AIProvider.OPENROUTER)?.id)
        assertEquals(second.id, creds.routeCredential(AIRoute.TEXT, AIProvider.OPENROUTER)?.id)
        assertEquals("sk-or-second-2222", creds.secret(second.id))
        assertNull(creds.find(first.id))
        assertNull(creds.secret(first.id))

        creds.delete(second.id).getOrThrow()
        assertNull(creds.routeCredential(AIRoute.PRIMARY, AIProvider.OPENROUTER))
    }

    @Test
    fun deletingOneOfTwoUnboundProfilesKeepsTheDefaultAvailable() {
        val creds = registry()
        val first = creds.add(AIProvider.OPENAI, "First", "sk-openai-first-1111").getOrThrow()
        val second = creds.add(AIProvider.OPENAI, "Second", "sk-openai-second-2222").getOrThrow()
        assertEquals(first.id, creds.routeCredential(AIRoute.PRIMARY, AIProvider.OPENAI)?.id)

        creds.delete(first.id).getOrThrow()

        assertEquals(second.id, creds.routeCredential(AIRoute.PRIMARY, AIProvider.OPENAI)?.id)
        assertEquals(second.id, creds.defaultFor(AIProvider.OPENAI)?.id)
    }

    @Test
    fun legacySetterNeverOverwritesANamedProfile() {
        val creds = registry()
        val personal = creds.add(AIProvider.OPENROUTER, "Personal", "sk-or-first-1111").getOrThrow()
        val work = creds.add(AIProvider.OPENROUTER, "Work", "sk-or-work-2222").getOrThrow()

        // The provider-wide API (onboarding) writes to the provider's unnamed slot and
        // leaves the keys the user named alone.
        creds.setDefaultSecret(AIProvider.OPENROUTER, "sk-or-updated-4444").getOrThrow()

        assertEquals("sk-or-first-1111", creds.secret(personal.id))
        assertEquals("sk-or-work-2222", creds.secret(work.id))
        val unnamed = creds.forProvider(AIProvider.OPENROUTER).single { it.label == null }
        assertEquals("sk-or-updated-4444", creds.secret(unnamed.id))
        assertEquals(unnamed.id, creds.defaultFor(AIProvider.OPENROUTER)?.id)
    }

    @Test
    fun legacySetterUpdatesTheUnnamedSlot() {
        val creds = registry()
        val unnamed = creds.add(AIProvider.OPENROUTER, null, "sk-or-first-1111").getOrThrow()

        creds.setDefaultSecret(AIProvider.OPENROUTER, "sk-or-updated-4444").getOrThrow()

        assertEquals("sk-or-updated-4444", creds.secret(unnamed.id))
        assertEquals(1, creds.forProvider(AIProvider.OPENROUTER).size)

        // Clearing removes the unnamed slot again.
        creds.setDefaultSecret(AIProvider.OPENROUTER, null).getOrThrow()
        assertNull(creds.find(unnamed.id))
        assertTrue(creds.forProvider(AIProvider.OPENROUTER).isEmpty())
    }

    @Test
    fun theProviderEffectiveKeyFollowsThePrimaryRoute() {
        val creds = registry()
        val personal = creds.add(AIProvider.OPENROUTER, "Personal", "sk-or-personal-1111").getOrThrow()
        val work = creds.add(AIProvider.OPENROUTER, "Work", "sk-or-work-2222").getOrThrow()

        // Before a binding the provider's default profile answers.
        assertEquals("sk-or-personal-1111", creds.effectiveSecret(AIProvider.OPENROUTER))

        // Binding the primary route moves the provider-wide key with it.
        creds.bindRoute(AIRoute.PRIMARY, work.id)
        assertEquals("sk-or-work-2222", creds.effectiveSecret(AIProvider.OPENROUTER))
        // Other routes keep their own credential.
        assertEquals("sk-or-personal-1111", creds.routeSecret(AIRoute.TEXT, AIProvider.OPENROUTER))
    }

    @Test
    fun deletingTheDefaultProfileFallsBackToAnotherOne() {
        val creds = registry()
        val first = creds.add(AIProvider.OPENROUTER, null, "sk-or-first-1111").getOrThrow()
        val second = creds.add(AIProvider.OPENROUTER, "Work", "sk-or-work-2222").getOrThrow()

        creds.delete(first.id).getOrThrow()

        assertEquals(second.id, creds.defaultFor(AIProvider.OPENROUTER)?.id)
        assertEquals("sk-or-work-2222", creds.routeSecret(AIRoute.PRIMARY, AIProvider.OPENROUTER))
    }

    @Test
    fun invalidInputIsRejectedWithoutStoringAnything() {
        val creds = registry()

        assertEquals("label_required", creds.add(AIProvider.OPENAI, " ", "sk-12345678").exceptionOrNull()?.message)
        assertEquals("key_required", creds.add(AIProvider.OPENAI, "Work", "  ").exceptionOrNull()?.message)
        assertEquals(
            "key_contains_whitespace",
            creds.add(AIProvider.OPENAI, "Work", "sk 1234 5678").exceptionOrNull()?.message
        )
        assertTrue(creds.all().isEmpty())
    }

    @Test
    fun corruptProfileMetadataRebuildsFromTheSecureLegacyKey() {
        val store = FakeSecrets(mutableMapOf("apikey_OPENAI" to "sk-openai-legacy-1234"))
        val creds = registry(store)
        assertEquals(1, creds.all().size)

        store.save("aiCredentialProfiles_v1", "{not json")

        val rebuilt = creds.all()
        assertEquals(1, rebuilt.size)
        assertEquals("sk-openai-legacy-1234", creds.secret(rebuilt.single().id))
        // The damaged file is kept so a manual recovery is still possible.
        assertEquals("{not json", store.load("aiCredentialProfiles_v1_corrupt_backup"))
    }

    @Test
    fun deleteAllRemovesProfilesRoutesAndLegacyMirrors() {
        val creds = registry()
        creds.add(AIProvider.OPENROUTER, "Personal", "sk-or-personal-1111").getOrThrow()
        creds.bindRoute(AIRoute.PRIMARY, creds.all().single().id)

        creds.deleteAll()

        assertTrue(creds.all().isEmpty())
        assertNull(creds.routeCredential(AIRoute.PRIMARY, AIProvider.OPENROUTER))
    }
}
