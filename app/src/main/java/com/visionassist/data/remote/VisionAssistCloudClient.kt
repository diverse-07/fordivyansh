package com.visionassist.data.remote

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * VisionAssist Cloud Client
 * Connects the mobile app to the FastAPI Gemini 2.0 Flash backend
 * for 12-frame Surroundings video analysis, AI conversational Q&A, and SOS dispatch.
 */
class VisionAssistCloudClient(
    private var baseUrl: String = "http://10.0.2.2:8000" // Default Android Emulator host, or Railway cloud URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    /**
     * Sends a camera frame to Gemini 2.0 Flash for audio-first pedestrian scene analysis.
     */
    suspend fun analyzeScene(bitmap: Bitmap): Result<SceneAnalysisResult> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val byteArray = stream.toByteArray()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "frame.jpg",
                    byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/api/v1/scene/analyze")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Server returned HTTP ${response.code}: ${response.message}")
            }

            val body = response.body?.string() ?: throw Exception("Empty response from server")
            val json = JSONObject(body).getJSONObject("data")

            SceneAnalysisResult(
                speechSummary = json.getString("speech_summary"),
                walkablePath = json.optString("walkable_path", "Clear path ahead"),
                suggestedAction = json.optString("suggested_action", "continue_straight"),
                safetyBeaconNeeded = json.optBoolean("safety_beacon_needed", false),
                beaconReason = json.optString("beacon_reason", "")
            )
        }
    }

    /**
     * Voice conversation query regarding current surroundings.
     */
    suspend fun askAssistant(question: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonPayload = JSONObject().put("question", question).toString()
            val requestBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$baseUrl/api/v1/chat/message")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response from assistant")
            val json = JSONObject(body)
            json.getString("response")
        }
    }

    /**
     * Broadcasts emergency SOS and coordinates to caretakers.
     */
    suspend fun triggerEmergencySOS(
        userId: String,
        latitude: Double,
        longitude: Double,
        triggerReason: String = "manual_triple_tap"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonPayload = JSONObject().apply {
                put("user_id", userId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("trigger_reason", triggerReason)
            }.toString()

            val requestBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$baseUrl/api/v1/emergency/sos")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        }
    }
}

data class SceneAnalysisResult(
    val speechSummary: String,
    val walkablePath: String,
    val suggestedAction: String,
    val safetyBeaconNeeded: Boolean,
    val beaconReason: String
)
