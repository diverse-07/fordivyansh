package com.librevision.data.remote

import android.util.Base64
import com.librevision.domain.assistant.*
import com.librevision.domain.navigation.NearbyCategory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*

class GatewayMuseAssistant(private val prefs: GatewayPreferences) : MuseAssistant {
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .callTimeout(23, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(21, TimeUnit.SECONDS)
            .build()

    override suspend fun ask(request: AssistantRequest): Result<AssistantReply> =
        try {
            check(prefs.url.isNotBlank()) { GatewayConfiguration.UNAVAILABLE }
            require(
                request.question.isNotBlank() &&
                    request.question.length <= 500 &&
                    request.context.length <= 4000
            )
            val body =
                withContext(Dispatchers.Default) {
                    val json =
                        JSONObject()
                            .put("question", request.question)
                            .put("context", request.context)
                            .put(
                                "history",
                                JSONArray().apply {
                                    request.history.takeLast(6).forEach {
                                        put(
                                            JSONObject()
                                                .put("role", it.role)
                                                .put("text", it.text.take(500))
                                        )
                                    }
                                },
                            )
                    request.surroundings?.let { scene ->
                        require(
                            scene.views.size in 10..12 &&
                                scene.views.sumOf { it.jpeg.size } <= 576000
                        )
                        json.put(
                            "surroundings",
                            JSONObject()
                                .put("ageMs", scene.ageMs)
                                .put("source", "video")
                                .put("durationMs", scene.videoDurationMs)
                                .put("initial", scene.initial),
                        )
                        json.put(
                            "views",
                            JSONArray().apply {
                                scene.views.forEach { view ->
                                    require(
                                        view.jpeg.size <= 48000 &&
                                            view.width in 1..512 &&
                                            view.height in 1..512
                                    )
                                    put(
                                        JSONObject()
                                            .put(
                                                "image",
                                                "data:image/jpeg;base64," +
                                                    Base64.encodeToString(
                                                        view.jpeg,
                                                        Base64.NO_WRAP,
                                                    ),
                                            )
                                            .put("width", view.width)
                                            .put("height", view.height)
                                            .put("offsetMs", view.offsetMs)
                                            .put(
                                                "ageMs",
                                                (android.os.SystemClock.elapsedRealtime() -
                                                        view.capturedAtMs)
                                                    .coerceAtLeast(0),
                                            )
                                    )
                                }
                            },
                        )
                    }
                    request.jpeg?.let {
                        require(
                            it.size <= 512000 &&
                                request.width in 1..1024 &&
                                request.height in 1..1024
                        )
                        json
                            .put(
                                "image",
                                "data:image/jpeg;base64," +
                                    Base64.encodeToString(it, Base64.NO_WRAP),
                            )
                            .put("width", request.width)
                            .put("height", request.height)
                    }
                    json.toString()
                }
            val builder =
                Request.Builder()
                    .url(prefs.url.trimEnd('/') + "/assistant")
                    .post(body.toRequestBody("application/json".toMediaType()))
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
                                                    "Netra Saathi is unreachable. Local commands still work."
                                                )
                                            )
                                        )
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    val result = runCatching {
                                        response.use {
                                            check(it.isSuccessful) {
                                                when (it.code) {
                                                    401,
                                                    403 -> "Netra Saathi gateway access denied."
                                                    429 -> "Netra Saathi is busy. Try again shortly."
                                                    504 -> "Netra Saathi timed out. Try again."
                                                    else ->
                                                        "Netra Saathi is unavailable. Local commands still work."
                                                }
                                            }
                                            val reader = checkNotNull(it.body).charStream()
                                            val text = StringBuilder()
                                            val chars = CharArray(2048)
                                            while (true) {
                                                val n = reader.read(chars)
                                                if (n < 0) break
                                                text.append(chars, 0, n)
                                                check(text.length <= 32000)
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
            Result.success(withContext(Dispatchers.Default) { MuseReplyParser.parse(raw) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}

object MuseReplyParser {
    fun text(value: String): String {
        val clean = value.replace(Regex("[*`#]"), "").replace(Regex("\\s+"), " ").trim()
        require(clean.isNotBlank() && clean.length <= 800)
        require(
            !Regex(
                    """(?:^|[.!?]\s+)(?:cross now|step into (?:the )?traffic|it(?:'s| is) safe to cross)""",
                    RegexOption.IGNORE_CASE,
                )
                .containsMatchIn(clean)
        )
        return clean
    }

    fun parse(raw: String): AssistantReply {
        val root = JSONObject(raw)
        val modelName = root.optString("model", "muse-spark-1.3")
        val action =
            root.optJSONObject("action")?.let { a ->
                require(
                    a.keys().asSequence().toSet() == setOf("action", "query", "category", "value")
                )
                fun optional(key: String) = if (a.isNull(key)) null else a.getString(key)
                MuseAction(
                        MuseActionKind.valueOf(a.getString("action")),
                        optional("query"),
                        optional("category")?.let(NearbyCategory::valueOf),
                        optional("value"),
                    )
                    .also { require(it.valid()) }
            }
        val answer = if (root.isNull("text")) null else text(root.getString("text"))
        require((answer != null) != (action != null))
        return AssistantReply(
            answer,
            action,
            root.optJSONObject("usage")?.optInt("outputTokens", -1)?.takeIf { it >= 0 },
        )
    }
}
