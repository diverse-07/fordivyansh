package com.librevision.domain.navigation

enum class VisualFeature(val label: String) {
    STOREFRONT("shopfront"),
    SHOP_SIGN("shop sign"),
    PHARMACY_SIGN("pharmacy sign"),
    BUS_STOP_SIGN("bus stop sign"),
    SIGN("sign"),
    BUILDING("building"),
    TREE("tree"),
    BENCH("bench"),
    POLE("pole"),
    PARKED_VEHICLE("parked vehicle"),
    VEHICLE("vehicle"),
    BICYCLE("bicycle"),
    PEDESTRIANS("pedestrians"),
    BARRIER("barrier"),
    STAIRS("stairs"),
    ENTRANCE("entrance"),
    AWNING("awning"),
    FENCE("fence"),
    WALL("wall"),
    OTHER("unidentified landmark"),
}

enum class LandmarkColor {
    NONE,
    RED,
    BLUE,
    GREEN,
    YELLOW,
    ORANGE,
    WHITE,
    BLACK,
    GRAY,
    BROWN,
    PURPLE,
    PINK,
    MULTICOLORED,
}

enum class ImagePosition {
    LEFT,
    CENTER,
    RIGHT,
    UNKNOWN,
}

enum class SceneCrowding {
    QUIET,
    MODERATE,
    BUSY,
    UNKNOWN,
}

enum class SceneVisibility {
    GOOD,
    LIMITED,
    UNKNOWN,
}

data class VisibleLandmark(val label: String, val position: ImagePosition, val uncertain: Boolean)

data class VisualContext(
    val landmarks: List<VisibleLandmark>,
    val crowding: SceneCrowding,
    val visibility: SceneVisibility,
) {
    fun spoken(): String {
        val landmark = landmarks.firstOrNull()
        val description =
            landmark?.let {
                val position =
                    when (it.position) {
                        ImagePosition.LEFT -> ", left"
                        ImagePosition.RIGHT -> ", right"
                        ImagePosition.CENTER -> ", ahead"
                        ImagePosition.UNKNOWN -> ""
                    }
                (if (it.uncertain) "Possible " else "") + it.label + position + "."
            } ?: "No landmark identified."
        return "View: $description" +
            when {
                visibility == SceneVisibility.LIMITED -> " Low visibility."
                crowding == SceneCrowding.BUSY -> " Busy scene."
                else -> ""
            }
    }
}

data class VisualContextTicket(val epoch: Long, val maneuver: Int?, val capturedAt: Long) {
    fun current(epoch: Long, maneuver: Int?, now: Long) =
        this.epoch == epoch && this.maneuver == maneuver && now - capturedAt in 0..15000
}
