package com.apoorvdarshan.calorietracker.services.ondevice

import kotlin.math.ceil

enum class LocalModelId {
    GEMMA_4_E2B,
    GEMMA_4_E4B,
    WHISPER_BASE,
    PARAKEET_V3,
    QWEN3_ASR
}

data class LocalModelFile(val path: String, val url: String, val bytes: Long, val sha256: String)

data class LocalModelDescriptor(
    val id: LocalModelId,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val minimumMemoryClassGb: Int?,
    val supportedAbis: Set<String>,
    val companionFiles: List<LocalModelFile> = emptyList()
) {
    val totalBytes: Long get() = expectedBytes + companionFiles.sumOf { it.bytes }

    // Preserve the original install paths for existing single-file downloads.
    fun parts(): List<LocalModelDescriptor> {
        if (companionFiles.isEmpty()) return listOf(this)
        val directory = id.name.lowercase()
        return listOf(copy(fileName = "$directory/$fileName", companionFiles = emptyList())) +
            companionFiles.map { file ->
                copy(
                    fileName = "$directory/${file.path}",
                    downloadUrl = file.url,
                    expectedBytes = file.bytes,
                    sha256 = file.sha256,
                    companionFiles = emptyList()
                )
            }
    }
}

enum class LocalModelIneligibility {
    LOW_RAM_DEVICE,
    UNSUPPORTED_ABI,
    INSUFFICIENT_MEMORY
}

object LocalModelCatalog {
    val gemma4E2b = LocalModelDescriptor(
        id = LocalModelId.GEMMA_4_E2B,
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm",
        expectedBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        licenseName = "Apache-2.0",
        licenseUrl = "https://ai.google.dev/gemma/apache_2",
        sourceUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94",
        minimumMemoryClassGb = 8,
        supportedAbis = setOf("arm64-v8a", "x86_64")
    )

    val whisperBase = LocalModelDescriptor(
        id = LocalModelId.WHISPER_BASE,
        displayName = "Whisper Base",
        fileName = "ggml-base.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
        expectedBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        licenseName = "MIT",
        licenseUrl = "https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE",
        sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1",
        minimumMemoryClassGb = null,
        supportedAbis = setOf("arm64-v8a")
    )

    val gemma4E4b = LocalModelDescriptor(
        id = LocalModelId.GEMMA_4_E4B,
        displayName = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0/gemma-4-E4B-it.litertlm",
        expectedBytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        licenseName = "Apache-2.0",
        licenseUrl = "https://ai.google.dev/gemma/apache_2",
        sourceUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/f254b5ba6e36f7d89cc7892e268e9877bc20f0a0",
        minimumMemoryClassGb = 12,
        supportedAbis = setOf("arm64-v8a", "x86_64")
    )

    val parakeetV3 = speechModel(
        LocalModelId.PARAKEET_V3, "Parakeet v3",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        "ed5d745623472f97cfc2e6c2916ee248798fc5a8",
        "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/",
        6
    ) { base ->
        listOf(
            file(base, "encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            file(base, "decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            file(base, "joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
            file(base, "tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
        )
    }

    val qwen3Asr = speechModel(
        LocalModelId.QWEN3_ASR, "Qwen3-ASR 0.6B",
        "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a",
        "Apache-2.0", "https://github.com/QwenLM/Qwen3-ASR/blob/main/LICENSE",
        8
    ) { base ->
        listOf(
            file(base, "encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
            file(base, "conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
            file(base, "decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            file(base, "tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            file(base, "tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
            file(base, "tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910")
        )
    }

    val all: List<LocalModelDescriptor> = listOf(gemma4E2b, gemma4E4b, whisperBase, parakeetV3, qwen3Asr)

    fun descriptor(id: LocalModelId): LocalModelDescriptor = all.first { it.id == id }

    private fun file(base: String, path: String, bytes: Long, sha256: String) =
        LocalModelFile(path, "$base/$path", bytes, sha256)

    private fun speechModel(
        id: LocalModelId,
        name: String,
        repository: String,
        revision: String,
        license: String,
        licenseUrl: String,
        ramGb: Int,
        artifacts: (String) -> List<LocalModelFile>
    ): LocalModelDescriptor {
        val root = "https://huggingface.co/$repository"
        val files = artifacts("$root/resolve/$revision")
        val first = files.first()
        return LocalModelDescriptor(
            id, name, first.path, first.url, first.bytes, first.sha256, license, licenseUrl,
            "$root/tree/$revision", ramGb, setOf("arm64-v8a", "x86_64"), files.drop(1)
        )
    }
}

/**
 * Android reserves some physical RAM before reporting [android.app.ActivityManager.MemoryInfo.totalMem].
 * Rounding the reported GiB upward maps common 5.5 GiB and 7.4 GiB readings back to their marketed
 * 6 GB and 8 GB device classes without relying on the much smaller per-app heap limit.
 */
object LocalModelEligibility {
    private const val GIB = 1_073_741_824.0

    fun memoryClassGb(totalMemoryBytes: Long): Int =
        ceil(totalMemoryBytes.coerceAtLeast(0L) / GIB).toInt()

    fun isEligible(
        descriptor: LocalModelDescriptor,
        totalMemoryBytes: Long,
        supportedAbis: Collection<String>,
        isLowRamDevice: Boolean = false
    ): Boolean = ineligibility(
        descriptor,
        totalMemoryBytes,
        supportedAbis,
        isLowRamDevice
    ) == null

    fun ineligibility(
        descriptor: LocalModelDescriptor,
        totalMemoryBytes: Long,
        supportedAbis: Collection<String>,
        isLowRamDevice: Boolean = false
    ): LocalModelIneligibility? = when {
        descriptor.minimumMemoryClassGb?.let { memoryClassGb(totalMemoryBytes) < it } == true ->
            LocalModelIneligibility.INSUFFICIENT_MEMORY
        isLowRamDevice && descriptor.minimumMemoryClassGb != null ->
            LocalModelIneligibility.LOW_RAM_DEVICE
        descriptor.supportedAbis.none(supportedAbis::contains) ->
            LocalModelIneligibility.UNSUPPORTED_ABI
        else -> null
    }
}
