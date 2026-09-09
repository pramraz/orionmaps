package cz.oinfo.orionmaps.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
                    Text("Open PDF Map")
                }
            }
            is MapUiState.Loading -> {
                CircularProgressIndicator(color = Color.White)
            }
            is MapUiState.Success -> {
                InteractiveMap(
                    bitmap = state.bitmap,
                    onOpenNewMap = { launcher.launch(arrayOf("application/pdf")) },
                    viewModel = viewModel
                )
            }
            is MapUiState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
}

@Composable
fun InteractiveMap(
    bitmap: android.graphics.Bitmap,
    onOpenNewMap: () -> Unit,
    viewModel: MapViewModel
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableStateOf(0f) }
    
    var gestureMode by remember { mutableStateOf(GestureMode.ALL) }
    var notificationText by remember { mutableStateOf("") }
    var showNotification by remember { mutableStateOf(false) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

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
            .onGloballyPositioned { containerSize = it.size }
            .pointerInput(gestureMode) {
                detectTransformGestures { centroid, pan, zoom, rotate ->
                    val effectiveZoom = if (gestureMode == GestureMode.LOCK_ZOOM) 1f else zoom
                    val effectiveRotation = if (gestureMode == GestureMode.LOCK_ROTATION) 0f else rotate

                    val oldScale = scale
                    val newScale = (scale * effectiveZoom).coerceIn(1f, 15f)
                    val scaleRatio = newScale / oldScale

                    val angleInRadians = effectiveRotation * PI / 180.0
                    val cos = cos(angleInRadians).toFloat()
                    val sin = sin(angleInRadians).toFloat()

                    // Vektor od aktuálního offsetu k centroidu (bodu mezi prsty)
                    val dx = centroid.x - offset.x
                    val dy = centroid.y - offset.y

                    // Aplikace rotace na tento vektor
                    val rx = dx * cos - dy * sin
                    val ry = dx * sin + dy * cos

                    // Aplikace změny měřítka
                    val sx = rx * scaleRatio
                    val sy = ry * scaleRatio

                    // Výpočet nového offsetu: stávající + posun prstu + korekční posun
                    offset = Offset(
                        x = offset.x + pan.x + dx - sx,
                        y = offset.y + pan.y + dy - sy
                    )

                    scale = newScale
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
                .graphicsLayer {
                    // ZÁSADNÍ OPRAVA: Fixace středu transformace do levého horního rohu
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
        )

        // Custom Notification Overlay
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible = showNotification,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier.padding(top = 96.dp),
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

        // Control Buttons in Top-Right Column
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 32.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Toggle Button (Lock icon) - Resized to 80%
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
                modifier = Modifier.size(56.dp * 0.8f),
                containerColor = when (gestureMode) {
                    GestureMode.ALL -> MaterialTheme.colorScheme.primaryContainer
                    GestureMode.LOCK_ROTATION -> Color(0xFFF44336) // Red
                    GestureMode.LOCK_ZOOM -> Color(0xFFFF9800) // Orange
                }
            ) {
                val icon = when (gestureMode) {
                    GestureMode.ALL -> Icons.Default.LockOpen
                    else -> Icons.Default.Lock
                }
                Icon(icon, contentDescription = "Toggle Gesture Mode")
            }

            // Settings Button (Gear icon)
            var showSettingsMenu by remember { mutableStateOf(false) }
            var showRecentMapsDialog by remember { mutableStateOf(false) }

            Box {
                FloatingActionButton(
                    onClick = { showSettingsMenu = true },
                    modifier = Modifier.size(56.dp * 0.8f),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }

                DropdownMenu(
                    expanded = showSettingsMenu,
                    onDismissRequest = { showSettingsMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Reset map") },
                        onClick = {
                            scale = 1f
                            rotation = 0f
                            offset = Offset.Zero
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.SettingsBackupRestore, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Open PDF map") },
                        onClick = {
                            onOpenNewMap()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Recent maps") },
                        onClick = {
                            showRecentMapsDialog = true
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                }
            }

            if (showRecentMapsDialog) {
                RecentMapsDialog(
                    onDismiss = { showRecentMapsDialog = false },
                    onMapSelected = { uriString ->
                        viewModel.loadPdf(android.net.Uri.parse(uriString))
                        showRecentMapsDialog = false
                    },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun RecentMapsDialog(
    onDismiss: () -> Unit,
    onMapSelected: (String) -> Unit,
    viewModel: MapViewModel
) {
    val recentMaps by viewModel.recentMaps.collectAsState()
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Maps",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    IconButton(onClick = { viewModel.clearRecentMaps() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Clear List")
                    }
                }

                if (recentMaps.isEmpty()) {
                    Text(
                        text = "No recent maps",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(recentMaps) { map ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = map.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.clickable {
                                    try {
                                        onMapSelected(map.uriString)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Error opening map",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }
}
