package cz.oinfo.orionmaps.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

enum class GestureMode {
    ALL, LOCK_ROTATION, LOCK_ZOOM
}

@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { viewModel.loadPdf(it) }
        }
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        when (val state = uiState) {
            is MapUiState.Empty -> {
                Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                    Text("Pick PDF Map")
                }
            }
            is MapUiState.Loading -> {
                CircularProgressIndicator(color = Color.White)
            }
            is MapUiState.Success -> {
                InteractiveMap(bitmap = state.bitmap)
            }
            is MapUiState.Error -> {
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { launcher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}

@Composable
fun InteractiveMap(bitmap: android.graphics.Bitmap) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableStateOf(0f) }
    
    // Requirement 2: Three-State Lock Mode
    var gestureMode by remember { mutableStateOf(GestureMode.ALL) }
    
    // Requirement 3: Custom 1-Second Notification
    var notificationText by remember { mutableStateOf("") }
    var showNotification by remember { mutableStateOf(false) }

    LaunchedEffect(notificationText, showNotification) {
        if (showNotification) {
            delay(1000)
            showNotification = false
        }
    }

    fun triggerNotification(text: String) {
        notificationText = text
        showNotification = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotate ->
                    // Requirement 2: Handle Lock Modes
                    val effectiveZoom = if (gestureMode == GestureMode.LOCK_ZOOM) 1f else zoom
                    val effectiveRotation = if (gestureMode == GestureMode.LOCK_ROTATION) 0f else rotate

                    // Requirement 1: Perfect Centroid Math
                    val angleInRadians = effectiveRotation * Math.PI / 180.0
                    val cos = kotlin.math.cos(angleInRadians).toFloat()
                    val sin = kotlin.math.sin(angleInRadians).toFloat()

                    // 1. Calculate the distance from the current offset to the centroid
                    val x = centroid.x - offset.x
                    val y = centroid.y - offset.y

                    // 2. Apply rotation to this distance
                    val rotatedX = x * cos - y * sin
                    val rotatedY = x * sin + y * cos

                    // 3. Apply scale to the rotated distance
                    val scaledX = rotatedX * effectiveZoom
                    val scaledY = rotatedY * effectiveZoom

                    // 4. Update offset (add pan, subtract the difference caused by scale/rotation)
                    offset = Offset(
                        x = offset.x + pan.x + x - scaledX,
                        y = offset.y + pan.y + y - scaledY
                    )
                    
                    // 5. Update scale and rotation
                    scale = (scale * effectiveZoom).coerceIn(1f, 10f)
                    rotation += effectiveRotation
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "PDF Map",
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    rotationZ = rotation,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )

        // Custom Notification Overlay
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible = showNotification,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier.padding(top = 64.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = notificationText,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // Control Buttons
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Requirement 4: Reset Button
            SmallFloatingActionButton(
                onClick = {
                    scale = 1f
                    rotation = 0f
                    offset = Offset.Zero
                    triggerNotification("Map Reset")
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset Map")
            }

            // Mode Toggle Button
            FloatingActionButton(
                onClick = {
                    gestureMode = when (gestureMode) {
                        GestureMode.ALL -> GestureMode.LOCK_ROTATION
                        GestureMode.LOCK_ROTATION -> GestureMode.LOCK_ZOOM
                        GestureMode.LOCK_ZOOM -> GestureMode.ALL
                    }
                    triggerNotification(when (gestureMode) {
                        GestureMode.ALL -> "Mode: All Gestures"
                        GestureMode.LOCK_ROTATION -> "Mode: Rotation Locked"
                        GestureMode.LOCK_ZOOM -> "Mode: Zoom Locked"
                    })
                },
                containerColor = when (gestureMode) {
                    GestureMode.ALL -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.tertiaryContainer
                }
            ) {
                val icon = when (gestureMode) {
                    GestureMode.ALL -> Icons.Default.Settings
                    GestureMode.LOCK_ROTATION -> Icons.Default.Lock
                    GestureMode.LOCK_ZOOM -> Icons.Default.Lock // Or another appropriate icon
                }
                Icon(icon, contentDescription = "Toggle Gesture Mode")
            }
        }
    }
}
