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
                    // Requirement 2: Fix Pivot Point for Zoom & Rotation (Centroid math)
                    
                    // 1. Calculate the change in scale
                    val oldScale = scale
                    scale *= zoom
                    scale = scale.coerceIn(0.5f, 15f) // Increased max scale for high-res map
                    
                    // 2. Adjust offset for Zoom around Centroid
                    // The formula: offset = (offset - centroid) * (newScale / oldScale) + centroid
                    // But since we are accumulating 'offset', we add the delta.
                    val zoomFactor = scale / oldScale
                    offset = (offset - centroid) * zoomFactor + centroid
                    
                    // 3. Adjust offset for Rotation around Centroid
                    rotation += rotate
                    val rotationRad = rotate * (PI.toFloat() / 180f)
                    val cosR = cos(rotationRad)
                    val sinR = sin(rotationRad)
                    
                    val relativeCentroid = centroid - offset
                    val rotatedCentroid = Offset(
                        relativeCentroid.x * cosR - relativeCentroid.y * sinR,
                        relativeCentroid.x * sinR + relativeCentroid.y * cosR
                    )
                    offset += relativeCentroid - rotatedCentroid

                    // 4. Apply Pan
                    offset += pan
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "PDF Map",
            modifier = Modifier
                .fillMaxSize()
                // Requirement 1: White Background
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
