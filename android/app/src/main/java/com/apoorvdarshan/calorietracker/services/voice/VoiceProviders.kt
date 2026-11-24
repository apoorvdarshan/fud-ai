package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.AIProvider

/**
 * How camera frames reach the model while the assistant's camera is open
 * (shared/voice-assistant/README.md, "Camera frames the model can see").
 */
enum class CameraFrames(val wireValue: String) {
    /** One JPEG a second while the camera is open (Gemini and hosted). */
    CONTINUOUS("continuous"),
    /** The latest frame with each user turn and before capture decisions (OpenAI). */
    PER_TURN("per_turn"),
    /** No images; the model asks the user to say when the food is in frame (xAI). */
    NONE("none")
}

/** A live speech-to-speech backend. Hosted is the Fud AI relay to Gemini Live. */
enum class LiveProvider(val storageValue: String) {
    HOSTED("hosted"),
    GEMINI("gemini"),
    OPENAI("openai"),
    XAI("xai"),
    /** Alibaba's Qwen Omni Realtime (Model Studio, international region). */
    QWEN("qwen"),
    /** OpenAI's GPT-Live, which hands tool use to a backend model. Uses the OpenAI key. */
    GPT_LIVE("gpt_live");

    /** Built-in models, default first. Hosted sessions always use the Worker's model. */
    val models: List<String>
        get() = when (this) {
            HOSTED -> listOf(HOSTED_MODEL)
            GEMINI -> listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview")
            OPENAI -> listOf("gpt-realtime-2.1-mini", "gpt-realtime-2.1")
            XAI -> listOf("grok-voice-latest", "grok-voice-3.0")
            QWEN -> listOf("qwen3.8-omni-flash-realtime")
            GPT_LIVE -> listOf("gpt-live-1")
        }

    val defaultModel: String get() = models.first()

    /** Voice ids exactly as the provider expects them, default first. */
    val voices: List<String>
        get() = when (this) {
            HOSTED, GEMINI -> listOf(
                "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda",
                "Orus", "Zephyr", "Sulafat", "Achird", "Despina", "Gacrux"
            )
            OPENAI -> listOf("marin", "cedar", "coral", "sage", "shimmer", "ash", "ballad", "alloy", "echo", "verse")
            XAI -> listOf("ara", "eve", "rex", "sal", "leo")
            QWEN -> listOf("Tina", "Cindy", "Raymond", "Katerina", "Ryan", "Mia", "Jennifer", "Aiden", "Sohee", "Lenn")
            GPT_LIVE -> listOf(
                "quartz", "ripple", "vesper", "willow", "stone", "gleam",
                "meridian", "bossa", "tempo", "beacon", "delta", "cinder"
            )
        }

    val defaultVoice: String get() = voices.first()

    /**
     * Whether the provider says when a spoken reply ends. GPT-Live doesn't, so its turns
     * end when the user speaks and its voice preview ends after a quiet moment.
     */
    val reportsTurnEnds: Boolean get() = this != GPT_LIVE

    val cameraFrames: CameraFrames
        get() = when (this) {
            HOSTED, GEMINI, QWEN -> CameraFrames.CONTINUOUS
            OPENAI -> CameraFrames.PER_TURN
            XAI, GPT_LIVE -> CameraFrames.NONE
        }

    /** 16-bit mono PCM rates: Gemini, Qwen and hosted take 16 kHz and answer at 24 kHz. */
    val inputSampleRate: Int get() = if (this == HOSTED || this == GEMINI || this == QWEN) 16_000 else 24_000
    val outputSampleRate: Int get() = 24_000

    /** The AI-provider key this backend reuses; hosted needs none. */
    val aiProvider: AIProvider?
        get() = when (this) {
            HOSTED -> null
            GEMINI -> AIProvider.GEMINI
            OPENAI -> AIProvider.OPENAI
            XAI -> AIProvider.XAI
            QWEN -> AIProvider.QWEN
            GPT_LIVE -> AIProvider.OPENAI
        }

    val usesOwnKey: Boolean get() = this != HOSTED

    fun supportedModelOrDefault(model: String?): String =
        model?.trim()?.takeIf { it in models } ?: defaultModel

    fun supportedVoiceOrDefault(voice: String?): String =
        voices.firstOrNull { it.equals(voice?.trim(), ignoreCase = true) } ?: defaultVoice

    companion object {
        /** Sent in the hosted setup for completeness; the Worker replaces it. */
        const val HOSTED_MODEL = "gemini-3.8-live"

        /** OpenAI comes before GPT-Live, so Automatic keeps an OpenAI key on the realtime models. */
        val ownKeyProviders: List<LiveProvider> = listOf(GEMINI, OPENAI, XAI, QWEN, GPT_LIVE)

        fun fromStorage(raw: String?): LiveProvider? = entries.firstOrNull { it.storageValue == raw }

        fun forAiProvider(provider: AIProvider): LiveProvider? =
            ownKeyProviders.firstOrNull { it.aiProvider == provider }

        /** Voice ids are lowercase for OpenAI/xAI; show them capitalized. */
        fun voiceDisplayName(voice: String): String = voice.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Settings → Voice Assistant → Provider. */
enum class VoiceProviderChoice(val storageValue: String, val provider: LiveProvider?) {
    AUTOMATIC("automatic", null),
    HOSTED("hosted", LiveProvider.HOSTED),
    GEMINI("gemini", LiveProvider.GEMINI),
    OPENAI("openai", LiveProvider.OPENAI),
    XAI("xai", LiveProvider.XAI),
    QWEN("qwen", LiveProvider.QWEN),
    GPT_LIVE("gpt_live", LiveProvider.GPT_LIVE);

    companion object {
        fun fromStorage(raw: String?): VoiceProviderChoice = entries.firstOrNull { it.storageValue == raw } ?: AUTOMATIC
    }
}

/** Whether a session can start, and with what. */
sealed interface VoiceAccess {
    data class Ready(val provider: LiveProvider) : VoiceAccess
    /** An own-key provider was chosen explicitly but its key is not saved. */
    data class MissingKey(val provider: LiveProvider) : VoiceAccess
    /** Fud AI hosting was chosen explicitly without a Plus/Pro plan. */
    data object NeedsSubscription : VoiceAccess
    /** Automatic found neither a usable key nor a plan: explain both options. */
    data object NeedsSetup : VoiceAccess
}

/**
 * The contract's provider rule. Automatic picks the selected own-key provider when its
 * key is saved, otherwise Fud AI hosting when subscribed. A key saved for another
 * provider is not selected implicitly: the user can choose it in Voice Assistant settings.
 */
object VoiceProviderResolver {
    fun resolve(
        choice: VoiceProviderChoice,
        selectedAiProvider: AIProvider,
        hasKey: (LiveProvider) -> Boolean,
        hostedEntitled: Boolean
    ): VoiceAccess = when (choice) {
        VoiceProviderChoice.HOSTED ->
            if (hostedEntitled) VoiceAccess.Ready(LiveProvider.HOSTED) else VoiceAccess.NeedsSubscription
        VoiceProviderChoice.GEMINI, VoiceProviderChoice.OPENAI, VoiceProviderChoice.XAI, VoiceProviderChoice.QWEN,
        VoiceProviderChoice.GPT_LIVE -> {
            val provider = choice.provider!!
            if (hasKey(provider)) VoiceAccess.Ready(provider) else VoiceAccess.MissingKey(provider)
        }
        VoiceProviderChoice.AUTOMATIC -> {
            val selected = LiveProvider.forAiProvider(selectedAiProvider)
            when {
                selected != null && hasKey(selected) -> VoiceAccess.Ready(selected)
                hostedEntitled -> VoiceAccess.Ready(LiveProvider.HOSTED)
                else -> VoiceAccess.NeedsSetup
            }
        }
    }

    /** The chosen own key is enough to start; only a possible hosted route needs billing. */
    internal suspend fun resolveStartup(
        choice: VoiceProviderChoice,
        selectedAiProvider: suspend () -> AIProvider,
        loadKey: suspend (LiveProvider) -> String?,
        hostedEntitled: suspend () -> Boolean
    ): StartupAccess {
        val automaticProvider = if (choice == VoiceProviderChoice.AUTOMATIC) selectedAiProvider() else null
        val candidate = automaticProvider?.let { LiveProvider.forAiProvider(it) } ?: choice.provider
        val key = candidate?.takeIf(LiveProvider::usesOwnKey)?.let { loadKey(it)?.trim()?.takeIf(String::isNotEmpty) }
        val checkHosted = choice == VoiceProviderChoice.HOSTED ||
            (choice == VoiceProviderChoice.AUTOMATIC && key == null)
        val access = resolve(
            choice,
            automaticProvider ?: AIProvider.GEMINI,
            { it == candidate && key != null },
            checkHosted && hostedEntitled()
        )
        return StartupAccess(access, key)
    }

    internal class StartupAccess(val access: VoiceAccess, val key: String?)
}
