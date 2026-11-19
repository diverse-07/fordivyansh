package com.librevision.domain.alerts

private val audioCueLessons = listOf(
    FeedbackCue.VOICE_TRIGGER to "Voice activated. This rising sound means the microphone was opened.",
    FeedbackCue.LISTENING to "Listening. Two bright notes mean you can start speaking.",
    FeedbackCue.PROCESSING to "Processing. A soft single tone means Saathi is working on your request.",
    FeedbackCue.CONFIRMED to "Ready. Two rising notes acknowledge a selection or action.",
    FeedbackCue.TURN_LEFT to "Turn left. Two falling notes accompany a mapped left turn.",
    FeedbackCue.TURN_RIGHT to "Turn right. Two rising notes accompany a mapped right turn.",
    FeedbackCue.OBSTACLE to "Obstruction. A low double pulse warns that something may be in your path.",
    FeedbackCue.CAPTURED to "View captured. A short chime means a picture was captured.",
    FeedbackCue.COMPLETE to "Complete. Three rising notes mean the capture finished.",
    FeedbackCue.UNAVAILABLE to "Unavailable. Two soft falling notes mean the requested action could not finish.",
)

suspend fun practiceAudioCues(
    narrate: suspend (String) -> Unit,
    play: suspend (FeedbackCue) -> Unit,
) {
    for ((cue, explanation) in audioCueLessons) {
        narrate(explanation)
        repeat(3) { play(cue) }
    }
}
