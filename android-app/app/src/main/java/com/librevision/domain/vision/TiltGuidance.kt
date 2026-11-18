package com.librevision.domain.vision

import kotlin.math.acos
import kotlin.math.sqrt

enum class PhoneTilt {
    UPRIGHT,
    ADJUST,
    UNKNOWN,
}

/** Gravity in display coordinates. This cannot measure physical height or camera direction. */
object TiltPolicy {
    fun classify(x: Float, y: Float, z: Float, rotationQuarterTurns: Int = 0): PhoneTilt {
        val norm = sqrt(x * x + y * y + z * z)
        if (!norm.isFinite() || norm < 6f || norm > 13f) return PhoneTilt.UNKNOWN
        // Android display axes: ROTATION_90 uses +Y and -X (SensorManager contract).
        val displayY =
            when (rotationQuarterTurns) {
                1 -> -x
                2 -> -y
                3 -> x
                else -> y
            }
        val angle = Math.toDegrees(acos((displayY / norm).coerceIn(-1f, 1f)).toDouble())
        return if (angle <= 35) PhoneTilt.UPRIGHT else PhoneTilt.ADJUST
    }
}

class TiltPromptPolicy {
    private var lastPrompt: Long? = null
    private var badSince: Long? = null
    private var explained = false

    fun update(tilt: PhoneTilt, now: Long): String? {
        if (tilt != PhoneTilt.ADJUST) {
            badSince = null
            return null
        }
        if (badSince == null) badSince = now
        if (now - badSince!! < 4000 || lastPrompt?.let { now - it < 60000 } == true) return null
        lastPrompt = now
        return if (explained) "Keep the phone upright."
        else "Hold the phone upright at chest height.".also { explained = true }
    }

    fun pause() {
        badSince = null
    }

    fun reset(initialPromptAt: Long? = null) {
        lastPrompt = initialPromptAt
        badSince = null
        explained = false
    }
}
