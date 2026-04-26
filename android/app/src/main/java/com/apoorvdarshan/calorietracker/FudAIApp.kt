package com.apoorvdarshan.calorietracker

import android.app.Application
import com.apoorvdarshan.calorietracker.data.BodyFatRepository
import com.apoorvdarshan.calorietracker.data.ChatRepository
import com.apoorvdarshan.calorietracker.data.FoodRepository
import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.data.ProfileRepository
import com.apoorvdarshan.calorietracker.data.WeightRepository
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.NotificationService
import com.apoorvdarshan.calorietracker.services.TestDataSeeder
import com.apoorvdarshan.calorietracker.services.WidgetSnapshotWriter
import com.apoorvdarshan.calorietracker.services.ai.ChatService
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.services.health.HealthConnectManager
import com.apoorvdarshan.calorietracker.services.speech.SpeechService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

/**
 * Application-scoped singleton wiring. Manual DI (no Hilt) — repositories and
 * services are instantiated once and handed to ViewModels via [container].
 */
class FudAIApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        container.widgetSnapshotWriter.observe().launchIn(appScope)
        // Re-arm the daily weight-log alarm on every cold start. AlarmManager
        // drops scheduled alarms on device reboot and (sometimes) on app
        // updates — without this, a user who enabled Notifications once would
        // silently stop receiving the reminder after the next reboot.
        appScope.launch {
            if (container.prefs.notificationsEnabled.first() &&
                container.notifications.canPostNotifications()
            ) {
                container.notifications.scheduleWeightReminder()
            }
        }
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
    val bodyFatRepository = BodyFatRepository(prefs, profileRepository)
    val chatRepository = ChatRepository(prefs)

    val foodAnalysis = FoodAnalysisService(prefs, keyStore)
    val chatService = ChatService(prefs, keyStore)
    val speechService = SpeechService(prefs, keyStore)

    val widgetSnapshotWriter = WidgetSnapshotWriter(app, prefs, foodRepository, profileRepository)
    val testDataSeeder = TestDataSeeder(this)

    /**
     * App-scoped flag set by [HomeViewModel] while a food analysis request is
     * in flight. The bottom nav reads this so the bar can hide during the
     * AnalyzingOverlay (matches iOS, where the analyzing sheet covers the
     * tab bar).
     */
    val analyzingFood: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
