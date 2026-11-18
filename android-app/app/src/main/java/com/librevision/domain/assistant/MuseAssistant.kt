package com.librevision.domain.assistant

import com.librevision.domain.navigation.NearbyCategory

enum class MuseActionKind {
    ACTIVATE_CONTROL,
    BACK,
    OPEN_SCREEN,
    NAVIGATE_NEARBY,
    FIND_NEARBY,
    SEARCH_DESTINATION,
    NAVIGATE_TO,
    START_NAVIGATION,
    STOP_NAVIGATION,
    START_SCANNING,
    STOP_SCANNING,
    INSPECT_VIEW,
    REPEAT,
    ROUTE_SUMMARY,
    RECENTER_MAP,
    TRAIN_AUDIO,
    STOP_AUDIO,
    SET_TEXT_SIZE,
    SET_VERBOSITY,
    SET_LANGUAGE,
    SET_DEPTH_ESTIMATES,
    SET_STEP_LENGTH,
    RETRY_MODEL_SETUP,
    CAPTURE_SURROUNDINGS,
    STOP_LISTENING,
    RESUME_LISTENING,
}

data class MuseAction(
    val kind: MuseActionKind,
    val query: String? = null,
    val category: NearbyCategory? = null,
    val value: String? = null,
) {
    fun valid(): Boolean {
        if (
            query != null &&
                (query.isBlank() || query.length > 160 || query.any { it.isISOControl() })
        )
            return false
        return when (kind) {
            MuseActionKind.ACTIVATE_CONTROL ->
                value != null &&
                    value.matches(Regex("[a-z][a-z0-9_]{0,39}")) &&
                    query == null &&
                    category == null
            MuseActionKind.NAVIGATE_NEARBY,
            MuseActionKind.FIND_NEARBY -> category != null && query == null && value == null
            MuseActionKind.SEARCH_DESTINATION,
            MuseActionKind.NAVIGATE_TO ->
                query != null && query.length >= 3 && category == null && value == null
            MuseActionKind.OPEN_SCREEN ->
                value in setOf("HOME", "NAVIGATION", "FOOTPATH", "SCENE", "TRAINING", "SETTINGS") &&
                    query == null &&
                    category == null
            MuseActionKind.INSPECT_VIEW -> category == null && value == null
            MuseActionKind.TRAIN_AUDIO ->
                value in setOf("ROUTINE", "URGENT") && category == null && query == null
            MuseActionKind.SET_TEXT_SIZE ->
                value in setOf("DEFAULT", "LARGE") && category == null && query == null
            MuseActionKind.SET_VERBOSITY ->
                value in setOf("BRIEF", "DETAILED") && category == null && query == null
            MuseActionKind.SET_LANGUAGE ->
                value in setOf("ENGLISH", "BANGLA") && category == null && query == null
            MuseActionKind.SET_STEP_LENGTH ->
                value?.toFloatOrNull()?.let { it.isFinite() && it in .4f..1f } == true &&
                    category == null &&
                    query == null
            MuseActionKind.SET_DEPTH_ESTIMATES ->
                value in setOf("ON", "OFF") && category == null && query == null
            else -> value == null && category == null && query == null
        }
    }
}

data class AssistantTurn(val role: String, val text: String)

data class AssistantRequest(
    val question: String,
    val context: String,
    val history: List<AssistantTurn> = emptyList(),
    val jpeg: ByteArray? = null,
    val width: Int = 0,
    val height: Int = 0,
    val surroundings: com.librevision.domain.scene.SurroundingsContext? = null,
)

data class AssistantReply(
    val text: String?,
    val action: MuseAction? = null,
    val outputTokens: Int? = null,
)

fun interface MuseAssistant {
    suspend fun ask(request: AssistantRequest): Result<AssistantReply>
}

enum class AssistantPhase {
    IDLE,
    LISTENING,
    THINKING,
    ANSWER,
    ERROR,
}

data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val query: String = "",
    val answer: String = "",
    val expectsFollowup: Boolean = false,
)
