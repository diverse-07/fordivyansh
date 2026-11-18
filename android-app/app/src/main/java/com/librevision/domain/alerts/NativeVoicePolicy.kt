package com.librevision.domain.alerts

/** Keep the selected device voice before considering any alternative installed voice. */
data class NativeVoice(
    val name: String,
    val language: String,
    val offline: Boolean,
    val installed: Boolean,
)

object NativeVoicePolicy {
    fun select(
        voices: List<NativeVoice>,
        language: String,
        currentName: String?,
        defaultName: String?,
    ): String? {
        val eligible = voices.filter { it.language == language && it.offline && it.installed }
        return eligible.firstOrNull { it.name == currentName }?.name
            ?: eligible.firstOrNull { it.name == defaultName }?.name
            ?: eligible.firstOrNull()?.name
    }
}
