package com.librevision.data.speech

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

object CompressedUtterance {
    const val MIME = "application/vnd.netrasaathi.wav+gzip"

    fun encode(samples: ShortArray): ByteArray {
        require(samples.size in 1600..128000) { "Use one utterance between 0.1 and 8 seconds." }
        val pcmBytes = samples.size * 2
        val wave = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wave
            .put("RIFF".toByteArray())
            .putInt(36 + pcmBytes)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(16000)
            .putInt(32000)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray())
            .putInt(pcmBytes)
        samples.forEach(wave::putShort)
        return try {
            ByteArrayOutputStream().use { out ->
                object : GZIPOutputStream(out) {
                        init {
                            def.setLevel(Deflater.BEST_SPEED)
                        }
                    }
                    .use { it.write(wave.array()) }
                out.toByteArray()
            }
        } finally {
            wave.array().fill(0)
        }
    }
}
