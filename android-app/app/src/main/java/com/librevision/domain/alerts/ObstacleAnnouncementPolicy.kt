package com.librevision.domain.alerts

/** Rate-limit spoken hazards across labels; UI detections still update on every frame. */
class ObstacleAnnouncementPolicy(
    private val minimumGapMs: Long = 5000,
    private val repeatMs: Long = 8000,
    private val expiryMs: Long = 4000,
) {
    private var lastAt: Long? = null
    private var lastLabel: String? = null
    private var lastWasTraffic = false

    fun canAnnounce(label: String, observedAt: Long, now: Long, traffic: Boolean = false): Boolean {
        if (label.isBlank() || now - observedAt !in 0..expiryMs) return false
        val elapsed = lastAt?.let { now - it } ?: return true
        if (elapsed < 0) return false
        if (traffic && !lastWasTraffic) return true
        return elapsed >= if (label == lastLabel) repeatMs else if (traffic) 2000 else minimumGapMs
    }

    /** Record only speech accepted by AlertCoordinator, never a rejected interruption. */
    fun accepted(label: String, now: Long, traffic: Boolean = false) {
        lastLabel = label
        lastAt = now
        lastWasTraffic = traffic
    }

    fun reset() {
        lastLabel = null
        lastAt = null
        lastWasTraffic = false
    }
}
