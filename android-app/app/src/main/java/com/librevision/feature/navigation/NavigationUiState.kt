package com.librevision.feature.navigation

import com.librevision.data.routing.WalkingServices
import com.librevision.domain.navigation.*

data class NavigationUiState(
    val contextBusy: Boolean = false,
    val contextConsent: Boolean = false,
    val contextText: String? = null,
    val gatewayUrl: String = "",
    val gatewayAccess: String = "",
    val cloudConsent: Boolean = false,
    val gatewayMessage: String = "",
    val pendingNavigation: Boolean = false,
    val destinationDialog: Boolean = false,
    val focusedResult: Int = 0,
    val query: String = "",
    val places: List<Destination> = emptyList(),
    val selected: Destination? = null,
    val route: WalkingRoute? = null,
    val location: LocationFix? = null,
    val locationMessage: String = "Locating…",
    val message: String = "Choose a destination",
    val searching: Boolean = false,
    val routing: Boolean = false,
    val focusVersion: Int = 0,
    val followUser: Boolean = true,
    val searchUrl: String = WalkingServices.PHOTON,
    val routingUrl: String = WalkingServices.VALHALLA,
    val tilesUrl: String = WalkingServices.TILES,
    val serviceMessage: String = "",
)
