package com.librevision

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Vibrator
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.librevision.core.audio.AndroidSpeechOutput
import com.librevision.core.audio.FeedbackCues
import com.librevision.core.audio.SpatialSonarEngine
import com.librevision.core.safety.LoudspeakerEmergencyAlarm
import com.librevision.domain.hazards.ThreeZoneHazardClassifier
import com.librevision.domain.hazards.HazardSeverity
import com.librevision.data.*
import com.librevision.data.models.*
import com.librevision.data.remote.*
import com.librevision.data.routing.*
import com.librevision.data.scene.SceneVideoRecorder
import com.librevision.data.scene.SurroundingsSession
import com.librevision.data.scene.VideoFrameExtractor
import com.librevision.data.scene.VideoPhase
import com.librevision.data.sensors.AndroidNavigationSensors
import com.librevision.data.sensors.AndroidTiltProvider
import com.librevision.data.speech.*
import com.librevision.data.vision.*
import com.librevision.domain.alerts.*
import com.librevision.domain.assistant.*
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import com.librevision.domain.scene.*
import com.librevision.domain.vision.*
import com.librevision.feature.navigation.NavigationUiState
import com.librevision.feature.welcome.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Screen {
    HOME,
    NAVIGATION,
    FOOTPATH,
    SCENE,
    TRAINING,
    SETTINGS,
}

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SUCCESS,
    FAILURE,
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val voice: VoiceState = VoiceState.IDLE,
    val voiceOpen: Boolean = false,
    val message: String = "",
    val selected: Destination? = null,
    val busy: Boolean = false,
    val scanRequested: Boolean = false,
    val scanPhase: ScanPhase = ScanPhase.IDLE,
    val detections: List<Detection> = emptyList(),
    val tilt: PhoneTilt = PhoneTilt.UNKNOWN,
    val indicator: FootpathIndicator = FootpathIndicator.UNKNOWN,
    val footpath: String = "Tap Start Scanning.",
    val scene: String = "Requires internet. Only a selected image is sent with your permission.",
    val hasSceneDescription: Boolean = false,
    val cue: String = "Choose a cue to practice.",
    val largeText: Boolean = false,
    val nightMode: Boolean = false,
    val detailed: Boolean = false,
    val bangla: Boolean = false,
    val handsFree: Boolean = true,
    val audioFallback: Boolean = false,
    val soundCues: Boolean = true,
    val haptics: Boolean = true,
    val stepLength: Float = .65f,
    val depthEnabled: Boolean = true,
    val depthMessage: String = "Estimated distance unavailable.",
    val speechAvailable: Boolean = true,
    val speechIssue: String? = null,
    val microphoneDenied: Boolean = false,
    val settingsExpanded: Boolean = false,
)

class LibreVisionViewModel(application: Application) : AndroidViewModel(application) {
    private var foreground = false
    private var hasGreetedBlindUser = false

    fun foreground() {
        foreground = true
        announceWelcomeStage()
        resumeHandsFreeIfReady()
        if (state.value.screen == Screen.NAVIGATION) {
            navigationSensors.start()
            syncNavigationCamera()
        }
        if (!hasGreetedBlindUser && welcome.value.stage == WelcomeStage.COMPLETE) {
            hasGreetedBlindUser = true
            viewModelScope.launch {
                delay(800)
                announce(
                    "Netra Saathi is ready. Hold Volume Up for two seconds to speak, or triple click for emergency help. What would you like to do?",
                    Priority.URGENT
                )
                delay(5000)
                if (foreground && !state.value.voiceOpen) {
                    voice()
                }
            }
        }
    }

    /**
     * Activates or stops the high-decibel built-in loudspeaker siren
     * and blind distress voice announcement.
     */
    fun triggerEmergencyLoudspeakerAlarm(): Boolean {
        val active = emergencyAlarm.toggle()
        if (active) {
            mutable.update { it.copy(message = "LOUDSPEAKER SOS ALARM ACTIVE!") }
        } else {
            announce("Emergency alarm stopped.", Priority.ROUTINE)
        }
        return active
    }

    private val preferences =
        application.getSharedPreferences("accessibility", Context.MODE_PRIVATE).also { prefs ->
            if (!prefs.contains("handsFreeExplicitChoice")) {
                // Recover a legacy test pause once; later deliberate microphone choices persist.
                prefs
                    .edit()
                    .putBoolean("handsFree", true)
                    .putBoolean("handsFreeExplicitChoice", false)
                    .apply()
            }
        }
    private val welcomePreferences =
        application.getSharedPreferences("welcome", Context.MODE_PRIVATE)
    private val welcomeFlow =
        WelcomeFlow(
            WelcomeSnapshot(
                welcomePreferences.getBoolean("permissionsAsked", false),
                welcomePreferences.getBoolean("completed", false),
                welcomePreferences.getInt("tourStep", -1).takeIf { it >= 0 },
            )
        ) { snapshot ->
            welcomePreferences
                .edit()
                .putBoolean("permissionsAsked", snapshot.permissionsAsked)
                .putBoolean("completed", snapshot.completed)
                .putInt("tourStep", snapshot.tourStep ?: -1)
                .commit()
        }
    val welcome = welcomeFlow.state
    private var tourUtterance: String? = null
    private var tourAdvance: Job? = null
    private var welcomeAnnounced: WelcomeStage? = null

    private val mutable =
        MutableStateFlow(
            UiState(
                largeText = preferences.getBoolean("large", false),
                nightMode = preferences.getBoolean("nightMode", false),
                detailed = preferences.getBoolean("detailed", false),
                bangla = preferences.getBoolean("bangla", false),
                handsFree =
                    HandsFreePreference.enabled(
                        preferences.getBoolean("handsFree", true),
                        preferences.getBoolean("handsFreeExplicitChoice", false),
                    ),
                soundCues = preferences.getBoolean("soundCues", true),
                haptics = preferences.getBoolean("haptics", true),
                stepLength = preferences.getFloat("stepLength", .65f),
                depthEnabled = preferences.getBoolean("depthEnabled", true),
            )
        )
    val state = mutable.asStateFlow()
    private val models = AppModelManager(application)
    val modelSetup = models.state
    private val voiceActivation = MuseVoiceSession()
    private val voiceCommands =
        VoiceCommandController(
            application,
            models,
            activation = voiceActivation,
            audioFallbackEnabled = { gateway.audioConsent },
        )
    val listening = voiceCommands.state
    private val scanner = FootpathScanner(application, models)
    private val tiltProvider = AndroidTiltProvider(application)
    private val evidence = FootpathEvidence()
    private val obstructionPolicy = ObstructionPolicy()
    private val obstacleAnnouncements = ObstacleAnnouncementPolicy()
    private var pendingObstacleAlert = false
    private val depthMatcher = DepthObservationMatcher()
    private val tiltPrompts = TiltPromptPolicy()
    private var lastObservation = Long.MIN_VALUE
    private var speechPlaying = false
    private var cuePlaying = false
    private var sceneReplyForListening: String? = null
    private var assistantReplyForListening: String? = null
    private var pendingTurnCue: Pair<String, FeedbackCue>? = null
    private val feedbackCues =
        FeedbackCues(application) { active ->
                cuePlaying = active
                updatePlaybackGate()
            }
            .apply {
                soundEnabled = state.value.soundCues
                hapticsEnabled = state.value.haptics
            }
    private val speech: AndroidSpeechOutput =
        AndroidSpeechOutput(
            application,
            ::speechFinished,
            { reason -> mutable.update { it.copy(speechAvailable = false, speechIssue = reason) } },
            { active ->
                speechPlaying = active
                if (!active) voiceActivation.playbackEnded(SystemClock.elapsedRealtime())
                updatePlaybackGate()
            },
            { mutable.update { it.copy(speechAvailable = true, speechIssue = null) } },
            voiceCommands::rememberPlaybackText,
        )
    private val alerts: AlertCoordinator = AlertCoordinator(speech)
    private val commands = VoiceCommandRouter()
    val emergencyAlarm = LoudspeakerEmergencyAlarm(application)
    private val spatialSonar = SpatialSonarEngine()
    private val hazardClassifier = ThreeZoneHazardClassifier()
    private val gateway = GatewayPreferences(application)
    private val audioTranscriber = GatewayAudioTranscriptionProvider(gateway, application)
    private val sceneProvider: SceneAnalysisProvider = GatewaySceneAnalysisProvider(gateway)
    private val muse: MuseAssistant = GatewayMuseAssistant(gateway)
    private val sceneVideo = SceneVideoRecorder(application)
    private val videoFrames = VideoFrameExtractor()
    private val surroundingsSession = SurroundingsSession(muse, SystemClock::elapsedRealtime)
    val sceneVideoState = sceneVideo.state
    val surroundingsState = surroundingsSession.state
    private var surroundingsJob: Job? = null
    private val assistant = MutableStateFlow(AssistantUiState())
    val assistantState = assistant.asStateFlow()
    private val assistantHistory = ArrayDeque<AssistantTurn>()
    private var assistantJob: Job? = null
    private var assistantEpoch = 0L
    private var pendingImageQuestion: String? = null
    private var pendingAssistantSpeech: Pair<Long, String>? = null
    private var assistantRouteTicket: Pair<Long, Int?>? = null
    private var contextJob: Job? = null
    private var lastContextRequest = Long.MIN_VALUE
    private var pendingContextSpeech: Pair<Long, String>? = null
    private var contextExpires = 0L
    private val temporal = TemporalStateEngine()
    private val services = ServicePreferences(application)
    private val routes: RouteProvider = ValhallaRouteProvider { services.routing }
    private val nearby: NearbyDestinationProvider = PhotonNearbyProvider { services.search }
    private var pendingQuick: NearbyCategory? = null
    private var pendingNearbyVoiceTicket: Long? = null
    private var pendingRoute = false
    private var pendingNearbyStart = true
    private var requestVersion = 0L
    private var quickJob: Job? = null
    private var navigationCameraEnabled = true
    private var pendingFeedback: String? = null
    private val places: PlaceSearchProvider = PhotonPlaceSearch { services.search }
    private val navigation =
        WalkingGuidanceEngine(SystemClock::elapsedRealtime).apply {
            setStepLength(state.value.stepLength)
        }
    private val navigationSensors = AndroidNavigationSensors(application)
    val guidance = navigation.currentGuidance()
    private val nav =
        MutableStateFlow(
            NavigationUiState(
                searchUrl = services.search,
                routingUrl = services.routing,
                tilesUrl = services.tiles,
                gatewayUrl = gateway.url,
                gatewayAccess = gateway.access,
                cloudConsent = gateway.consent,
            )
        )
    val navigationState = nav.asStateFlow()
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var lastGuidanceKey = ""
    private var latestObservation: Observation? = null
    private var lastHazardAt = Long.MIN_VALUE
    private var lastDepthAt = Long.MIN_VALUE
    private var lastHazardLabel: String? = null
    private var operation: Job? = null
    private var voiceJob: Job? = null
    private var audioEpoch = 0L
    private var lastSpeechFailureAt = Long.MIN_VALUE
    private var expiry: Job? = null

    init {
        mutable.update { it.copy(audioFallback = gateway.audioConsent) }
        if (welcomeFlow.permissionsAsked) models.prepare()
        viewModelScope.launch {
            sceneVideo.state.collect { video ->
                if (video.phase == VideoPhase.RECORDING)
                    surroundingsSession.recording(video.elapsedMs)
            }
        }
        viewModelScope.launch {
            modelSetup.collect {
                welcomeFlow.preparation(
                    it.percentage,
                    it.phase == SetupPhase.READY,
                    it.phase == SetupPhase.ERROR,
                )
                announceWelcomeStage()
                if (it.phase == SetupPhase.READY) resumeHandsFreeIfReady()
            }
        }
        scanner.depthEnabled = state.value.depthEnabled
        viewModelScope.launch {
            guidance.map { it.status }.distinctUntilChanged().collect { syncNavigationCamera() }
        }
        viewModelScope.launch { navigationSensors.heading.collect(navigation::updateHeading) }
        viewModelScope.launch {
            navigationSensors.location.collect { location ->
                nav.update { it.copy(location = location) }
                location?.let(navigation::updateLocation)
                processPendingNavigation()
            }
        }
        viewModelScope.launch {
            navigationSensors.status.collect { message ->
                nav.update { it.copy(locationMessage = message) }
            }
        }
        viewModelScope.launch {
            scanner.depth.collect { depth ->
                mutable.update { it.copy(depthMessage = depth.message) }
                maybeSpeakDistance()
            }
        }
        viewModelScope.launch { voiceCommands.commands.collect(::handleRecognizedSpeech) }
        viewModelScope.launch {
            scanner.state.collect { scan ->
                mutable.update { it.copy(scanPhase = scan.phase) }
                if (scan.phase == ScanPhase.ERROR && state.value.scanRequested) {
                    mutable.update {
                        it.copy(
                            scanRequested = false,
                            detections = emptyList(),
                            indicator = FootpathIndicator.UNKNOWN,
                            footpath = scan.message,
                        )
                    }
                    tiltProvider.stop()
                    announce("Scanning stopped. Camera or model unavailable.", Priority.URGENT)
                } else if (scan.phase == ScanPhase.PREPARING)
                    mutable.update { it.copy(footpath = scan.message) }
                val observation = scan.observation
                if (
                    state.value.scanRequested &&
                        observation != null &&
                        observation.timestampMs > lastObservation
                ) {
                    lastObservation = observation.timestampMs
                    latestObservation = observation
                    depthMatcher.record(observation)
                    val now = SystemClock.elapsedRealtime()
                    val relevant = obstructionPolicy.select(observation.detections).take(1)
                    evidence.update(observation, relevant, now)
                    val result = temporal.consume(observation.copy(detections = relevant), now)
                    val warning =
                        relevant.firstOrNull()?.let { obstacleMessage(it, observation, now) }
                    val fresh = now - observation.timestampMs in 0..4000
                    mutable.update {
                        it.copy(
                            detections = if (fresh) observation.detections else emptyList(),
                            footpath =
                                if (!fresh) "Camera result is stale. Path safety unknown."
                                else
                                    warning
                                        ?: when {
                                            evidence.indicator(true, true, it.tilt, now) ==
                                                FootpathIndicator.OBSTRUCTION ->
                                                "Recent obstruction ahead."
                                            it.tilt == PhoneTilt.UPRIGHT ->
                                                "No obstruction reported in the centre."
                                            else -> "Keep the phone upright for a clearer view."
                                        },
                        )
                    }
                    refreshIndicator()
                    if (fresh && result.alert && result.alertLabel != null) {
                        pendingObstacleAlert = true
                    }
                    maybeSpeakObstacle()
                }
            }
        }
        expiry = viewModelScope.launch {
            while (isActive) {
                delay(300)
                if (surroundingsSession.tick()) {
                    cancelAssistant()
                    mutable.update { it.copy(hasSceneDescription = false, scene = UiState().scene) }
                }
                navigationSensors.tick()
                navigation.tick()
                maybeSpeakObstacle()
                maybeSpeakDistance()
                speakFeedback()
                speakCurrentGuidance()
                speakContext()
                speakAssistant()
                voiceCommands.tick(state.value.handsFree)
                val awaitingFollowup = voiceCommands.awaitingCommand()
                assistant.update {
                    if (
                        it.phase == AssistantPhase.LISTENING &&
                            !awaitingFollowup &&
                            !voiceActivation.awaiting(SystemClock.elapsedRealtime())
                    )
                        it.copy(phase = AssistantPhase.IDLE, expectsFollowup = false)
                    else it.copy(expectsFollowup = awaitingFollowup)
                }
                if (contextExpires > 0 && SystemClock.elapsedRealtime() > contextExpires) {
                    contextExpires = 0
                    nav.update { it.copy(contextText = null) }
                }
                if (state.value.scanRequested) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastObservation != Long.MIN_VALUE && now - lastObservation > 4000)
                        mutable.update {
                            it.copy(
                                detections = emptyList(),
                                footpath = "No recent camera evidence. Path safety unknown.",
                            )
                        }
                    refreshIndicator()
                    if (
                        !alerts.isSpeaking() &&
                            surroundingsState.value.phase != SurroundingsPhase.CAPTURING &&
                            assistant.value.phase != AssistantPhase.THINKING &&
                            !voiceActivation.awaiting(now) &&
                            (guidance.value.maneuverDistanceMeters ?: 1000.0) > 35
                    )
                        tiltPrompts.update(state.value.tilt, now)?.let { announce(it) }
                }
            }
        }
        viewModelScope.launch {
            tiltProvider.tilt.collect { tilt -> mutable.update { it.copy(tilt = tilt) } }
        }
    }

    private fun refreshIndicator() {
        mutable.update {
            it.copy(
                indicator =
                    evidence.indicator(
                        it.scanRequested,
                        it.scanPhase == ScanPhase.RUNNING,
                        it.tilt,
                        SystemClock.elapsedRealtime(),
                    )
            )
        }
    }

    fun testSpeech() {
        alerts.stop()
        speech.language(false)
        alerts.offer(Alert("Netra Saathi speech is working.", Priority.URGENT))
    }

    private fun distanceFor(
        detection: Detection,
        observation: Observation,
        now: Long,
    ): DepthDistance? {
        if (!state.value.depthEnabled) return null
        val frame =
            scanner.depth.value.frame?.takeIf { it.timestampMs == observation.timestampMs }
                ?: return null
        return detection.bounds?.let { frame.estimate(it, now) }
    }

    private fun obstacleMessage(detection: Detection, observation: Observation, now: Long) =
        ObstacleSpeech.message(
            detection.label,
            distanceFor(detection, observation, now),
            state.value.stepLength,
            now,
        )

    private fun maybeSpeakObstacle() {
        if (!foreground || !state.value.scanRequested || !pendingObstacleAlert) return
        val observation = latestObservation ?: return
        val now = SystemClock.elapsedRealtime()
        val primary = obstructionPolicy.select(observation.detections).firstOrNull()
        if (primary == null || now - observation.timestampMs !in 0..4000) {
            pendingObstacleAlert = false
            return
        }
        val traffic = TrafficObjects.contains(primary.label)
        if (
            !obstacleAnnouncements.canAnnounce(primary.label, observation.timestampMs, now, traffic)
        )
            return

        // Vehicle alert audio/haptic alert handled via alerts.offer below; torch flashing disabled
        val depthEst = distanceFor(primary, observation, now)
        val depthMeters = depthEst?.meters ?: 2.0f
        val box = primary.bounds
        val top = box?.top ?: 0.5f
        val bottom = box?.bottom ?: 0.8f
        val centerX = if (box != null) (box.left + box.right) / 2f else 0.5f
        val lateralOffset = (centerX - 0.5f) * 2.0f
        spatialSonar.updateSteering(lateralOffset, depthMeters)

        val hazard = hazardClassifier.classifyHazard(primary.label, top, bottom, centerX, depthMeters)
        val message = hazard.spokenWarning
        speech.language(false)
        val isCritical = hazard.severity == HazardSeverity.CRITICAL || traffic
        if (!alerts.offer(Alert(message, if (traffic) Priority.TRAFFIC else (if (isCritical) Priority.URGENT else Priority.ROUTINE))))
            return
        playCue(FeedbackCue.OBSTACLE, urgent = isCritical)
        obstacleAnnouncements.accepted(primary.label, now, traffic)
        pendingObstacleAlert = false
        lastHazardAt = now
        lastHazardLabel = primary.label
        lastDepthAt = now
    }

    private fun maybeSpeakDistance() {
        if (!foreground || !state.value.scanRequested || !state.value.depthEnabled) return
        val observation = latestObservation ?: return
        val now = SystemClock.elapsedRealtime()
        val primary = obstructionPolicy.select(observation.detections).firstOrNull() ?: return
        if (TrafficObjects.contains(primary.label)) return
        val distance = depthMatcher.estimate(scanner.depth.value.frame, primary, now) ?: return
        if (
            primary.label != lastHazardLabel ||
                lastHazardAt == Long.MIN_VALUE ||
                (lastDepthAt != Long.MIN_VALUE && now - lastDepthAt < 8000)
        )
            return
        val message = ObstacleSpeech.message(primary.label, distance, state.value.stepLength, now)
        if ("Estimated." !in message) return
        speech.language(false)
        if (!alerts.offerRefinement(Alert(message, Priority.URGENT))) return
        lastDepthAt = now
        mutable.update { it.copy(footpath = message) }
    }

    private fun feedback(text: String) {
        pendingFeedback = text
        speakFeedback()
    }

    private fun speakFeedback() {
        val text = pendingFeedback ?: return
        if (!foreground) return
        speech.language(false)
        if (alerts.offer(Alert(text, Priority.ROUTINE))) pendingFeedback = null
    }

    private fun speakCurrentGuidance() {
        val value = guidance.value
        if (pendingFeedback != null || value.status == NavigationStatus.WAITING_FOR_GPS) return
        if (!foreground || value.speechKey.isBlank() || value.speechKey == lastGuidanceKey) return
        speech.language(false)
        if (alerts.offer(Alert(value.message, Priority.ROUTINE))) {
            lastGuidanceKey = value.speechKey
            val type = nav.value.route?.instructions?.getOrNull(value.maneuverIndex)?.type
            val cue =
                when (type) {
                    9,
                    10,
                    11,
                    23 -> FeedbackCue.TURN_RIGHT
                    14,
                    15,
                    16,
                    24 -> FeedbackCue.TURN_LEFT
                    else -> FeedbackCue.CONFIRMED
                }
            pendingTurnCue = value.message to cue
        }
    }

    private fun cancelNavigationRequest() {
        cancelContext()
        requestVersion++
        pendingQuick = null
        pendingRoute = false
        quickJob?.cancel()
        routeJob?.cancel()
        searchJob?.cancel()
        voiceCommands.finishCommand(pendingNearbyVoiceTicket)
        pendingNearbyVoiceTicket = null
        nav.update { it.copy(pendingNavigation = false, routing = false, searching = false) }
    }

    fun open(screen: Screen) {
        if (!returningToPage && screen != state.value.screen) {
            if (screen == Screen.HOME) pageHistory.clear()
            else pageHistory.addLast(state.value.screen)
            while (pageHistory.size > 12) pageHistory.removeFirst()
        }
        clearSurroundings()
        cancelAssistantWork()
        cancelNavigationRequest()
        stopScanning()
        cancelWork()
        navigation.stop()
        navigationSensors.stop()
        stopAudio()
        lastGuidanceKey = ""
        pendingFeedback = null
        navigationCameraEnabled = screen == Screen.NAVIGATION
        mutable.update { it.copy(screen = screen, voiceOpen = false, voice = VoiceState.IDLE) }
        playCue(FeedbackCue.CONFIRMED)
    }

    private fun syncNavigationCamera() {
        if (state.value.screen != Screen.NAVIGATION) return
        val shouldScan =
            NavigationCameraPolicy.shouldScan(
                guidance.value.status,
                foreground,
                nav.value.destinationDialog,
                navigationCameraEnabled,
            )
        if (shouldScan && modelSetup.value.phase == SetupPhase.READY) scan()
        else if (!shouldScan && state.value.scanRequested) stopScanning()
    }

    fun enterNavigation() {
        navigationSensors.start()
        syncNavigationCamera()
    }

    fun enableNavigationLocation() {
        navigationSensors.start()
        processPendingNavigation()
    }

    fun showDestinations() {
        nav.update { it.copy(destinationDialog = true) }
        syncNavigationCamera()
    }

    fun dismissDestinations() {
        nav.update { it.copy(destinationDialog = false) }
        syncNavigationCamera()
    }

    fun updateQuery(query: String) {
        nav.update { it.copy(query = query.take(160)) }
    }

    private fun planningFix(): LocationFix? =
        nav.value.location?.takeIf {
            NavigationLocationPolicy.usableForPlanning(it, SystemClock.elapsedRealtime())
        }

    fun searchDestinations(voice: Boolean = false, autoStart: Boolean = false) {
        val query = nav.value.query
        if (query.trim().length < 3) return
        val voiceTicket = if (voice) voiceCommands.holdDictationWindow() else null
        cancelNavigationRequest()
        navigation.stop()
        syncNavigationCamera()
        alerts.cancelRoutine()
        lastGuidanceKey = ""
        val version = requestVersion
        searchJob = viewModelScope.launch {
            nav.update {
                it.copy(searching = true, message = "Searching…", route = null, selected = null)
            }
            try {
                val near = planningFix()?.let { GeoPoint(it.latitude, it.longitude) }
                places
                    .search(query, near)
                    .onSuccess { results ->
                        if (version != requestVersion) return@onSuccess
                        nav.update {
                            it.copy(
                                places = results,
                                focusedResult = 0,
                                message =
                                    if (results.isEmpty()) "No destinations found"
                                    else "Choose a destination",
                                destinationDialog = true,
                            )
                        }
                        if (autoStart && results.size == 1) {
                            select(results.first())
                            return@onSuccess
                        }
                        if (voice && results.isNotEmpty()) {
                            nav.update { it.copy(selected = results.first()) }
                            feedback(
                                "Found ${shortName(results.first())}. Say Go, Next destination, or choose an option."
                            )
                        } else
                            feedback(
                                if (results.isEmpty()) "No destination found. Try another name."
                                else "${results.size} destinations found."
                            )
                    }
                    .onFailure {
                        if (version == requestVersion) {
                            nav.update { it.copy(message = "Search unavailable") }
                            feedback("Search is unavailable. Please try again.")
                        }
                    }
            } finally {
                voiceCommands.finishCommand(voiceTicket)
                if (version == requestVersion) nav.update { it.copy(searching = false) }
            }
        }
    }

    private fun shortName(destination: Destination) = destination.name.substringBefore(',').take(65)

    fun quickNavigate(kind: QuickDestination) = navigateNearby(kind.category)

    fun navigateNearby(kind: NearbyCategory, start: Boolean = true) {
        if (state.value.screen != Screen.NAVIGATION) open(Screen.NAVIGATION)
        cancelNavigationRequest()
        navigation.stop()
        syncNavigationCamera()
        alerts.cancelRoutine()
        lastGuidanceKey = ""
        pendingNearbyVoiceTicket = voiceCommands.holdDictationWindow()
        pendingNearbyStart = start
        pendingQuick = kind
        nav.update {
            it.copy(
                pendingNavigation = true,
                selected = null,
                route = null,
                destinationDialog = false,
                message = "Finding ${kind.label.lowercase()}…",
            )
        }
        feedback("Finding a nearby ${kind.label.lowercase()}.")
        navigationSensors.start()
        processPendingNavigation()
    }

    private fun processPendingNavigation() {
        if (!foreground || state.value.screen != Screen.NAVIGATION) return
        val fix = planningFix() ?: return
        val quick = pendingQuick
        if (quick != null) {
            pendingQuick = null
            val version = requestVersion
            val voiceTicket = pendingNearbyVoiceTicket
            pendingNearbyVoiceTicket = null
            quickJob = viewModelScope.launch {
                try {
                    nearby
                        .find(quick, GeoPoint(fix.latitude, fix.longitude))
                        .onSuccess { results ->
                            if (version != requestVersion) return@onSuccess
                            if (results.isEmpty()) {
                                nav.update {
                                    it.copy(
                                        pendingNavigation = false,
                                        message = "No nearby ${quick.label.lowercase()}",
                                    )
                                }
                                feedback(
                                    "No nearby ${quick.label.lowercase()} found. Try a destination search."
                                )
                            } else {
                                nav.update {
                                    it.copy(
                                        selected = results.first(),
                                        places = results,
                                        pendingNavigation = pendingNearbyStart,
                                        destinationDialog = !pendingNearbyStart,
                                    )
                                }
                                if (pendingNearbyStart) requestRoute()
                                else
                                    feedback(
                                        "Nearest ${quick.label.lowercase()}: ${shortName(results.first())}. Say Go to navigate."
                                    )
                            }
                        }
                        .onFailure {
                            if (version == requestVersion) {
                                nav.update {
                                    it.copy(
                                        pendingNavigation = false,
                                        message = "Nearby search unavailable",
                                    )
                                }
                                feedback("Nearby search is unavailable. Try destination search.")
                            }
                        }
                } finally {
                    voiceCommands.finishCommand(voiceTicket)
                }
            }
        } else if (pendingRoute && !nav.value.routing) requestRoute()
    }

    fun select(destination: Destination) {
        cancelNavigationRequest()
        navigation.stop()
        syncNavigationCamera()
        alerts.cancelRoutine()
        nav.update {
            it.copy(
                selected = destination,
                route = null,
                destinationDialog = false,
                pendingNavigation = true,
            )
        }
        requestRoute()
    }

    private fun requestRoute() {
        val destination = nav.value.selected ?: return
        if (nav.value.routing) return
        val fix = planningFix()
        if (fix == null) {
            pendingRoute = true
            nav.update { it.copy(pendingNavigation = true, message = "Locating…") }
            navigationSensors.start()
            return
        }
        pendingRoute = false
        val version = requestVersion
        routeJob = viewModelScope.launch {
            nav.update {
                it.copy(routing = true, pendingNavigation = true, message = "Finding route…")
            }
            try {
                routes
                    .calculateRoute(destination, fix)
                    .onSuccess { route ->
                        if (version != requestVersion) return@onSuccess
                        nav.update {
                            it.copy(
                                route = route,
                                routing = false,
                                pendingNavigation = false,
                                message = shortName(destination),
                            )
                        }
                        lastGuidanceKey = ""
                        navigation.start(route)
                        val current = nav.value.location ?: fix
                        navigation.updateLocation(current)
                        navigationCameraEnabled = true
                        syncNavigationCamera()
                        feedback("Route: ${navigation.routeOverview()}")
                    }
                    .onFailure {
                        if (version == requestVersion) {
                            nav.update {
                                it.copy(pendingNavigation = false, message = "Route unavailable")
                            }
                            feedback("Couldn't find a walking route. Try another destination.")
                        }
                    }
            } finally {
                if (version == requestVersion) nav.update { it.copy(routing = false) }
            }
        }
    }

    fun startNavigation() {
        if (nav.value.pendingNavigation || nav.value.routing) {
            feedback("Finding your route.")
            return
        }
        if (nav.value.selected == null) {
            showDestinations()
            feedback(
                "Where would you like to go? Say Park, Market, Bus stop, Pharmacy, or a place name."
            )
            return
        }
        nav.update { it.copy(pendingNavigation = true, destinationDialog = false) }
        requestRoute()
    }

    fun stopNavigation() {
        cancelNavigationRequest()
        navigation.stop()
        syncNavigationCamera()
        lastGuidanceKey = ""
        pendingFeedback = null
        alerts.cancelRoutine()
        nav.update { it.copy(message = "Navigation stopped") }
        feedback("Navigation stopped.")
    }

    fun replayNavigation(routeOnly: Boolean = false) {
        if (
            !routeOnly &&
                nav.value.contextText != null &&
                SystemClock.elapsedRealtime() < contextExpires
        ) {
            replayContext()
            return
        }
        alerts.cancelRoutine()
        feedback(
            if (nav.value.route != null || guidance.value.speechKey.isNotBlank())
                guidance.value.message
            else "Choose a destination"
        )
    }

    fun mapPanned() {
        nav.update { it.copy(followUser = false) }
    }

    fun centerMap() {
        if (state.value.screen != Screen.NAVIGATION) open(Screen.NAVIGATION)
        navigationSensors.start()
        nav.update { it.copy(focusVersion = it.focusVersion + 1, followUser = true) }
        feedback(
            if (nav.value.location != null || nav.value.route != null) "Map centered."
            else "Locating…"
        )
    }

    fun navigationVoiceHelp() {
        voice()
    }

    private fun nextVoiceDestination() {
        if (nav.value.places.isEmpty()) {
            feedback("Say Navigate to a place name first.")
            return
        }
        val index = (nav.value.focusedResult + 1) % nav.value.places.size
        val destination = nav.value.places[index]
        nav.update { it.copy(focusedResult = index, selected = destination) }
        feedback("${shortName(destination)}. Say Go to start, or Next destination.")
    }

    fun saveGateway(url: String, access: String, consent: Boolean) {
        if (!gateway.debug) return
        if (!GatewayPreferences.validGatewayUrl(url, gateway.debug)) {
            nav.update { it.copy(gatewayMessage = "Use HTTPS, or debug loopback HTTP.") }
            return
        }
        val token = access.trim()
        if (token.length > 512 || token.any { it.code !in 33..126 }) {
            nav.update { it.copy(gatewayMessage = "Use a single-line gateway access token.") }
            return
        }
        cancelContext()
        gateway.url = url
        gateway.access = token
        gateway.consent = consent
        nav.update {
            it.copy(
                gatewayUrl = gateway.url,
                gatewayAccess = token,
                cloudConsent = consent,
                gatewayMessage = "Netra Saathi Cloud AI saved.",
            )
        }
    }

    fun setCloudImageConsent(consent: Boolean) {
        if (!consent) cancelContext()
        gateway.consent = consent
        nav.update { it.copy(cloudConsent = consent) }
    }

    fun requestNavigationContext() {
        requestVisualQuestion(
            "Explain what matters in this camera view for the upcoming mapped maneuver."
        )
    }

    fun requestVisualQuestion(question: String) {
        if (state.value.screen == Screen.SCENE && surroundingsSession.hasViews) {
            askSurroundings(question)
            return
        }
        mutable.update { it.copy(voiceOpen = true) }
        pendingImageQuestion = question.take(500)
        if (!gateway.consent) {
            nav.update { it.copy(contextConsent = true) }
            feedback("May I send one camera image to Meta? Say Send image or Cancel image.")
            return
        }
        beginVisualQuestion()
    }

    fun dismissContextConsent() {
        pendingImageQuestion = null
        nav.update { it.copy(contextConsent = false) }
        assistant.update { it.copy(phase = AssistantPhase.IDLE) }
    }

    fun confirmContextConsent() {
        if (!nav.value.contextConsent) return
        gateway.consent = true
        nav.update { it.copy(contextConsent = false, cloudConsent = true) }
        beginVisualQuestion()
    }

    private fun maneuverForContext(): Int? =
        guidance.value.takeIf { it.status == NavigationStatus.ACTIVE }?.maneuverIndex

    private fun beginVisualQuestion() {
        val question = pendingImageQuestion ?: return
        pendingImageQuestion = null
        if (
            !state.value.scanRequested ||
                state.value.screen !in setOf(Screen.NAVIGATION, Screen.FOOTPATH, Screen.SCENE)
        )
            scan(preserveScene = state.value.screen == Screen.SCENE)
        if (!state.value.scanRequested) {
            showAssistantError("Camera models are not ready.")
            return
        }
        mutable.update { it.copy(voiceOpen = true) }
        askMuse(question, withImage = true)
    }

    private fun speakContext() {
        val pending = pendingContextSpeech ?: return
        if (!foreground || SystemClock.elapsedRealtime() > pending.first) {
            pendingContextSpeech = null
            return
        }
        speech.language(false)
        if (alerts.offer(Alert(pending.second, Priority.SCENE))) pendingContextSpeech = null
    }

    fun replayContext() {
        nav.value.contextText
            ?.takeIf { SystemClock.elapsedRealtime() < contextExpires }
            ?.let {
                pendingContextSpeech = contextExpires to it
                speakContext()
            }
    }

    private fun cancelContext() {
        contextJob?.cancel()
        contextJob = null
        pendingContextSpeech = null
        contextExpires = 0
        nav.update { it.copy(contextBusy = false, contextConsent = false, contextText = null) }
    }

    fun saveWalkingServices(search: String, routing: String, tiles: String) {
        if (
            !WalkingServices.validUrl(search) ||
                !WalkingServices.validUrl(routing) ||
                !WalkingServices.validUrl(tiles) ||
                !listOf("{z}", "{x}", "{y}").all { it in tiles }
        ) {
            nav.update {
                it.copy(serviceMessage = "Use valid HTTPS URLs; tile URL needs {z}, {x}, {y}.")
            }
            return
        }
        services.search = search
        services.routing = routing
        services.tiles = tiles
        nav.update {
            it.copy(
                searchUrl = search,
                routingUrl = routing,
                tilesUrl = tiles,
                serviceMessage = "Walking services saved.",
                route = null,
            )
        }
    }

    fun permissionBatchRequested() = welcomeFlow.permissionsAsked

    fun beginWelcome() {
        alerts.stop()
        pendingFeedback = null
        welcomeFlow.begin()
    }

    fun claimWelcomePermissions() = welcomeFlow.claimPermissionRequest()

    fun welcomePermissionsFinished() {
        preferences.edit().putBoolean("microphoneAsked", true).apply()
        val denied = buildList {
            fun granted(permission: String) =
                ContextCompat.checkSelfPermission(getApplication(), permission) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted(Manifest.permission.CAMERA)) add("Camera")
            if (!granted(Manifest.permission.RECORD_AUDIO)) add("Microphone")
            if (
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            )
                add("Location")
        }
        welcomeFlow.permissionsFinished(denied)
        models.prepare()
        val setup = modelSetup.value
        welcomeFlow.preparation(
            setup.percentage,
            setup.phase == SetupPhase.READY,
            setup.phase == SetupPhase.ERROR,
        )
        announceWelcomeStage()
    }

    private fun announceWelcomeStage() {
        val stage = welcome.value.stage
        if (!foreground || stage == welcomeAnnounced) return
        val text =
            when (stage) {
                WelcomeStage.WELCOME ->
                    "Welcome to Netra Saathi. Tap Get started to allow permissions and prepare the app."
                WelcomeStage.TOUR_OFFER ->
                    "Ready. Would you like a spoken tour? Tap Take the tour, or Skip."
                else -> return
            }
        welcomeAnnounced = stage
        feedback(text)
    }

    fun startTour() {
        if (welcome.value.stage !in setOf(WelcomeStage.TOUR_OFFER, WelcomeStage.COMPLETE)) return
        open(Screen.HOME)
        welcomeFlow.startTour()
        resumeHandsFreeIfReady()
        speakTour()
    }

    private fun stopTourSpeech() {
        tourAdvance?.cancel()
        tourAdvance = null
        tourUtterance = null
        pendingFeedback = null
        alerts.stop()
        feedbackCues.stop()
    }

    private fun speakTour() {
        if (!foreground || welcome.value.stage != WelcomeStage.TOUR) return
        val text = WelcomeFlow.lessons[welcome.value.step].text
        tourUtterance = text
        speech.language(false)
        if (!alerts.offer(Alert(text, Priority.SCENE))) {
            tourUtterance = null
            welcomeFlow.pause()
        }
    }

    fun pauseTour() {
        welcomeFlow.pause()
        stopTourSpeech()
        voiceActivation.rememberSpeech("", SystemClock.elapsedRealtime())
        resumeHandsFreeIfReady()
    }

    fun resumeTour() {
        if (welcome.value.stage == WelcomeStage.TOUR) {
            stopTourSpeech()
            welcomeFlow.resume()
            speakTour()
        }
    }

    fun repeatTour() {
        if (welcome.value.stage == WelcomeStage.TOUR) {
            stopTourSpeech()
            speakTour()
        }
    }

    fun nextTour() {
        if (welcome.value.stage != WelcomeStage.TOUR) return
        stopTourSpeech()
        welcomeFlow.next()
        if (welcome.value.stage == WelcomeStage.COMPLETE) completeWelcome()
        else if (!welcome.value.paused) speakTour()
    }

    fun finishWelcome() {
        stopTourSpeech()
        welcomeFlow.finish()
        if (welcome.value.stage == WelcomeStage.COMPLETE) completeWelcome()
    }

    private fun completeWelcome() {
        open(Screen.HOME)
        // Tour narration may contain the wake phrase; no microphone was running to hear it.
        voiceActivation.rememberSpeech("", SystemClock.elapsedRealtime())
        resumeHandsFreeIfReady()
        if (!hasGreetedBlindUser) {
            hasGreetedBlindUser = true
            viewModelScope.launch {
                delay(800)
                announce(
                    "Netra Saathi is ready. Hold Volume Up for two seconds to speak, or triple click for emergency help. What would you like to do?",
                    Priority.URGENT
                )
                delay(5000)
                if (foreground && !state.value.voiceOpen) {
                    voice()
                }
            }
        }
    }

    fun retryModelSetup() {
        models.prepare()
    }

    private fun resumeHandsFreeIfReady() {
        if (
            foreground &&
                welcome.value.stage in
                    setOf(WelcomeStage.COMPLETE, WelcomeStage.TOUR_OFFER, WelcomeStage.TOUR) &&
                state.value.handsFree &&
                modelSetup.value.phase == SetupPhase.READY &&
                ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
        )
            voiceCommands.start()
    }

    /** Request once on first setup; denial always leaves manual controls available. */
    fun requestAutomaticMicrophonePermission(): Boolean {
        if (permissionBatchRequested()) return false
        if (
            ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            resumeHandsFreeIfReady()
            return false
        }
        if (preferences.getBoolean("microphoneAsked", false)) return false
        preferences.edit().putBoolean("microphoneAsked", true).apply()
        return true
    }

    fun scan(preserveScene: Boolean = false) {
        if (modelSetup.value.phase != SetupPhase.READY) {
            mutable.update {
                it.copy(
                    footpath = "Offline models are not ready. Complete or retry model setup first."
                )
            }
            return
        }
        if (state.value.scanRequested) return
        val onNavigation = state.value.screen == Screen.NAVIGATION
        if (onNavigation) navigationCameraEnabled = true
        if (!onNavigation && state.value.screen != Screen.FOOTPATH) {
            cancelWork()
            navigation.stop()
        }
        evidence.reset()
        temporal.reset()
        depthMatcher.reset()
        obstacleAnnouncements.reset()
        pendingObstacleAlert = false
        tiltPrompts.pause()
        lastObservation = Long.MIN_VALUE
        latestObservation = null
        lastHazardAt = Long.MIN_VALUE
        lastDepthAt = Long.MIN_VALUE
        lastHazardLabel = null
        mutable.update {
            it.copy(
                screen =
                    if (onNavigation) Screen.NAVIGATION
                    else if (preserveScene && it.screen == Screen.SCENE) Screen.SCENE
                    else Screen.FOOTPATH,
                voiceOpen = if (onNavigation) it.voiceOpen else false,
                scanRequested = true,
                detections = emptyList(),
                indicator = FootpathIndicator.UNKNOWN,
                footpath = "Preparing camera…",
            )
        }
        scanner.captureOnly = preserveScene && state.value.screen == Screen.SCENE
        scanner.start()
        spatialSonar.start()
    }

    fun stopScanning(announceStop: Boolean = false) {
        if (surroundingsJob?.isActive == true) {
            surroundingsJob?.cancel()
            surroundingsJob = null
            surroundingsSession.clear()
        }
        cancelContext()
        if (announceStop && state.value.screen == Screen.NAVIGATION) navigationCameraEnabled = false
        val wasActive = state.value.scanRequested
        latestObservation = null
        alerts.stop()
        scanner.stop()
        scanner.captureOnly = false
        spatialSonar.pause()
        tiltProvider.stop()
        evidence.reset()
        temporal.reset()
        depthMatcher.reset()
        obstacleAnnouncements.reset()
        pendingObstacleAlert = false
        tiltPrompts.pause()
        lastObservation = Long.MIN_VALUE
        mutable.update {
            it.copy(
                scanRequested = false,
                detections = emptyList(),
                indicator = FootpathIndicator.UNKNOWN,
                footpath = "Scanning stopped. Path safety unknown.",
            )
        }
        if (announceStop && wasActive) announce("Scanning stopped.")
    }

    fun attachCamera(view: PreviewView, owner: LifecycleOwner) {
        scanner.attach(view, owner)
        if (state.value.screen == Screen.NAVIGATION) lastGuidanceKey = ""
        tiltProvider.start { view.display?.rotation ?: 0 }
    }

    fun detachCamera() {
        scanner.detach()
        tiltProvider.stop()
    }

    fun cameraDenied() {
        stopScanning()
        if (state.value.screen == Screen.SCENE)
            surroundingsSession.fail(
                "Camera access is required. Enable Camera in Android settings, then retry."
            )
        lastGuidanceKey = ""
        mutable.update {
            it.copy(
                footpath =
                    "Camera permission is required. Tap Start Scanning to retry, or enable Camera in Android settings."
            )
        }
    }

    fun voice() {
        cancelAssistant()
        voiceCommands.openDictationWindow()
        mutable.update { it.copy(voiceOpen = true, message = "", voice = VoiceState.LISTENING) }
        if (assistant.value.phase != AssistantPhase.THINKING)
            assistant.update { it.copy(phase = AssistantPhase.LISTENING, expectsFollowup = false) }
        playCue(FeedbackCue.VOICE_TRIGGER)
        startMicrophoneIfReady()
    }

    fun wakeMuse() {
        pendingFeedback = null
        alerts.cancelRoutine()
        voice()
        voiceActivation.arm(SystemClock.elapsedRealtime())
        assistant.update { it.copy(phase = AssistantPhase.LISTENING, expectsFollowup = true) }
        playCue(FeedbackCue.LISTENING)
    }

    fun startVoiceCommands() {
        preferences
            .edit()
            .putBoolean("handsFree", true)
            .putBoolean("handsFreeExplicitChoice", true)
            .apply()
        mutable.update { it.copy(microphoneDenied = false, handsFree = true) }
        resumeHandsFreeIfReady()
    }

    private fun startMicrophoneIfReady() {
        if (
            foreground &&
                modelSetup.value.phase == SetupPhase.READY &&
                ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
        )
            voiceCommands.start()
    }

    fun stopVoiceCommands() {
        cancelSpeechFallback()
        preferences
            .edit()
            .putBoolean("handsFree", false)
            .putBoolean("handsFreeExplicitChoice", true)
            .apply()
        mutable.update { it.copy(handsFree = false) }
        voiceCommands.stop()
    }

    /** Pause capture without changing the user's setting (lifecycle and instrumentation). */
    fun pauseVoiceCapture() {
        cancelSpeechFallback()
        voiceCommands.stop()
    }

    fun resumeVoiceCapture() = resumeHandsFreeIfReady()

    fun handsFree(enabled: Boolean) {
        if (enabled) startVoiceCommands() else stopVoiceCommands()
    }

    fun audioFallback(enabled: Boolean) {
        gateway.audioConsent = enabled
        mutable.update { it.copy(audioFallback = enabled) }
        if (!enabled) cancelSpeechFallback()
    }

    private fun cancelSpeechFallback() {
        audioEpoch++
        voiceJob?.cancel()
        voiceJob = null
        mutable.update {
            if (it.voice == VoiceState.PROCESSING) it.copy(voice = VoiceState.IDLE, message = "")
            else it
        }
    }

    private fun speechFailure(message: String) {
        mutable.update { it.copy(voice = VoiceState.FAILURE, message = message, voiceOpen = true) }
        val now = SystemClock.elapsedRealtime()
        if (lastSpeechFailureAt == Long.MIN_VALUE || now - lastSpeechFailureAt >= 10000) {
            lastSpeechFailureAt = now
            feedback(message)
        }
    }

    private fun handleRecognizedSpeech(event: RecognizedCommand) {
        val pcm = event.pcm
        if (!foreground || !voiceCommands.authorized(event.activationId)) {
            pcm?.fill(0)
            return
        }
        val command = commands.interpret(event.transcript)
        val bareWake =
            AppSpeechPhrases.wakePhrases.any {
                CommandInterpreter().normalize(event.transcript) == it
            }
        cancelSpeechFallback()
        val afterWake =
            AppSpeechPhrases.wakePattern
                .replaceFirst(CommandInterpreter().normalize(event.transcript), "")
                .trim()
        val stop = commands.interpret(afterWake)
        if (
            stop in
                setOf(
                    Command.STOP,
                    Command.STOP_SCANNING,
                    Command.STOP_NAVIGATION,
                    Command.STOP_LISTENING,
                )
        ) {
            pcm?.fill(0)
            cancelAssistant()
            executeCommand(stop, afterWake)
            voiceCommands.finishCommand(event.activationId)
            return
        }
        if (
            event.wakeOnly ||
                bareWake ||
                pcm == null ||
                !gateway.audioConsent ||
                VoiceControls.match(afterWake, voiceControlActions().map { it.first }) != null ||
                !SpeechFallbackPolicy.needsAudio(
                    DecodedSpeech(event.transcript, event.confidence),
                    command,
                )
        ) {
            pcm?.fill(0)
            mutable.update { it.copy(message = "") }
            try {
                handleVoice(event.transcript, event.wakeOnly)
            } finally {
                voiceCommands.finishCommand(event.activationId)
            }
            return
        }
        if (!audioTranscriber.online()) {
            pcm.fill(0)
            voiceCommands.finishCommand(event.activationId)
            speechFailure("No internet. Please repeat or use the controls.")
            return
        }
        val epoch = audioEpoch
        mutable.update {
            it.copy(
                voice = VoiceState.PROCESSING,
                message = "Recognizing speech online…",
                voiceOpen = true,
            )
        }
        voiceJob = viewModelScope.launch {
            try {
                val text = audioTranscriber.transcribe(pcm).getOrThrow()
                if (
                    !foreground ||
                        epoch != audioEpoch ||
                        !voiceCommands.isCurrent(event.session) ||
                        !voiceCommands.authorized(event.activationId) ||
                        !gateway.audioConsent
                )
                    return@launch
                mutable.update { it.copy(voice = VoiceState.SUCCESS, message = "") }
                val intent = commands.interpret(text)
                if (activateVoiceControl(text)) {
                    // Local button controls take precedence over a place-search fallback.
                } else if (
                    intent == Command.UNKNOWN &&
                        state.value.screen == Screen.NAVIGATION &&
                        nav.value.destinationDialog &&
                        !Regex("(?i)^(where|what|how|why|can|could|please|help|hey)\\b")
                            .containsMatchIn(text.trim())
                ) {
                    updateQuery(text)
                    searchDestinations(voice = true)
                } else if (
                    intent == Command.UNKNOWN &&
                        !AppSpeechPhrases.hasWake(text) &&
                        !voiceActivation.awaiting(SystemClock.elapsedRealtime())
                ) {
                    askMuse(text)
                } else handleVoice(text, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (foreground && epoch == audioEpoch)
                    speechFailure(e.message ?: "Speech was not understood. Please repeat.")
            } finally {
                pcm.fill(0)
                voiceCommands.finishCommand(event.activationId)
                if (epoch == audioEpoch) voiceJob = null
            }
        }
    }

    fun microphoneDenied() {
        mutable.update {
            it.copy(
                microphoneDenied = true,
                message =
                    "Microphone permission denied. Enable it in Android settings or use the scan button.",
            )
        }
    }

    fun dismissVoice() {
        cancelAssistant()
        if (!state.value.handsFree) voiceCommands.stop()
        mutable.update { it.copy(voiceOpen = false) }
    }

    fun describe() {
        if (surroundingsState.value.phase == SurroundingsPhase.CAPTURING) {
            finishSurroundingsRecording()
            return
        }
        if (
            surroundingsState.value.phase in
                setOf(SurroundingsPhase.PREPARING, SurroundingsPhase.EXTRACTING)
        )
            return
        if (state.value.screen != Screen.SCENE) open(Screen.SCENE)
        if (
            surroundingsState.value.phase == SurroundingsPhase.ERROR && surroundingsSession.hasViews
        ) {
            askSurroundings(INITIAL_SURROUNDINGS_QUESTION, true)
            return
        }
        cancelAssistant()
        surroundingsSession.requestConsent()
        val disclosure =
            "Record a video of your surroundings. Twelve pictures will go to Meta. Say Start recording, or Cancel capture."
        sceneReplyForListening = disclosure
        feedback(disclosure)
    }

    fun confirmSurroundingsCapture() {
        if (surroundingsState.value.phase != SurroundingsPhase.CONSENT) return
        cancelAssistantWork()
        pendingFeedback = null
        alerts.cancelRoutine()
        mutable.update { it.copy(voiceOpen = false, hasSceneDescription = false) }
        val previous = surroundingsJob
        previous?.cancel()
        surroundingsJob = null
        sceneVideo.cancel()
        stopScanning()
        surroundingsSession.clear()
        surroundingsSession.preparing()
        surroundingsJob = viewModelScope.launch {
            var recorded: com.librevision.data.scene.RecordedSceneVideo? = null
            try {
                previous?.cancelAndJoin()
                recorded = sceneVideo.record()
                surroundingsSession.extracting()
                val selected = videoFrames.extract(recorded.file, recorded.endedAtMs)
                surroundingsSession.useVideo(selected)
                pendingFeedback = null
                surroundingsJob = null
                playCue(FeedbackCue.COMPLETE)
                askSurroundings(INITIAL_SURROUNDINGS_QUESTION, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                surroundingsSession.fail(e.message ?: "Recording failed. Try again.")
                playCue(FeedbackCue.UNAVAILABLE)
                feedback(e.message ?: "Recording failed. Try again.")
            } finally {
                recorded?.file?.delete()
                if (surroundingsJob === currentCoroutineContext().job) surroundingsJob = null
            }
        }
    }

    fun finishSurroundingsRecording() = sceneVideo.finish()

    fun attachSceneVideo(view: PreviewView, owner: LifecycleOwner) = sceneVideo.attach(view, owner)

    fun detachSceneVideo() = sceneVideo.detach()

    fun sceneVideoDenied() {
        cancelSurroundingsCapture()
        surroundingsSession.fail(
            "Camera access is required. Enable Camera in Android settings and try again."
        )
    }

    fun cancelSurroundingsCapture() {
        pendingFeedback = null
        surroundingsJob?.cancel()
        surroundingsJob = null
        sceneVideo.cancel()
        surroundingsSession.clear()
        stopScanning()
        cancelAssistant()
    }

    fun dismissSurroundingsConsent() {
        surroundingsSession.cancelConsent()
        cancelAssistant()
        pendingFeedback = null
        alerts.cancelRoutine()
    }

    fun cancelSceneQuestion() {
        cancelAssistant()
        surroundingsSession.fail("Description cancelled. You can retry using the saved views.")
    }

    fun describeCurrentView() {
        clearSurroundings()
        requestVisualQuestion(
            "Describe only the useful things for a visually impaired person in this single current view. Be very concise."
        )
    }

    private fun clearSurroundings() {
        surroundingsJob?.cancel()
        surroundingsJob = null
        sceneVideo.cancel()
        surroundingsSession.clear()
        sceneReplyForListening = null
        mutable.update { it.copy(hasSceneDescription = false, scene = UiState().scene) }
    }

    private fun sceneQuestion(query: String): Boolean =
        state.value.screen == Screen.SCENE &&
            surroundingsSession.hasViews &&
            !Regex(
                    "(?i)\\b(?:navigate|navigation|route|go to|take me|nearest (?:hospital|pharmacy|clinic|park|market|bus stop|restaurant)|settings|text size|language|verbosity|audio cues|start scanning)\\b"
                )
                .containsMatchIn(query)

    private fun askSurroundings(question: String, initial: Boolean = false) {
        if (!foreground || !surroundingsSession.hasViews) return
        val ticket = voiceCommands.holdDictationWindow()
        assistantJob?.cancel()
        val epoch = ++assistantEpoch
        voiceActivation.clear()
        sceneReplyForListening = null
        assistant.value = AssistantUiState(AssistantPhase.THINKING, question)
        assistantJob = viewModelScope.launch {
            try {
                val answer = surroundingsSession.ask(question, initial).getOrThrow()
                if (!foreground || epoch != assistantEpoch || state.value.screen != Screen.SCENE)
                    return@launch
                assistant.value = AssistantUiState(AssistantPhase.ANSWER, question, answer)
                mutable.update {
                    it.copy(
                        scene = answer,
                        hasSceneDescription = true,
                        voice = VoiceState.SUCCESS,
                        voiceOpen = false,
                    )
                }
                voiceCommands.closeDictationWindow()
                sceneReplyForListening =
                    answer.replace(Regex("(?i)hey\\s+(?:there\\s+)?vision"), "the wake phrase")
                pendingAssistantSpeech = (SystemClock.elapsedRealtime() + 30000) to answer
                speakAssistant()
                if (!state.value.speechAvailable) openSceneFollowup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == assistantEpoch) {
                    showAssistantError(e.message ?: "Muse is unavailable. Try again.")
                    playCue(FeedbackCue.UNAVAILABLE)
                }
            } finally {
                voiceCommands.finishCommand(ticket)
            }
        }
    }

    private fun openSceneFollowup() {
        sceneReplyForListening = null
        if (
            !foreground ||
                state.value.screen != Screen.SCENE ||
                (!surroundingsSession.hasViews &&
                    surroundingsState.value.phase != SurroundingsPhase.CONSENT)
        )
            return
        voiceCommands.openDictationWindow()
        startMicrophoneIfReady()
        assistant.update { it.copy(expectsFollowup = true) }
        playCue(FeedbackCue.LISTENING)
    }

    private fun speechFinished() {
        val completed = alerts.currentAlert()
        alerts.finished()
        if (tourUtterance != null && completed?.text == tourUtterance) {
            tourUtterance = null
            if (!state.value.speechAvailable) welcomeFlow.pause()
            if (welcome.value.stage == WelcomeStage.TOUR && !welcome.value.paused) {
                val cue = WelcomeFlow.lessons[welcome.value.step].cue
                cue?.let { feedbackCues.play(it) }
                tourAdvance = viewModelScope.launch {
                    delay(if (cue != null) 1000 else 700)
                    nextTour()
                }
            }
            voiceCommands.openDictationWindow()
            voiceActivation.arm(SystemClock.elapsedRealtime())
            return
        }
        if (sceneReplyForListening != null && completed?.text == sceneReplyForListening)
            openSceneFollowup()
        if (assistantReplyForListening != null && completed?.text == assistantReplyForListening) {
            assistantReplyForListening = null
            voiceActivation.arm(SystemClock.elapsedRealtime())
            voiceCommands.openDictationWindow()
            assistant.update { it.copy(expectsFollowup = true) }
        }
        pendingTurnCue?.let { (text, cue) -> if (completed?.text == text) playCue(cue) }
        pendingTurnCue = null
    }

    private fun updatePlaybackGate() {
        voiceCommands.suppressRecognitionForPlayback(speechPlaying || cuePlaying)
    }

    private fun playCue(cue: FeedbackCue, urgent: Boolean = false) {
        if (!urgent && alerts.currentAlert()?.priority?.let { it >= Priority.URGENT } == true)
            return
        feedbackCues.play(cue, withSound = !alerts.isSpeaking())
    }

    fun feedbackSettings(
        sound: Boolean = state.value.soundCues,
        haptics: Boolean = state.value.haptics,
    ) {
        feedbackCues.soundEnabled = sound
        feedbackCues.hapticsEnabled = haptics
        mutable.update { it.copy(soundCues = sound, haptics = haptics) }
        preferences.edit().putBoolean("soundCues", sound).putBoolean("haptics", haptics).apply()
    }

    companion object {
        private const val INITIAL_SURROUNDINGS_QUESTION =
            "Give a concise initial overview of the most useful things in these surroundings for a visually impaired person. Prioritize relevant obstructions, footpath layout, traffic and useful landmarks. Omit small irrelevant details."
    }

    fun replayScene() {
        if (state.value.hasSceneDescription) {
            if (surroundingsSession.hasViews) {
                voiceCommands.closeDictationWindow()
                sceneReplyForListening =
                    state.value.scene.replace(
                        Regex("(?i)hey\\s+(?:there\\s+)?vision"),
                        "the wake phrase",
                    )
            }
            pendingAssistantSpeech = (SystemClock.elapsedRealtime() + 20000) to state.value.scene
            assistantRouteTicket = null
            speakAssistant()
        }
    }

    fun repeatFootpath() {
        val message =
            when (state.value.indicator) {
                FootpathIndicator.OBSTRUCTION -> state.value.footpath
                FootpathIndicator.NO_OBSTRUCTION ->
                    "No obstruction reported. Path safety is not verified."
                FootpathIndicator.UNKNOWN -> "The current view is uncertain."
            }
        announce(
            message,
            if (state.value.indicator == FootpathIndicator.OBSTRUCTION) Priority.URGENT
            else Priority.ROUTINE,
        )
    }

    fun submitAssistantText(text: String) {
        if (commands.interpret(text) != Command.UNKNOWN) {
            voiceActivation.clear()
            handleVoice(text, false)
        } else askMuse(text)
    }

    fun transcript(text: String) {
        handleVoice(text, false)
    }

    private fun handleVoice(text: String, wakeOnly: Boolean) {
        if (!foreground || text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (!wakeOnly && !AppSpeechPhrases.hasWake(text) && activateVoiceControl(text)) return
        if (
            !wakeOnly &&
                commands.interpret(text) in
                    setOf(
                        Command.STOP,
                        Command.STOP_SCANNING,
                        Command.STOP_NAVIGATION,
                        Command.STOP_LISTENING,
                    )
        ) {
            voiceActivation.clear()
            cancelAssistant()
            executeCommand(commands.interpret(text), text)
            return
        }
        if (!wakeOnly && surroundingsState.value.phase == SurroundingsPhase.CONSENT) {
            when (commands.interpret(text)) {
                Command.START_SCENE_RECORDING,
                Command.CONFIRM_IMAGE_UPLOAD -> {
                    confirmSurroundingsCapture()
                    return
                }
                Command.CANCEL_IMAGE_UPLOAD -> {
                    dismissSurroundingsConsent()
                    return
                }
                else -> Unit
            }
        }
        if (!wakeOnly && nav.value.contextConsent) {
            val consentCommand = commands.interpret(text)
            if (consentCommand == Command.CONFIRM_IMAGE_UPLOAD) {
                confirmContextConsent()
                return
            }
            if (consentCommand == Command.CANCEL_IMAGE_UPLOAD) {
                dismissContextConsent()
                return
            }
        }
        // Follow-up controls such as Go and Next destination stay deterministic, including after
        // wake.
        val localCommand = commands.interpret(text)
        if (!wakeOnly && !AppSpeechPhrases.hasWake(text) && localCommand != Command.UNKNOWN) {
            voiceActivation.clear()
            executeCommand(localCommand, text)
            return
        }
        if (
            !wakeOnly &&
                !AppSpeechPhrases.hasWake(text) &&
                localCommand == Command.UNKNOWN &&
                sceneQuestion(text)
        ) {
            askSurroundings(text)
            return
        }
        when (val decision = voiceActivation.decide(text, now, wakeOnly)) {
            MuseVoiceDecision.Ignore -> return
            MuseVoiceDecision.Wake -> {
                wakeMuse()
                return
            }
            is MuseVoiceDecision.Ask -> {
                val local = commands.interpret(decision.question)
                if (!activateVoiceControl(decision.question)) {
                    if (local != Command.UNKNOWN) executeCommand(local, decision.question)
                    else {
                        wakeMuse()
                        askMuse(decision.question)
                    }
                }
                return
            }
            MuseVoiceDecision.Local -> Unit
        }
        val command = commands.interpret(text)
        if (command == Command.UNKNOWN) {
            mutable.update { it.copy(voiceOpen = true) }
            askMuse(text)
            return
        }
        mutable.update {
            it.copy(
                voice = if (command == Command.UNKNOWN) VoiceState.FAILURE else VoiceState.SUCCESS
            )
        }
        executeCommand(command, text)
    }

    private val pageHistory = ArrayDeque<Screen>()
    private var returningToPage = false

    /** One back action for visible controls, Android Back and speech. Close the top layer first. */
    fun back() {
        when {
            welcome.value.stage == WelcomeStage.TOUR -> pauseTour()
            nav.value.contextConsent -> dismissContextConsent()
            surroundingsState.value.phase == SurroundingsPhase.CONSENT ->
                dismissSurroundingsConsent()
            state.value.voiceOpen -> dismissVoice()
            nav.value.destinationDialog -> dismissDestinations()
            else -> {
                val previous = pageHistory.removeLastOrNull() ?: Screen.HOME
                returningToPage = true
                try {
                    open(previous)
                } finally {
                    returningToPage = false
                }
                feedback(if (previous == Screen.HOME) "Home." else "${previous.name.lowercase()}.")
            }
        }
    }

    private fun voiceControlActions(): List<Pair<VoiceControl, () -> Unit>> = buildList {
        fun key(id: String, label: String, vararg aliases: String, action: () -> Unit) {
            add(VoiceControl(id, label, aliases.toSet()) to action)
        }
        key("back", "Back", "go back", "close", "close dialog", action = ::back)
        if (nav.value.contextConsent) {
            key("send_image", "Send image", action = ::confirmContextConsent)
            key("cancel", "Cancel", action = ::dismissContextConsent)
            return@buildList
        }
        if (surroundingsState.value.phase == SurroundingsPhase.CONSENT) {
            key("record", "Start recording", action = ::confirmSurroundingsCapture)
            key("cancel", "Cancel", action = ::dismissSurroundingsConsent)
            return@buildList
        }
        if (welcome.value.stage == WelcomeStage.TOUR) {
            key("next", "Next", "next lesson", action = ::nextTour)
            key("repeat", "Repeat", action = ::repeatTour)
            if (welcome.value.paused) key("resume", "Resume", action = ::resumeTour)
            else key("pause", "Pause", action = ::pauseTour)
            key("skip_tour", "Skip tour", "skip", action = ::finishWelcome)
            return@buildList
        }
        if (welcome.value.stage == WelcomeStage.TOUR_OFFER) {
            key("start_tour", "Take a tour", "start tour", action = ::startTour)
            key("skip_tour", "Skip tour", "skip", action = ::finishWelcome)
            return@buildList
        }
        if (state.value.voiceOpen) {
            key("assistant_speak", "Speak", "try again", action = ::wakeMuse)
            if (assistant.value.answer.isNotBlank())
                key("assistant_replay", "Replay", action = ::replayAssistant)
            if (assistant.value.phase == AssistantPhase.THINKING)
                key("assistant_cancel", "Cancel", action = ::cancelAssistant)
        }
        key("home", "Home", "go home") { open(Screen.HOME) }
        key("settings", "Settings") { open(Screen.SETTINGS) }
        when (state.value.screen) {
            Screen.HOME -> {
                key("navigate", "Navigate", "navigation") { open(Screen.NAVIGATION) }
                key("footpath", "Check Footpath") { open(Screen.FOOTPATH) }
                key("surroundings", "Describe Surroundings") { open(Screen.SCENE) }
                key("training", "Learn Audio Cues", "audio cues") { open(Screen.TRAINING) }
            }
            Screen.NAVIGATION -> {
                key("search", "Search") {
                    if (nav.value.destinationDialog) searchDestinations(voice = true)
                    else showDestinations()
                }
                key("center", "Center map", "recenter map", action = ::centerMap)
                key("repeat", "Repeat") { replayNavigation() }
                val active =
                    guidance.value.status in
                        setOf(
                            NavigationStatus.ACTIVE,
                            NavigationStatus.WAITING_FOR_GPS,
                            NavigationStatus.OFF_ROUTE,
                        )
                if (active || nav.value.pendingNavigation || nav.value.routing)
                    key("stop", "Stop", "stop navigation", action = ::stopNavigation)
                else key("go", "Go", "start navigation", action = ::startNavigation)
                QuickDestination.entries.forEach { quick ->
                    key("nearby_${quick.name.lowercase()}", quick.label) { quickNavigate(quick) }
                }
                nav.value.places.forEachIndexed { index, place ->
                    key(
                        "destination_${index + 1}",
                        place.name,
                        "option ${index + 1}",
                        "option ${listOf("one", "two", "three", "four", "five").getOrNull(index) ?: index + 1}",
                    ) {
                        select(place)
                    }
                }
                if (nav.value.places.isNotEmpty())
                    key(
                        "next_result",
                        "Next result",
                        "next destination",
                        action = ::nextVoiceDestination,
                    )
            }
            Screen.FOOTPATH -> {
                if (state.value.scanRequested)
                    key("stop_scan", "Stop Scanning") { stopScanning(true) }
                else if (modelSetup.value.phase == SetupPhase.READY)
                    key("start_scan", "Start Scanning", action = { scan() })
                else if (modelSetup.value.phase == SetupPhase.ERROR)
                    key("retry_setup", "Retry model setup", action = ::retryModelSetup)
                key("repeat", "Repeat", action = ::repeatFootpath)
            }
            Screen.SCENE -> {
                when (surroundingsState.value.phase) {
                    SurroundingsPhase.CAPTURING -> {
                        key(
                            "finish_recording",
                            "Finish recording",
                            "stop recording",
                            action = ::finishSurroundingsRecording,
                        )
                        key("cancel", "Cancel", action = ::cancelSurroundingsCapture)
                    }
                    SurroundingsPhase.PREPARING,
                    SurroundingsPhase.EXTRACTING ->
                        key("cancel", "Cancel", action = ::cancelSurroundingsCapture)
                    SurroundingsPhase.ANALYZING ->
                        key("cancel", "Cancel", action = ::cancelSceneQuestion)
                    else -> {
                        key("record", "Record video", "describe surroundings", action = ::describe)
                        if (
                            !state.value.voiceOpen &&
                                state.value.hasSceneDescription &&
                                surroundingsState.value.phase != SurroundingsPhase.ERROR
                        )
                            key("replay", "Replay", "repeat", action = ::replayScene)
                    }
                }
            }
            Screen.TRAINING -> {
                listOf(
                        "Listening" to FeedbackCue.LISTENING,
                        "Turn left" to FeedbackCue.TURN_LEFT,
                        "Turn right" to FeedbackCue.TURN_RIGHT,
                        "Obstruction" to FeedbackCue.OBSTACLE,
                        "View captured" to FeedbackCue.CAPTURED,
                        "Complete" to FeedbackCue.COMPLETE,
                        "Unavailable" to FeedbackCue.UNAVAILABLE,
                    )
                    .forEach { (label, cue) ->
                        key("cue_${cue.name.lowercase()}", label) { practiceCue(cue) }
                    }
                key("vibration", "Vibration", action = ::vibrationCue)
                key("stop", "Stop", action = ::stopAudio)
            }
            Screen.SETTINGS -> {
                key(
                    "more_settings",
                    if (state.value.settingsExpanded) "Less settings" else "More settings",
                    action = ::toggleSettingsExpanded,
                )
                if (state.value.settingsExpanded)
                    key("preview_audio", "Preview audio detail", action = ::verbosityExample)
                key("tour", "Replay tour", "take a tour", action = ::startTour)
                key("permissions", "App permissions") {
                    getApplication<Application>()
                        .startActivity(
                            android.content
                                .Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse(
                                        "package:${getApplication<Application>().packageName}"
                                    ),
                                )
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                }
                key("standard_text", "Standard", "standard text") { settings(large = false) }
                key("large_text", "Large", "large text") { settings(large = true) }
                key("night", "Night", "night mode") { appearance(true) }
                key("field", "Field", "day mode") { appearance(false) }
                key("brief", "Brief") { settings(detailed = false) }
                key("detailed", "Detailed") { settings(detailed = true) }
                key("english", "English") { settings(bangla = false) }
                key("bangla", "Bangla", "Bengali") { settings(bangla = true) }
                key("test_speech", "Test speech", action = ::testSpeech)
                key("hands_free", "Hands-free voice") { handsFree(!state.value.handsFree) }
                key("online_speech", "Online speech fallback") {
                    audioFallback(!state.value.audioFallback)
                }
                key("sound_cues", "Short sound cues") {
                    feedbackSettings(sound = !state.value.soundCues)
                }
                key("haptics", "Haptic cues") { feedbackSettings(haptics = !state.value.haptics) }
                key("depth", "Estimated depth distances") {
                    settings(depthEnabled = !state.value.depthEnabled)
                }
                key("retry_setup", "Retry model setup", action = ::retryModelSetup)
            }
        }
        key("ask", "Ask Muse", "voice command", action = ::wakeMuse)
    }

    fun toggleSettingsExpanded() {
        mutable.update { it.copy(settingsExpanded = !it.settingsExpanded) }
    }

    private fun activateVoiceControl(text: String): Boolean {
        val normalized = CommandInterpreter().normalize(text)
        val actions = voiceControlActions()
        val match = VoiceControls.match(text, actions.map { it.first }) ?: return false
        // Dismiss the assistant sheet when operating the page beneath it; keep consent sheets.
        if (
            state.value.voiceOpen &&
                (match.id !in
                    setOf(
                        "back",
                        "ask",
                        "assistant_speak",
                        "assistant_replay",
                        "assistant_cancel",
                    ) || (match.id == "back" && normalized !in setOf("close", "close dialog")))
        ) {
            // Operating a page must not revoke the already authorized follow-up window.
            cancelAssistantWork()
            mutable.update { it.copy(voiceOpen = false) }
        }
        actions.single { it.first == match }.second()
        if (
            match.id in
                setOf(
                    "home",
                    "settings",
                    "navigate",
                    "footpath",
                    "surroundings",
                    "training",
                    "standard_text",
                    "large_text",
                    "night",
                    "field",
                    "brief",
                    "detailed",
                    "english",
                    "bangla",
                    "more_settings",
                )
        )
            feedback("${match.label}.")
        return true
    }

    private fun positionContext(): org.json.JSONObject {
        val now = SystemClock.elapsedRealtime()
        val fix = navigationSensors.location.value
        val fresh = AssistantLocationPolicy.fresh(fix, now)
        return org.json.JSONObject().put("available", fresh).apply {
            if (fresh && fix != null) {
                put("latitude", fix.latitude).put("longitude", fix.longitude)
                put("accuracyMetres", fix.accuracyMeters)
                    .put("ageSeconds", (now - fix.timestampMs) / 1000)
            } else put("reason", "No recent location fix. Do not guess position.")
        }
    }

    private suspend fun currentAssistantContext(question: String): String {
        if (
            !Regex(
                    "(?i)\\b(where|position|location|near|nearest|nearby|around|here|lost|situation|hospital|pharmacy|market)\\b"
                )
                .containsMatchIn(question)
        )
            return assistantContext()
        // Only request GPS for a user-authorized question, and stop it outside navigation.
        navigationSensors.start()
        return try {
            withTimeoutOrNull(2000) {
                navigationSensors.location.first {
                    AssistantLocationPolicy.fresh(it, SystemClock.elapsedRealtime())
                }
            }
            val context = org.json.JSONObject(assistantContext())
            val position = context.getJSONObject("currentPosition")
            val fix = navigationSensors.location.value
            if (
                position.optBoolean("available") &&
                    fix != null &&
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                    android.location.Geocoder.isPresent()
            ) {
                val address =
                    withTimeoutOrNull(1800) {
                        kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
                            try {
                                android.location
                                    .Geocoder(getApplication(), java.util.Locale.ENGLISH)
                                    .getFromLocation(
                                        fix.latitude,
                                        fix.longitude,
                                        1,
                                        object : android.location.Geocoder.GeocodeListener {
                                            override fun onGeocode(
                                                addresses: MutableList<android.location.Address>
                                            ) {
                                                if (continuation.isActive)
                                                    continuation.resume(
                                                        addresses
                                                            .firstOrNull()
                                                            ?.getAddressLine(0)
                                                            ?.take(220)
                                                    ) { _, _, _ ->
                                                    }
                                            }

                                            override fun onError(errorMessage: String?) {
                                                if (continuation.isActive)
                                                    continuation.resume(null) { _, _, _ -> }
                                            }
                                        },
                                    )
                            } catch (_: Exception) {
                                if (continuation.isActive) continuation.resume(null) { _, _, _ -> }
                            }
                        }
                    }
                if (address != null) position.put("approximateAddress", address)
            }
            context.toString()
        } finally {
            if (state.value.screen != Screen.NAVIGATION) navigationSensors.stop()
        }
    }

    private fun assistantContext(): String {
        val guide = guidance.value
        return org.json
            .JSONObject()
            .put("screen", state.value.screen.name)
            .put("destinationSearchOpen", nav.value.destinationDialog)
            .put("searchQuery", nav.value.query.take(160))
            .put(
                "availableControls",
                org.json.JSONArray(
                    voiceControlActions().map { (control, _) ->
                        org.json
                            .JSONObject()
                            .put("id", control.id)
                            .put("label", control.label.take(80))
                    }
                ),
            )
            .put("currentPosition", positionContext())
            .put("scanning", state.value.scanRequested)
            .put("phoneAngle", state.value.tilt.name)
            .put("latestLocalAlert", state.value.footpath.take(180))
            .put(
                "recentObjects",
                org.json.JSONArray(
                    latestObservation
                        ?.takeIf { SystemClock.elapsedRealtime() - it.timestampMs in 0..4000 }
                        ?.detections
                        ?.map { it.label }
                        ?.distinct()
                        ?.take(10)
                        .orEmpty()
                ),
            )
            .put("routeStatus", guide.status.name)
            .put("mappedGuidance", guide.message.take(300))
            .put("plannedTotalMetres", guide.totalMeters ?: org.json.JSONObject.NULL)
            .put("estimatedTotalSteps", guide.totalEstimatedSteps ?: org.json.JSONObject.NULL)
            .put("remainingMetres", guide.remainingMeters ?: org.json.JSONObject.NULL)
            .put("mappedTurnMetres", guide.maneuverDistanceMeters ?: org.json.JSONObject.NULL)
            .put(
                "mappedTurnEstimatedSteps",
                guide.maneuverEstimatedSteps ?: org.json.JSONObject.NULL,
            )
            .put("destination", nav.value.selected?.name?.take(160) ?: org.json.JSONObject.NULL)
            .put("handsFreeVoice", state.value.handsFree)
            .put("nightMode", state.value.nightMode)
            .put("soundCues", state.value.soundCues)
            .put("haptics", state.value.haptics)
            .put("language", if (state.value.bangla) "BANGLA" else "ENGLISH")
            .put(
                "headingDegrees",
                navigationSensors.heading.value.takeIf { it.reliable }?.degrees
                    ?: org.json.JSONObject.NULL,
            )
            .put("largerText", state.value.largeText)
            .put("detailedAudio", state.value.detailed)
            .put("stepLengthMetres", state.value.stepLength)
            .toString()
    }

    private fun rememberAssistant(role: String, text: String) {
        assistantHistory.addLast(AssistantTurn(role, text.take(500)))
        while (assistantHistory.size > 6) assistantHistory.removeFirst()
    }

    fun askMuse(question: String, withImage: Boolean = false) {
        val query = question.trim().take(500)
        if (query.isBlank() || !foreground) return
        if (!withImage && surroundingsState.value.phase == SurroundingsPhase.CONSENT) {
            when (commands.interpret(query)) {
                Command.START_SCENE_RECORDING,
                Command.CONFIRM_IMAGE_UPLOAD -> {
                    confirmSurroundingsCapture()
                    return
                }
                Command.CANCEL_IMAGE_UPLOAD -> {
                    dismissSurroundingsConsent()
                    return
                }
                else -> Unit
            }
        }
        if (!withImage && commands.interpret(query) == Command.FINISH_SCENE_RECORDING) {
            finishSurroundingsRecording()
            return
        }
        if (
            !withImage &&
                commands.interpret(query) in
                    setOf(Command.DESCRIBE_SCENE, Command.START_SCENE_RECORDING)
        ) {
            describe()
            return
        }
        if (!withImage && sceneQuestion(query)) {
            askSurroundings(query)
            return
        }
        val voiceTicket = voiceCommands.holdDictationWindow()
        assistantJob?.cancel()
        val epoch = ++assistantEpoch
        voiceActivation.clear()
        pendingAssistantSpeech = null
        assistantRouteTicket = null
        assistant.value = AssistantUiState(AssistantPhase.THINKING, query)
        nav.update { it.copy(contextBusy = withImage, contextText = null) }
        val maneuver = maneuverForContext()
        val routeEpoch = requestVersion
        assistantJob = viewModelScope.launch {
            try {
                val still =
                    if (withImage) {
                        val camera =
                            withTimeout(10000) {
                                combine(scanner.state, state) { scan, ui ->
                                        scan to ui.scanRequested
                                    }
                                    .first {
                                        !it.second ||
                                            it.first.phase in
                                                setOf(ScanPhase.RUNNING, ScanPhase.ERROR)
                                    }
                            }
                        check(camera.second && camera.first.phase == ScanPhase.RUNNING) {
                            "Camera view unavailable. Enable camera permission and try again."
                        }
                        scanner.selectedFrame()
                    } else null
                val reply =
                    muse
                        .ask(
                            AssistantRequest(
                                query,
                                currentAssistantContext(query),
                                assistantHistory.toList(),
                                still?.jpeg,
                                still?.width ?: 0,
                                still?.height ?: 0,
                            )
                        )
                        .getOrThrow()
                if (!foreground || epoch != assistantEpoch) return@launch
                if (
                    (withImage || maneuver != null) &&
                        (routeEpoch != requestVersion || maneuver != maneuverForContext())
                ) {
                    showAssistantError("The route changed. Ask again.")
                    return@launch
                }
                if (reply.action != null) {
                    require(reply.action.valid())
                    if (reply.action.kind == MuseActionKind.INSPECT_VIEW) {
                        assistantJob = null
                        requestVisualQuestion(reply.action.query ?: query)
                        return@launch
                    }
                    rememberAssistant("user", query)
                    // The validated action may navigate; do not cancel this completed request from
                    // open().
                    assistantJob = null
                    assistant.value =
                        AssistantUiState(
                            AssistantPhase.ANSWER,
                            query,
                            dispatchMuseAction(reply.action),
                        )
                    rememberAssistant("assistant", assistant.value.answer)
                } else {
                    rememberAssistant("user", query)
                    val answer = checkNotNull(reply.text)
                    if (maneuver != null) assistantRouteTicket = routeEpoch to maneuver
                    assistant.value = AssistantUiState(AssistantPhase.ANSWER, query, answer, false)
                    rememberAssistant("assistant", answer)
                    contextExpires = SystemClock.elapsedRealtime() + 20000
                    if (state.value.screen == Screen.NAVIGATION)
                        nav.update { it.copy(contextText = answer) }
                    if (withImage)
                        mutable.update { it.copy(scene = answer, hasSceneDescription = true) }
                    pendingAssistantSpeech = (SystemClock.elapsedRealtime() + 25000) to answer
                    speakAssistant()
                }
            } catch (_: TimeoutCancellationException) {
                if (epoch == assistantEpoch)
                    showAssistantError("Camera view unavailable. Try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == assistantEpoch)
                    showAssistantError(
                        e.message ?: "Muse is unavailable. Local commands still work."
                    )
            } finally {
                voiceCommands.finishCommand(voiceTicket)
                if (epoch == assistantEpoch) nav.update { it.copy(contextBusy = false) }
            }
        }
    }

    private fun showAssistantError(message: String) {
        assistant.update { it.copy(phase = AssistantPhase.ERROR, answer = message) }
        feedback(message)
    }

    private fun speakAssistant() {
        val pending = pendingAssistantSpeech ?: return
        if (
            assistantRouteTicket?.let {
                it.first != requestVersion || it.second != maneuverForContext()
            } == true
        ) {
            pendingAssistantSpeech = null
            nav.update { it.copy(contextText = null) }
            return
        }
        if (!foreground || SystemClock.elapsedRealtime() > pending.first) {
            pendingAssistantSpeech = null
            return
        }
        val spoken =
            pending.second.replace(Regex("(?i)hey\\s+(?:there\\s+)?vision"), "the wake phrase")
        speech.language(false)
        if (alerts.offer(Alert(spoken, Priority.SCENE))) {
            pendingAssistantSpeech = null
            assistantReplyForListening = spoken
        }
    }

    fun replayAssistant() {
        assistant.value.answer
            .takeIf { it.isNotBlank() }
            ?.let {
                pendingAssistantSpeech = (SystemClock.elapsedRealtime() + 20000) to it
                speakAssistant()
            }
    }

    private fun cancelAssistantWork() {
        cancelSpeechFallback()
        assistantJob?.cancel()
        assistantEpoch++
        pendingAssistantSpeech = null
        assistantRouteTicket = null
        sceneReplyForListening = null
        assistantReplyForListening = null
        voiceActivation.clear()
        assistant.update { it.copy(phase = AssistantPhase.IDLE) }
        nav.update { it.copy(contextBusy = false) }
    }

    fun cancelAssistant() {
        voiceCommands.closeDictationWindow()
        cancelAssistantWork()
    }

    private fun dispatchMuseAction(action: MuseAction): String {
        require(action.valid())
        fun acknowledge(text: String): String {
            feedback(text)
            return text
        }
        return when (action.kind) {
            MuseActionKind.BACK -> {
                activateVoiceControl("go back")
                "Back."
            }
            MuseActionKind.ACTIVATE_CONTROL -> {
                val control = voiceControlActions().singleOrNull { it.first.id == action.value }
                if (control == null) acknowledge("That control is not available here.")
                else {
                    activateVoiceControl(control.first.label)
                    "${control.first.label}."
                }
            }
            MuseActionKind.NAVIGATE_NEARBY -> {
                navigateNearby(action.category!!)
                "Finding a nearby ${action.category.label.lowercase()}."
            }
            MuseActionKind.FIND_NEARBY -> {
                findNearby(action.category!!)
                "Finding nearby ${action.category.label.lowercase()}."
            }
            MuseActionKind.SEARCH_DESTINATION,
            MuseActionKind.NAVIGATE_TO -> {
                if (state.value.screen != Screen.NAVIGATION) open(Screen.NAVIGATION)
                updateQuery(action.query!!)
                searchDestinations(
                    voice = true,
                    autoStart = action.kind == MuseActionKind.NAVIGATE_TO,
                )
                "Searching for ${action.query}."
            }
            MuseActionKind.OPEN_SCREEN -> {
                open(Screen.valueOf(action.value!!))
                acknowledge("${action.value.lowercase().replace('_',' ')} opened.")
            }
            MuseActionKind.START_SCANNING -> {
                scan()
                acknowledge(
                    if (state.value.scanRequested) "Starting scanning."
                    else "Offline models are not ready. Retry setup first."
                )
            }
            MuseActionKind.STOP_SCANNING -> {
                stopScanning(announceStop = true)
                "Scanning stopped."
            }
            MuseActionKind.START_NAVIGATION -> {
                if (state.value.screen != Screen.NAVIGATION) open(Screen.NAVIGATION)
                startNavigation()
                if (nav.value.selected == null) "Choose a destination."
                else "Finding your walking route."
            }
            MuseActionKind.STOP_NAVIGATION -> {
                stopNavigation()
                "Navigation stopped."
            }
            MuseActionKind.INSPECT_VIEW -> {
                requestVisualQuestion(action.query ?: assistant.value.query)
                "Checking the camera view."
            }
            MuseActionKind.REPEAT -> {
                alerts.replay()
                "Repeated the latest speech."
            }
            MuseActionKind.ROUTE_SUMMARY ->
                acknowledge(navigation.routeOverview() ?: "Choose a destination.")
            MuseActionKind.RECENTER_MAP -> {
                centerMap()
                "Map centered."
            }
            MuseActionKind.TRAIN_AUDIO -> {
                open(Screen.TRAINING)
                cue(action.value == "URGENT")
                "Playing the practice cue."
            }
            MuseActionKind.STOP_AUDIO -> {
                pendingFeedback = null
                pendingAssistantSpeech = null
                stopAudio()
                "Audio stopped."
            }
            MuseActionKind.SET_TEXT_SIZE -> {
                settings(large = action.value == "LARGE")
                acknowledge("Text size updated.")
            }
            MuseActionKind.SET_VERBOSITY -> {
                settings(detailed = action.value == "DETAILED")
                acknowledge("Audio verbosity updated.")
            }
            MuseActionKind.SET_LANGUAGE -> {
                settings(bangla = action.value == "BANGLA")
                acknowledge("Speech language preference updated.")
            }
            MuseActionKind.SET_DEPTH_ESTIMATES -> {
                settings(depthEnabled = action.value == "ON")
                acknowledge("Depth estimates ${action.value!!.lowercase()}.")
            }
            MuseActionKind.SET_STEP_LENGTH -> {
                settings(stepLength = action.value!!.toFloat())
                acknowledge("Step length updated.")
            }
            MuseActionKind.STOP_LISTENING -> {
                stopVoiceCommands()
                acknowledge("Microphone off.")
            }
            MuseActionKind.RESUME_LISTENING -> {
                startVoiceCommands()
                acknowledge("Listening.")
            }
            MuseActionKind.CAPTURE_SURROUNDINGS -> {
                describe()
                "Ready to record your surroundings."
            }
            MuseActionKind.RETRY_MODEL_SETUP -> {
                retryModelSetup()
                acknowledge(
                    if (modelSetup.value.phase == SetupPhase.READY) "Models are ready."
                    else "Retrying model setup."
                )
            }
        }
    }

    fun findNearby(category: NearbyCategory) {
        // Same real nearby provider, with candidate selection rather than automatic navigation.
        navigateNearby(category, false)
    }

    private fun executeCommand(command: Command, transcript: String = "") {
        if (!foreground) return
        when (command.scanAction()) {
            ScanCommandAction.OPEN_FOOTPATH_AND_START -> {
                scan()
                return
            }
            ScanCommandAction.STOP -> {
                stopScanning(announceStop = true)
                return
            }
            ScanCommandAction.NONE -> Unit
        }
        when (command) {
            Command.BACK -> back()
            Command.HOME -> open(Screen.HOME)
            Command.SETTINGS -> open(Screen.SETTINGS)
            Command.START_SCENE_RECORDING ->
                if (surroundingsState.value.phase == SurroundingsPhase.CONSENT)
                    confirmSurroundingsCapture()
                else describe()
            Command.FINISH_SCENE_RECORDING -> finishSurroundingsRecording()
            Command.ROUTE_SUMMARY -> feedback(navigation.routeOverview() ?: "Choose a destination.")
            Command.EXPLAIN_NAVIGATION ->
                if (sceneQuestion(transcript)) askSurroundings(transcript)
                else requestNavigationContext()
            Command.CONFIRM_IMAGE_UPLOAD -> confirmContextConsent()
            Command.CANCEL_IMAGE_UPLOAD -> dismissContextConsent()
            Command.REPEAT_VISUAL_CONTEXT -> replayContext()
            Command.START_SCANNING,
            Command.STOP_SCANNING -> Unit
            Command.NAVIGATION_DESTINATION ->
                when (val request = NavigationVoiceRequest.parse(transcript)) {
                    is NavigationVoiceRequest.Quick -> quickNavigate(request.destination)
                    is NavigationVoiceRequest.Place -> {
                        if (state.value.screen != Screen.NAVIGATION) open(Screen.NAVIGATION)
                        nav.update { it.copy(query = request.query) }
                        searchDestinations(voice = true)
                    }
                    is NavigationVoiceRequest.Result ->
                        nav.value.places.getOrNull(request.index)?.let { select(it) }
                            ?: feedback("That option isn't available.")
                    null -> Unit
                }
            Command.RECENTER_MAP -> centerMap()
            Command.NEXT_DESTINATION -> nextVoiceDestination()
            Command.STOP_LISTENING -> {
                stopVoiceCommands()
                feedback("Microphone off.")
            }
            Command.START_NAVIGATION -> {
                if (state.value.screen != Screen.NAVIGATION) open(Screen.NAVIGATION)
                startNavigation()
            }
            Command.STOP_NAVIGATION -> stopNavigation()
            Command.REPEAT_NAVIGATION -> replayNavigation(routeOnly = true)
            Command.NAVIGATE ->
                if (state.value.screen != Screen.NAVIGATION) commandScreen(Screen.NAVIGATION)
            Command.CHECK_FOOTPATH -> commandScreen(Screen.FOOTPATH)
            Command.DESCRIBE_SCENE -> {
                commandScreen(Screen.SCENE)
                describe()
            }
            Command.LEARN_AUDIO_CUES -> commandScreen(Screen.TRAINING)
            Command.STOP -> {
                cancelNavigationRequest()
                pendingFeedback = null
                lastGuidanceKey = ""
                stopScanning()
                cancelWork()
                navigation.stop()
                stopAudio()
            }
            Command.REPEAT ->
                if (state.value.screen == Screen.SCENE && surroundingsSession.hasViews)
                    replayScene()
                else alerts.replay()
            Command.HELP -> {
                val help =
                    "Say a button name, Go back, or Home. You can ask where you are, find a destination, or ask any question."
                feedback(help)
                assistantReplyForListening = help
            }
            Command.EMERGENCY_ALARM -> {
                triggerEmergencyLoudspeakerAlarm()
            }
            Command.UNKNOWN -> Unit
        }
    }

    private fun commandScreen(screen: Screen) {
        open(screen)
    }

    fun cue(urgent: Boolean) =
        practiceCue(if (urgent) FeedbackCue.OBSTACLE else FeedbackCue.CONFIRMED)

    fun practiceCue(cue: FeedbackCue) {
        mutable.update { it.copy(cue = "Practice: ${cue.label}.") }
        feedbackCues.play(cue)
        if (state.value.detailed) announce("Practice: ${cue.label}.")
    }

    fun vibrationCue() {
        feedbackCues.play(FeedbackCue.CAPTURED, withSound = false)
        mutable.update { it.copy(cue = "Practice: a short tap.") }
    }

    fun unavailableCue() = practiceCue(FeedbackCue.UNAVAILABLE)

    fun stopAudio() {
        sceneReplyForListening = null
        pendingTurnCue = null
        feedbackCues.stop()
        alerts.stop()
        getApplication<Application>().getSystemService(Vibrator::class.java)?.cancel()
        mutable.update { it.copy(cue = "Audio and vibration stopped.") }
    }

    fun background() {
        foreground = false
        if (welcome.value.stage == WelcomeStage.TOUR) pauseTour()
        clearSurroundings()
        cancelSpeechFallback()
        cancelAssistant()
        cancelNavigationRequest()
        pendingFeedback = null
        navigationSensors.stop()
        searchJob?.cancel()
        routeJob?.cancel()
        stopScanning()
        voiceCommands.stop()
        cancelWork()
        navigation.stop()
        stopAudio()
        dismissVoice()
    }

    private fun cancelWork() {
        operation?.cancel()
        mutable.update { it.copy(busy = false) }
    }

    private fun announce(text: String, priority: Priority = Priority.ROUTINE) {
        speech.language(priority < Priority.URGENT && state.value.bangla)
        alerts.offer(Alert(text, priority))
    }

    fun settings(
        large: Boolean = state.value.largeText,
        detailed: Boolean = state.value.detailed,
        bangla: Boolean = state.value.bangla,
        stepLength: Float = state.value.stepLength,
        depthEnabled: Boolean = state.value.depthEnabled,
    ) {
        val stride = NavigationMeasures.validStride(stepLength)
        navigation.setStepLength(stride)
        mutable.update {
            it.copy(
                largeText = large,
                detailed = detailed,
                bangla = bangla,
                stepLength = stride,
                depthEnabled = depthEnabled,
            )
        }
        scanner.depthEnabled = depthEnabled
        preferences
            .edit()
            .putFloat("stepLength", stride)
            .putBoolean("depthEnabled", depthEnabled)
            .putBoolean("large", large)
            .putBoolean("detailed", detailed)
            .putBoolean("bangla", bangla)
            .apply()
    }

    fun appearance(night: Boolean) {
        mutable.update { it.copy(nightMode = night) }
        preferences.edit().putBoolean("nightMode", night).apply()
    }

    fun verbosityExample() {
        announce(
            if (state.value.detailed)
                "Practice guidance. An example maneuver is upcoming. This is a stationary demonstration; no real route is active."
            else "Practice maneuver upcoming."
        )
    }

    override fun onCleared() {
        emergencyAlarm.close()
        spatialSonar.close()
        models.close()
        scanner.close()
        navigationSensors.stop()
        tiltProvider.stop()
        voiceCommands.close()
        clearSurroundings()
        feedbackCues.close()
        sceneVideo.close()
        speech.close()
        super.onCleared()
    }
}
