package com.librevision.domain.hazards

/**
 * 3-Zone Hazard Classification Engine (Comprehensive Safety Stack)
 * Detects:
 * 1. GROUND HAZARDS: Potholes, road craters, open/ajar manholes, road cracks, dug-up construction trenches, steps down.
 * 2. TORSO TRAFFIC: Bicycles, motorcycles, oncoming crowds, cars, poles, bollards.
 * 3. HEAD HEIGHT: Overhanging tree branches, low signs, low shop awnings, hanging wires.
 */
data class DetectedHazard(
    val id: String,
    val label: String,
    val zone: HazardZone,
    val distanceMeters: Float,
    val lateralOffsetNorm: Float, // -1.0 (left) to +1.0 (right)
    val severity: HazardSeverity,
    val spokenWarning: String
)

enum class HazardZone {
    GROUND_LEVEL, // Potholes, road craters, open manholes, trenches, cracks, curbs
    TORSO_LEVEL,  // Bicycles, approaching traffic, pedestrians, utility poles
    HEAD_HEIGHT   // Overhanging tree branches, low signs, awnings, hanging wires
}

enum class HazardSeverity {
    CRITICAL, // Immediate collision course (<1.5m) -> Urgent STOP voice + haptics
    WARNING,  // Caution zone (1.5m - 3.8m) -> Directional speech announcement
    INFO      // Awareness (>3.8m) -> Spatial sonar tracking
}

class ThreeZoneHazardClassifier {

    /**
     * Maps dual-detector outputs and metric depth into actionable blind pedestrian audio alerts.
     */
    fun classifyHazard(
        label: String,
        topNorm: Float,
        bottomNorm: Float,
        centerXNorm: Float,
        depthMeters: Float
    ): DetectedHazard {
        val lateralOffset = (centerXNorm - 0.5f) * 2.0f // -1.0 (left) to +1.0 (right)
        val directionText = when {
            lateralOffset < -0.3f -> "on your left"
            lateralOffset > 0.3f -> "on your right"
            else -> "straight ahead"
        }

        // Zone 1: Critical Ground Hazards
        val isGroundHazard = bottomNorm > 0.65f ||
                label in setOf(
                    "pothole", "crater", "manhole", "open_manhole", "crack", 
                    "trench", "construction_trench", "curb", "stairs_down", "ditch"
                )

        // Zone 3: Head-Height Hazards (Upper 40% of frame)
        val isHeadHeight = (topNorm < 0.40f && bottomNorm < 0.65f) ||
                label in setOf("tree_branch", "branch", "awning", "hanging_wire", "overhanging_sign", "umbrella")

        val zone = when {
            isGroundHazard -> HazardZone.GROUND_LEVEL
            isHeadHeight -> HazardZone.HEAD_HEIGHT
            else -> HazardZone.TORSO_LEVEL
        }

        val severity = when {
            // Ground craters and open manholes get immediate CRITICAL rating if within 2.2m
            (label in setOf("pothole", "crater", "manhole", "open_manhole", "trench") && depthMeters < 2.2f) -> 
                HazardSeverity.CRITICAL
            depthMeters < 1.6f -> HazardSeverity.CRITICAL
            depthMeters < 3.8f -> HazardSeverity.WARNING
            else -> HazardSeverity.INFO
        }

        val formattedDistance = String.format("%.1f", depthMeters)

        val warning = when (zone) {
            HazardZone.GROUND_LEVEL -> {
                when (label) {
                    "open_manhole", "manhole" -> "DANGER! Open manhole cover $directionText, $formattedDistance meters! Stop!"
                    "pothole", "crater" -> "Warning: Deep pothole $directionText, $formattedDistance meters."
                    "trench", "construction_trench" -> "Warning: Construction trench dug up $directionText, $formattedDistance meters."
                    "crack" -> "Road crack and broken surface $directionText."
                    else -> "Ground drop off $directionText, $formattedDistance meters."
                }
            }
            HazardZone.HEAD_HEIGHT -> {
                if (label.contains("branch")) {
                    "Tree branch at head height $directionText! Duck or step aside."
                } else {
                    "Low hanging sign or awning $directionText, $formattedDistance meters."
                }
            }
            HazardZone.TORSO_LEVEL -> {
                if (label == "bicycle" || label == "motorcycle" || label == "car") {
                    "${label.capitalize()} approaching $directionText! Strobe beacon flashing."
                } else {
                    "${label.replace('_', ' ').capitalize()} $directionText, $formattedDistance meters."
                }
            }
        }

        return DetectedHazard(
            id = "${label}_${System.currentTimeMillis()}",
            label = label,
            zone = zone,
            distanceMeters = depthMeters,
            lateralOffsetNorm = lateralOffset,
            severity = severity,
            spokenWarning = warning
        )
    }
}
