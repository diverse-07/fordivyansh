package com.librevision.domain.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Destination(val id: String, val name: String, val point: GeoPoint? = null)

data class WalkingRoute(
    val destination: Destination,
    val maneuvers: List<String>,
    val simulated: Boolean,
    val geometry: List<GeoPoint> = emptyList(),
    val instructions: List<RouteManeuver> = emptyList(),
    val origin: GeoPoint? = null,
    val source: String = "Demo",
)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val accuracyMeters: Float,
)

data class HeadingEstimate(val degrees: Float?, val reliable: Boolean)

interface OrientationProvider {
    val heading: StateFlow<HeadingEstimate>
}

interface RouteProvider {
    suspend fun calculateRoute(
        destination: Destination,
        origin: LocationFix? = null,
    ): Result<WalkingRoute>
}

interface DestinationProvider {
    fun examples(): List<Destination>
}

enum class NavigationStatus {
    IDLE,
    ACTIVE,
    ARRIVED,
    STOPPED,
    OFF_ROUTE,
    WAITING_FOR_GPS,
}

data class GuidanceState(
    val status: NavigationStatus = NavigationStatus.IDLE,
    val message: String = "Select an example destination.",
    val headingReliable: Boolean = false,
    val lastLocationTimestampMs: Long? = null,
    val remainingMeters: Double? = null,
    val speechKey: String = "",
    val maneuverIndex: Int = 0,
    val displayText: String? = null,
    val totalMeters: Double? = null,
    val totalEstimatedSteps: Int? = null,
    val remainingEstimatedSteps: Int? = null,
    val maneuverDistanceMeters: Double? = null,
    val maneuverEstimatedSteps: Int? = null,
)

interface NavigationGuidanceEngine {
    fun updateLocation(location: LocationFix)

    fun updateHeading(heading: HeadingEstimate)

    fun currentGuidance(): StateFlow<GuidanceState>
}

class DemoNavigationEngine(private val now: () -> Long) : NavigationGuidanceEngine {
    private val state = MutableStateFlow(GuidanceState())
    private var route: WalkingRoute? = null
    private var step = 0
    private var latestFix = Long.MIN_VALUE

    override fun currentGuidance(): StateFlow<GuidanceState> = state

    fun start(value: WalkingRoute) {
        require(value.simulated && value.maneuvers.isNotEmpty())
        route = value
        step = 0
        latestFix = Long.MIN_VALUE
        state.value = GuidanceState(NavigationStatus.ACTIVE, "Demo: ${value.maneuvers.first()}")
    }

    fun advance() {
        val current = route ?: return
        if (state.value.status != NavigationStatus.ACTIVE) return
        step++
        state.value =
            state.value.copy(
                status =
                    if (step >= current.maneuvers.size) NavigationStatus.ARRIVED
                    else NavigationStatus.ACTIVE,
                message =
                    if (step >= current.maneuvers.size)
                        "Demo complete. No real destination reached."
                    else "Demo: ${current.maneuvers[step]}",
            )
    }

    fun stop() {
        route = null
        state.value = GuidanceState(NavigationStatus.STOPPED, "Navigation stopped.")
    }

    override fun updateLocation(location: LocationFix) {
        if (state.value.status != NavigationStatus.ACTIVE) return
        if (
            location.timestampMs <= latestFix ||
                location.timestampMs > now() ||
                now() - location.timestampMs > 15000 ||
                location.accuracyMeters !in 0f..50f ||
                location.latitude !in -90.0..90.0 ||
                location.longitude !in -180.0..180.0
        )
            return
        latestFix = location.timestampMs
        state.value = state.value.copy(lastLocationTimestampMs = latestFix)
        // Demo deliberately does not turn GPS fixes into real route progress.
    }

    override fun updateHeading(heading: HeadingEstimate) {
        state.value =
            state.value.copy(
                headingReliable =
                    heading.reliable &&
                        heading.degrees?.let { it.isFinite() && it in 0f..360f } == true
            )
    }
}
