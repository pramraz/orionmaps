package cz.oinfo.orionmaps.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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
    
    // Requirement 2: Separate Zoom and Rotation (Mode Toggle)
    var isZoomLocked by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isZoomLocked) {
                detectTransformGestures { _, pan, zoom, rotate ->
                    // Requirement 1: Simplify Transformations (Center Pivot)
                    // Offset is always accumulated directly
                    offset += pan
                    
                    // Requirement 3: Conditional Gestures
                    if (!isZoomLocked) {
                        // Move & Zoom mode
                        scale *= zoom
                        scale = scale.coerceIn(0.5f, 15f)
                    } else {
                        // Move & Rotate mode
                        rotation += rotate
                    }
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
                    translationY = offset.y,
                    // Use center pivot by default
                    transformOrigin = TransformOrigin.Center
                )
        )

        // UI Toggle Button
        FloatingActionButton(
            onClick = { isZoomLocked = !isZoomLocked },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = if (isZoomLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = if (isZoomLocked) Icons.Default.Lock else Icons.Default.Refresh,
                contentDescription = if (isZoomLocked) "Switch to Zoom" else "Switch to Rotate",
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Mode Indicator Text
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            color = Color.Black.copy(alpha = 0.5f),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = if (isZoomLocked) "MODE: ROTATE & PAN" else "MODE: ZOOM & PAN",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
