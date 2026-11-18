package com.visionassist.domain.hazards

/**
 * 3-Zone Hazard Classification Engine
 * Specifically addresses head-height collisions (tree branches, low shop signs),
 * torso traffic (bicycles, moving pedestrians, cars), and ground-level hazards (potholes, open manholes).
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
    GROUND_LEVEL, // Potholes, open manholes, curbs, descending stairs
    TORSO_LEVEL,  // Bicycles, oncoming pedestrians, cars, bollards, poles
    HEAD_HEIGHT   // Tree branches, low-hanging signs, wires, shop awnings
}

enum class HazardSeverity {
    CRITICAL, // Immediate collision course (<1.5m) -> Urgent STOP voice + haptics
    WARNING,  // Caution zone (1.5m - 3.5m) -> Directional speech announcement
    INFO      // Awareness (>3.5m) -> Background tracking
}

class ThreeZoneHazardClassifier {

    /**
     * Maps raw bounding boxes and metric depth values into 3-zone pedestrian hazards.
     * 
     * @param label Object class name (e.g. "pothole", "bicycle", "tree_branch", "person", "sign")
     * @param topNorm Bounding box top coordinate normalized [0.0, 1.0]
     * @param bottomNorm Bounding box bottom coordinate normalized [0.0, 1.0]
     * @param centerXNorm Bounding box center X normalized [0.0, 1.0]
     * @param depthMeters Metric distance in meters estimated by Depth Anything V2
     */
    fun classifyHazard(
        label: String,
        topNorm: Float,
        bottomNorm: Float,
        centerXNorm: Float,
        depthMeters: Float
    ): DetectedHazard {
        val lateralOffset = (centerXNorm - 0.5f) * 2.0f // Map 0.0..1.0 to -1.0..+1.0
        val directionText = when {
            lateralOffset < -0.3f -> "on your left"
            lateralOffset > 0.3f -> "on your right"
            else -> "straight ahead"
        }

        // Zone 3: Head-height detection
        // If the obstacle is in the upper 40% of the camera frame and represents an overhang
        val isHeadHeight = (topNorm < 0.40f && bottomNorm < 0.65f) ||
                label in setOf("tree_branch", "awning", "hanging_wire", "overhanging_sign")

        // Zone 1: Ground-level detection
        val isGroundHazard = bottomNorm > 0.70f ||
                label in setOf("pothole", "crack", "manhole", "curb", "stairs_down")

        val zone = when {
            isHeadHeight -> HazardZone.HEAD_HEIGHT
            isGroundHazard -> HazardZone.GROUND_LEVEL
            else -> HazardZone.TORSO_LEVEL
        }

        val severity = when {
            depthMeters < 1.8f -> HazardSeverity.CRITICAL
            depthMeters < 3.8f -> HazardSeverity.WARNING
            else -> HazardSeverity.INFO
        }

        val warning = when (zone) {
            HazardZone.HEAD_HEIGHT -> {
                val cleanLabel = if (label == "tree_branch") "Low tree branch" else "Head height hazard"
                "$cleanLabel $directionText. Duck or step aside."
            }
            HazardZone.GROUND_LEVEL -> {
                val cleanLabel = when (label) {
                    "pothole" -> "Pothole in the ground"
                    "manhole" -> "Open manhole"
                    else -> "Ground hazard"
                }
                "$cleanLabel $directionText, ${String.format("%.1f", depthMeters)} meters."
            }
            HazardZone.TORSO_LEVEL -> {
                if (label == "bicycle" || label == "motorcycle") {
                    "Bicycle approaching $directionText. Strobe light active."
                } else {
                    "${label.replace('_', ' ').capitalize()} $directionText, ${String.format("%.1f", depthMeters)} meters."
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
