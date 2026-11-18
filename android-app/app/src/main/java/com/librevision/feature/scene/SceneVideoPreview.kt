package com.librevision.feature.scene

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librevision.LibreVisionViewModel

@Composable
fun SceneVideoPreview(model: LibreVisionViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (!it) model.sceneVideoDenied()
        }
    LaunchedEffect(Unit) {
        if (!granted) {
            if (model.permissionBatchRequested()) model.sceneVideoDenied()
            else permission.launch(Manifest.permission.CAMERA)
        }
    }
    if (granted) {
        val view = remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        }
        DisposableEffect(view, owner) {
            model.attachSceneVideo(view, owner)
            onDispose { model.detachSceneVideo() }
        }
        AndroidView(
            factory = { view },
            modifier =
                modifier.fillMaxSize().clipToBounds().background(Color.Black).semantics {
                    contentDescription = "Live camera for video recording"
                },
        )
    } else Text("Allow camera access to record.", fontSize = 22.sp)
}
