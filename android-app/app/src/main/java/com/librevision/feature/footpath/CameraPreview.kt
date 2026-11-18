package com.librevision.feature.footpath

import android.Manifest
import android.content.pm.PackageManager
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librevision.LibreVisionViewModel
import com.librevision.UiState

@Composable
fun FootpathCameraPanel(
    model: LibreVisionViewModel,
    state: UiState,
    overlay: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().clipToBounds()) {
        Box(Modifier.fillMaxWidth().zIndex(1f)) { FootpathStatusBanner(state.indicator) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().zIndex(0f).clipToBounds()) {
            CameraPreview(model, state)
            overlay?.invoke()
        }
    }
}

@Composable
fun CameraPreview(
    model: LibreVisionViewModel,
    state: UiState,
    modifier: Modifier = Modifier.height(280.dp),
    compact: Boolean = false,
    showDetections: Boolean = true,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
            granted = result
            if (!result) model.cameraDenied()
        }
    LaunchedEffect(state.scanRequested) {
        if (state.scanRequested && !granted) {
            if (model.permissionBatchRequested()) model.cameraDenied()
            else launcher.launch(Manifest.permission.CAMERA)
        }
    }
    if (state.scanRequested && granted) {
        val infiniteScan = rememberInfiniteTransition(label = "RadarSweep")
        val scanProgress by infiniteScan.animateFloat(
            initialValue = 0.04f,
            targetValue = 0.96f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ScanSweepY"
        )
        val pulseStroke by infiniteScan.animateFloat(
            initialValue = 3.0f,
            targetValue = 5.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "PulseStroke"
        )
        val view =
            remember(showDetections) {
                PreviewView(context).apply {
                    scaleType =
                        if (showDetections) PreviewView.ScaleType.FILL_CENTER
                        else PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
        // Native clipping is essential: PreviewView's fill transform can exceed its Compose bounds.
        val host =
            remember(view) {
                FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }
        DisposableEffect(view, owner) {
            var disposed = false
            view.doOnLayout { if (!disposed) model.attachCamera(view, owner) }
            onDispose {
                disposed = true
                model.detachCamera()
            }
        }
        Box(
            modifier.fillMaxWidth().clipToBounds().background(Color.Black).semantics {
                contentDescription =
                    if (showDetections)
                        "Live rear camera. Recognized objects shown with bounding boxes. Warnings appear below."
                    else "Live rear camera for surroundings capture."
            }
        ) {
            AndroidView(factory = { host }, modifier = Modifier.fillMaxSize().clipToBounds())
            if (showDetections)
                Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                    // Shading and guides never crop the model's full-frame input.
                    drawRect(
                        Color.Black.copy(alpha = .25f),
                        Offset.Zero,
                        Size(size.width * .25f, size.height),
                    )
                    drawRect(
                        Color.Black.copy(alpha = .25f),
                        Offset(size.width * .75f, 0f),
                        Size(size.width * .25f, size.height),
                    )
                    listOf(.25f, .75f).forEach { fraction ->
                        drawLine(
                            Color.White.copy(alpha = 0.6f),
                            Offset(size.width * fraction, 0f),
                            Offset(size.width * fraction, size.height),
                            2.dp.toPx(),
                        )
                    }

                    // 3-Zone horizontal safety guides
                    // Head Zone (upper 35%)
                    drawLine(
                        Color(0xFFFFB300).copy(alpha = 0.4f),
                        Offset(0f, size.height * 0.35f),
                        Offset(size.width, size.height * 0.35f),
                        1.5.dp.toPx(),
                    )
                    // Ground Hazard Zone (bottom 35%)
                    drawLine(
                        Color(0xFFFF5252).copy(alpha = 0.4f),
                        Offset(0f, size.height * 0.65f),
                        Offset(size.width, size.height * 0.65f),
                        1.5.dp.toPx(),
                    )

                    // Animated Futuristic Radar / Sonar Scanning Sweep Line
                    val sweepY = size.height * scanProgress
                    val sweepGradient = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x4000E5FF),
                            Color(0xFF5CE1C6),
                            Color(0x4000E5FF),
                            Color.Transparent
                        )
                    )
                    drawLine(
                        brush = sweepGradient,
                        start = Offset(0f, sweepY),
                        end = Offset(size.width, sweepY),
                        strokeWidth = 3.dp.toPx(),
                    )

                    state.detections.forEach { detection ->
                        detection.bounds?.let { box ->
                            if (box.valid()) {
                                val left = box.left.coerceIn(0f, 1f) * size.width
                                val top = box.top.coerceIn(0f, 1f) * size.height
                                val right = box.right.coerceIn(0f, 1f) * size.width
                                val bottom = box.bottom.coerceIn(0f, 1f) * size.height

                                val isGroundHazard = box.bottom > 0.65f || detection.label in setOf(
                                    "pothole", "crater", "manhole", "open_manhole", "crack", "trench", "construction_trench", "drop-off"
                                )
                                val isHeadHazard = box.top < 0.35f && detection.label in setOf(
                                    "tree branch", "tree_branch", "branch", "awning", "hanging_wire", "overhanging_sign"
                                )
                                val isTraffic = detection.label in setOf("bicycle", "motorcycle", "car", "bus", "truck")

                                val boxColor = when {
                                    isGroundHazard -> Color(0xFFFF5252) // Hazard Red
                                    isTraffic -> Color(0xFFFF1744)      // Urgent Traffic Red
                                    isHeadHazard -> Color(0xFFFFB300)   // Head Warning Amber
                                    else -> Color(0xFF00E676)           // High-contrast Emerald Green
                                }

                                val isCritical = isGroundHazard || isTraffic
                                val strokeW = if (isCritical) pulseStroke.dp.toPx() else 3.dp.toPx()
                                drawRect(
                                    boxColor,
                                    Offset(left, top),
                                    Size(right - left, bottom - top),
                                    style = Stroke(strokeW),
                                )

                                val tag = detection.label.replace('_', ' ').uppercase()
                                drawContext.canvas.nativeCanvas.apply {
                                    val textPaint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.WHITE
                                        textSize = 28f
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                        isAntiAlias = true
                                    }
                                    val bgPaint = android.graphics.Paint().apply {
                                        color = boxColor.toArgb()
                                        style = android.graphics.Paint.Style.FILL
                                    }
                                    val textWidth = textPaint.measureText(tag)
                                    val badgeH = 34f
                                    val badgeTop = (top - badgeH).coerceAtLeast(0f)
                                    drawRect(left, badgeTop, left + textWidth + 18f, badgeTop + badgeH, bgPaint)
                                    drawText(tag, left + 8f, badgeTop + 24f, textPaint)
                                }
                            }
                        }
                    }
                }
        }
    } else {
        if (compact)
            androidx.compose.material3.TextButton(
                onClick = model::scan,
                modifier = modifier.fillMaxWidth(),
            ) {
                Text("Scan")
            }
        else Text("Rear camera is off. Tap Start Scanning to detect objects on this device.")
    }
}
