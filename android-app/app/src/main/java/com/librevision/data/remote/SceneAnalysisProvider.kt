package com.librevision.data.remote

import com.librevision.domain.vision.Detection

enum class SceneTask {
    DESCRIBE,
    VISUAL_QUESTION,
    CONTEXTUALIZE_MANEUVER,
    SEGMENT,
}

enum class SceneModel {
    MUSE_SPARK,
    SAM_3_1,
}

data class SceneRequest(
    val jpeg: ByteArray,
    val task: SceneTask,
    val model: SceneModel,
    val question: String?,
    val routeContext: String?,
    val detections: List<Detection>,
    val width: Int = 0,
    val height: Int = 0,
)

data class ApiTokenUsage(val input: Int?, val output: Int?, val reasoning: Int?)

data class SceneResponse(
    val text: String,
    val context: com.librevision.domain.navigation.VisualContext? = null,
    val latencyMs: Long? = null,
    val usage: ApiTokenUsage? = null,
)

interface SceneAnalysisProvider {
    val unavailableReason: String?

    suspend fun analyze(request: SceneRequest): Result<SceneResponse>
}

/** No HTTP or fabricated provider responses until the gateway contract is verified. */
class UnconfiguredSceneAnalysisProvider : SceneAnalysisProvider {
    override val unavailableReason =
        "Cloud AI gateway is not configured. No image was captured or sent."

    override suspend fun analyze(request: SceneRequest): Result<SceneResponse> =
        Result.failure(IllegalStateException("Cloud AI gateway is not configured. No image was sent."))
}
