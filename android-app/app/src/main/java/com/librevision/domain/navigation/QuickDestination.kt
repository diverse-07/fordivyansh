package com.librevision.domain.navigation

enum class NearbyCategory(val label: String, val filters: List<Pair<String, String>>) {
    PARK("Park", listOf("leisure" to "park")),
    MARKET("Market", listOf("amenity" to "marketplace", "shop" to "supermarket")),
    BUS_STOP("Bus stop", listOf("highway" to "bus_stop", "public_transport" to "platform")),
    PHARMACY("Pharmacy", listOf("amenity" to "pharmacy")),
    HOSPITAL("Hospital", listOf("amenity" to "hospital")),
    CLINIC("Clinic", listOf("amenity" to "clinic")),
    ATM("ATM", listOf("amenity" to "atm")),
    TOILET("Toilet", listOf("amenity" to "toilets")),
    RESTAURANT("Restaurant", listOf("amenity" to "restaurant")),
    POLICE("Police station", listOf("amenity" to "police")),
}

enum class QuickDestination(val category: NearbyCategory) {
    PARK(NearbyCategory.PARK),
    MARKET(NearbyCategory.MARKET),
    BUS_STOP(NearbyCategory.BUS_STOP),
    PHARMACY(NearbyCategory.PHARMACY);

    val label
        get() = category.label

    val filters
        get() = category.filters
}

object NavigationLocationPolicy {
    fun acceptsUpdate(previous: LocationFix?, candidate: LocationFix, now: Long): Boolean {
        if (!usableForPlanning(candidate, now)) return false
        if (previous == null) return true
        if (candidate.timestampMs < previous.timestampMs) return false
        // A stale precise cache must not suppress a fresh network/fused fix during cold GPS
        // startup.
        return !(now - previous.timestampMs in 0..15000 &&
            candidate.timestampMs - previous.timestampMs < 10000 &&
            candidate.accuracyMeters > previous.accuracyMeters * 2)
    }

    /**
     * Route discovery can use a recent approximate fix; precise turn timing is handled separately.
     */
    fun usableForPlanning(fix: LocationFix?, now: Long): Boolean =
        fix != null &&
            GeoPoint(fix.latitude, fix.longitude).valid() &&
            now - fix.timestampMs in 0..120000 &&
            fix.accuracyMeters in 0f..500f
}
