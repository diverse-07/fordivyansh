package com.librevision.data.speech

import android.content.Context
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import org.json.JSONObject

class EnglishModelStore(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val bytes: (Long) -> Unit = {},
    private val report: (String) -> Unit,
) {
    private fun checkActive() {
        if (cancelled.get()) throw InterruptedIOException("Cancelled")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                checkActive()
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, target: File, size: Long, hash: String) {
        if (target.isFile && target.length() == size && sha256(target) == hash) {
            bytes(size)
            return
        }
        checkActive()
        target.parentFile!!.mkdirs()
        val temporary = File(target.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        try {
            check(connection.responseCode == 200) {
                "Download failed: HTTP ${connection.responseCode}"
            }
            var total = 0L
            var lastUpdate = -1L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= size) { "Unexpected model download size" }
                        output.write(buffer, 0, n)
                        bytes(total)
                        digest.update(buffer, 0, n)
                        val mb = total / 1000000
                        if (mb != lastUpdate) {
                            report("Downloading ${target.name}: $mb / ${size / 1000000} MB")
                            lastUpdate = mb
                        }
                    }
                }
            }
            check(total == size) { "Incomplete model download; tap Start to retry" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == hash) { "Model checksum mismatch; tap Start to retry" }
            check(temporary.renameTo(target)) { "Cannot save model; check free storage" }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    fun english(): File {
        val base = File(context.filesDir, "english")
        val model = File(base, "vosk-model-small-en-us-0.15")
        val spec =
            context.assets.open("english-model.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        if (File(base, "ready").exists() && File(model, "am/final.mdl").isFile) {
            bytes(spec.getLong("bytes"))
            return model
        }
        val zip = File(context.cacheDir, "english.zip")
        download(spec.getString("url"), zip, spec.getLong("bytes"), spec.getString("sha256"))
        base.deleteRecursively()
        base.mkdirs()
        report("Unpacking English model…")
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                checkActive()
                val entry = input.nextEntry ?: break
                val out = File(base, entry.name)
                check(out.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
                    "Invalid model archive"
                }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile!!.mkdirs()
                    out.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
        check(File(model, "am/final.mdl").isFile && File(model, "conf/model.conf").isFile) {
            "Invalid English model"
        }
        File(base, "ready").writeText("1")
        zip.delete()
        return model
    }
}
