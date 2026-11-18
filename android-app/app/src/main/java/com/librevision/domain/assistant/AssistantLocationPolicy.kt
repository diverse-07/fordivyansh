package com.librevision.domain.assistant

import com.librevision.domain.navigation.GeoPoint
import com.librevision.domain.navigation.LocationFix

/** A cached routing fix may be useful for planning but too old to answer "where am I?". */
object AssistantLocationPolicy {
    fun fresh(fix: LocationFix?, now: Long): Boolean =
        fix != null &&
            GeoPoint(fix.latitude, fix.longitude).valid() &&
            now - fix.timestampMs in 0..30000 &&
            fix.accuracyMeters in 0f..200f
}
