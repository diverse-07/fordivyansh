package com.librevision.data.speech

import com.librevision.domain.commands.AppSpeechPhrases
import com.librevision.domain.commands.DecodedSpeech
import com.librevision.domain.commands.UtteranceRecognizer
import com.sun.jna.NativeLibrary
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Construction, warm-up, decode, reset and close all run on the dedicated STT thread. */
class VoskUtteranceRecognizer(
    directory: File,
    private val dictationAllowed: () -> Boolean = { true },
) : UtteranceRecognizer {
    private val model = Model(directory.path)
    // Grammar cannot add new acoustic words. Skip entire unsupported phrases instead of silently
    // dropping names.
    val supportedPhrases: Set<String>
    private val commands: Recognizer
    private val wakeRecognizer: Recognizer
    private var dictation: Recognizer? = null
    var commandDecodes = 0
        private set

    var dictationDecodes = 0
        private set

    init {
        try {
            val lookup = NativeLibrary.getInstance("vosk").getFunction("vosk_model_find_word")
            val vocabulary = mutableMapOf<String, Boolean>()
            supportedPhrases =
                AppSpeechPhrases.phrases
                    .filter { phrase ->
                        phrase.split(' ').all { word ->
                            vocabulary.getOrPut(word) {
                                lookup.invokeInt(arrayOf(model.pointer, word)) >= 0
                            }
                        }
                    }
                    .toSet()
            check("check foot path" in supportedPhrases && "hey vision" in supportedPhrases) {
                "English model does not support the app phrase grammar"
            }
            commands =
                Recognizer(model, 16000f, JSONArray(supportedPhrases.toList() + "[unk]").toString())
                    .apply { setWords(true) }
            wakeRecognizer =
                Recognizer(
                        model,
                        16000f,
                        JSONArray(
                                AppSpeechPhrases.wakePhrases.filter { it in supportedPhrases } +
                                    "[unk]"
                            )
                            .toString(),
                    )
                    .apply { setWords(true) }
        } catch (error: Throwable) {
            model.close()
            throw error
        }
        try {
            commands.acceptWaveForm(ShortArray(16000), 16000)
            commands.finalResult
            commands.reset()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun decode(samples: ShortArray): String = decodeDetailed(samples).text

    override fun decodeDetailed(samples: ShortArray): DecodedSpeech {
        if (!dictationAllowed()) {
            val wake = decodeWith(wakeRecognizer, samples)
            val text = wake.optString("text").trim()
            val words = wake.optJSONArray("result")
            val scores =
                words
                    ?.let { data ->
                        (0 until minOf(2, data.length())).map {
                            data.getJSONObject(it).optDouble("conf", 0.0)
                        }
                    }
                    .orEmpty()
            if (!AppSpeechPhrases.hasWake(text) || scores.size != 2 || scores.any { it < .75 })
                return DecodedSpeech("", 0f)
            val result = decodeActive(samples)
            return result.copy(
                text =
                    if (AppSpeechPhrases.hasWake(result.text)) result.text
                    else text,
                wakeDetected = true,
            )
        }
        return decodeActive(samples)
    }

    private fun decodeActive(samples: ShortArray): DecodedSpeech {
        commandDecodes++
        val candidate = decodeWith(commands, samples)
        val text = candidate.optString("text").trim()
        val words = candidate.optJSONArray("result")
        val confidence =
            words
                ?.let { data ->
                    (0 until data.length()).map { data.getJSONObject(it).optDouble("conf", 0.0) }
                }
                .orEmpty()
        val known =
            text in supportedPhrases &&
                AppSpeechPhrases.actionable(text) &&
                confidence.isNotEmpty() &&
                confidence.average() >= .75 &&
                confidence.all { it >= .4 }
        val wake =
            AppSpeechPhrases.hasWake(text) &&
                confidence.take(2).let { it.size == 2 && it.all { c -> c >= .55 } }
        if (known && !wake) return DecodedSpeech(text, confidence.average().toFloat())
        // Reached only after explicit wake/tap authorization, or a positively detected wake phrase.
        val full =
            dictation ?: Recognizer(model, 16000f).apply { setWords(true) }.also { dictation = it }
        dictationDecodes++
        val result = decodeWith(full, samples)
        val freeText = result.optString("text").trim()
        if (wake && !AppSpeechPhrases.hasWake(freeText))
            return DecodedSpeech(
                text,
                confidence.take(2).average().toFloat(),
            )
        val fullWords = result.optJSONArray("result")
        val scores =
            fullWords
                ?.let { data ->
                    (0 until data.length()).map { data.getJSONObject(it).optDouble("conf", 0.0) }
                }
                .orEmpty()
        return DecodedSpeech(freeText, if (scores.isEmpty()) 0f else scores.average().toFloat())
    }

    private fun decodeWith(recognizer: Recognizer, samples: ShortArray): JSONObject {
        return try {
            val endpoint = recognizer.acceptWaveForm(samples, samples.size)
            JSONObject(if (endpoint) recognizer.result else recognizer.finalResult)
        } finally {
            recognizer.reset()
        }
    }

    override fun close() {
        try {
            dictation?.close()
            commands.close()
            wakeRecognizer.close()
        } finally {
            model.close()
        }
    }
}
