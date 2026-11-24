package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

@Composable
internal fun AIProviderBrandIcon(provider: AIProvider, modifier: Modifier = Modifier) {
    ProviderBrandIcon(
        drawableRes = provider.logoDrawableRes,
        fallback = Icons.Outlined.Settings,
        modifier = modifier
    )
}

@Composable
internal fun SpeechProviderBrandIcon(provider: SpeechProvider, modifier: Modifier = Modifier) {
    ProviderBrandIcon(
        drawableRes = provider.logoDrawableRes,
        fallback = Icons.Outlined.Settings,
        modifier = modifier
    )
}

@Composable
private fun ProviderBrandIcon(
    drawableRes: Int?,
    fallback: ImageVector,
    modifier: Modifier
) {
    if (drawableRes != null) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = null,
            tint = AppColors.Calorie,
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = fallback,
            contentDescription = null,
            tint = AppColors.Calorie,
            modifier = modifier
        )
    }
}

private val AIProvider.logoDrawableRes: Int?
    get() = when (this) {
        AIProvider.GEMINI -> R.drawable.provider_gemini
        AIProvider.OPENAI -> R.drawable.provider_openai
        AIProvider.ANTHROPIC -> R.drawable.provider_anthropic
        AIProvider.XAI -> R.drawable.provider_xai
        AIProvider.OPENROUTER -> R.drawable.provider_openrouter
        AIProvider.TOGETHER_AI -> R.drawable.provider_together
        AIProvider.GROQ -> R.drawable.provider_groq
        AIProvider.HUGGING_FACE -> R.drawable.provider_huggingface
        AIProvider.FIREWORKS -> R.drawable.provider_fireworks
        AIProvider.DEEP_INFRA -> R.drawable.provider_deepinfra
        AIProvider.MISTRAL -> R.drawable.provider_mistral
        AIProvider.DEEPSEEK -> R.drawable.provider_deepseek
        AIProvider.QWEN -> R.drawable.provider_qwen
        AIProvider.CEREBRAS -> R.drawable.provider_cerebras
        AIProvider.LOCAL_GEMMA, AIProvider.LOCAL_GEMMA_E4B -> null
        AIProvider.OLLAMA -> R.drawable.provider_ollama
        AIProvider.CUSTOM_OPENAI -> null
    }

private val SpeechProvider.logoDrawableRes: Int
    get() = when (this) {
        SpeechProvider.NATIVE -> R.drawable.provider_android
        SpeechProvider.LOCAL_WHISPER, SpeechProvider.LOCAL_PARAKEET, SpeechProvider.LOCAL_QWEN3_ASR ->
            R.drawable.provider_android
        SpeechProvider.GEMINI -> R.drawable.provider_gemini
        SpeechProvider.OPENAI -> R.drawable.provider_openai
        SpeechProvider.GROQ -> R.drawable.provider_groq
        SpeechProvider.MISTRAL -> R.drawable.provider_mistral
        SpeechProvider.DEEPGRAM -> R.drawable.provider_deepgram
        SpeechProvider.ASSEMBLY_AI -> R.drawable.provider_assemblyai
    }
