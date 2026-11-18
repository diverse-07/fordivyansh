package com.librevision.domain.navigation

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Metric route distances and stride-based estimates; these are not pedometer measurements. */
object NavigationMeasures {
    fun validStride(value: Float) = value.takeIf { it.isFinite() && it in .4f..1f } ?: .65f

    fun steps(metres: Double, stride: Float): Int {
        require(metres.isFinite() && metres >= 0)
        return (metres / validStride(stride)).coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt()
    }

    fun number(value: Int) = NumberFormat.getIntegerInstance(Locale.US).format(value)

    private fun rounded(metres: Double): Double {
        require(metres.isFinite() && metres >= 0)
        return if (metres < 1000) (metres / 5).roundToInt() * 5.0
        else (metres / 100).roundToInt() * 100.0
    }

    fun display(metres: Double): String {
        val value = rounded(metres)
        return if (value < 1000) "${value.toInt()} m"
        else String.format(Locale.US, "%.1f km", value / 1000)
    }

    fun spoken(metres: Double): String {
        val value = rounded(metres)
        return if (value < 1000) "${value.toInt()} metres"
        else String.format(Locale.US, "%.1f kilometres", value / 1000)
    }

    fun turnSteps(metres: Double, stride: Float) = steps(rounded(metres), stride)

    fun totalDisplay(metres: Double, stride: Float) =
        "Total ${display(metres)} · ≈${number(steps(metres,stride))} steps"

    fun totalSpeech(metres: Double, stride: Float) =
        "${spoken(metres)}, about ${number(steps(metres,stride))} steps total."

    fun toTurnSpeech(metres: Double, stride: Float) =
        "${spoken(metres)}, about ${number(turnSteps(metres,stride))} steps"
}
