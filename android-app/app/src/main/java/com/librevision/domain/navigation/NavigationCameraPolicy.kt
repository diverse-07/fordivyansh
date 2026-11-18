package com.librevision.domain.navigation

/** Destination selection and route calculation never start obstacle inference. */
object NavigationCameraPolicy {
    fun shouldScan(
        status: NavigationStatus,
        foreground: Boolean,
        choosingDestination: Boolean,
        enabled: Boolean,
    ): Boolean =
        foreground &&
            enabled &&
            !choosingDestination &&
            status in
                setOf(
                    NavigationStatus.ACTIVE,
                    NavigationStatus.WAITING_FOR_GPS,
                    NavigationStatus.OFF_ROUTE,
                )
}
