package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.librevision.data.models.SetupPhase
import com.librevision.data.speech.VoskUtteranceRecognizer
import com.librevision.data.vision.ScanPhase
import com.librevision.domain.assistant.MuseVoiceDecision
import com.librevision.domain.assistant.MuseVoiceSession
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import com.librevision.domain.vision.FootpathIndicator
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Real device checks. Route/GPS, speech and distance-warning fixtures are explicitly synthetic. */
class NavigationUxInstrumentation : Instrumentation() {
    private var globalVoiceOnly = false
    private var speechOnly = false
    private var audioFallbackOnly = false
    private var wakePrivacyOnly = false
    private var followupOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        globalVoiceOnly = arguments?.getString("check") == "globalVoice"
        speechOnly = arguments?.getString("check") == "speech"
        audioFallbackOnly = arguments?.getString("check") == "audioFallback"
        wakePrivacyOnly = arguments?.getString("check") == "wakePrivacy"
        followupOnly = arguments?.getString("check") == "followup"
        start()
    }

    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync {
            try {
                block()
            } catch (e: Throwable) {
                error = e
            }
        }
        error?.let { throw it }
    }

    private fun until(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (!test() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        check(test()) { "Timed out waiting for device state" }
    }

    override fun onStart() {
        val result = Bundle()
        var vm: LibreVisionViewModel? = null
        var originalHandsFree = true
        var originalNight = false
        var resultCode = Activity.RESULT_OK
        try {
            if (globalVoiceOnly) {
                result.putString("stream", globalVoiceChecks().toString() + "\n")
            } else if (followupOnly) {
                result.putString("stream", followupChecks().toString() + "\n")
            } else if (wakePrivacyOnly) {
                result.putString("stream", wakePrivacyChecks().toString() + "\n")
            } else if (audioFallbackOnly) {
                result.putString("stream", cloudSpeechChecks().toString() + "\n")
            } else if (speechOnly) {
                result.putString("stream", speechChecks().toString() + "\n")
            } else {
                val activity =
                    startActivitySync(
                        Intent(targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                        as MainActivity
                main { vm = ViewModelProvider(activity)[LibreVisionViewModel::class.java] }
                val model = checkNotNull(vm)
                originalHandsFree = model.state.value.handsFree
                originalNight = model.state.value.nightMode
                until { model.modelSetup.value.phase == SetupPhase.READY }
                main {
                    model.pauseVoiceCapture()
                    model.open(Screen.NAVIGATION)
                    model.enterNavigation()
                }
                waitForIdleSync()
                Thread.sleep(1000)
                check(!model.state.value.scanRequested) { "Camera started before a route" }
                main { model.showDestinations() }
                check(!model.state.value.scanRequested)
                main { model.dismissDestinations() }
                check(!model.state.value.scanRequested)
                val report = JSONObject().put("planning_camera_off", true)
                // Exercise the real camera lifecycle with a declared route fixture, never a live
                // navigation claim.
                val navigation =
                    LibreVisionViewModel::class
                        .java
                        .getDeclaredField("navigation")
                        .apply { isAccessible = true }
                        .get(model) as WalkingGuidanceEngine
                main {
                    model.open(Screen.FOOTPATH)
                    model.scan()
                    navigation.updateHeading(HeadingEstimate(90f, true))
                }
                until {
                    model.state.value.scanRequested &&
                        model.state.value.scanPhase == ScanPhase.RUNNING
                }
                main { model.stopScanning() }
                report.put("manual_footpath_scan_works_without_starting_navigation", true)
                main { model.open(Screen.NAVIGATION) }
                val origin = GeoPoint(23.7465, 90.3784)
                val end = GeoPoint(23.7475, 90.3784)
                main {
                    navigation.start(
                        WalkingRoute(
                            Destination("ux-fixture", "Instrumentation fixture", end),
                            listOf("Continue."),
                            false,
                            listOf(origin, end),
                            listOf(
                                RouteManeuver(0, "Continue.", 1),
                                RouteManeuver(1, "Route end.", 4),
                            ),
                            origin,
                            "instrumentation fixture",
                        )
                    )
                }
                until {
                    model.state.value.scanRequested &&
                        model.state.value.scanPhase == ScanPhase.RUNNING
                }
                main { model.showDestinations() }
                check(!model.state.value.scanRequested)
                main { model.dismissDestinations() }
                until { model.state.value.scanRequested }
                main { model.stopNavigation() }
                check(!model.state.value.scanRequested)
                report.put("route_fixture_camera_start_pause_resume_stop", true)
                @Suppress("UNCHECKED_CAST")
                val state =
                    LibreVisionViewModel::class
                        .java
                        .getDeclaredField("mutable")
                        .apply { isAccessible = true }
                        .get(model) as MutableStateFlow<UiState>
                val captures = JSONArray()
                for (night in listOf(false, true)) for (screen in
                    listOf(Screen.NAVIGATION, Screen.FOOTPATH)) {
                    main {
                        model.open(screen)
                        model.appearance(night)
                        state.update {
                            it.copy(
                                footpath = "Stop, cat about 3 steps away. Estimated.",
                                indicator = FootpathIndicator.OBSTRUCTION,
                            )
                        }
                    }
                    waitForIdleSync()
                    Thread.sleep(250)
                    until { uiAutomation.rootInActiveWindow != null }
                    val root = checkNotNull(uiAutomation.rootInActiveWindow)
                    val buttons =
                        if (screen == Screen.NAVIGATION) listOf("Go", "Search", "Repeat")
                        else listOf("Start Scanning", "Ask Muse", "Repeat")
                    val frame = Rect()
                    root.getBoundsInScreen(frame)
                    for (label in buttons) {
                        val node = find(root, label) ?: error("Missing control: $label")
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        check(!bounds.isEmpty && frame.contains(bounds)) {
                            "$label is clipped: $bounds outside $frame"
                        }
                    }
                    check(
                        find(root, "Stop, cat about 3 steps away. Estimated.", contains = true) !=
                            null
                    )
                    val scale = targetContext.resources.configuration.fontScale
                    val file =
                        File(
                            targetContext.filesDir,
                            "ux-${screen.name.lowercase()}-${if(night)"night"else "field"}-$scale.png",
                        )
                    uiAutomation.takeScreenshot().let { bitmap ->
                        file.outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    captures.put(file.name)
                }
                main {
                    model.open(Screen.HOME)
                    model.transcript("Check the but")
                }
                check(model.state.value.screen == Screen.FOOTPATH)
                main {
                    model.open(Screen.HOME)
                    model.transcript("Hey Vision")
                }
                check(model.state.value.voiceOpen)
                main {
                    model.dismissVoice()
                    model.handsFree(true)
                }
                until {
                    model.listening.value.phase ==
                        com.librevision.data.speech.ListeningPhase.LISTENING
                }
                main { model.pauseVoiceCapture() }
                until {
                    model.listening.value.phase == com.librevision.data.speech.ListeningPhase.IDLE
                }
                report
                    .put("corrected_footpath_alias", true)
                    .put("hey_vision_global_wake", true)
                    .put("live_mic_starts_and_stops", true)
                    .put("synthetic_warning_state", true)
                    .put("font_scale", targetContext.resources.configuration.fontScale)
                    .put("screenshots", captures)
                result.putString("stream", report.toString() + "\n")
            }
        } catch (e: Throwable) {
            result.putString("stream", "FAILED: ${e.stackTraceToString()}\n")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            main {
                vm?.let {
                    it.stopNavigation()
                    it.stopScanning()
                    it.dismissVoice()
                    it.appearance(originalNight)
                    it.handsFree(originalHandsFree)
                    it.open(Screen.HOME)
                }
            }
        }
        finish(resultCode, result)
    }

    private fun find(
        node: AccessibilityNodeInfo,
        label: String,
        contains: Boolean = false,
    ): AccessibilityNodeInfo? {
        val values =
            listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
        if (values.any { if (contains) it.contains(label) else it == label }) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let {
            find(it, label, contains)?.let { found ->
                return found
            }
        }
        return null
    }

    private fun speechChecks(): JSONObject {
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts =
            TextToSpeech(targetContext) {
                status = it
                initialized.countDown()
            }
        val rows = JSONArray()
        try {
            check(initialized.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS)
            check(tts.setLanguage(Locale.US) >= 0)
            var fullAllowed = true
            VoskUtteranceRecognizer(
                    File(targetContext.filesDir, "english/vosk-model-small-en-us-0.15"),
                    { fullAllowed },
                )
                .use { recognizer ->
                    val report =
                        JSONObject()
                            .put("synthetic_speech_only", true)
                            .put("microphone_audio_uploaded", false)
                            .put("supported_grammar_phrases", recognizer.supportedPhrases.size)
                            .put(
                                "unsupported_phrases_skipped",
                                AppSpeechPhrases.phrases.size - recognizer.supportedPhrases.size,
                            )
                    val checks =
                        listOf(
                            "check foot path" to Command.CHECK_FOOTPATH,
                            "check the foot path" to Command.CHECK_FOOTPATH,
                            "start scanning" to Command.START_SCANNING,
                            "stop scanning" to Command.STOP_SCANNING,
                            "do not start scanning" to Command.UNKNOWN,
                            "the weather is lovely today" to Command.UNKNOWN,
                            "navigate to square hospital" to Command.NAVIGATION_DESTINATION,
                            "go to lab aid" to Command.NAVIGATION_DESTINATION,
                        )
                    for ((phrase, expected) in checks) {
                        val pcm = synthesize(tts, phrase)
                        val before = recognizer.dictationDecodes
                        val started = SystemClock.elapsedRealtime()
                        val transcript = recognizer.decode(pcm)
                        val actual = VoiceCommandRouter().interpret(transcript)
                        rows.put(
                            JSONObject()
                                .put("phrase", phrase)
                                .put("transcript", transcript)
                                .put("command", actual.name)
                                .put("ms", SystemClock.elapsedRealtime() - started)
                        )
                        check(actual == expected) {
                            "Speech fixture '$phrase' became '$transcript' ($actual), expected $expected. Results: $rows"
                        }
                        if (expected != Command.UNKNOWN)
                            check(recognizer.dictationDecodes == before) {
                                "Known commands unnecessarily invoked full dictation"
                            }
                    }
                    for (wake in listOf("hey vision")) {
                        val transcript = recognizer.decode(synthesize(tts, wake))
                        rows.put(JSONObject().put("phrase", wake).put("transcript", transcript))
                        check(
                            MuseVoiceSession().decide(transcript, 100) == MuseVoiceDecision.Wake
                        ) {
                            "Wake fixture '$wake' became '$transcript'"
                        }
                    }
                    val inline =
                        recognizer.decode(
                            synthesize(tts, "Hey Vision, where is the nearest hospital?")
                        )
                    val inlineDecision = MuseVoiceSession().decide(inline, 100)
                    check(
                        inlineDecision is MuseVoiceDecision.Ask &&
                            inlineDecision.question.contains("hospital")
                    ) {
                        "Inline wake question was lost: $inline"
                    }
                    rows.put(
                        JSONObject()
                            .put("phrase", "Hey Vision, where is the nearest hospital?")
                            .put("transcript", inline)
                    )
                    fullAllowed = true
                    val before = recognizer.dictationDecodes
                    val transcript =
                        recognizer.decode(synthesize(tts, "Where can I get help with this app?"))
                    check(transcript.isNotBlank() && recognizer.dictationDecodes > before)
                    return report.put("checks", rows).put("full_dictation_without_wake", transcript)
                }
        } finally {
            tts.shutdown()
        }
    }

    private fun globalVoiceChecks(): JSONObject {
        val activity =
            startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
                as MainActivity
        lateinit var vm: LibreVisionViewModel
        main {
            vm = ViewModelProvider(activity)[LibreVisionViewModel::class.java]
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val report = JSONObject()
        until { vm.modelSetup.value.phase == SetupPhase.READY && vm.listening.value.sessionActive }
        check(vm.state.value.handsFree) { "Saved temporary pause still disabled wake" }
        report.put("automatic_microphone_start", true)
        val originalNight = vm.state.value.nightMode
        val originalLarge = vm.state.value.largeText
        val originalFallback = vm.state.value.audioFallback
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts =
            TextToSpeech(targetContext) {
                status = it
                initialized.countDown()
            }
        val audio = targetContext.getSystemService(android.media.AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        try {
            main {
                vm.audioFallback(false)
                vm.open(Screen.HOME)
                vm.stopAudio()
            }
            check(initialized.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS)
            check(tts.setLanguage(Locale.US) >= 0)
            val modelDirectory = File(targetContext.filesDir, "english/vosk-model-small-en-us-0.15")
            val vadFile = File(targetContext.noBackupFilesDir, "silero/silero_vad.onnx")
            val vadPath =
                if (vadFile.exists()) vadFile
                else
                    targetContext.noBackupFilesDir.walkTopDown().first {
                        it.isFile && it.name.contains("silero") && it.extension == "onnx"
                    }
            val native = JSONArray()
            var allowed = false
            VoskUtteranceRecognizer(modelDirectory, { allowed }).use { recognizer ->
                for (phrase in
                    listOf(
                        "The weather is lovely today",
                        "Hey Vision",
                        "Press settings",
                        "Go back",
                        "Press start scanning",
                    )) {
                    val samples = synthesize(tts, phrase)
                    val results = mutableListOf<DecodedSpeech>()
                    com.librevision.data.speech.SileroVad(vadPath).use { vad ->
                        val segmenter = SpeechSegmenter()
                        var offset = 0
                        while (offset < samples.size) {
                            val frame = ShortArray(512)
                            samples.copyInto(frame, 0, offset, minOf(offset + 512, samples.size))
                            segmenter.accept(frame, vad.probability(frame))?.let { segment ->
                                results.add(recognizer.decodeDetailed(segment))
                                segment.fill(0)
                            }
                            offset += 512
                        }
                    }
                    samples.fill(0)
                    val decoded = results.firstOrNull { it.text.isNotBlank() }
                    if (phrase == "The weather is lovely today") check(decoded == null)
                    else if (phrase == "Hey Vision") {
                        check(decoded?.wakeDetected == true) { "Silero/Vosk wake missed: $results" }
                        allowed = true
                    } else
                        check(
                            decoded != null &&
                                (VoiceControls.match(
                                    decoded.text,
                                    listOf(
                                        VoiceControl("test", phrase.removePrefix("Press ")),
                                        VoiceControl("back", "Back", setOf("go back")),
                                    ),
                                ) != null ||
                                    VoiceCommandRouter().interpret(decoded?.text.orEmpty()) !=
                                        Command.UNKNOWN)
                        ) {
                            "Native command missed: $phrase -> $results"
                        }
                    native.put(
                        JSONObject()
                            .put("fixture", phrase)
                            .put("decoded", decoded?.text ?: "")
                            .put("vad_segments", results.size)
                    )
                }
            }
            report.put("real_silero_vosk_generated_audio", native)
            // Acoustic loopback: generated speech travels through the phone speaker and
            // AudioRecord.
            // No remote audio fallback is enabled during this test.
            main {
                vm.open(Screen.HOME)
                vm.dismissVoice()
                vm.stopAudio()
            }
            Thread.sleep(1800)
            audio.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                (audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) * .7f).toInt(),
                0,
            )
            fun play(phrase: String) {
                val pcm = synthesize(tts, phrase)
                val track =
                    android.media.AudioTrack.Builder()
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        .setAudioFormat(
                            android.media.AudioFormat.Builder()
                                .setSampleRate(16000)
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.size * 2)
                        .build()
                try {
                    check(track.write(pcm, 0, pcm.size) == pcm.size)
                    track.play()
                    Thread.sleep(pcm.size * 1000L / 16000 + 1200)
                } finally {
                    track.stop()
                    track.release()
                    pcm.fill(0)
                }
            }
            play("Hey Vision")
            until { vm.state.value.voiceOpen }
            report.put("speaker_to_microphone_wake", true)
            play("Press settings")
            until { vm.state.value.screen == Screen.SETTINGS }
            Thread.sleep(1800) // Let the short Settings acknowledgement and speaker tail finish.
            play("Go back")
            until { vm.state.value.screen == Screen.HOME && !vm.state.value.voiceOpen }
            report.put("acoustic_followup_settings_and_back", true)
            main {
                vm.pauseVoiceCapture()
                vm.stopAudio()
            }
            for (screen in Screen.entries.filter { it != Screen.HOME }) {
                main {
                    vm.open(Screen.HOME)
                    vm.open(screen)
                    vm.transcript("Hey Vision, go back")
                    check(vm.state.value.screen == Screen.HOME) { "Back failed on $screen" }
                }
            }
            main {
                vm.open(Screen.NAVIGATION)
                vm.showDestinations()
                vm.transcript("Hey Vision, go back")
                check(
                    !vm.navigationState.value.destinationDialog &&
                        vm.state.value.screen == Screen.NAVIGATION
                )
                vm.transcript("go back")
                check(vm.state.value.screen == Screen.HOME)
                vm.transcript("Hey Vision, press settings")
                check(vm.state.value.screen == Screen.SETTINGS)
                vm.transcript("Press more settings")
                check(vm.state.value.settingsExpanded)
                vm.transcript("Press night")
                check(vm.state.value.nightMode)
                vm.transcript("Press large")
                check(vm.state.value.largeText)
                vm.transcript("Hey Vision, go back")
                check(vm.state.value.screen == Screen.HOME)
                vm.transcript("Hey Vision, press check footpath")
                check(vm.state.value.screen == Screen.FOOTPATH)
                vm.transcript("press start scanning")
                check(vm.state.value.scanRequested)
                vm.transcript("press stop scanning")
                check(!vm.state.value.scanRequested)
                vm.open(Screen.SCENE)
                vm.describe()
                check(
                    vm.surroundingsState.value.phase ==
                        com.librevision.domain.scene.SurroundingsPhase.CONSENT
                )
                vm.transcript("Hey Vision, press cancel")
                check(
                    vm.surroundingsState.value.phase !=
                        com.librevision.domain.scene.SurroundingsPhase.CONSENT
                )
            }
            main {
                vm.open(Screen.SETTINGS)
                vm.transcript("press replay tour")
                check(vm.welcome.value.stage == com.librevision.feature.welcome.WelcomeStage.TOUR)
                vm.transcript("Hey Vision, pause")
                check(vm.welcome.value.paused)
                val lesson = vm.welcome.value.step
                vm.transcript("press next")
                check(vm.welcome.value.step == lesson + 1)
                vm.transcript("press skip tour")
                check(
                    vm.welcome.value.stage == com.librevision.feature.welcome.WelcomeStage.COMPLETE
                )
                vm.pauseVoiceCapture()
            }
            report.put("tour_pause_next_skip_voice", true)
            report.put("global_page_button_sheet_controls", true)
            val museField =
                LibreVisionViewModel::class.java.getDeclaredField("muse").apply {
                    isAccessible = true
                }
            val actualMuse = museField.get(vm)
            val screens = mutableListOf<String>()
            try {
                main {
                    museField.set(
                        vm,
                        com.librevision.domain.assistant.MuseAssistant { request ->
                            screens.add(JSONObject(request.context).getString("screen"))
                            check(request.question == "Why does rain fall?")
                            check(request.context.length <= 4000) {
                                "Assistant context exceeds gateway limit"
                            }
                            Result.success(
                                com.librevision.domain.assistant.AssistantReply(
                                    "Generated test answer."
                                )
                            )
                        },
                    )
                }
                for (screen in Screen.entries) {
                    main {
                        vm.open(screen)
                        vm.transcript("Hey Vision, Why does rain fall?")
                    }
                    until {
                        vm.assistantState.value.phase ==
                            com.librevision.domain.assistant.AssistantPhase.ANSWER
                    }
                }
                check(screens == Screen.entries.map { it.name })
                report.put("arbitrary_question_all_pages_fixture", screens.size)
            } finally {
                main {
                    vm.open(Screen.HOME)
                    museField.set(vm, actualMuse)
                }
            }

            // Live gateway, synthetic scenario, no camera image or microphone upload.
            main {
                vm.open(Screen.HOME)
                vm.transcript("Hey Vision, what can you help me with if I feel lost?")
            }
            until {
                vm.assistantState.value.phase !=
                    com.librevision.domain.assistant.AssistantPhase.THINKING
            }
            check(
                vm.assistantState.value.phase ==
                    com.librevision.domain.assistant.AssistantPhase.ANSWER
            ) {
                vm.assistantState.value.answer
            }
            report.put("live_general_assistance", vm.assistantState.value.answer)
            return report
        } finally {
            tts.shutdown()
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, originalVolume, 0)
            main {
                vm.audioFallback(originalFallback)
                vm.appearance(originalNight)
                vm.settings(large = originalLarge)
                if (vm.state.value.settingsExpanded) vm.toggleSettingsExpanded()
                vm.open(Screen.HOME)
                vm.resumeVoiceCapture()
            }
        }
    }

    private fun wakePrivacyChecks(): JSONObject {
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts =
            TextToSpeech(targetContext) {
                status = it
                initialized.countDown()
            }
        var clock = SystemClock.elapsedRealtime()
        val gate = VoiceInputWindow { clock }
        val rows = JSONArray()
        try {
            check(initialized.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS)
            check(tts.setLanguage(Locale.US) >= 0)
            VoskUtteranceRecognizer(
                    File(targetContext.filesDir, "english/vosk-model-small-en-us-0.15"),
                    gate::allowsDictation,
                )
                .use { recognizer ->
                    for (phrase in
                        listOf(
                            "Hey Muse",
                            "Hey News",
                            "The weather is lovely today",
                            "Start scanning",
                            "Navigate to Square Hospital",
                            "Please check footpath",
                            "Can you bring some water",
                            "Stop scanning",
                        )) {
                        val samples = synthesize(tts, phrase)
                        val result =
                            try {
                                recognizer.decodeDetailed(samples)
                            } finally {
                                samples.fill(0)
                            }
                        check(result.text.isBlank() && !result.wakeDetected) {
                            "Ambient speech activated wake: '$phrase' -> $result"
                        }
                        check(gate.authorize(result) == null)
                        check(recognizer.commandDecodes == 0 && recognizer.dictationDecodes == 0) {
                            "Idle audio reached command/full STT"
                        }
                        rows.put(
                            JSONObject()
                                .put("fixture", phrase)
                                .put("ignored_without_command_or_upload", true)
                        )
                    }
                    for (wake in listOf("Hey Vision")) {
                        val samples = synthesize(tts, wake)
                        val result =
                            try {
                                recognizer.decodeDetailed(samples)
                            } finally {
                                samples.fill(0)
                            }
                        check(result.wakeDetected && AppSpeechPhrases.hasWake(result.text)) {
                            "Wake missed: '$wake' -> $result"
                        }
                        check(gate.authorize(result) != null && gate.allowsDictation())
                        val request = synthesize(tts, "Start scanning")
                        val decoded =
                            try {
                                recognizer.decodeDetailed(request)
                            } finally {
                                request.fill(0)
                            }
                        check(
                            VoiceCommandRouter().interpret(decoded.text) == Command.START_SCANNING
                        )
                        val authorization = checkNotNull(gate.authorize(decoded))
                        check(!gate.allowsDictation() && gate.authorized(authorization))
                        val before = recognizer.commandDecodes
                        val background = synthesize(tts, "Navigate to Square Hospital")
                        val unrelated =
                            try {
                                recognizer.decodeDetailed(background)
                            } finally {
                                background.fill(0)
                            }
                        check(
                            unrelated.text.isBlank() &&
                                gate.authorize(unrelated) == null &&
                                recognizer.commandDecodes == before
                        )
                        gate.finish(authorization)
                        check(gate.allowsDictation())
                        clock += 17000
                        val go = synthesize(tts, "Go")
                        val followup =
                            try {
                                recognizer.decodeDetailed(go)
                            } finally {
                                go.fill(0)
                            }
                        check(
                            VoiceCommandRouter().interpret(followup.text) ==
                                Command.START_NAVIGATION
                        ) {
                            "Follow-up Go missed: $followup"
                        }
                        gate.finish(checkNotNull(gate.authorize(followup)))
                        clock += 18000
                        check(!gate.allowsDictation())
                        val after = synthesize(tts, "Start scanning")
                        val expired =
                            try {
                                recognizer.decodeDetailed(after)
                            } finally {
                                after.fill(0)
                            }
                        check(expired.text.isBlank() && gate.authorize(expired) == null)
                        rows.put(
                            JSONObject()
                                .put("fixture", wake)
                                .put("transcript", result.text)
                                .put("go_without_second_wake", true)
                                .put("expires_after_18_seconds", true)
                        )
                    }
                    val inline = synthesize(tts, "Hey Vision, where is the nearest hospital?")
                    val decoded =
                        try {
                            recognizer.decodeDetailed(inline)
                        } finally {
                            inline.fill(0)
                        }
                    check(decoded.wakeDetected && decoded.text.contains("hospital")) {
                        "Inline wake request lost: $decoded"
                    }
                    val id = checkNotNull(gate.authorize(decoded))
                    check(!gate.allowsDictation())
                    gate.finish(id)
                    rows.put(
                        JSONObject()
                            .put("fixture", "Hey Vision, where is the nearest hospital?")
                            .put("transcript", decoded.text)
                    )
                    gate.open() // Explicit voice-button tap starts the same timed conversation.
                    checkNotNull(gate.claim())
                    check(!gate.allowsDictation())
                    gate.close()
                    val report =
                        JSONObject()
                            .put("generated_audio_only", true)
                            .put("network_requests", 0)
                            .put("idle_full_stt_decodes", 0)
                            .put("followup_window_ms", 18000)
                            .put("checks", rows)
                    File(targetContext.filesDir, "wake-privacy-test.json")
                        .writeText(report.toString(2))
                    return report
                }
        } finally {
            gate.close()
            tts.shutdown()
        }
    }

    private fun followupChecks(): JSONObject {
        val activity =
            startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
                as MainActivity
        lateinit var model: LibreVisionViewModel
        main { model = ViewModelProvider(activity)[LibreVisionViewModel::class.java] }
        until { model.modelSetup.value.phase == SetupPhase.READY }
        val originalHandsFree = model.state.value.handsFree
        val originalAudio = model.state.value.audioFallback
        fun field(name: String) =
            LibreVisionViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        val placesField = field("places")
        val originalPlaces = placesField.get(model)
        val museField = field("muse")
        val originalMuse = museField.get(model)
        val routesField = field("routes")
        val originalRoutes = routesField.get(model)
        val controller =
            field("voiceCommands").get(model) as com.librevision.data.speech.VoiceCommandController
        val gate =
            com.librevision.data.speech.VoiceCommandController::class
                .java
                .getDeclaredField("inputWindow")
                .apply { isAccessible = true }
                .get(controller) as VoiceInputWindow
        val dispatch =
            LibreVisionViewModel::class
                .java
                .getDeclaredMethod(
                    "handleRecognizedSpeech",
                    com.librevision.data.speech.RecognizedCommand::class.java,
                )
                .apply { isAccessible = true }
        val destination =
            Destination("followup-fixture", "Square Hospital", GeoPoint(23.752, 90.381))
        val museCalls = java.util.concurrent.atomic.AtomicInteger()
        val routeCalls = java.util.concurrent.atomic.AtomicInteger()
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts =
            TextToSpeech(targetContext) {
                status = it
                initialized.countDown()
            }
        try {
            main {
                model.pauseVoiceCapture()
                model.audioFallback(false)
                placesField.set(
                    model,
                    object : com.librevision.data.routing.PlaceSearchProvider {
                        override suspend fun search(
                            query: String,
                            near: GeoPoint?,
                        ): Result<List<Destination>> {
                            kotlinx.coroutines.delay(300)
                            return Result.success(listOf(destination))
                        }
                    },
                )
                museField.set(
                    model,
                    object : com.librevision.domain.assistant.MuseAssistant {
                        override suspend fun ask(
                            request: com.librevision.domain.assistant.AssistantRequest
                        ): Result<com.librevision.domain.assistant.AssistantReply> {
                            museCalls.incrementAndGet()
                            kotlinx.coroutines.delay(300)
                            return Result.success(
                                com.librevision.domain.assistant.AssistantReply(
                                    null,
                                    com.librevision.domain.assistant.MuseAction(
                                        com.librevision.domain.assistant.MuseActionKind
                                            .SEARCH_DESTINATION,
                                        query = "Square Hospital",
                                    ),
                                )
                            )
                        }
                    },
                )
                routesField.set(
                    model,
                    object : RouteProvider {
                        override suspend fun calculateRoute(
                            destination: Destination,
                            origin: LocationFix?,
                        ): Result<WalkingRoute> {
                            check(destination.id == "followup-fixture")
                            routeCalls.incrementAndGet()
                            return Result.failure(
                                IllegalStateException(
                                    "Route request reached fixture; no real route generated"
                                )
                            )
                        }
                    },
                )
            }
            check(initialized.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS)
            check(tts.setLanguage(Locale.US) >= 0)
            val rows = JSONArray()
            VoskUtteranceRecognizer(
                    File(targetContext.filesDir, "english/vosk-model-small-en-us-0.15"),
                    gate::allowsDictation,
                )
                .use { recognizer ->
                    fun say(phrase: String) {
                        val samples = synthesize(tts, phrase)
                        val decoded =
                            try {
                                recognizer.decodeDetailed(samples)
                            } finally {
                                samples.fill(0)
                            }
                        val authorization =
                            checkNotNull(gate.authorize(decoded)) { "Rejected '$phrase': $decoded" }
                        main {
                            dispatch.invoke(
                                model,
                                com.librevision.data.speech.RecognizedCommand(
                                    VoiceCommandRouter().interpret(decoded.text),
                                    decoded.text,
                                    0,
                                    confidence = decoded.confidence,
                                    activationId = authorization,
                                ),
                            )
                        }
                    }
                    for ((wake, request) in
                        listOf(
                            "Hey Vision" to "Navigate to Square Hospital",
                            "Hey Vision" to "Where is the nearest hospital?",
                        )) {
                        main {
                            model.dismissVoice()
                            model.open(Screen.HOME)
                        }
                        say(wake)
                        say(request)
                        until {
                            model.navigationState.value.selected?.id == destination.id &&
                                controller.awaitingCommand()
                        }
                        check(model.state.value.screen == Screen.NAVIGATION)
                        val beforeMuse = museCalls.get()
                        val beforeRoute = routeCalls.get()
                        @Suppress("UNCHECKED_CAST")
                        val nav =
                            field("nav").get(model)
                                as
                                MutableStateFlow<
                                    com.librevision.feature.navigation.NavigationUiState
                                >
                        main {
                            nav.update {
                                it.copy(
                                    location =
                                        LocationFix(23.75, 90.38, SystemClock.elapsedRealtime(), 5f)
                                )
                            }
                        }
                        say("Go")
                        until { routeCalls.get() == beforeRoute + 1 }
                        check(museCalls.get() == beforeMuse) {
                            "Go was sent to Muse instead of starting navigation locally"
                        }
                        check(controller.awaitingCommand())
                        rows.put(
                            JSONObject()
                                .put("wake", wake)
                                .put("request", request)
                                .put("go_without_second_wake", true)
                                .put("go_is_local", true)
                        )
                    }
                }
            val report =
                JSONObject()
                    .put("generated_audio_with_native_vosk", true)
                    .put("simulated_search_muse_and_route_providers", true)
                    .put("cloud_audio_uploads", 0)
                    .put("muse_fixture_calls", museCalls.get())
                    .put("route_fixture_calls", routeCalls.get())
                    .put("checks", rows)
            File(targetContext.filesDir, "voice-followup-test.json").writeText(report.toString(2))
            return report
        } finally {
            tts.shutdown()
            main {
                model.stopNavigation()
                model.dismissVoice()
                model.open(Screen.HOME)
                placesField.set(model, originalPlaces)
                museField.set(model, originalMuse)
                routesField.set(model, originalRoutes)
                model.audioFallback(originalAudio)
                model.handsFree(originalHandsFree)
            }
        }
    }

    private fun cloudSpeechChecks(): JSONObject {
        val prefs = com.librevision.data.remote.GatewayPreferences(targetContext)
        val original = prefs.audioConsent
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts =
            TextToSpeech(targetContext) {
                status = it
                initialized.countDown()
            }
        try {
            check(initialized.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS)
            check(tts.setLanguage(Locale.US) >= 0)
            prefs.audioConsent = true
            val provider =
                com.librevision.data.remote.GatewayAudioTranscriptionProvider(prefs, targetContext)
            val results = JSONArray()
            for (phrase in listOf("Navigate to Square Hospital", "Please check footpath")) {
                val samples = synthesize(tts, phrase)
                try {
                    val packet = com.librevision.data.speech.CompressedUtterance.encode(samples)
                    val compressedBytes = packet.size
                    packet.fill(0)
                    val start = SystemClock.elapsedRealtime()
                    val text =
                        kotlinx.coroutines.runBlocking { provider.transcribe(samples).getOrThrow() }
                    val command = VoiceCommandRouter().interpret(text)
                    if (phrase.startsWith("Navigate"))
                        check(
                            command == Command.NAVIGATION_DESTINATION &&
                                text.contains("square", true)
                        ) {
                            "Place transcription mismatch: $text"
                        }
                    else
                        check(command == Command.CHECK_FOOTPATH) {
                            "Command transcription mismatch: $text"
                        }
                    results.put(
                        JSONObject()
                            .put("fixture", phrase)
                            .put("transcript", text)
                            .put("command", command.name)
                            .put("raw_bytes", 44 + samples.size * 2)
                            .put("compressed_bytes", compressedBytes)
                            .put("round_trip_ms", SystemClock.elapsedRealtime() - start)
                    )
                    val offline =
                        com.librevision.data.remote.GatewayAudioTranscriptionProvider(
                            prefs,
                            targetContext,
                            networkOverride = { false },
                        )
                    val failure = kotlinx.coroutines.runBlocking { offline.transcribe(samples) }
                    check(
                        failure.isFailure &&
                            failure.exceptionOrNull()?.message?.contains("No internet") == true
                    )
                    prefs.audioConsent = false
                    check(kotlinx.coroutines.runBlocking { provider.transcribe(samples) }.isFailure)
                    prefs.audioConsent = true
                } finally {
                    samples.fill(0)
                }
            }
            val report =
                JSONObject()
                    .put("generated_audio_only", true)
                    .put("real_meta_voice_api", true)
                    .put("offline_state_injected_no_request", true)
                    .put("consent_off_blocks_upload", true)
                    .put("results", results)
            File(targetContext.filesDir, "audio-fallback-test.json").writeText(report.toString(2))
            return report
        } finally {
            prefs.audioConsent = original
            tts.shutdown()
        }
    }

    private fun synthesize(tts: TextToSpeech, text: String): ShortArray {
        val completed = CountDownLatch(1)
        var failed = false
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    completed.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    failed = true
                    completed.countDown()
                }
            }
        )
        val file = File(targetContext.cacheDir, "ux-synthetic-speech.wav")
        try {
            check(tts.synthesizeToFile(text, Bundle(), file, "ux-fixture") == TextToSpeech.SUCCESS)
            check(completed.await(20, TimeUnit.SECONDS) && !failed)
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var position = 12
            var rate = 0
            var channels = 0
            var bits = 0
            var start = 0
            var length = 0
            while (position + 8 <= bytes.size) {
                val chunk = String(bytes, position, 4, Charsets.US_ASCII)
                val size = buffer.getInt(position + 4)
                check(size >= 0 && position + 8L + size <= bytes.size)
                if (chunk == "fmt ") {
                    check(buffer.getShort(position + 8).toInt() == 1)
                    channels = buffer.getShort(position + 10).toInt()
                    rate = buffer.getInt(position + 12)
                    bits = buffer.getShort(position + 22).toInt()
                }
                if (chunk == "data") {
                    start = position + 8
                    length = size
                    break
                }
                position += 8 + size + (size % 2)
            }
            check(rate > 0 && channels == 1 && bits == 16 && length > 0)
            val input = ShortArray(length / 2) { buffer.getShort(start + it * 2) }
            val output =
                ShortArray((input.size * 16000L / rate).toInt()) { index ->
                    val source = index * rate / 16000.0
                    val left = source.toInt().coerceAtMost(input.lastIndex)
                    val right = (left + 1).coerceAtMost(input.lastIndex)
                    (input[left] + (input[right] - input[left]) * (source - left)).toInt().toShort()
                }
            return ShortArray(3200 + output.size + 11200).also { output.copyInto(it, 3200) }
        } finally {
            file.delete()
        }
    }
}
