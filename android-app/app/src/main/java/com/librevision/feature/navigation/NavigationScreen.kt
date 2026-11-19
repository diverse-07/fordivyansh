package com.librevision.feature.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.domain.navigation.*
import com.librevision.domain.vision.FootpathIndicator
import com.librevision.feature.footpath.CameraPreview
import com.librevision.feature.footpath.FootpathStatusBanner
import kotlin.math.roundToInt

@Composable
fun NavigationScreen(model: LibreVisionViewModel, state: UiState) {
    val colors = LocalIndustrialColors.current
    val nav by model.navigationState.collectAsStateWithLifecycle()
    val guide by model.guidance.collectAsStateWithLifecycle()
    val setup by model.modelSetup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            model.enableNavigationLocation()
        }
    LaunchedEffect(Unit) {
        val fine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) model.enableNavigationLocation()
        else if (model.permissionBatchRequested()) model.enableNavigationLocation()
        else
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
    }
    LaunchedEffect(setup.phase) {
        if (setup.phase == com.librevision.data.models.SetupPhase.READY) model.enterNavigation()
    }
    val active =
        guide.status in
            setOf(
                NavigationStatus.ACTIVE,
                NavigationStatus.WAITING_FOR_GPS,
                NavigationStatus.OFF_ROUTE,
            )
    val working = active || nav.pendingNavigation || nav.routing
    val large = LocalDensity.current.fontScale > 1.3f
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Navigate", model::back, model::voice)
        FootpathStatusBanner(state.indicator, compact = true, scanning = state.scanRequested)
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
        ) {
            RouteMap(
                nav,
                model::select,
                Modifier.fillMaxSize(),
                following = active,
                onPan = model::mapPanned,
            )
            if (state.scanRequested) MovableCamera(model, state)
            InstrumentIcon(
                LVIcons.Center,
                "Center map",
                model::centerMap,
                Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        val cue =
            when {
                state.indicator == FootpathIndicator.OBSTRUCTION -> state.footpath
                nav.pendingNavigation || nav.routing -> nav.message
                guide.status == NavigationStatus.ARRIVED -> guide.message
                active -> guide.displayText ?: guide.message
                nav.message in
                    listOf(
                        "Navigation stopped",
                        "Route unavailable",
                        "Search unavailable",
                        "Nearby search unavailable",
                    ) || nav.message.startsWith("No nearby ") -> nav.message
                nav.selected != null -> nav.selected!!.name.substringBefore(',')
                else -> "Where to?"
            }
        val cueIcon =
            when {
                state.indicator == FootpathIndicator.OBSTRUCTION -> LVIcons.Alert
                active && cue.substringBefore('\n').contains("left", true) -> LVIcons.Left
                active && cue.substringBefore('\n').contains("right", true) -> LVIcons.Right
                else -> LVIcons.Navigate
            }
        GuidanceReadout(cue, cueIcon)
        if (!working && guide.status != NavigationStatus.ARRIVED) {
            QuickDestination.entries.toList().chunked(if (large) 2 else 4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { quick ->
                        QuickKey(quick, { model.quickNavigate(quick) }, Modifier.weight(1f), large)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InstrumentKey(
                if (working) "Stop" else "Go",
                { if (working) model.stopNavigation() else model.startNavigation() },
                Modifier.weight(1f),
                if (working) LVIcons.Stop else LVIcons.Play,
                fill = if (working) colors.well else colors.accent,
                ink = if (working) colors.onWell else colors.onAccent,
                stacked = true,
                compact = true,
            )
            InstrumentKey(
                "Search",
                model::showDestinations,
                Modifier.weight(1f),
                LVIcons.Search,
                stacked = true,
                compact = true,
            )
            InstrumentKey(
                "Repeat",
                { model.replayNavigation() },
                Modifier.weight(1f),
                LVIcons.Speaker,
                stacked = true,
                compact = true,
            )
        }
    }
    if (nav.destinationDialog) DestinationDialog(model, nav)
}

@Composable
private fun QuickKey(
    quick: QuickDestination,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val colors = LocalIndustrialColors.current
    val icon =
        when (quick) {
            QuickDestination.PARK -> LVIcons.Park
            QuickDestination.MARKET -> LVIcons.Market
            QuickDestination.BUS_STOP -> LVIcons.Bus
            QuickDestination.PHARMACY -> LVIcons.Pharmacy
        }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = colors.key,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.outline),
        shadowElevation = 2.dp,
        modifier =
            modifier.heightIn(min = if (large) 64.dp else 100.dp).semantics(
                mergeDescendants = true
            ) {
                role = Role.Button
            },
    ) {
        if (large && LocalDensity.current.fontScale > 1.8f) {
            Column(
                Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Glyph(icon, modifier = Modifier.size(26.dp))
                Text(
                    quick.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else if (large)
            Row(
                Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Glyph(icon, modifier = Modifier.size(30.dp))
                Text(
                    quick.label,
                    fontSize = 18.sp,
                    letterSpacing = 0.sp,
                    maxLines = 2,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        else
            Column(
                Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Glyph(icon, modifier = Modifier.size(34.dp))
                Text(
                    quick.label,
                    fontSize = 15.sp,
                    letterSpacing = 0.sp,
                    maxLines = 2,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
    }
}

@Composable
private fun BoxScope.MovableCamera(model: LibreVisionViewModel, state: UiState) {
    val density = LocalDensity.current
    val colors = LocalIndustrialColors.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barHeight = maxOf(32.dp, with(density) { 16.sp.toDp() } + 8.dp)
        val width = minOf(128.dp, maxWidth / 2, (maxHeight - barHeight).coerceAtLeast(0.dp) * 4 / 3)
        val feedHeight = width * 3 / 4
        val panelHeight = feedHeight + barHeight
        val maxX = with(density) { (maxWidth - width).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - panelHeight).toPx() }.coerceAtLeast(0f)
        var position by remember { mutableStateOf<Offset?>(null) }
        fun move(dx: Float, dy: Float): Boolean {
            val old = position ?: Offset((maxX - 8).coerceAtLeast(0f), 8f)
            position = Offset((old.x + dx).coerceIn(0f, maxX), (old.y + dy).coerceIn(0f, maxY))
            return true
        }
        Box(
            Modifier.offset {
                    val p = position ?: Offset((maxX - 8).coerceAtLeast(0f), 8f)
                    IntOffset(
                        p.x.coerceIn(0f, maxX).roundToInt(),
                        p.y.coerceIn(0f, maxY).roundToInt(),
                    )
                }
                .width(width)
                .height(panelHeight)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.well)
                .border(2.dp, colors.outline, RoundedCornerShape(5.dp))
                .pointerInput(maxX, maxY) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        move(delta.x, delta.y)
                    }
                }
                .semantics {
                    contentDescription = "Movable camera preview"
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Move camera left") { move(-80f, 0f) },
                            CustomAccessibilityAction("Move camera right") { move(80f, 0f) },
                            CustomAccessibilityAction("Move camera up") { move(0f, -80f) },
                            CustomAccessibilityAction("Move camera down") { move(0f, 80f) },
                        )
                }
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(barHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Glyph(LVIcons.Grip, modifier = Modifier.size(22.dp), tint = colors.onWell)
                    if (width >= 112.dp)
                        Text(
                            "Camera",
                            fontSize = if (density.fontScale > 1.8f) 11.sp else 13.sp,
                            maxLines = 1,
                            color = colors.onWell,
                        )
                }
                CameraPreview(
                    model,
                    state,
                    Modifier.fillMaxWidth().height(feedHeight),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun DestinationDialog(model: LibreVisionViewModel, nav: NavigationUiState) {
    InstrumentSheet(model::dismissDestinations) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Where to?",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            InstrumentIcon(LVIcons.Close, "Close", model::dismissDestinations)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickDestination.entries.toList().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { quick ->
                        QuickKey(
                            quick,
                            { model.quickNavigate(quick) },
                            Modifier.weight(1f),
                            large = true,
                        )
                    }
                }
            }
            OutlinedTextField(
                nav.query,
                model::updateQuery,
                label = { Text("Place name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            InstrumentKey(
                if (nav.searching) "Searching…" else "Search",
                { model.searchDestinations() },
                Modifier.fillMaxWidth(),
                LVIcons.Search,
                enabled = !nav.searching && nav.query.trim().length >= 3,
            )
            nav.places.forEachIndexed { index, destination ->
                InstrumentKey(
                    "${index+1}. ${destination.name}",
                    { model.select(destination) },
                    Modifier.fillMaxWidth(),
                )
            }
            if (nav.places.isEmpty()) Text(nav.message, fontSize = 18.sp)
        }
    }
}

@Composable
fun WalkingServiceSettings(model: LibreVisionViewModel) {
    val nav by model.navigationState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var search by remember(nav.searchUrl) { mutableStateOf(nav.searchUrl) }
    var routing by remember(nav.routingUrl) { mutableStateOf(nav.routingUrl) }
    var tiles by remember(nav.tilesUrl) { mutableStateOf(nav.tilesUrl) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 64.dp)) {
        Text("Walking service providers", fontSize = 20.sp)
    }
    if (expanded) {
        Text(
            "Public endpoints are for prototype use. Replace them for a deployment.",
            fontSize = 18.sp,
        )
        Text(
            "Map tiles, destination searches and routes use internet and send requested locations to these services. Microphone audio stays on this device; Muse can send a selected image with permission.",
            fontSize = 18.sp,
        )
        Text("Nearby destinations use the same Photon provider as place search.", fontSize = 18.sp)
        OutlinedTextField(
            search,
            { search = it },
            label = { Text("Photon search URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            routing,
            { routing = it },
            label = { Text("Valhalla route URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            tiles,
            { tiles = it },
            label = { Text("Raster tile URL template") },
            modifier = Modifier.fillMaxWidth(),
        )
        Action("Save service providers") { model.saveWalkingServices(search, routing, tiles) }
        Text(nav.serviceMessage, fontSize = 18.sp)
    }
}

@Composable
fun MuseGatewaySettings(model: LibreVisionViewModel) {
    val nav by model.navigationState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var url by remember(nav.gatewayUrl) { mutableStateOf(nav.gatewayUrl) }
    var access by remember(nav.gatewayAccess) { mutableStateOf(nav.gatewayAccess) }
    var consent by remember(nav.cloudConsent) { mutableStateOf(nav.cloudConsent) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 64.dp)) {
        Text("Netra Saathi Cloud AI", fontSize = 20.sp)
    }
    if (expanded) {
        Text(
            "On request, one selected camera image goes to Netra Saathi Cloud AI (Gemini 3 Flash). Images are not continuously uploaded. Cloud AI describes surroundings and answers questions; real-time obstacle avoidance runs 100% on-device.",
            fontSize = 18.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow selected images", fontSize = 20.sp, modifier = Modifier.weight(1f))
            Switch(
                consent,
                {
                    consent = it
                    model.setCloudImageConsent(it)
                },
                modifier =
                    Modifier.semantics {
                        contentDescription = "Allow selected camera images to be sent to Netra Saathi Cloud AI"
                    },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow surroundings descriptions", fontSize = 20.sp, modifier = Modifier.weight(1f))
            Switch(
                nav.surroundingsConsent,
                model::setSurroundingsConsent,
                modifier = Modifier.semantics {
                    contentDescription = "Allow sending selected pictures from surroundings recordings to Google Gemini"
                },
            )
        }
        if (com.librevision.BuildConfig.DEBUG) {
            Text("Developer connection override", fontSize = 18.sp)
            OutlinedTextField(
                url,
                { url = it },
                label = { Text("Cloud AI Backend URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                access,
                { access = it },
                label = { Text("Gateway access token (private test gateways only)") },
                visualTransformation =
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Action("Save developer connection") { model.saveGateway(url, access, consent) }
            Text(nav.gatewayMessage, fontSize = 18.sp)
        }
    }
}
