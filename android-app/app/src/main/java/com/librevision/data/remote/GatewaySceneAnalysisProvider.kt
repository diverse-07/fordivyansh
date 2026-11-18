package com.librevision.data.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Base64
import com.librevision.BuildConfig
import com.librevision.domain.navigation.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*

class GatewayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("scene_gateway", Context.MODE_PRIVATE)
    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    var url: String
        get() =
            GatewayConfiguration.resolve(
                BuildConfig.MUSE_GATEWAY_URL,
                debug,
                prefs.getString("developer_url", null),
            )
        set(value) {
            check(debug)
            require(validGatewayUrl(value, true))
            prefs
                .edit()
                .apply {
                    if (value.isBlank() || value.trimEnd('/') == BuildConfig.MUSE_GATEWAY_URL)
                        remove("developer_url")
                    else putString("developer_url", value.trimEnd('/'))
                }
                .apply()
        }

    var access: String
        get() =
            if (debug && !prefs.getString("developer_url", null).isNullOrBlank())
                prefs.getString("developer_access", "")!!
            else ""
        set(value) {
            check(debug)
            prefs.edit().putString("developer_access", value).apply()
        }

    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(value) {
            prefs.edit().putBoolean("consent", value).apply()
        }

    var audioConsent: Boolean
        get() = prefs.getBoolean("audioConsent", false)
        set(value) {
            prefs.edit().putBoolean("audioConsent", value).apply()
        }

    companion object {
        fun validGatewayUrl(value: String, debug: Boolean) =
            GatewayConfiguration.validUrl(value, debug)
    }
}

class GatewaySceneAnalysisProvider(private val prefs: GatewayPreferences) : SceneAnalysisProvider {
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .build()
    override val unavailableReason: String?
        get() = if (prefs.url.isBlank()) GatewayConfiguration.UNAVAILABLE else null

    override suspend fun analyze(request: SceneRequest): Result<SceneResponse> =
        try {
            check(unavailableReason == null) { unavailableReason!! }
            require(
                request.task in
                    setOf(
                        SceneTask.DESCRIBE,
                        SceneTask.CONTEXTUALIZE_MANEUVER,
                        SceneTask.VISUAL_QUESTION,
                    ) && request.model == SceneModel.MUSE_SPARK
            )
            require(
                request.jpeg.size in 1..512000 &&
                    request.width in 1..1024 &&
                    request.height in 1..1024
            )
            require((request.routeContext?.length ?: 0) <= 300)
            val body =
                withContext(Dispatchers.Default) {
                    JSONObject()
                        .put("task", request.task.name)
                        .put("model", "muse-spark-1.3")
                        .put(
                            "image",
                            "data:image/jpeg;base64," +
                                Base64.encodeToString(request.jpeg, Base64.NO_WRAP),
                        )
                        .put(
                            "question",
                            request.question
                                ?: "Explain the selected view in relation to the supplied mapped maneuver.",
                        )
                        .put("width", request.width)
                        .put("height", request.height)
                        .put("routeContext", request.routeContext ?: JSONObject.NULL)
                        .put(
                            "detections",
                            JSONArray(request.detections.map { it.label }.distinct().take(10)),
                        )
                        .toString()
                }
            val builder =
                Request.Builder()
                    .url(prefs.url.trimEnd('/') + "/analyze")
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
                                                    "Muse is unreachable. Local alerts continue."
                                                )
                                            )
                                        )
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    val result = runCatching {
                                        response.use {
                                            if (!it.isSuccessful)
                                                throw IOException(
                                                    when (it.code) {
                                                        401,
                                                        403 ->
                                                            "Muse access is unavailable. Local alerts continue."
                                                        429 ->
                                                            "Muse rate limit reached. Try again shortly."
                                                        504 ->
                                                            "Muse timed out. Local alerts continue."
                                                        else ->
                                                            "Muse is unavailable. Local alerts continue."
                                                    }
                                                )
                                            val reader = checkNotNull(it.body).charStream()
                                            val text = StringBuilder()
                                            val buffer = CharArray(4096)
                                            while (true) {
                                                val n = reader.read(buffer)
                                                if (n < 0) break
                                                text.append(buffer, 0, n)
                                                check(text.length <= 32000) {
                                                    "Muse response is too large"
                                                }
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
            Result.success(withContext(Dispatchers.Default) { GatewayResponseParser.parse(raw) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}

object GatewayResponseParser {
    fun parse(raw: String): SceneResponse {
        require(raw.length <= 32000)
        val root = JSONObject(raw)
        require(root.getString("model") == "muse-spark-1.3")
        if (root.has("text")) {
            val answer = MuseReplyParser.text(root.getString("text"))
            val reported = root.optJSONObject("usage")
            fun tokens(key: String) =
                reported?.optLong(key, -1)?.takeIf { it in 0..10000000 }?.toInt()
            return SceneResponse(
                answer,
                null,
                root.optLong("latencyMs", -1).takeIf { it in 0..60000 },
                reported?.let {
                    ApiTokenUsage(
                        tokens("inputTokens"),
                        tokens("outputTokens"),
                        tokens("reasoningTokens"),
                    )
                },
            )
        }
        val data = root.getJSONObject("context")
        require(data.keys().asSequence().toSet() == setOf("f", "c", "p", "q", "n"))
        val name = data.getString("f")
        val feature = if (name == "NONE") null else VisualFeature.valueOf(name)
        val color = LandmarkColor.valueOf(data.getString("c"))
        val position = ImagePosition.valueOf(data.getString("p"))
        val certainty = data.getString("q")
        require(certainty in setOf("CONFIDENT", "UNCERTAIN"))
        val notice = data.getString("n")
        require(notice in setOf("NONE", "BUSY", "LOW_VISIBILITY"))
        val landmark =
            feature
                ?.takeIf { it != VisualFeature.OTHER }
                ?.let {
                    val label =
                        (if (color == LandmarkColor.NONE) ""
                        else color.name.lowercase(java.util.Locale.ROOT) + " ") + it.label
                    VisibleLandmark(
                        label,
                        position,
                        certainty == "UNCERTAIN" ||
                            it in setOf(VisualFeature.STAIRS, VisualFeature.ENTRANCE),
                    )
                }
        val context =
            VisualContext(
                listOfNotNull(landmark),
                if (notice == "BUSY") SceneCrowding.BUSY else SceneCrowding.UNKNOWN,
                if (notice == "LOW_VISIBILITY") SceneVisibility.LIMITED
                else SceneVisibility.UNKNOWN,
            )
        val reported = root.optJSONObject("usage")
        fun tokens(key: String) = reported?.optLong(key, -1)?.takeIf { it in 0..10000000 }?.toInt()
        val usage = reported?.let {
            ApiTokenUsage(tokens("inputTokens"), tokens("outputTokens"), tokens("reasoningTokens"))
        }
        return SceneResponse(
            context.spoken(),
            context,
            root.optLong("latencyMs", -1).takeIf { it in 0..60000 },
            usage,
        )
    }
}
