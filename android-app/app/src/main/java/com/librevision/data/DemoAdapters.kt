package com.librevision.data

import com.librevision.domain.navigation.*
import com.librevision.domain.vision.*
import kotlinx.coroutines.flow.MutableStateFlow

class DemoRouteProvider : RouteProvider, DestinationProvider {
    override fun examples() =
        listOf(
            Destination("park", "Local park"),
            Destination("market", "Market"),
            Destination("bus", "Bus stop"),
            Destination("pharmacy", "Pharmacy"),
        )

    override suspend fun calculateRoute(destination: Destination, origin: LocationFix?) =
        Result.success(
            WalkingRoute(
                destination,
                listOf(
                    "Example straight-ahead maneuver.",
                    "Example turn maneuver. Remain stationary during practice.",
                ),
                true,
            )
        )
}

class UnavailableOrientationProvider : OrientationProvider {
    override val heading = MutableStateFlow(HeadingEstimate(null, false))
}
