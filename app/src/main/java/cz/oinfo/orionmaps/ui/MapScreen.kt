package cz.oinfo.orionmaps.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotate ->
                    // Correcting the Gesture Math:
                    // To keep the point under the fingers (centroid) fixed:
                    // 1. We rotate and scale the current translation (offset) and the centroid point.
                    
                    val oldScale = scale
                    scale = (scale * zoom).coerceIn(0.5f, 15f)
                    val scaleFactor = scale / oldScale

                    val rotationRad = rotate * (PI.toFloat() / 180f)
                    
                    // The correct formula for centroid-based transformation:
                    // newOffset = (offset - centroid) * scaleFactor + centroid + pan
                    // AND accounting for rotation:
                    
                    // Step 1: Pan is simply added
                    offset += pan
                    
                    // Step 2: Rotate and Scale around the centroid
                    // Shift the coordinate system so the centroid is at the origin
                    val centeredOffset = offset - centroid
                    
                    // Rotate the vector from centroid to current offset
                    val cosR = cos(rotationRad)
                    val sinR = sin(rotationRad)
                    val rotatedOffset = Offset(
                        centeredOffset.x * cosR - centeredOffset.y * sinR,
                        centeredOffset.x * sinR + centeredOffset.y * cosR
                    )
                    
                    // Scale and shift back
                    offset = rotatedOffset * scaleFactor + centroid
                    
                    rotation += rotate
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
    }
}
