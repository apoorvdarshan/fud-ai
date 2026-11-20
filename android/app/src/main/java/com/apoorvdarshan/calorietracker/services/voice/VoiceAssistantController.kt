package com.apoorvdarshan.calorietracker.services.voice

import android.net.Uri
import android.os.SystemClock
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.VoiceAssistantPreferences
import com.apoorvdarshan.calorietracker.models.AppLanguage
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.services.ai.CoachContext
import com.apoorvdarshan.calorietracker.ui.home.ContentUriBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

enum class VoicePhase { IDLE, PREPARING, CONNECTING, LIVE }

/** What the orb shows: idle, listening, thinking (waiting on the model or a tool) or speaking. */
enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

/** Why there is no conversation, explained in the overlay with a way forward. */
sealed interface VoiceNotice {
    /** Automatic found neither a plan nor a key: subscribe, or add a Gemini/OpenAI/xAI key. */
    data object NeedsSetup : VoiceNotice
    data class MissingKey(val provider: LiveProvider) : VoiceNotice
    data object NeedsSubscription : VoiceNotice
    data object OutOfMinutes : VoiceNotice
    data object SessionLimit : VoiceNotice
    data object MicrophoneDenied : VoiceNotice
    data object MicrophoneUnavailable : VoiceNotice
    data class KeyRejected(val provider: LiveProvider) : VoiceNotice
    data object HostedUnauthorized : VoiceNotice
    data object RateLimited : VoiceNotice
    data object VoiceUnavailable : VoiceNotice
    data object ConnectionFailed : VoiceNotice
    data object ConnectionLost : VoiceNotice
    /** The provider's own reason for closing (English, from the provider). */
    data class ProviderMessage(val message: String) : VoiceNotice
}

data class VoiceUiState(
    val visible: Boolean = false,
    val expanded: Boolean = true,
    val phase: VoicePhase = VoicePhase.IDLE,
    val orb: OrbMode = OrbMode.IDLE,
    val provider: LiveProvider? = null,
    val userCaption: String = "",
    val assistantCaption: String = "",
    val camera: VoiceCameraMode? = null,
    /** The shutter's photo is being taken and scanned. */
    val capturing: Boolean = false,
    val notice: VoiceNotice? = null,
    /** A non-fatal provider error, shown briefly. */
    val transientError: String? = null,
    /** Hosted: voice time left, from the relay. */
    val secondsRemaining: Int? = null,
    /** Bumped when a session should start; the overlay asks for the microphone first. */
    val startRequest: Long = 0,
    /** Bumped when pick_photos wants the photo picker. */
    val photoPickRequest: Long = 0,
    val photoPickMax: Int = 4
)

/**
 * What a relay close means. Before closing, the hosted relay may send `fudVoice.error`:
 * `voice_quota_exceeded` (then 4402), `session_limit` (4408), `rate_limited`,
 * `invalid_setup` / `invalid_message` (1008), `setup_timeout`, `upstream_error` and
 * `voice_unavailable` (1011). Unknown codes are a generic failure; a clean upstream close
 * arrives as 1000. Null when an own-key close needs the provider's own reason instead.
 */
internal fun closeNotice(code: Int, hostedNotice: String?, hosted: Boolean): VoiceNotice? = when {
    code == HostedLiveWire.CLOSE_OUT_OF_MINUTES || hostedNotice == "voice_quota_exceeded" -> VoiceNotice.OutOfMinutes
    code == HostedLiveWire.CLOSE_SESSION_LIMIT || hostedNotice == "session_limit" -> VoiceNotice.SessionLimit
    !hosted -> null
    hostedNotice == "rate_limited" -> VoiceNotice.RateLimited
    code == LiveSession.NORMAL_CLOSURE && hostedNotice == null -> VoiceNotice.ConnectionLost
    else -> VoiceNotice.VoiceUnavailable
}

/** A preview frame for the model: JPEG, at most 768 px on the long side. */
class CameraFrame(val jpeg: ByteArray, val takenAtMillis: Long)

/** The in-orb camera panel, registered while it is bound. */
interface VoiceCameraHandle {
    fun latestFrame(): CameraFrame?
    suspend fun takePhoto(): ByteArray?
}

/**
 * The live voice assistant: one conversation at a time, app-wide. The microphone streams
 * to the chosen provider, the reply plays back, tool calls run against the app, and each
 * finished turn can be saved to Coach. Modelled on Scowld's LiveConversation (same
 * author, MIT).
 *
 * A session ends after 45 s with no speech either way (not while a tool or card waits for
 * the user), after 15 minutes, when the user closes it, on `end_conversation`, or when the
 * app goes to the background.
 */
class VoiceAssistantController(private val container: AppContainer) : VoiceUiBridge {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            VoiceLog.warn(TAG, "Voice assistant task failed", error)
        }
    )

    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    private val _cards = MutableStateFlow<List<VoiceCard>>(emptyList())
    val cards: StateFlow<List<VoiceCard>> = _cards.asStateFlow()

    private val ports = AndroidVoiceAppPorts(container, this)

    private class SessionEvent(val number: Int, val event: LiveEvent)

    private val events = Channel<SessionEvent>(Channel.UNLIMITED)

    // The current session.
    private var sessionNumber = 0
    private var sessionScope: CoroutineScope? = null
    private var session: LiveSession? = null
    private var audio: LiveAudioIO? = null
    private var executor: VoiceToolExecutor? = null
    private var preferences = VoiceAssistantPreferences()
    private var startedAt = 0L
    private val lastActivity = AtomicLong(0)
    private val lastUserSound = AtomicLong(0)
    private var endingAfterSpeech = false
    private var lastHostedNotice: String? = null
    private var startCounter = 0L
    private var claimedStart = 0L
    private var begunStart = 0L
    private var pickCounter = 0L
    private var claimedPick = 0L

    // The current turn.
    private var pendingUserText = ""
    private var userUtteranceOpen = false
    private var replyText = ""
    private var replyItemId: String? = null
    private var replyItemStartMs = 0L
    private var awaitingReplySince = 0L
    private var toolsRunning = 0
    private var responseInProgress = false
    private var pendingResponseCreate = false
    private val pendingNotes = mutableListOf<String>()
    private val cancelledCalls = HashSet<String>()
    private val runningCalls = HashMap<String, Pair<String, Job>>()
    private var waitingForUser = 0
    private var errorClear: Job? = null

    // Camera and photo picker.
    private var cameraHandle: VoiceCameraHandle? = null
    private var cameraOpening: CompletableDeferred<CameraOutcome>? = null
    private var barcodeWait: CompletableDeferred<BarcodeOutcome>? = null
    private var photoWait: CompletableDeferred<PickOutcome>? = null
    private var lastFrameSentAt = 0L

    /** True while the photo picker or a permission screen covers the app; backgrounding then doesn't end the session. */
    @Volatile
    var externalActivityPending = false

    init {
        scope.launch {
            for (item in events) {
                if (item.number == sessionNumber) handle(item.event)
            }
        }
    }

    val isActive: Boolean get() = _ui.value.phase != VoicePhase.IDLE

    // MARK: - Starting and ending

    /** Opens the assistant (orb button, tile, shortcut, widget). Already running: just expands it. */
    fun requestStart() {
        container.voicePreview.stop()
        if (isActive) {
            expand()
            return
        }
        startCounter += 1
        _ui.value = VoiceUiState(
            visible = true,
            expanded = true,
            phase = VoicePhase.PREPARING,
            orb = OrbMode.THINKING,
            startRequest = startCounter,
            photoPickRequest = pickCounter
        )
    }

    /** True once per start request, for the overlay that asks for the microphone. */
    fun claimStartRequest(id: Long): Boolean {
        if (id == 0L || id == claimedStart) return false
        claimedStart = id
        return true
    }

    /** True once per pending `pick_photos` request, for the overlay that opens the picker. */
    fun claimPhotoPickRequest(id: Long): Boolean {
        if (id == 0L || id == claimedPick || photoWait == null) return false
        claimedPick = id
        return true
    }

    /** The microphone is allowed: connect. */
    fun beginSession() {
        if (_ui.value.phase != VoicePhase.PREPARING || session != null || begunStart == startCounter) return
        begunStart = startCounter
        val number = ++sessionNumber
        scope.launch {
            try {
                start(number)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                VoiceLog.warn(TAG, "The voice session could not start", error)
                if (number == sessionNumber) end(VoiceNotice.ConnectionFailed)
            }
        }
    }

    fun microphoneDenied() {
        end(VoiceNotice.MicrophoneDenied)
    }

    private suspend fun start(number: Int) {
        val prefs = container.prefs.voiceAssistantPreferences.first()
        val selectedAi = container.prefs.selectedAIProvider.first()
        val entitlement = withTimeoutOrNull(ENTITLEMENT_WAIT_MS) {
            container.billing.entitlement.first { it.loaded }
        } ?: container.billing.entitlement.value
        val keys = withContext(Dispatchers.IO) {
            LiveProvider.ownKeyProviders.associateWith { provider ->
                provider.aiProvider?.let { container.keyStore.apiKey(it) }?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        if (number != sessionNumber) return
        val access = VoiceProviderResolver.resolve(prefs.choice, selectedAi, { keys[it] != null }, entitlement.isEntitled)
        val provider = when (access) {
            is VoiceAccess.Ready -> access.provider
            is VoiceAccess.MissingKey -> return end(VoiceNotice.MissingKey(access.provider))
            VoiceAccess.NeedsSubscription -> return end(VoiceNotice.NeedsSubscription)
            VoiceAccess.NeedsSetup -> return end(VoiceNotice.NeedsSetup)
        }
        val hostedUserId = if (provider == LiveProvider.HOSTED) {
            container.billing.appUserId()?.takeIf { it.isNotBlank() } ?: return end(VoiceNotice.NeedsSubscription)
        } else {
            null
        }

        val catalog = withContext(Dispatchers.IO) { VoiceToolCatalog.bundled(container.appContext) }
        val context = VoiceSessionContext(provider, prefs.confirmSaves, prefs.autoCapture, workoutDiaryEnabled = true)
        val instructions = withContext(Dispatchers.Default) { buildInstructions(context, prefs) }
        if (number != sessionNumber) return

        val config = LiveSessionConfig(
            provider = provider,
            model = if (provider == LiveProvider.HOSTED) LiveProvider.HOSTED_MODEL else prefs.model(provider),
            voice = prefs.voice(provider),
            instructions = instructions,
            tools = VoiceToolCatalog.declared(catalog, context.workoutDiaryEnabled),
            apiKey = keys[provider],
            hostedUserId = hostedUserId
        )
        val executor = VoiceToolExecutor(ports, catalog, context).also {
            it.onEndConversation = { endAfterSpeech(number) }
        }
        val session = LiveSession(config) { event -> events.trySend(SessionEvent(number, event)) }
        val audio = LiveAudioEngine(container.appContext)
        audio.onMicrophoneAudio = { pcm ->
            session.sendAudio(pcm)
            if (LiveAudioEngine.loudness(pcm) >= SPEECH_LEVEL) {
                val now = SystemClock.elapsedRealtime()
                lastActivity.set(now)
                lastUserSound.set(now)
            }
        }
        audio.onPlaybackFinished = { playbackFinished(number) }
        audio.onInterrupted = { if (number == sessionNumber) end() }
        try {
            withContext(Dispatchers.IO) { audio.start(session.wire.inputSampleRate, session.wire.outputSampleRate) }
        } catch (error: Throwable) {
            VoiceLog.warn(TAG, "Audio failed to start", error)
            audio.stop()
            if (number == sessionNumber) end(VoiceNotice.MicrophoneUnavailable)
            return
        }
        if (number != sessionNumber) {
            audio.stop()
            return
        }

        val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        this.sessionScope = sessionScope
        this.session = session
        this.audio = audio
        this.executor = executor
        this.preferences = prefs
        startedAt = SystemClock.elapsedRealtime()
        lastActivity.set(startedAt)
        resetTurn()
        _ui.update {
            it.copy(
                phase = VoicePhase.CONNECTING,
                provider = provider,
                notice = null,
                orb = OrbMode.THINKING,
                userCaption = "",
                assistantCaption = ""
            )
        }
        sessionScope.launch { executor.cards.collect { _cards.value = it } }
        sessionScope.launch {
            container.prefs.voiceAssistantPreferences.collect { latest ->
                // Settings remain reachable from the compact assistant. In particular,
                // disabling Coach history must take effect in this conversation.
                val changedControls = preferences.confirmSaves != latest.confirmSaves ||
                    preferences.autoCapture != latest.autoCapture
                preferences = latest
                executor.context = executor.context.copy(
                    confirmSaves = latest.confirmSaves,
                    autoCapture = latest.autoCapture
                )
                if (changedControls) session.sendText(
                    "${VoiceToolExecutor.APP_NOTE} Voice settings changed: confirm before saving is " +
                        "${if (latest.confirmSaves) "on" else "off"}; auto-capture is ${if (latest.autoCapture) "on" else "off"}.",
                    respond = false
                )
            }
        }
        sessionScope.launch { watchdog(number) }
        sessionScope.launch { cameraFrames(number) }
        sessionScope.launch { orbTicker(number) }
        if (provider == LiveProvider.HOSTED) {
            sessionScope.launch {
                val quota = runCatching { withContext(Dispatchers.IO) { container.hostedAiClient.fetchVoiceQuota() } }.getOrNull()
                if (quota != null && number == sessionNumber && _ui.value.secondsRemaining == null) {
                    _ui.update { it.copy(secondsRemaining = quota.totalSecondsLeft) }
                }
            }
        }
        session.connect()
    }

    /** Ends the conversation. A [notice] keeps the overlay open to explain what happened. */
    fun end(notice: VoiceNotice? = null) {
        sessionNumber += 1
        saveTurn()
        sessionScope?.cancel()
        sessionScope = null
        session?.close()
        session = null
        audio?.stop()
        audio = null
        executor?.reset()
        executor = null
        ports.clearSessionImages()
        _cards.value = emptyList()
        cameraOpening?.complete(CameraOutcome.Unavailable)
        cameraOpening = null
        barcodeWait?.complete(BarcodeOutcome.Cancelled)
        barcodeWait = null
        photoWait?.complete(PickOutcome.Cancelled)
        photoWait = null
        cameraHandle = null
        externalActivityPending = false
        endingAfterSpeech = false
        lastHostedNotice = null
        errorClear?.cancel()
        resetTurn()
        _ui.value = VoiceUiState(
            visible = notice != null,
            expanded = true,
            phase = VoicePhase.IDLE,
            notice = notice,
            startRequest = startCounter,
            photoPickRequest = pickCounter
        )
    }

    /** The user closed the assistant. */
    fun close() = end()

    /** The app left the foreground. */
    fun appBackgrounded() {
        if (externalActivityPending) return
        if (isActive) end() else if (_ui.value.visible) end()
    }

    fun expand() = _ui.update { it.copy(expanded = true) }

    fun collapse() {
        closeCamera()
        _ui.update { it.copy(expanded = false) }
    }

    override fun collapseForNavigation() = collapse()

    /** Says goodbye first: ends once the reply has played (or after a few seconds). */
    private fun endAfterSpeech(number: Int) {
        if (number != sessionNumber) return
        endingAfterSpeech = true
        sessionScope?.launch {
            delay(GOODBYE_GRACE_MS)
            val deadline = SystemClock.elapsedRealtime() + GOODBYE_MAX_MS
            while (number == sessionNumber && (audio?.isPlaying == true || awaitingReplySince > 0) &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                delay(200)
            }
            if (number == sessionNumber) end()
        }
    }

    // MARK: - Cards

    fun confirmCard(cardId: String) = cardAction { it.confirmCard(cardId) }
    fun cancelCard(cardId: String) = cardAction { it.cancelCard(cardId) }
    fun undoCard(cardId: String) = cardAction { it.undoCard(cardId) }
    fun logDraft(cardId: String) = cardAction { it.logDraftFromCard(cardId) }

    fun dismissCard(cardId: String) {
        executor?.dismissCard(cardId)
    }

    private fun cardAction(action: suspend (VoiceToolExecutor) -> String?) {
        val executor = executor ?: return
        val number = sessionNumber
        sessionScope?.launch {
            noteActivity()
            val note = action(executor) ?: return@launch
            if (number == sessionNumber) injectNote(note)
        }
    }

    /** Tells the model what the user tapped, through the next turn. */
    private fun injectNote(note: String) {
        val current = session ?: return
        if (current.wire.toolOutputsNeedResponseCreate) {
            if (responseInProgress) {
                pendingNotes += note
                return
            }
            current.sendText(note, respond = true)
            responseInProgress = true
        } else {
            current.sendText(note, respond = true)
        }
        awaitingReplySince = SystemClock.elapsedRealtime()
        updateOrb()
    }

    // MARK: - Provider events

    private fun handle(event: LiveEvent) {
        when (event) {
            LiveEvent.Ready -> {
                _ui.update { it.copy(phase = VoicePhase.LIVE) }
                noteActivity()
                updateOrb()
            }
            is LiveEvent.Audio -> {
                noteActivity()
                val audio = audio ?: return
                awaitingReplySince = 0
                if (event.itemId != null && event.itemId != replyItemId) {
                    replyItemId = event.itemId
                    replyItemStartMs = audio.scheduledEndMs
                }
                audio.play(event.pcm)
                updateOrb()
            }
            is LiveEvent.AssistantText -> {
                noteActivity()
                replyText += event.text
                _ui.update { it.copy(assistantCaption = caption(replyText)) }
            }
            is LiveEvent.UserText -> {
                noteActivity()
                if (!userUtteranceOpen) {
                    pendingUserText = ""
                    userUtteranceOpen = true
                }
                pendingUserText = if (event.replacing) event.text else pendingUserText + event.text
                awaitingReplySince = SystemClock.elapsedRealtime()
                _ui.update { it.copy(userCaption = caption(pendingUserText)) }
            }
            LiveEvent.UserSpeechStarted -> {
                noteActivity()
                interruptReply(tellProvider = true)
                sendFrameForTurn()
            }
            LiveEvent.UserSpeechStopped -> {
                awaitingReplySince = SystemClock.elapsedRealtime()
                updateOrb()
            }
            LiveEvent.Interrupted -> interruptReply(tellProvider = false)
            is LiveEvent.ResponseStarted -> responseInProgress = true
            is LiveEvent.TurnComplete -> {
                if (event.endsResponse) responseInProgress = false
                if (event.endsSpokenTurn) {
                    awaitingReplySince = 0
                    saveTurn()
                }
                if (event.endsResponse) flushDeferred()
                updateOrb()
            }
            is LiveEvent.ToolCalls -> runTools(event.calls)
            is LiveEvent.ToolCallsCancelled -> event.ids.forEach { id ->
                cancelledCalls += id
                runningCalls[id]?.let { (name, job) -> if (name in WAITING_TOOLS) job.cancel() }
            }
            is LiveEvent.HostedStatus -> _ui.update { it.copy(secondsRemaining = event.secondsRemaining) }
            is LiveEvent.HostedNotice -> lastHostedNotice = event.code
            is LiveEvent.ProviderError -> {
                VoiceLog.warn(TAG, "The provider reported an error")
                responseInProgress = false
                if (_ui.value.phase == VoicePhase.CONNECTING) {
                    end(VoiceNotice.ProviderMessage(event.message.take(300)))
                } else {
                    showTransientError(event.message)
                }
            }
            is LiveEvent.Closed -> {
                val provider = _ui.value.provider
                end(event.failure?.let { noticeFor(it, provider) } ?: VoiceNotice.ConnectionLost)
            }
            is LiveEvent.ResumptionHandle, LiveEvent.GoAway -> Unit
        }
    }

    private fun noticeFor(failure: LiveFailure, provider: LiveProvider?): VoiceNotice {
        val hosted = provider == LiveProvider.HOSTED
        return when (failure) {
            is LiveFailure.Refused -> when (failure.status) {
                401 -> if (hosted) VoiceNotice.HostedUnauthorized else VoiceNotice.KeyRejected(provider ?: LiveProvider.GEMINI)
                402 -> if (hosted) VoiceNotice.OutOfMinutes else VoiceNotice.KeyRejected(provider ?: LiveProvider.GEMINI)
                403 -> if (hosted) VoiceNotice.NeedsSubscription else VoiceNotice.KeyRejected(provider ?: LiveProvider.GEMINI)
                429 -> VoiceNotice.RateLimited
                503 -> VoiceNotice.VoiceUnavailable
                else -> VoiceNotice.ConnectionFailed
            }
            is LiveFailure.Closed -> hostedCloseNotice(failure, hosted) ?: when {
                failure.reason?.contains("API key", ignoreCase = true) == true && provider != null ->
                    VoiceNotice.KeyRejected(provider)
                !failure.reason.isNullOrBlank() && failure.code != LiveSession.NORMAL_CLOSURE ->
                    VoiceNotice.ProviderMessage(failure.reason.take(300))
                else -> VoiceNotice.ConnectionLost
            }
            is LiveFailure.Network -> VoiceNotice.ConnectionFailed
        }
    }

    private fun hostedCloseNotice(failure: LiveFailure.Closed, hosted: Boolean): VoiceNotice? =
        closeNotice(failure.code, lastHostedNotice, hosted)

    private fun runTools(calls: List<LiveToolCall>) {
        val sessionScope = sessionScope ?: return
        val executor = executor ?: return
        val number = sessionNumber
        sessionScope.launch {
            toolsRunning += 1
            updateOrb()
            val results = mutableListOf<LiveToolResult>()
            try {
                for (call in calls) {
                    if (call.id in cancelledCalls) continue
                    val job = sessionScope.async { executor.execute(call) }
                    runningCalls[call.id] = call.name to job
                    val result = try {
                        job.await()
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                        null
                    } finally {
                        runningCalls.remove(call.id)
                    }
                    if (result == null || call.id in cancelledCalls) continue
                    results += LiveToolResult(call.id, call.name, result)
                    noteActivity()
                }
            } finally {
                if (number == sessionNumber) {
                    toolsRunning -= 1
                    updateOrb()
                }
            }
            if (number != sessionNumber || results.isEmpty()) return@launch
            val current = session ?: return@launch
            current.sendToolResults(results)
            awaitingReplySince = SystemClock.elapsedRealtime()
            if (current.wire.toolOutputsNeedResponseCreate) requestResponse()
        }
    }

    /** OpenAI/xAI: one `response.create` once the current response is over. */
    private fun requestResponse() {
        if (responseInProgress) {
            pendingResponseCreate = true
            return
        }
        session?.sendResponseCreate()
        responseInProgress = true
    }

    private fun flushDeferred() {
        val current = session ?: return
        if (!current.wire.toolOutputsNeedResponseCreate) return
        if (pendingNotes.isEmpty() && !pendingResponseCreate) return
        pendingNotes.forEach { current.sendText(it, respond = false) }
        pendingNotes.clear()
        pendingResponseCreate = false
        current.sendResponseCreate()
        responseInProgress = true
    }

    private fun playbackFinished(number: Int) {
        if (number != sessionNumber) return
        noteActivity()
        updateOrb()
    }

    /** The user spoke over the reply: stop it, and tell OpenAI how much was heard. */
    private fun interruptReply(tellProvider: Boolean) {
        val audio = audio ?: return
        if (!audio.isPlaying) return
        if (tellProvider) {
            replyItemId?.let { id ->
                session?.truncate(id, (audio.playbackPositionMs - replyItemStartMs).coerceAtLeast(0).toInt())
            }
        }
        audio.stopPlayback()
        replyItemId = null
        updateOrb()
    }

    // MARK: - Coach transcript

    /** Adds what the user said, then the reply, to Coach's chat history. */
    private fun saveTurn() {
        val user = pendingUserText.trim()
        val reply = replyText.trim()
        pendingUserText = ""
        userUtteranceOpen = false
        replyText = ""
        if (!preferences.saveToCoach || (user.isEmpty() && reply.isEmpty())) return
        scope.launch {
            runCatching {
                if (user.isNotEmpty()) container.chatRepository.append(ChatMessage(role = ChatMessage.Role.USER, content = user))
                if (reply.isNotEmpty()) container.chatRepository.append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply))
            }.onFailure { VoiceLog.warn(TAG, "Couldn't save the turn to Coach", it) }
        }
    }

    // MARK: - Timers

    private suspend fun watchdog(number: Int) {
        while (currentCoroutineContext().isActive && number == sessionNumber) {
            delay(1_000)
            if (number != sessionNumber) return
            executor?.expireUndoCards()
            val now = SystemClock.elapsedRealtime()
            val state = _ui.value
            if (state.phase == VoicePhase.CONNECTING && now - startedAt > CONNECT_TIMEOUT_MS) {
                end(VoiceNotice.ConnectionFailed)
                return
            }
            if (now - startedAt > MAX_SESSION_MS) {
                end(VoiceNotice.SessionLimit)
                return
            }
            val waiting = waitingForUser > 0 || toolsRunning > 0 || state.camera != null ||
                executor?.hasPendingConfirmation == true
            if (state.phase == VoicePhase.LIVE && audio?.isPlaying != true && !waiting &&
                now - lastActivity.get() > IDLE_TIMEOUT_MS
            ) {
                VoiceLog.info(TAG, "Ending an idle conversation")
                end()
                return
            }
        }
    }

    /** Gemini and hosted watch about one frame a second while the camera is open. */
    private suspend fun cameraFrames(number: Int) {
        while (currentCoroutineContext().isActive && number == sessionNumber) {
            delay(FRAME_INTERVAL_MS)
            val provider = _ui.value.provider ?: continue
            if (provider.cameraFrames != CameraFrames.CONTINUOUS) return
            if (_ui.value.phase != VoicePhase.LIVE || _ui.value.camera == null) continue
            sendLatestFrame()
        }
    }

    /** OpenAI sees the latest frame with each thing the user says. */
    private fun sendFrameForTurn() {
        if (_ui.value.provider?.cameraFrames != CameraFrames.PER_TURN || _ui.value.camera == null) return
        sendLatestFrame()
    }

    private fun sendLatestFrame() {
        val frame = cameraHandle?.latestFrame() ?: return
        if (frame.takenAtMillis <= lastFrameSentAt) return
        lastFrameSentAt = frame.takenAtMillis
        session?.sendImage(frame.jpeg)
    }

    /** Keeps listening/thinking current between events (local speech detection). */
    private suspend fun orbTicker(number: Int) {
        while (currentCoroutineContext().isActive && number == sessionNumber) {
            delay(200)
            if (awaitingReplySince > 0 && SystemClock.elapsedRealtime() - awaitingReplySince > REPLY_WAIT_MS) {
                awaitingReplySince = 0
            }
            updateOrb()
        }
    }

    private fun updateOrb() {
        val state = _ui.value
        val now = SystemClock.elapsedRealtime()
        val orb = when (state.phase) {
            VoicePhase.IDLE -> OrbMode.IDLE
            VoicePhase.PREPARING, VoicePhase.CONNECTING -> OrbMode.THINKING
            VoicePhase.LIVE -> when {
                audio?.isPlaying == true -> OrbMode.SPEAKING
                now - lastUserSound.get() < USER_SOUND_HOLD_MS -> OrbMode.LISTENING
                toolsRunning > 0 || awaitingReplySince > 0 -> OrbMode.THINKING
                else -> OrbMode.LISTENING
            }
        }
        if (orb != state.orb) _ui.update { it.copy(orb = orb) }
    }

    /** The loudness of whoever is talking, for the orb. */
    fun orbLevel(): Float {
        val audio = audio ?: return 0f
        return if (audio.isPlaying) audio.playbackLevel else audio.microphoneLevel
    }

    private fun noteActivity() {
        lastActivity.set(SystemClock.elapsedRealtime())
    }

    private fun resetTurn() {
        pendingUserText = ""
        userUtteranceOpen = false
        replyText = ""
        replyItemId = null
        replyItemStartMs = 0
        awaitingReplySince = 0
        toolsRunning = 0
        responseInProgress = false
        pendingResponseCreate = false
        pendingNotes.clear()
        cancelledCalls.clear()
        runningCalls.clear()
        waitingForUser = 0
        lastFrameSentAt = 0
    }

    private fun showTransientError(message: String) {
        _ui.update { it.copy(transientError = message.take(200)) }
        errorClear?.cancel()
        errorClear = scope.launch {
            delay(ERROR_DISPLAY_MS)
            _ui.update { it.copy(transientError = null) }
        }
    }

    // MARK: - Camera and photos (VoiceUiBridge)

    override fun cameraMode(): VoiceCameraMode? = _ui.value.camera

    override suspend fun openCamera(mode: VoiceCameraMode): CameraOutcome {
        if (!isActive) return CameraOutcome.Unavailable
        val number = sessionNumber
        if (_ui.value.camera == mode && cameraHandle != null) return CameraOutcome.Opened
        val opening = CompletableDeferred<CameraOutcome>()
        cameraOpening?.complete(CameraOutcome.Unavailable)
        cameraOpening = opening
        cameraHandle = null
        _ui.update { it.copy(camera = mode, expanded = true) }
        waitingForUser += 1
        val outcome = try {
            withTimeoutOrNull(CAMERA_OPEN_TIMEOUT_MS) { opening.await() } ?: CameraOutcome.TimedOut
        } finally {
            if (number == sessionNumber) waitingForUser -= 1
            if (cameraOpening === opening) {
                cameraOpening = null
            }
        }
        if (number == sessionNumber && cameraOpening == null && outcome != CameraOutcome.Opened) closeCamera()
        return outcome
    }

    override fun closeCamera() {
        if (_ui.value.camera != null) _ui.update { it.copy(camera = null) }
        cameraHandle = null
        cameraOpening?.complete(CameraOutcome.Unavailable)
        barcodeWait?.complete(BarcodeOutcome.Cancelled)
    }

    override suspend fun takePhoto(): PhotoOutcome {
        val handle = cameraHandle ?: return PhotoOutcome.NotOpen
        val number = sessionNumber
        return try {
            val photo = handle.takePhoto()
            currentCoroutineContext().ensureActive()
            if (number != sessionNumber || cameraHandle !== handle) PhotoOutcome.NotOpen
            else photo?.let { PhotoOutcome.Taken(it) } ?: PhotoOutcome.Failed
        } finally {
            // Do not dismiss a camera opened while this capture was suspended.
            if (number == sessionNumber && cameraHandle === handle) closeCamera()
        }
    }

    override suspend fun scanBarcode(): BarcodeOutcome {
        val number = sessionNumber
        val wait = CompletableDeferred<BarcodeOutcome>()
        barcodeWait?.complete(BarcodeOutcome.Cancelled)
        barcodeWait = wait
        waitingForUser += 1
        val outcome = try {
            // Register before opening: the first analyzed frame can already contain a barcode.
            when (openCamera(VoiceCameraMode.BARCODE)) {
                CameraOutcome.Opened -> Unit
                CameraOutcome.PermissionDenied -> return BarcodeOutcome.PermissionDenied
                CameraOutcome.TimedOut -> return BarcodeOutcome.TimedOut
                CameraOutcome.Unavailable -> return BarcodeOutcome.Unavailable
            }
            withTimeoutOrNull(WAIT_FOR_USER_MS) { wait.await() } ?: BarcodeOutcome.TimedOut
        } finally {
            if (number == sessionNumber) waitingForUser -= 1
            if (barcodeWait === wait) {
                barcodeWait = null
                if (_ui.value.camera == VoiceCameraMode.BARCODE) closeCamera()
            }
        }
        return outcome
    }

    override suspend fun pickPhotos(maxCount: Int): PickOutcome {
        if (!isActive) return PickOutcome.Cancelled
        closeCamera()
        val number = sessionNumber
        val wait = CompletableDeferred<PickOutcome>()
        photoWait?.complete(PickOutcome.Cancelled)
        photoWait = wait
        waitingForUser += 1
        externalActivityPending = true
        pickCounter += 1
        _ui.update { it.copy(photoPickRequest = pickCounter, photoPickMax = maxCount) }
        return try {
            withTimeoutOrNull(WAIT_FOR_USER_MS) { wait.await() } ?: PickOutcome.TimedOut
        } finally {
            if (number == sessionNumber) waitingForUser -= 1
            if (photoWait === wait) {
                externalActivityPending = false
                photoWait = null
            }
        }
    }

    /** The camera panel is bound and ready. */
    fun cameraReady(handle: VoiceCameraHandle) {
        if (!isActive || _ui.value.camera == null) return
        cameraHandle = handle
        // CameraBinding reports ready only after its first frame. Send it before the
        // open_camera result so OpenAI can base its next capture decision on the image.
        if (_ui.value.provider?.cameraFrames == CameraFrames.PER_TURN) {
            sendLatestFrame()
        }
        cameraOpening?.complete(CameraOutcome.Opened)
    }

    fun cameraFailed(permissionDenied: Boolean) {
        cameraOpening?.complete(if (permissionDenied) CameraOutcome.PermissionDenied else CameraOutcome.Unavailable)
        barcodeWait?.complete(if (permissionDenied) BarcodeOutcome.PermissionDenied else BarcodeOutcome.Unavailable)
        closeCamera()
    }

    fun cameraUnbound(handle: VoiceCameraHandle) {
        if (cameraHandle === handle) cameraHandle = null
    }

    /**
     * The user tapped the shutter: the same photo and food scan as `capture_photo`, then the
     * model hears the draft so it can read it back.
     */
    fun captureFromShutter() {
        val executor = executor ?: return
        if (_ui.value.capturing || _ui.value.camera != VoiceCameraMode.FOOD) return
        val number = sessionNumber
        _ui.update { it.copy(capturing = true) }
        sessionScope?.launch {
            noteActivity()
            val result = try {
                executor.execute(LiveToolCall("shutter_${SystemClock.elapsedRealtime()}", "capture_photo", JsonObject(emptyMap())))
            } finally {
                if (number == sessionNumber) _ui.update { it.copy(capturing = false) }
            }
            if (number != sessionNumber) return@launch
            injectNote(
                "${VoiceToolExecutor.APP_NOTE} The user took a photo with the shutter and the app scanned it: $result. " +
                    "Read back the main items and the total calories, then ask whether to log it."
            )
        }
    }

    /** The user closed the camera panel. */
    fun userClosedCamera() {
        noteActivity()
        closeCamera()
    }

    fun barcodeDetected(handle: VoiceCameraHandle, code: String) {
        if (cameraHandle !== handle) return
        barcodeWait?.complete(BarcodeOutcome.Scanned(code))
    }

    /** The photo picker returned (empty when cancelled). */
    fun photosPicked(uris: List<Uri>) {
        externalActivityPending = false
        val wait = photoWait ?: return
        if (uris.isEmpty()) {
            wait.complete(PickOutcome.Cancelled)
            return
        }
        scope.launch {
            val resolver = container.appContext.contentResolver
            val images = withContext(Dispatchers.IO) {
                uris.take(_ui.value.photoPickMax).mapNotNull { ContentUriBytes.readBounded(resolver, it) }
            }
            wait.complete(if (images.isEmpty()) PickOutcome.Cancelled else PickOutcome.Picked(images))
        }
    }

    // MARK: - Instructions

    private suspend fun buildInstructions(context: VoiceSessionContext, prefs: VoiceAssistantPreferences): String {
        val profile = container.profileRepository.current()
        val foods = container.foodRepository.entries.first()
        val weightMetric = container.prefs.weightUnit.first() == "kg"
        val summary = profile?.let {
            val workouts = container.workoutRepository.snapshot()
            CoachContext.summaryLines(
                profile = it,
                weights = container.weightRepository.entries.first(),
                bodyFats = container.bodyFatRepository.entries.first(),
                measurements = container.bodyMeasurementRepository.entries.first(),
                foods = foods,
                fastingSessions = container.fastingRepository.sessions.first(),
                heightMetric = container.prefs.heightUnit.first() == "cm",
                weightMetric = weightMetric,
                workoutSessions = workouts.completedSessions,
                workoutPlans = workouts.dayPlans.values.toList(),
                energyUnit = container.prefs.energyUnit.first()
            )
        }.orEmpty()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayFoods = foods.filter { it.timestamp.atZone(zone).toLocalDate() == today }
        val waterOn = container.prefs.waterTrackingEnabled.first()
        val water = if (waterOn) {
            container.waterRepository.entries.first()
                .filter { it.date.atZone(zone).toLocalDate() == today }
                .sumOf { it.milliliters }
        } else null
        val todayLine = VoiceInstructions.todayLine(
            calories = todayFoods.sumOf { it.calories },
            protein = todayFoods.sumOf { it.protein },
            carbs = todayFoods.sumOf { it.carbs },
            fat = todayFoods.sumOf { it.fat },
            calorieGoal = profile?.effectiveCalories,
            proteinGoal = profile?.effectiveProtein,
            carbsGoal = profile?.effectiveCarbs,
            fatGoal = profile?.effectiveFat,
            waterMl = water,
            waterGoalMl = if (waterOn) container.prefs.waterDailyGoalMl.first() else null
        )
        return VoiceInstructions.build(
            VoiceInstructionInput(
                context = context,
                today = today,
                time = LocalTime.now(zone),
                timeZone = zone.id,
                replyLanguage = replyLanguage(prefs.language),
                metric = weightMetric,
                coachSummary = summary,
                todayLine = todayLine,
                userContext = container.prefs.userContext.first()
            )
        )
    }

    private fun replyLanguage(setting: String): String? = when (setting) {
        VoiceAssistantPreferences.LANGUAGE_AUTOMATIC -> null
        VoiceAssistantPreferences.LANGUAGE_APP, "" -> VoiceInstructions.languageName(shownAppLanguage())
        else -> VoiceInstructions.languageName(setting)
    }

    companion object {
        private const val TAG = "VoiceAssistant"

        const val IDLE_TIMEOUT_MS = 45_000L
        const val MAX_SESSION_MS = 15 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val ENTITLEMENT_WAIT_MS = 3_000L
        private const val CAMERA_OPEN_TIMEOUT_MS = 30_000L
        /** Tools that wait for the user give up after 90 s. */
        const val WAIT_FOR_USER_MS = 90_000L
        private const val FRAME_INTERVAL_MS = 1_000L
        private const val GOODBYE_GRACE_MS = 600L
        private const val GOODBYE_MAX_MS = 8_000L
        private const val REPLY_WAIT_MS = 10_000L
        private const val USER_SOUND_HOLD_MS = 450L
        private const val ERROR_DISPLAY_MS = 8_000L
        private const val SPEECH_LEVEL = 0.35f
        private const val CAPTION_CHARS = 240

        /** Tools a cancellation may stop while they wait for the user. */
        private val WAITING_TOOLS = setOf("pick_photos", "scan_barcode", "open_camera")

        private fun caption(text: String): String {
            val trimmed = text.trim()
            if (trimmed.length <= CAPTION_CHARS) return trimmed
            val tail = trimmed.takeLast(CAPTION_CHARS)
            return "…" + tail.substringAfter(' ', tail)
        }
    }
}

/** The language the app's own strings are shown in, which App Language replies match. */
internal fun shownAppLanguage(): String = AppLanguage.shownTag()
