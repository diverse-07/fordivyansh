package com.librevision.feature.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librevision.data.routing.WalkingServices
import com.librevision.domain.navigation.*
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.json.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.*
import org.maplibre.android.maps.*
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

private object MapNetworking {
    private var configured = false

    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        MapLibre.getInstance(context.applicationContext)
        val client =
            OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "map-http"), 64L * 1024 * 1024))
                .addInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header("User-Agent", WalkingServices.USER_AGENT)
                            .build()
                    )
                }
                .build()
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        configured = true
    }
}

private fun geometry(type: String, points: List<GeoPoint>): String {
    if (points.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val coords =
        if (type == "Point") JSONArray().put(points.first().longitude).put(points.first().latitude)
        else
            JSONArray().also { a ->
                points.forEach { a.put(JSONArray().put(it.longitude).put(it.latitude)) }
            }
    return JSONObject()
        .put("type", "Feature")
        .put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", type).put("coordinates", coords))
        .toString()
}

@Composable
fun RouteMap(
    state: NavigationUiState,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier.height(328.dp),
    following: Boolean = false,
    onPan: () -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val selection by rememberUpdatedState(onSelect)
    val panned by rememberUpdatedState(onPan)
    var ready by remember { mutableStateOf<MapLibreMap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val view = remember {
        MapNetworking.configure(context)
        MapView(
                context,
                MapLibreMapOptions.createFromAttributes(context)
                    .textureMode(true)
                    .setPrefetchesTiles(false)
                    .setPrefetchZoomDelta(0),
            )
            .apply {
                onCreate(null)
                setMaximumFps(20)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // Native map gesture hints were stealing initial accessibility focus and scrolling
                // past search.
                // Route text, location status, search, centering and attribution remain
                // Compose-accessible.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addOnDidFailLoadingMapListener {
                    error = "Map unavailable. Loaded route text and audio remain available."
                }
            }
    }
    val host =
        remember(view) {
            FrameLayout(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    DisposableEffect(view, owner) {
        var started = false
        var resumed = false
        fun sync(state: Lifecycle.State) {
            if (state.isAtLeast(Lifecycle.State.STARTED) && !started) {
                view.onStart()
                started = true
            }
            if (state.isAtLeast(Lifecycle.State.RESUMED) && !resumed) {
                view.onResume()
                resumed = true
            }
            if (!state.isAtLeast(Lifecycle.State.RESUMED) && resumed) {
                view.onPause()
                resumed = false
            }
            if (!state.isAtLeast(Lifecycle.State.STARTED) && started) {
                view.onStop()
                started = false
            }
        }
        val observer = LifecycleEventObserver { _, _ -> sync(owner.lifecycle.currentState) }
        owner.lifecycle.addObserver(observer)
        sync(owner.lifecycle.currentState)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            sync(Lifecycle.State.CREATED)
            view.onDestroy()
        }
    }
    LaunchedEffect(view, state.tilesUrl) {
        ready = null
        val style =
            JSONObject()
                .put("version", 8)
                .put(
                    "sources",
                    JSONObject()
                        .put(
                            "osm",
                            JSONObject()
                                .put("type", "raster")
                                .put("tiles", JSONArray().put(state.tilesUrl))
                                .put("tileSize", 256)
                                .put("attribution", "© OpenStreetMap contributors"),
                        ),
                )
                .put(
                    "layers",
                    JSONArray()
                        .put(
                            JSONObject().put("id", "osm").put("type", "raster").put("source", "osm")
                        ),
                )
        view.getMapAsync { map ->
            if (view.isDestroyed) return@getMapAsync
            map.setPrefetchesTiles(false)
            map.setPrefetchZoomDelta(0)
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.setStyle(Style.Builder().fromJson(style.toString())) { loaded ->
                if (view.isDestroyed) return@setStyle
                listOf("walking-route", "current-position", "destination").forEach {
                    loaded.addSource(GeoJsonSource(it))
                }
                loaded.addLayer(
                    LineLayer("route-line", "walking-route")
                        .withProperties(lineColor("#126B5B"), lineWidth(7f))
                )
                loaded.addLayer(
                    CircleLayer("position-point", "current-position")
                        .withProperties(
                            circleColor("#126BDE"),
                            circleRadius(9f),
                            circleStrokeColor("#FFFFFF"),
                            circleStrokeWidth(3f),
                        )
                )
                loaded.addLayer(
                    CircleLayer("destination-point", "destination")
                        .withProperties(
                            circleColor("#7D35BB"),
                            circleRadius(9f),
                            circleStrokeColor("#FFFFFF"),
                            circleStrokeWidth(3f),
                        )
                )
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(23.8103, 90.4125), 11.0))
                ready = map
                error = null
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) panned()
            }
            map.addOnMapLongClickListener { point ->
                selection(
                    Destination(
                        "map-point",
                        "Map-selected destination",
                        GeoPoint(point.latitude, point.longitude),
                    )
                )
                true
            }
        }
    }
    LaunchedEffect(ready, state.route, state.location, state.selected) {
        ready?.style?.let { style ->
            style
                .getSourceAs<GeoJsonSource>("walking-route")
                ?.setGeoJson(geometry("LineString", state.route?.geometry.orEmpty()))
            style
                .getSourceAs<GeoJsonSource>("current-position")
                ?.setGeoJson(
                    geometry(
                        "Point",
                        state.location
                            ?.let { listOf(GeoPoint(it.latitude, it.longitude)) }
                            .orEmpty(),
                    )
                )
            style
                .getSourceAs<GeoJsonSource>("destination")
                ?.setGeoJson(geometry("Point", state.selected?.point?.let { listOf(it) }.orEmpty()))
        }
    }
    LaunchedEffect(ready, state.route, state.focusVersion, state.location != null) {
        val map = ready ?: return@LaunchedEffect
        val points = state.route?.geometry.orEmpty()
        if (points.size >= 2) {
            val bounds =
                LatLngBounds.Builder()
                    .includes(points.map { LatLng(it.latitude, it.longitude) })
                    .build()
            runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 32)) }
        } else
            state.location?.let {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 16.0)
                )
            }
    }
    LaunchedEffect(ready, state.location, following, state.followUser, state.focusVersion) {
        if (following && state.followUser)
            state.location?.let { fix ->
                if (android.os.SystemClock.elapsedRealtime() - fix.timestampMs in 0..15000)
                    ready?.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 17.0)
                    )
            }
    }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        Box(
            Modifier.fillMaxSize().semantics {
                contentDescription =
                    if (state.route == null) "Map. Use quick destinations or destination search."
                    else "Walking route map. Spoken guidance is available."
            }
        ) {
            AndroidView(factory = { host }, modifier = Modifier.fillMaxSize().clipToBounds())
        }
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))
                )
            },
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .background(Color(0xE6121C2E))
                    .heightIn(min = 48.dp),
        ) {
            Text("© OpenStreetMap", fontSize = 12.sp, color = Color.White)
        }
        error?.let {
            Text(
                "Map unavailable",
                color = Color.White,
                modifier =
                    Modifier.align(Alignment.TopStart).background(Color(0xE6121C2E)).padding(8.dp),
            )
        }
    }
}
