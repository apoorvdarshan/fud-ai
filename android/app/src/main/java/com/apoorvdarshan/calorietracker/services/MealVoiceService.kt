package com.apoorvdarshan.calorietracker.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.speech.AudioRecorder
import com.apoorvdarshan.calorietracker.services.speech.NativeSpeechRecognizer
import com.apoorvdarshan.calorietracker.services.speech.SttEvent
import com.apoorvdarshan.calorietracker.ui.home.toFoodEntry
import com.apoorvdarshan.calorietracker.widget.MealVoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backs the voice-meal widget: records while the widget is "on", then on the second tap runs the
 * same pipeline as Describe meal > Voice (STT -> AI text analysis -> log) without opening the app,
 * and posts a "Meal saved" notification.
 */
class MealVoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null
    private var timeoutJob: Job? = null
    private var recorder: AudioRecorder? = null
    private var provider = SpeechProvider.NATIVE

    // Native recognition accumulates final segments in `committed`; `partial` is the in-flight one.
    private var committed = ""
    private var partial = ""
    private var fatalError: String? = null

    private sealed interface Outcome {
        data class Saved(val name: String, val calories: Int) : Outcome
        object NothingHeard : Outcome
        data class Failed(val message: String) : Outcome
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAndSave()
            else -> beginRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.cancel()
        scope.cancel()
        MealVoiceState.set(applicationContext, MealVoiceState.Phase.IDLE)
        super.onDestroy()
    }

    // ── Recording ──────────────────────────────────────────────────────────

    private fun beginRecording() {
        // startForegroundService() obliges us to call startForeground() whatever happens next.
        createChannels()
        startInForeground(progressNotification(R.string.meal_voice_notif_recording, R.string.meal_voice_notif_recording_text, withStop = true))

        val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (MealVoiceState.phase != MealVoiceState.Phase.IDLE) return
        if (!micGranted) {
            scope.launch {
                notifyResult(Outcome.Failed(getString(R.string.meal_voice_mic_denied)))
                finish()
            }
            return
        }
        MealVoiceState.set(this, MealVoiceState.Phase.RECORDING)
        committed = ""
        partial = ""
        fatalError = null

        val container = (application as FudAIApp).container
        listenJob = scope.launch {
            provider = container.prefs.selectedSpeechProvider.first()
            val language = container.prefs.selectedSpeechLanguage(provider).first()
            if (provider == SpeechProvider.NATIVE) {
                listenNative(language.nativeLocaleTag())
            } else {
                val rec = AudioRecorder(this@MealVoiceService)
                recorder = rec
                if (rec.start() == null) {
                    fatalError = getString(R.string.voice_mic_start_failed)
                    stopAndSave()
                }
            }
        }
        // Safety net so a forgotten recording can't run (and bill an STT API) forever.
        timeoutJob = scope.launch {
            delay(MAX_RECORDING_MS)
            stopAndSave()
        }
    }

    /** Continuous native recognition: re-arms after every final/soft-error session, like the in-app sheet. */
    private suspend fun listenNative(localeTag: String?) {
        val native = NativeSpeechRecognizer(this)
        // 0 = offline in the chosen language, 1 = online, 2 = online with auto-detected language.
        var mode = 0
        while (currentCoroutineContext().isActive && MealVoiceState.phase == MealVoiceState.Phase.RECORDING) {
            native.listen(
                locale = if (mode == 2) null else localeTag,
                preferOffline = mode == 0
            ).collect { event ->
                when (event) {
                    is SttEvent.Partial -> partial = event.text
                    is SttEvent.Final -> {
                        committed = join(committed, event.text)
                        partial = ""
                    }
                    is SttEvent.Error -> {
                        if (NativeSpeechRecognizer.isLanguageSupportError(event.code) && mode < 2) {
                            mode++
                        } else if (!NativeSpeechRecognizer.isRecoverableSessionError(event.code)) {
                            fatalError = event.message
                        }
                        if (partial.isNotBlank()) {
                            committed = join(committed, partial)
                            partial = ""
                        }
                    }
                    else -> Unit
                }
            }
            if (fatalError != null) {
                stopAndSave()
                return
            }
            delay(300)
        }
    }

    // ── Stop, transcribe, analyse, log ─────────────────────────────────────

    private fun stopAndSave() {
        if (MealVoiceState.phase != MealVoiceState.Phase.RECORDING) return
        MealVoiceState.set(this, MealVoiceState.Phase.PROCESSING)
        timeoutJob?.cancel()
        listenJob?.cancel()
        val spoken = join(committed, partial)
        val pendingError = fatalError
        val file = if (provider == SpeechProvider.NATIVE) null else recorder?.stop()
        recorder = null
        startInForeground(progressNotification(R.string.meal_voice_notif_processing, null, withStop = false))

        scope.launch {
            val outcome = try {
                logMeal(spoken, file, pendingError)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Voice meal failed", e)
                Outcome.Failed(
                    when (e) {
                        is AiError -> e.userMessage(this@MealVoiceService)
                        else -> e.localizedMessage ?: getString(R.string.meal_voice_failed_generic)
                    }
                )
            }
            if (outcome is Outcome.Saved) {
                // Independent of notification permission: the widget button shows "+N" for a few seconds.
                val unit = (application as FudAIApp).container.prefs.energyUnit.first()
                MealVoiceState.showAdded(this@MealVoiceService, unit.displayValue(outcome.calories))
            }
            notifyResult(outcome)
            finish()
        }
    }

    private suspend fun logMeal(nativeText: String, audio: java.io.File?, pendingError: String?): Outcome {
        val container = (application as FudAIApp).container
        val text = if (provider == SpeechProvider.NATIVE) {
            nativeText
        } else {
            audio?.let { container.speechService.transcribeRecordedAudio(it) }.orEmpty()
        }
        if (text.isBlank()) {
            return pendingError?.let { Outcome.Failed(it) } ?: Outcome.NothingHeard
        }
        // Same analysis and storage as Describe meal > Voice; only the "tap Save" step is skipped.
        val analysis = container.foodAnalysis.analyzeText(text)
        val entry = analysis.toFoodEntry(source = FoodSource.TEXT_INPUT, mealType = MealType.currentMeal)
        return if (container.foodRepository.addEntry(entry)) {
            Outcome.Saved(entry.name, entry.calories)
        } else {
            Outcome.Failed(getString(R.string.meal_voice_failed_generic))
        }
    }

    private fun finish() {
        MealVoiceState.set(this, MealVoiceState.Phase.IDLE)
        scope.launch {
            ServiceCompat.stopForeground(this@MealVoiceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.meal_voice_channel_progress), NotificationManager.IMPORTANCE_LOW)
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.meal_voice_channel_result), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_PROGRESS, notification, type)
    }

    private fun progressNotification(titleRes: Int, textRes: Int?, withStop: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(titleRes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        textRes?.let { builder.setContentText(getString(it)) }
        if (withStop) {
            val stop = PendingIntent.getService(
                this, 0,
                Intent(this, MealVoiceService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.meal_voice_action_stop), stop)
        }
        return builder.build()
    }

    private suspend fun notifyResult(outcome: Outcome) {
        val container = (application as FudAIApp).container
        if (!container.notifications.canPostNotifications()) return
        val (title, text) = when (outcome) {
            is Outcome.Saved -> {
                val unit = container.prefs.energyUnit.first()
                getString(R.string.meal_voice_saved_title) to
                    getString(R.string.meal_voice_saved_text, outcome.name, unit.format(resources, outcome.calories))
            }
            Outcome.NothingHeard -> getString(R.string.meal_voice_failed_title) to getString(R.string.meal_voice_nothing_heard)
            is Outcome.Failed -> getString(R.string.meal_voice_failed_title) to outcome.message
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_RESULT, notification)
    }

    private fun join(a: String, b: String) = (a + " " + b).trim()

    companion object {
        private const val TAG = "MealVoiceService"
        private const val ACTION_START = "com.apoorvdarshan.calorietracker.action.MEAL_VOICE_START"
        private const val ACTION_STOP = "com.apoorvdarshan.calorietracker.action.MEAL_VOICE_STOP"
        private const val CHANNEL_PROGRESS = "meal_voice_progress"
        private const val CHANNEL_RESULT = "meal_voice_result"
        private const val NOTIFICATION_PROGRESS = 7301
        private const val NOTIFICATION_RESULT = 7302
        private const val MAX_RECORDING_MS = 120_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MealVoiceService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MealVoiceService::class.java).setAction(ACTION_STOP))
        }
    }
}
