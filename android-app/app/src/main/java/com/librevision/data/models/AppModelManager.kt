package com.librevision.data.models

import android.content.Context
import com.librevision.data.speech.EnglishModelStore
import com.librevision.data.vision.rfdetr.ModelStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class SetupPhase {
    PREPARING,
    READY,
    ERROR,
}

data class SetupState(
    val phase: SetupPhase = SetupPhase.PREPARING,
    val message: String = "Getting ready…",
    val percentage: Int = 0,
)

data class ModelFiles(val vision: File, val english: File, val vad: File, val depth: File)

/** Sole owner of installation. Feature runtimes never download or race model extraction. */
class AppModelManager(private val context: Context) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val mutable = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = mutable
    @Volatile private var installed: ModelFiles? = null

    fun readyFiles(): ModelFiles =
        checkNotNull(installed) {
            "Offline models are still being prepared. Retry setup if a download failed."
        }

    fun prepare() {
        if (cancelled.get() || installed != null || !running.compareAndSet(false, true)) return
        mutable.value = SetupState()
        worker.execute {
            try {
                fun metadata(name: String) =
                    context.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
                val manifest = metadata("model-manifest.json").getJSONArray("files")
                val sizes = mutableMapOf<String, Long>()
                for (i in 0 until manifest.length()) {
                    val spec = manifest.getJSONObject(i)
                    if (spec.getString("name").endsWith(".tflite"))
                        sizes[spec.getString("name")] = spec.getLong("bytes")
                }
                sizes["speech"] = metadata("english-model.json").getLong("bytes")
                sizes["vad"] = metadata("vad-model.json").getLong("bytes")
                sizes["depth"] = metadata("monocular-model.json").getLong("bytes")
                val progress = PreparationProgress(sizes)
                fun bytes(key: String, amount: Long) {
                    if (!cancelled.get()) {
                        val percent = progress.update(key, amount)
                        if (percent != mutable.value.percentage)
                            mutable.value = SetupState(percentage = percent)
                    }
                }
                val vision = ModelStore(context, cancelled, bytes = ::bytes) {}.prepare()
                val english =
                    EnglishModelStore(context, cancelled, bytes = { bytes("speech", it) }) {}
                        .english()
                val vad = installVad { bytes("vad", it) }
                val depth = installDepth { bytes("depth", it) }
                if (!cancelled.get()) {
                    installed = ModelFiles(vision, english, vad, depth)
                    mutable.value = SetupState(SetupPhase.READY, "Ready", 100)
                }
            } catch (error: Exception) {
                if (!cancelled.get())
                    android.util.Log.e("NetraSaathiSetup", "Preparation failed", error)
                if (!cancelled.get())
                    mutable.value =
                        SetupState(
                            SetupPhase.ERROR,
                            "Couldn't finish setup. Check your connection and free space, then retry.",
                            mutable.value.percentage,
                        )
            } finally {
                running.set(false)
            }
        }
    }

    private fun installDepth(bytes: (Long) -> Unit): File {
        val metadata =
            context.assets.open("monocular-model.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        val target = File(context.noBackupFilesDir, "depth/${metadata.getString("sha256")}.onnx")
        fun valid(file: File): Boolean {
            if (!file.isFile || file.length() != metadata.getLong("bytes")) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val b = ByteArray(65536)
                while (true) {
                    check(!cancelled.get())
                    val n = input.read(b)
                    if (n < 0) break
                    digest.update(b, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } ==
                metadata.getString("sha256")
        }
        if (valid(target)) {
            bytes(metadata.getLong("bytes"))
            return target
        }
        target.parentFile!!.mkdirs()
        val part = File(target.path + ".part")
        try {
            var copied = 0L
            context.assets.open(metadata.getString("assetName")).use { input ->
                part.outputStream().use { output ->
                    val b = ByteArray(65536)
                    while (true) {
                        check(!cancelled.get())
                        val n = input.read(b)
                        if (n < 0) break
                        output.write(b, 0, n)
                        copied += n
                        bytes(copied)
                    }
                }
            }
            check(valid(part)) { "Bundled depth checksum mismatch" }
            check(part.renameTo(target))
            return target
        } finally {
            part.delete()
        }
    }

    private fun installVad(bytes: (Long) -> Unit): File {
        val spec =
            context.assets.open("vad-model.json").bufferedReader().use { JSONObject(it.readText()) }
        val target =
            File(context.noBackupFilesDir, "vad/${spec.getString("revision")}/silero_vad.onnx")
        val size = spec.getLong("bytes")
        val hash = spec.getString("sha256")
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    check(!cancelled.get()) { "Cancelled" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && target.length() == size && sha(target) == hash) {
            bytes(size)
            return target
        }
        target.parentFile!!.mkdirs()
        val temporary = File(target.path + ".part")
        val connection = URL(spec.getString("url")).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        try {
            check(connection.responseCode == 200) {
                "Silero download HTTP ${connection.responseCode}"
            }
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        check(!cancelled.get()) { "Cancelled" }
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        check(total <= size) { "Unexpected Silero model size" }
                        output.write(buffer, 0, n)
                        bytes(total)
                    }
                }
            }
            check(total == size && sha(temporary) == hash) { "Silero checksum mismatch" }
            check(temporary.renameTo(target)) { "Cannot save Silero model" }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
        return target
    }

    override fun close() {
        cancelled.set(true)
        worker.shutdown()
    }
}
