package com.librevision.data.speech

import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** RunAnywhere Silero VAD v5 export. Sequential 512-sample frames, 64-sample context, 16 kHz. */
class SileroVad(model: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session =
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            environment.createSession(model.path, options)
        }
    private val state = FloatArray(2 * 128)
    private val input = FloatArray(64 + 512)

    init {
        try {
            check(session.inputNames == setOf("input", "state", "sr")) {
                "Unexpected Silero inputs"
            }
            check(session.outputNames.containsAll(setOf("output", "stateN"))) {
                "Unexpected Silero outputs"
            }
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    fun probability(samples: ShortArray): Float {
        require(samples.size == 512)
        for (i in samples.indices) input[64 + i] = samples[i] / 32768f
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 576)).use {
            audio ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
                .use { recurrent ->
                    OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(longArrayOf(16000)),
                            longArrayOf(),
                        )
                        .use { rate ->
                            session
                                .run(mapOf("input" to audio, "state" to recurrent, "sr" to rate))
                                .use { result ->
                                    val probability =
                                        (result.get("output").get() as OnnxTensor)
                                            .floatBuffer
                                            .get(0)
                                    (result.get("stateN").get() as OnnxTensor)
                                        .floatBuffer
                                        .get(state)
                                    check(probability.isFinite() && probability in 0f..1f) {
                                        "Invalid VAD result"
                                    }
                                    input.copyInto(input, 0, 512, 576)
                                    return probability
                                }
                        }
                }
        }
    }

    fun reset() {
        input.fill(0f)
        state.fill(0f)
    }

    override fun close() {
        session.close()
    } // Process-wide environment is shared; never close it here.
}
