package com.librevision.data.vision.rfdetr

import android.content.Context
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class ModelStore(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val bytes: (String, Long) -> Unit = { _, _ -> },
    private val progress: (String) -> Unit,
) {
    fun prepare(): File {
        val manifest =
            context.assets.open("model-manifest.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        val dir =
            File(context.noBackupFilesDir, "rfdetr/${manifest.getString("revision")}").apply {
                mkdirs()
            }
        val specs = manifest.getJSONArray("files")
        for (i in 0 until specs.length()) {
            val spec = specs.getJSONObject(i)
            if (!spec.getString("name").endsWith(".tflite")) continue
            active()
            val target = File(dir, spec.getString("name"))
            val expected = spec.getString("sha256")
            if (
                target.isFile && target.length() == spec.getLong("bytes") && sha(target) == expected
            ) {
                bytes(target.name, spec.getLong("bytes"))
                continue
            }
            val temporary = File(target.path + ".part")
            val repository = manifest.getString("repository")
            val revision = manifest.getString("revision")
            val connection =
                URL("https://huggingface.co/$repository/resolve/$revision/${target.name}")
                    .openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            try {
                check(connection.responseCode == 200) {
                    "Model download HTTP ${connection.responseCode}"
                }
                var total = 0L
                var reported = -1L
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            active()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            check(total <= spec.getLong("bytes")) { "Unexpected download size" }
                            output.write(buffer, 0, n)
                            bytes(target.name, total)
                            if (total / 1000000 != reported) {
                                reported = total / 1000000
                                progress(
                                    "Downloading ${target.name}: $reported / ${spec.getLong("bytes") / 1000000} MB"
                                )
                            }
                        }
                    }
                }
                check(total == spec.getLong("bytes") && sha(temporary) == expected) {
                    "Model checksum failed; retry Start"
                }
                check(temporary.renameTo(target)) { "Could not save model" }
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }
        return dir
    }

    private fun active() {
        if (cancelled.get()) throw InterruptedIOException("Cancelled")
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                active()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
