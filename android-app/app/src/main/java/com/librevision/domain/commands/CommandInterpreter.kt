package com.librevision.domain.commands

import java.text.Normalizer

enum class Command {
    BACK,
    HOME,
    SETTINGS,
    START_SCENE_RECORDING,
    FINISH_SCENE_RECORDING,
    ROUTE_SUMMARY,
    EXPLAIN_NAVIGATION,
    CONFIRM_IMAGE_UPLOAD,
    CANCEL_IMAGE_UPLOAD,
    REPEAT_VISUAL_CONTEXT,
    NAVIGATION_DESTINATION,
    RECENTER_MAP,
    NEXT_DESTINATION,
    STOP_LISTENING,
    START_NAVIGATION,
    STOP_NAVIGATION,
    REPEAT_NAVIGATION,
    START_SCANNING,
    STOP_SCANNING,
    NAVIGATE,
    CHECK_FOOTPATH,
    DESCRIBE_SCENE,
    LEARN_AUDIO_CUES,
    REPEAT,
    STOP,
    HELP,
    EMERGENCY_ALARM,
    UNKNOWN,
}

class CommandInterpreter(private val aliases: Map<Command, Set<String>> = defaults) {
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    fun interpret(text: String): Command {
        val input = normalize(text)
        val matches = aliases.filterValues { values -> values.any { normalize(it) == input } }.keys
        return matches.singleOrNull() ?: Command.UNKNOWN
    }

    companion object {
        val defaults =
            mapOf(
                Command.BACK to setOf("back", "go back", "previous page", "close", "close dialog"),
                Command.HOME to setOf("home", "go home", "home page", "main menu"),
                Command.SETTINGS to setOf("settings", "open settings"),
                Command.START_SCENE_RECORDING to
                    setOf("start recording", "record video", "record surroundings", "start video"),
                Command.FINISH_SCENE_RECORDING to
                    setOf("finish recording", "stop recording", "finish video", "describe video"),
                Command.ROUTE_SUMMARY to setOf("route summary", "total route steps", "total steps"),
                Command.EXPLAIN_NAVIGATION to
                    setOf(
                        "ask saathi",
                        "hey saathi",
                        "netra saathi",
                        "ask muse",
                        "explain this view",
                        "explain next turn",
                        "what is ahead",
                        "visual guidance",
                        "saathi guidance",
                    ),
                Command.CONFIRM_IMAGE_UPLOAD to
                    setOf("send image", "send this image", "confirm image", "start capture"),
                Command.CANCEL_IMAGE_UPLOAD to
                    setOf("cancel image", "do not send image", "cancel capture"),
                Command.REPEAT_VISUAL_CONTEXT to
                    setOf("repeat context", "repeat saathi", "repeat visual guidance"),
                Command.RECENTER_MAP to
                    setOf("center map", "centre map", "recenter map", "show my location"),
                Command.NEXT_DESTINATION to setOf("next destination", "next result"),
                Command.STOP_LISTENING to setOf("stop listening", "microphone off"),
                Command.START_NAVIGATION to
                    setOf(
                        "start navigation",
                        "begin navigation",
                        "start route",
                        "go",
                        "lets go",
                        "let s go",
                    ),
                Command.STOP_NAVIGATION to setOf("stop navigation", "end navigation", "stop route"),
                Command.REPEAT_NAVIGATION to
                    setOf("repeat navigation", "repeat route guidance", "repeat route"),
                Command.NAVIGATE to
                    setOf("navigate", "navigation", "নেভিগেট", "পথ দেখাও", "রাস্তা দেখাও"),
                Command.CHECK_FOOTPATH to
                    setOf(
                        "check footpath",
                        "check foot path",
                        "check the footpath",
                        "check the foot path",
                        "check the but",
                        "check the path",
                        "ফুটপাত দেখো",
                        "ফুটপাত পরীক্ষা করো",
                    ),
                Command.DESCRIBE_SCENE to
                    setOf(
                        "capture surroundings",
                        "capture 360",
                        "start 360 capture",
                        "scan all around",
                        "describe surroundings",
                        "describe scene",
                        "tell me what i'm looking at",
                        "tell me what i am looking at",
                        "can you tell me what i'm looking at",
                        "can you tell me what i am looking at",
                        "what am i looking at",
                        "describe what's in front of me",
                        "tell me what's in front of me",
                        "চারপাশ বর্ণনা করো",
                        "চারপাশ দেখাও",
                    ),
                Command.LEARN_AUDIO_CUES to
                    setOf("learn audio cues", "audio training", "teach me the audio cues", "শব্দ শেখাও"),
                Command.REPEAT to setOf("repeat", "say again", "আবার বলো"),
                Command.STOP to setOf("stop", "cancel", "থামো", "বন্ধ করো"),
                Command.HELP to setOf("help", "how to use", "what can i say", "what can i do here", "voice commands", "সাহায্য"),
                Command.EMERGENCY_ALARM to setOf("emergency", "sos", "siren", "danger", "call help", "start siren"),
            )
    }
}
