package cz.oinfo.orionmaps.ui

import android.Manifest
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
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
    val gpsMode by viewModel.gpsMode.collectAsState()
    val recentMaps by viewModel.recentMaps.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    var showRecentMapsDialog by remember { mutableStateOf(false) }
    var showAppSettingsDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    // Handle Keep Screen On
    DisposableEffect(keepScreenOn) {
        val window = (context as? ComponentActivity)?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                viewModel.startLocationUpdates(context)
            }
        }
    )

    LaunchedEffect(uiState, gpsMode) {
        val state = uiState
        if (state is MapUiState.Success && state.georeference != null && gpsMode != GpsMode.HIDDEN) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                viewModel.startLocationUpdates(context)
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        } else if (state !is MapUiState.Loading) {
            viewModel.stopLocationUpdates()
        }
    }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { 
                val fileName = viewModel.getFileName(it) ?: ""
                if (fileName.endsWith(".kmz", ignoreCase = true)) {
                    viewModel.loadKmz(it)
                } else {
                    viewModel.loadPdf(it)
                }
            }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                        Text("Open PDF map")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(arrayOf("application/vnd.google-earth.kmz")) }) {
                        Text("Open KMZ map")
                    }
                    if (recentMaps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showRecentMapsDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Recent maps")
                        }
                    }
                }
            }
            is MapUiState.Loading -> {
                CircularProgressIndicator(color = Color.White)
            }
            is MapUiState.Success -> {
                InteractiveMap(
                    bitmap = state.bitmap,
                    georeference = state.georeference,
                    onOpenNewPdfMap = { launcher.launch(arrayOf("application/pdf")) },
                    onOpenNewKmzMap = { launcher.launch(arrayOf("application/vnd.google-earth.kmz")) },
                    onOpenAppSettings = { showAppSettingsDialog = true },
                    viewModel = viewModel
                )
            }
            is MapUiState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                    Row(modifier = Modifier.padding(top = 16.dp)) {
                        Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                            Text("PDF")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { launcher.launch(arrayOf("application/vnd.google-earth.kmz")) }) {
                            Text("KMZ")
                        }
                    }
                    if (recentMaps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showRecentMapsDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Recent maps")
                        }
                    }
                }
            }
        }

        if (showRecentMapsDialog) {
            RecentMapsDialog(
                onDismiss = { showRecentMapsDialog = false },
                onMapSelected = { uriString ->
                    val uri = android.net.Uri.parse(uriString)
                    val fileName = viewModel.getFileName(uri) ?: ""
                    if (fileName.endsWith(".kmz", ignoreCase = true)) {
                        viewModel.loadKmz(uri)
                    } else {
                        viewModel.loadPdf(uri)
                    }
                    showRecentMapsDialog = false
                },
                viewModel = viewModel
            )
        }

        if (showAppSettingsDialog) {
            AppSettingsDialog(
                onDismiss = { showAppSettingsDialog = false },
                viewModel = viewModel
            )
        }
    }
}

@Composable
fun InteractiveMap(
    bitmap: android.graphics.Bitmap,
    georeference: MapGeoreference?,
    onOpenNewPdfMap: () -> Unit,
    onOpenNewKmzMap: () -> Unit,
    onOpenAppSettings: () -> Unit,
    viewModel: MapViewModel
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableStateOf(0f) }
    
    val currentLocation by viewModel.currentLocation.collectAsState()
    val gpsMode by viewModel.gpsMode.collectAsState()
    val isTrackingSuspended by viewModel.isTrackingSuspended.collectAsState()
    val compassBearing by viewModel.compassBearing.collectAsState()
    
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
            .pointerInput(gestureMode, gpsMode) {
                detectTransformGestures { centroid, pan, zoom, rotate ->
                    if (gpsMode == GpsMode.FOLLOW && (pan != Offset.Zero || zoom != 1f)) {
                        viewModel.setTrackingSuspended(true)
                    }

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
        val effectiveRotation = if (gpsMode == GpsMode.FOLLOW && !isTrackingSuspended) {
        -compassBearing
    } else {
        rotation
    }

    // Effect to handle map centering in FOLLOW mode
    LaunchedEffect(currentLocation, gpsMode, isTrackingSuspended, scale, effectiveRotation, containerSize) {
        if (gpsMode == GpsMode.FOLLOW && !isTrackingSuspended && currentLocation != null && georeference != null && containerSize.width > 0) {
            val percentPos = getMapPercentages(currentLocation!!, georeference)
            
            val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val containerRatio = containerSize.width.toFloat() / containerSize.height.toFloat()

            val renderedWidth: Float
            val renderedHeight: Float
            val renderOffsetX: Float
            val renderOffsetY: Float

            if (imgRatio > containerRatio) {
                renderedWidth = containerSize.width.toFloat()
                renderedHeight = containerSize.width.toFloat() / imgRatio
                renderOffsetX = 0f
                renderOffsetY = (containerSize.height.toFloat() - renderedHeight) / 2f
            } else {
                renderedHeight = containerSize.height.toFloat()
                renderedWidth = containerSize.height.toFloat() * imgRatio
                renderOffsetX = (containerSize.width.toFloat() - renderedWidth) / 2f
                renderOffsetY = 0f
            }

            val baseDotX = renderOffsetX + percentPos.first * renderedWidth
            val baseDotY = renderOffsetY + percentPos.second * renderedHeight

            // Calculate offset to center baseDotX, baseDotY
            // Target is bottom third
            val centerX = containerSize.width / 2f
            val centerY = containerSize.height * (2f / 3f)

            val angleInRadians = effectiveRotation * PI / 180.0
            val cos = cos(angleInRadians).toFloat()
            val sin = sin(angleInRadians).toFloat()

            // scaled position relative to top-left of rendered image
            val sx = baseDotX * scale
            val sy = baseDotY * scale

            // rotated
            val rx = sx * cos - sy * sin
            val ry = sx * sin + sy * cos

            offset = Offset(centerX - rx, centerY - ry)
            rotation = effectiveRotation
        }
    }

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
                    rotationZ = if (gpsMode == GpsMode.FOLLOW && !isTrackingSuspended) effectiveRotation else rotation
                }
        )

// GPS Dot
        if (gpsMode != GpsMode.HIDDEN && georeference != null && currentLocation != null && containerSize.width > 0) {
            val percentPos = getMapPercentages(
                currentLocation!!,
                georeference
            )

            // Výpočet reálného zmenšení a posunu mapy na obrazovce (ContentScale.Fit)
            val imgRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val containerRatio = containerSize.width.toFloat() / containerSize.height.toFloat()

            val renderedWidth: Float
            val renderedHeight: Float
            val renderOffsetX: Float
            val renderOffsetY: Float

            if (imgRatio > containerRatio) {
                renderedWidth = containerSize.width.toFloat()
                renderedHeight = containerSize.width.toFloat() / imgRatio
                renderOffsetX = 0f
                renderOffsetY = (containerSize.height.toFloat() - renderedHeight) / 2f
            } else {
                renderedHeight = containerSize.height.toFloat()
                renderedWidth = containerSize.height.toFloat() * imgRatio
                renderOffsetX = (containerSize.width.toFloat() - renderedWidth) / 2f
                renderOffsetY = 0f
            }

            // Finální X/Y pozice na displeji pro náš bod
            val baseDotX = renderOffsetX + percentPos.first * renderedWidth
            val baseDotY = renderOffsetY + percentPos.second * renderedHeight

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        translationX = offset.x
                        translationY = offset.y
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                    }
            ) {
                // Directional Marker
                Canvas(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                baseDotX.roundToInt(),
                                baseDotY.roundToInt()
                            )
                        }
                        .size(4.dp)
                        .offset((-2).dp, (-2).dp)
                        .graphicsLayer {
                            // Points to compass north. Subtract map rotation because parent is rotated.
                            rotationZ = compassBearing - rotation
                        }
                ) {
                    val w = this.size.width
                    val h = this.size.height
                    val center = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f)
                    val dotRadius = w / 4.5f

                    // 1. Draw Heading Triangle (Beak) - slightly offset from center
                    val trianglePath = Path().apply {
                        moveTo(w / 2f, h * 0.01f) // Top tip
                        lineTo(w * 0.75f, h * 0.3f) // Right base
                        lineTo(w * 0.25f, h * 0.3f) // Left base
                        close()
                    }
                    
                    // White outline for triangle
                    drawPath(
                        path = trianglePath,
                        color = Color.White,
                        style = Stroke(width = 0.5.dp.toPx())
                    )
                    // Blue fill for triangle
                    drawPath(
                        path = trianglePath,
                        color = Color(0xFF2196F3)
                    )

                    // 2. Draw Position Dot with White Outline
                    drawCircle(
                        color = Color.White,
                        radius = dotRadius + 0.5.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Color(0xFF2196F3),
                        radius = dotRadius,
                        center = center
                    )
                }
            }
        }

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
            // Settings Button (Gear icon)
            var showSettingsMenu by remember { mutableStateOf(false) }
            var showRecentMapsDialog by remember { mutableStateOf(false) }

            Box {
                FloatingActionButton(
                    onClick = { showSettingsMenu = true },
                    modifier = Modifier.size(44.dp),
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
                            onOpenNewPdfMap()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Open KMZ map") },
                        onClick = {
                            onOpenNewKmzMap()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Recent maps") },
                        onClick = {
                            showRecentMapsDialog = true
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("App settings") },
                        onClick = {
                            onOpenAppSettings()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.SettingsSuggest, contentDescription = null) }
                    )
                }
            }

            if (georeference != null) {
                FloatingActionButton(
                    onClick = { viewModel.cycleGpsMode() },
                    modifier = Modifier.size(44.dp),
                    containerColor = when (gpsMode) {
                        GpsMode.HIDDEN -> MaterialTheme.colorScheme.surfaceVariant
                        GpsMode.FREE -> MaterialTheme.colorScheme.primaryContainer
                        GpsMode.FOLLOW -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    val icon = when (gpsMode) {
                        GpsMode.HIDDEN -> Icons.Default.LocationOff
                        GpsMode.FREE -> Icons.Default.LocationOn
                        GpsMode.FOLLOW -> Icons.Default.Navigation
                    }
                    Icon(icon, contentDescription = "Cycle GPS Mode")
                }

                if (gpsMode == GpsMode.FOLLOW && isTrackingSuspended) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.setTrackingSuspended(false) },
                        modifier = Modifier.size(40.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Recenter")
                    }
                }
            }

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
                modifier = Modifier.size(44.dp),
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

            if (showRecentMapsDialog) {
                RecentMapsDialog(
                    onDismiss = { showRecentMapsDialog = false },
                    onMapSelected = { uriString ->
                        val uri = android.net.Uri.parse(uriString)
                        val fileName = viewModel.getFileName(uri) ?: ""
                        if (fileName.endsWith(".kmz", ignoreCase = true)) {
                            viewModel.loadKmz(uri)
                        } else {
                            viewModel.loadPdf(uri)
                        }
                        showRecentMapsDialog = false
                    },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun AppSettingsDialog(
    onDismiss: () -> Unit,
    viewModel: MapViewModel
) {
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val showOverLockScreen by viewModel.showOverLockScreen.collectAsState()

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "App Settings",
                    style = MaterialTheme.typography.headlineSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Keep screen on", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Prevent display from turning off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Show over lock screen", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Show app without unlocking phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showOverLockScreen,
                        onCheckedChange = { viewModel.setShowOverLockScreen(it) }
                    )
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

fun getMapPercentages(
    location: android.location.Location,
    geo: MapGeoreference
): Pair<Float, Float> {
    val midLat = (geo.north + geo.south) / 2.0
    val midLon = (geo.east + geo.west) / 2.0

    val dLat = location.latitude - midLat
    val dLon = location.longitude - midLon

    // Korekce sférického zkreslení Země
    val aspect = kotlin.math.cos(Math.toRadians(midLat))
    val dLonAdj = dLon * aspect

    // Rotace GPS bodu vůči mapě (inverzní k rotaci mapy v KML)
    val angleRad = Math.toRadians(-geo.rotation)
    val cos = kotlin.math.cos(angleRad)
    val sin = kotlin.math.sin(angleRad)

    val rotDLonAdj = dLonAdj * cos - dLat * sin
    val rotDLat = dLonAdj * sin + dLat * cos

    // Návrat z korigované délky
    val rotDLon = rotDLonAdj / aspect

    // Výpočet procentuální pozice vůči hranicím mapy
    val percentX = (rotDLon + (geo.east - geo.west) / 2.0) / (geo.east - geo.west)
    val percentY = ((geo.north - geo.south) / 2.0 - rotDLat) / (geo.north - geo.south)

    return Pair(percentX.toFloat(), percentY.toFloat())
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
