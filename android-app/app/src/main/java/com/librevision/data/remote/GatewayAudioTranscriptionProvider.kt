package com.librevision.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.librevision.data.speech.CompressedUtterance
import com.librevision.domain.commands.AudioTranscriptionProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class GatewayAudioTranscriptionProvider(
    private val prefs: GatewayPreferences,
    context: Context,
    private val networkOverride: (() -> Boolean)? = null,
) : AudioTranscriptionProvider {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.SECONDS)
            .build()

    fun online(): Boolean {
        networkOverride?.let {
            return it()
        }
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun transcribe(samples: ShortArray): Result<String> {
        var compressed: ByteArray? = null
        return try {
            check(prefs.audioConsent) { "Online speech fallback is off in Settings." }
            check(online()) { "No internet. Please repeat or use the controls." }
            check(prefs.url.isNotBlank()) { GatewayConfiguration.UNAVAILABLE }
            val packet =
                withContext(Dispatchers.Default) { CompressedUtterance.encode(samples) }
                    .also { compressed = it }
            check(prefs.audioConsent) { "Online speech fallback is off in Settings." }
            val builder =
                Request.Builder()
                    .url(prefs.url.trimEnd('/') + "/transcribe")
                    .post(packet.toRequestBody(CompressedUtterance.MIME.toMediaType()))
            if (prefs.access.isNotBlank()) builder.header("Authorization", "Bearer ${prefs.access}")
            val raw =
                suspendCancellableCoroutine<Result<String>> { continuation ->
                        val call = client.newCall(builder.build())
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(
                            object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    if (continuation.isActive)
                                        continuation.resume(
                                            Result.failure(
                                                IOException(
                                                    "Could not reach online speech recognition. Check your connection and try again."
                                                )
                                            )
                                        )
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    val result = runCatching {
                                        response.use {
                                            check(it.isSuccessful) {
                                                when (it.code) {
                                                    401, 403 ->
                                                        "The configured gateway denied access to online speech."
                                                    404, 415 ->
                                                        "The configured gateway does not support this app’s online speech requests."
                                                    400, 413 ->
                                                        "The recording could not be processed. Try one short sentence."
                                                    429 ->
                                                        "Online speech has reached a request limit. Try again later."
                                                    422 ->
                                                        "Speech was not understood. Please repeat."
                                                    502, 503 ->
                                                        "Online speech recognition is temporarily unavailable. Try again."
                                                    504 ->
                                                        "Online speech recognition timed out. Try a shorter request."
                                                    else ->
                                                        "Online speech recognition failed. Try again."
                                                }
                                            }
                                            val reader = checkNotNull(it.body).charStream()
                                            val buffer = CharArray(1024)
                                            val text = StringBuilder()
                                            while (true) {
                                                val n = reader.read(buffer)
                                                if (n < 0) break
                                                text.append(buffer, 0, n)
                                                check(text.length <= 4000)
                                            }
                                            text.toString()
                                        }
                                    }
                                    if (continuation.isActive) continuation.resume(result)
                                }
                            }
                        )
                    }
                    .getOrThrow()
            val json = JSONObject(raw)
            val text = json.optString("text", "")
            require(text.isNotBlank() && text.length <= 500)
            Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                if (e is IOException || e is IllegalStateException) e
                else IOException("Online speech returned an invalid result. Please repeat.")
            )
        } finally {
            compressed?.fill(0)
        }
    }
}
