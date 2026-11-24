package com.apoorvdarshan.calorietracker.services.ondevice

import com.apoorvdarshan.calorietracker.data.executableAIModelOrDefault
import com.apoorvdarshan.calorietracker.data.executableAIProviderOrDefault
import com.apoorvdarshan.calorietracker.data.executableSpeechProviderOrDefault
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.ui.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelEligibilityTest {
    @Test
    fun offlineVoiceHasTwoPinnedArtifactsAndIsNotADictationProvider() {
        val model = LocalModelCatalog.lfm3Audio
        val gib = 1_073_741_824L
        assertEquals(1_403_437_856L, model.totalBytes)
        assertEquals(listOf(
            "lfm3_audio/LFM3-Audio-2B-Q4_K_M.gguf",
            "lfm3_audio/mmproj-LFM3-Audio-2B-Q8_0.gguf"
        ), model.parts().map { it.fileName })
        assertFalse(LocalModelEligibility.isEligible(model, 6 * gib, setOf("arm64-v8a")))
        assertTrue(LocalModelEligibility.isEligible(model, 8 * gib, setOf("arm64-v8a")))
        assertFalse(LocalModelEligibility.isEligible(model, 12 * gib, setOf("x86_64")))
        assertFalse(SpeechProvider.entries.any { it.name.contains("LFM3") })
    }

    @Test
    fun newModelsAreGatedIndependently() {
        val abi = setOf("arm64-v8a")
        val gib = 1_073_741_824L
        val e4b = LocalModelCatalog.gemma4E4b
        val parakeet = LocalModelCatalog.parakeetV3
        val qwen = LocalModelCatalog.qwen3Asr
        assertFalse(LocalModelEligibility.isEligible(e4b, 8 * gib, abi))
        assertTrue(LocalModelEligibility.isEligible(e4b, 12 * gib, abi))
        assertFalse(LocalModelEligibility.isEligible(parakeet, 4 * gib, abi))
        assertTrue(LocalModelEligibility.isEligible(parakeet, 6 * gib, abi))
        assertFalse(LocalModelEligibility.isEligible(qwen, 6 * gib, abi))
        assertTrue(LocalModelEligibility.isEligible(qwen, 8 * gib, abi))
        assertFalse(LocalModelEligibility.isEligible(qwen, 12 * gib, setOf("armeabi-v7a")))
        assertEquals(AIProvider.GEMINI, executableAIProviderOrDefault(AIProvider.LOCAL_GEMMA_E4B, {
            it == LocalModelId.GEMMA_4_E2B
        }))
        assertEquals(SpeechProvider.LOCAL_QWEN3_ASR, executableSpeechProviderOrDefault(
            SpeechProvider.LOCAL_QWEN3_ASR, { it == LocalModelId.QWEN3_ASR }, SpeechProvider.NATIVE
        ))
        assertEquals(SpeechProvider.NATIVE, executableSpeechProviderOrDefault(
            SpeechProvider.LOCAL_PARAKEET, { it == LocalModelId.QWEN3_ASR }, SpeechProvider.NATIVE
        ))
    }

    @Test
    fun multiFileSpeechArtifactsHaveDistinctPathsAndExactTotals() {
        val parakeet = LocalModelCatalog.parakeetV3
        val qwen = LocalModelCatalog.qwen3Asr
        assertEquals(670_576_784L, parakeet.totalBytes)
        assertEquals(987_015_347L, qwen.totalBytes)
        val parts = parakeet.parts() + qwen.parts()
        assertEquals(10, parts.map { it.fileName }.toSet().size)
        assertTrue(parts.all { it.sha256.matches(Regex("[a-f0-9]{64}")) && it.expectedBytes > 0 })
        assertTrue(qwen.parts().any { it.fileName == "qwen3_asr/tokenizer/vocab.json" })
    }

    @Test
    fun longSpeechIsChunkedWithoutDroppingSamplesAndPrefersAPause() {
        val samples = FloatArray(45 * 16_000) { 0.5f }
        samples.fill(0f, 18 * 16_000, 18 * 16_000 + 640)
        val chunks = speechChunks(samples, 16_000)
        assertEquals(0, chunks.first().first)
        assertTrue(chunks.first().last in 18 * 16_000..18 * 16_000 + 640)
        assertEquals(samples.lastIndex, chunks.last().last)
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.last + 1 == b.first })
        assertTrue(chunks.all { it.last - it.first + 1 <= 320_000 })
    }
    @Test
    fun exactPublishedArtifactsArePinned() {
        assertEquals("gemma-4-E2B-it.litertlm", LocalModelCatalog.gemma4E2b.fileName)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm",
            LocalModelCatalog.gemma4E2b.downloadUrl
        )
        assertEquals(2_588_147_712L, LocalModelCatalog.gemma4E2b.expectedBytes)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            LocalModelCatalog.gemma4E2b.sha256
        )
        assertEquals("Apache-2.0", LocalModelCatalog.gemma4E2b.licenseName)
        assertEquals(
            "https://ai.google.dev/gemma/apache_2",
            LocalModelCatalog.gemma4E2b.licenseUrl
        )
        assertTrue(
            LocalModelCatalog.gemma4E2b.sourceUrl.contains(
                "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94"
            )
        )
        assertEquals("ggml-base.bin", LocalModelCatalog.whisperBase.fileName)
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
            LocalModelCatalog.whisperBase.downloadUrl
        )
        assertEquals(147_951_465L, LocalModelCatalog.whisperBase.expectedBytes)
        assertEquals(
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            LocalModelCatalog.whisperBase.sha256
        )
    }

    @Test
    fun gemmaRequiresAnEightGbClassSupportedDevice() {
        val gib = 1_073_741_824L

        assertFalse(
            LocalModelEligibility.isEligible(LocalModelCatalog.gemma4E2b, 6L * gib, listOf("arm64-v8a"))
        )
        assertTrue(
            LocalModelEligibility.isEligible(
                LocalModelCatalog.gemma4E2b,
                7L * gib + 1L,
                listOf("arm64-v8a")
            )
        )
        assertFalse(
            LocalModelEligibility.isEligible(LocalModelCatalog.gemma4E2b, 8L * gib, listOf("armeabi-v7a"))
        )
        assertEquals(
            LocalModelIneligibility.INSUFFICIENT_MEMORY,
            LocalModelEligibility.ineligibility(
                LocalModelCatalog.gemma4E2b,
                6L * gib,
                listOf("arm64-v8a"),
                isLowRamDevice = true
            )
        )
        assertEquals(
            LocalModelIneligibility.UNSUPPORTED_ABI,
            LocalModelEligibility.ineligibility(
                LocalModelCatalog.gemma4E2b,
                8L * gib,
                listOf("armeabi-v7a")
            )
        )
    }

    @Test
    fun whisperBaseIsOfferedOnArm64AcrossMemoryTiers() {
        val gib = 1_073_741_824L

        assertTrue(
            LocalModelEligibility.isEligible(LocalModelCatalog.whisperBase, 4L * gib, listOf("arm64-v8a"))
        )
        assertTrue(
            LocalModelEligibility.isEligible(LocalModelCatalog.whisperBase, 8L * gib, listOf("arm64-v8a"))
        )
        assertFalse(
            LocalModelEligibility.isEligible(LocalModelCatalog.whisperBase, 8L * gib, listOf("x86_64"))
        )
        assertTrue(
            LocalModelEligibility.isEligible(
                LocalModelCatalog.whisperBase,
                8L * gib,
                listOf("arm64-v8a"),
                isLowRamDevice = true
            )
        )
        assertEquals(
            null,
            LocalModelEligibility.ineligibility(
                LocalModelCatalog.whisperBase,
                8L * gib,
                listOf("arm64-v8a"),
                isLowRamDevice = true
            )
        )
        assertEquals(
            LocalModelIneligibility.LOW_RAM_DEVICE,
            LocalModelEligibility.ineligibility(
                LocalModelCatalog.gemma4E2b,
                8L * gib,
                listOf("arm64-v8a"),
                isLowRamDevice = true
            )
        )
    }

    @Test
    fun stalePersistedLocalRoutesResolveToSafeDefaultsBeforeReconciliationFinishes() {
        assertEquals(
            AIProvider.GEMINI,
            executableAIProviderOrDefault(AIProvider.LOCAL_GEMMA, isExecutable = { false })
        )
        assertEquals(
            AIProvider.LOCAL_GEMMA,
            executableAIProviderOrDefault(AIProvider.LOCAL_GEMMA, isExecutable = { it == LocalModelId.GEMMA_4_E2B })
        )
        assertEquals(
            AIProvider.GEMINI.defaultModel,
            executableAIModelOrDefault(
                provider = AIProvider.LOCAL_GEMMA,
                model = AIProvider.LOCAL_GEMMA.defaultModel,
                isExecutable = { false },
                defaultModel = AIProvider.GEMINI.defaultModel
            )
        )
        assertEquals(
            AIProvider.LOCAL_GEMMA.defaultModel,
            executableAIModelOrDefault(
                provider = AIProvider.LOCAL_GEMMA,
                model = AIProvider.LOCAL_GEMMA.defaultModel,
                isExecutable = { it == LocalModelId.GEMMA_4_E2B },
                defaultModel = AIProvider.GEMINI.defaultModel
            )
        )
        assertEquals(
            SpeechProvider.NATIVE,
            executableSpeechProviderOrDefault(
                SpeechProvider.LOCAL_WHISPER,
                isExecutable = { false },
                default = SpeechProvider.NATIVE
            )
        )
        assertEquals(
            SpeechProvider.LOCAL_WHISPER,
            executableSpeechProviderOrDefault(
                SpeechProvider.LOCAL_WHISPER,
                isExecutable = { it == LocalModelId.WHISPER_BASE },
                default = SpeechProvider.NATIVE
            )
        )
    }

    @Test
    fun localProvidersAreHiddenUntilTheVerifiedModelIsExecutable() {
        val missing = SettingsUiState(
            localModelStates = mapOf(
                LocalModelId.GEMMA_4_E2B to LocalModelState(
                    LocalModelCatalog.gemma4E2b,
                    eligible = true,
                    status = LocalModelInstallStatus.NotInstalled
                ),
                LocalModelId.WHISPER_BASE to LocalModelState(
                    LocalModelCatalog.whisperBase,
                    eligible = true,
                    status = LocalModelInstallStatus.NotInstalled
                )
            )
        )
        assertFalse(AIProvider.LOCAL_GEMMA in missing.availableVisionProviders)
        assertFalse(AIProvider.LOCAL_GEMMA in missing.availableTextProviders)
        assertFalse(SpeechProvider.LOCAL_WHISPER in missing.availableSpeechProviders)
        assertFalse(SpeechProvider.LOCAL_WHISPER in missing.availableSpeechFallbackProviders)

        val installed = missing.copy(
            localModelStates = missing.localModelStates.mapValues { (_, state) ->
                state.copy(status = LocalModelInstallStatus.Installed)
            }
        )
        assertTrue(AIProvider.LOCAL_GEMMA in installed.availableVisionProviders)
        assertTrue(AIProvider.LOCAL_GEMMA in installed.availableTextProviders)
        assertTrue(SpeechProvider.LOCAL_WHISPER in installed.availableSpeechProviders)
        assertTrue(SpeechProvider.LOCAL_WHISPER in installed.availableSpeechFallbackProviders)

        val ineligible = installed.copy(
            localModelStates = installed.localModelStates.mapValues { (id, state) ->
                state.copy(
                    eligible = false,
                    ineligibility = if (id == LocalModelId.GEMMA_4_E2B) {
                        LocalModelIneligibility.INSUFFICIENT_MEMORY
                    } else {
                        LocalModelIneligibility.UNSUPPORTED_ABI
                    }
                )
            }
        )
        assertFalse(AIProvider.LOCAL_GEMMA in ineligible.availableVisionProviders)
        assertFalse(AIProvider.LOCAL_GEMMA in ineligible.availableTextProviders)
        assertFalse(SpeechProvider.LOCAL_WHISPER in ineligible.availableSpeechProviders)
        assertFalse(SpeechProvider.LOCAL_WHISPER in ineligible.availableSpeechFallbackProviders)
    }
}
