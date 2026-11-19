package com.librevision.domain.hazards
data class DetectedHazard(val id: String, val label: String, val zone: HazardZone,
    val distanceMeters: Float?, val lateralOffsetNorm: Float,
    val severity: HazardSeverity, val spokenWarning: String)
enum class HazardZone { GROUND_LEVEL, TORSO_LEVEL, HEAD_HEIGHT }
enum class HazardSeverity { CRITICAL, WARNING, INFO }
/** Image position alone cannot establish ground damage or an overhead hazard. */
class ThreeZoneHazardClassifier {
    fun classifyHazard(label: String, topNorm: Float, bottomNorm: Float,
        centerXNorm: Float, depthMeters: Float?): DetectedHazard {
        val name = label.lowercase(java.util.Locale.ROOT).replace(' ', '_')
        val ground = name in setOf("pothole","crater","manhole","open_manhole","crack",
            "trench","construction_trench","curb","stairs_down","ditch","drop-off","open_drain")
        val overhead = name in setOf("tree_branch","branch","awning","hanging_wire","overhanging_sign")
        val zone = when { ground -> HazardZone.GROUND_LEVEL; overhead -> HazardZone.HEAD_HEIGHT; else -> HazardZone.TORSO_LEVEL }
        val lateral = if (centerXNorm.isFinite()) ((centerXNorm-.5f)*2).coerceIn(-1f,1f) else 0f
        val direction = when { lateral < -.3f -> "on your left"; lateral > .3f -> "on your right"; else -> "ahead" }
        return DetectedHazard(name + "_" + System.nanoTime(),label,zone,
            depthMeters?.takeIf { it.isFinite() && it in .2f..10f },lateral,
            if (ground) HazardSeverity.CRITICAL else HazardSeverity.WARNING,
            "Stop. " + label.replace('_',' ') + " detected " + direction + ".")
    }
}
