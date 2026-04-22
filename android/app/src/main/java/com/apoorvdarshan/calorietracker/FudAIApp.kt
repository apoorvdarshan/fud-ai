package com.apoorvdarshan.calorietracker

import android.app.Application
import com.apoorvdarshan.calorietracker.data.ChatRepository
import com.apoorvdarshan.calorietracker.data.FoodRepository
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.data.ProfileRepository
import com.apoorvdarshan.calorietracker.data.WeightRepository
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.NotificationService
import com.apoorvdarshan.calorietracker.services.ai.ChatService
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import com.apoorvdarshan.calorietracker.services.speech.SpeechService

/**
 * Application-scoped singleton wiring. Manual DI (no Hilt) — repositories and
 * services are instantiated once and handed to ViewModels via [container].
 */
class FudAIApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
    }
}

class AppContainer(app: FudAIApp) {
    val prefs = PreferencesStore(app)
    val keyStore = KeyStore(app)
    val imageStore = FoodImageStore(app)
    val notifications = NotificationService(app)
    val health = HealthConnectManager(app)

    val profileRepository = ProfileRepository(prefs)
    val foodRepository = FoodRepository(prefs)
    val weightRepository = WeightRepository(prefs, profileRepository)
    val chatRepository = ChatRepository(prefs)

    val foodAnalysis = FoodAnalysisService(prefs, keyStore)
    val chatService = ChatService(prefs, keyStore)
    val speechService = SpeechService(prefs, keyStore)
}
