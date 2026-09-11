package cz.oinfo.orionmaps.ui

import android.Manifest
import androidx.compose.ui.res.stringResource
import cz.oinfo.orionmaps.R
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
                Box(modifier = Modifier.fillMaxSize()) {
                    // App Name and Logo at Top
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Image(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(MaterialTheme.shapes.medium)
                        )
                    }

                    // Main Controls in Center
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (recentMaps.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.openLastMap() }
                            ) {
                                Text(stringResource(R.string.open_last_map))
                            }
                            Text(
                                text = recentMaps[0].name,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )
                        }

                        Row {
                            Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                                Text(stringResource(R.string.open_pdf_map))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = { launcher.launch(arrayOf("application/vnd.google-earth.kmz")) }) {
                                Text(stringResource(R.string.open_kmz_map))
                            }
                        }

                        if (recentMaps.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { showRecentMapsDialog = true }
                            ) {
                                Text(stringResource(R.string.recent_maps))
                            }
                        }
                    }

                    // Version Info at Bottom
                    Text(
                        text = stringResource(
                            R.string.version_label,
                            viewModel.getVersionName(),
                            viewModel.getVersionCode()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 16.dp)
                    )
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
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.error_opening_map),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Row {
                        Button(onClick = { launcher.launch(arrayOf("application/pdf")) }) {
                            Text(stringResource(R.string.open_pdf_map))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { launcher.launch(arrayOf("application/vnd.google-earth.kmz")) }) {
                            Text(stringResource(R.string.open_kmz_map))
                        }
                    }
                    if (recentMaps.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showRecentMapsDialog = true }
                        ) {
                            Text(stringResource(R.string.recent_maps))
                        }
                    }
                }
            }
        }

        // Status Bar Background
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .align(Alignment.TopCenter)
        )

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

    val isRecordingEnabled by viewModel.isRecordingEnabled.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val showRecordedTrack by viewModel.showRecordedTrack.collectAsState()
    val recordedTrack by viewModel.recordedTrack.collectAsState()

    val context = LocalContext.current

    var notificationText by remember { mutableStateOf("") }
    var showNotification by remember { mutableStateOf(false) }

    fun triggerNotification(text: String) {
        notificationText = text
        showNotification = true
    }

    val trackSavedMsg = stringResource(R.string.track_saved_success)
    val trackErrorMsg = stringResource(R.string.error_saving_track)

    val createGpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        uri?.let {
            val gpxString = viewModel.exportTrackToGpxString()
            try {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(gpxString.toByteArray())
                }
                viewModel.clearRecordedTrack()
                triggerNotification(trackSavedMsg)
            } catch (e: Exception) {
                triggerNotification(trackErrorMsg)
            }
        }
    }

    val loadGpxLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val fileName = viewModel.getFileName(it) ?: ""
            if (fileName.endsWith(".gpx", ignoreCase = true)) {
                viewModel.importGpx(it)
            }
        }
    }
    
    var gestureMode by remember { mutableStateOf(GestureMode.ALL) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(notificationText, showNotification) {
        if (showNotification) {
            delay(1000)
            showNotification = false
        }
    }

    var showRecentMapsDialog by remember { mutableStateOf(false) }

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
        val currentRotation = if (gpsMode == GpsMode.FOLLOW && !isTrackingSuspended) -compassBearing else rotation

        // Calculation for content positioning (reused for centering math and marker positioning)
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

        val activeOffset = if (gpsMode == GpsMode.FOLLOW && !isTrackingSuspended && currentLocation != null && georeference != null && containerSize.width > 0) {
            val percentPos = getMapPercentages(currentLocation!!, georeference)
            
            val baseDotX = renderOffsetX + percentPos.first * renderedWidth
            val baseDotY = renderOffsetY + percentPos.second * renderedHeight

            // Target is bottom third (70% down)
            val targetX = containerSize.width / 2f
            val targetY = containerSize.height * 0.7f

            val angleInRadians = currentRotation * PI / 180.0
            val cos = cos(angleInRadians).toFloat()
            val sin = sin(angleInRadians).toFloat()

            // scaled position relative to top-left of rendered image
            val sx = baseDotX * scale
            val sy = baseDotY * scale

            // rotated
            val rx = sx * cos - sy * sin
            val ry = sx * sin + sy * cos

            Offset(targetX - rx, targetY - ry)
        } else {
            offset
        }

        val trackPath = remember(recordedTrack, containerSize, georeference) {
            val path = androidx.compose.ui.graphics.Path()
            if (georeference != null && containerSize.width > 0) {
                recordedTrack.forEachIndexed { index, loc ->
                    val percentPos = getMapPercentages(loc, georeference)
                    val x = renderOffsetX + percentPos.first * renderedWidth
                    val y = renderOffsetY + percentPos.second * renderedHeight

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
            }
            path
        }

        val baseDotX: Float
        val baseDotY: Float
        if (georeference != null && currentLocation != null) {
            val percentPos = getMapPercentages(currentLocation!!, georeference)
            baseDotX = renderOffsetX + percentPos.first * renderedWidth
            baseDotY = renderOffsetY + percentPos.second * renderedHeight
        } else {
            baseDotX = 0f
            baseDotY = 0f
        }

        // MASTER TRANSFORM BOX
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    translationX = activeOffset.x
                    translationY = activeOffset.y
                    scaleX = scale
                    scaleY = scale
                    rotationZ = currentRotation
                }
        ) {
            // 1. MAP
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF Map",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )

            // 2. GPX TRACK
            if (showRecordedTrack && !trackPath.isEmpty) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPath(
                        path = trackPath,
                        color = Color.Red,
                        style = Stroke(
                            width = 3.dp.toPx() / scale,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                        )
                    )
                }
            }

// 3. GPS DOT
            if (georeference != null && currentLocation != null) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(baseDotX.roundToInt(), baseDotY.roundToInt()) }
                        .size(32.dp) // Větší kontejner, aby se šipka při rotaci neusekla
                        .offset((-16).dp, (-16).dp) // Vycentrování na přesnou souřadnici
                        .graphicsLayer {
                            rotationZ = compassBearing
                            // ZÁSADNÍ TRIK: Inverzní měřítko zruší zoom mapy, tečka bude stále stejně velká
                            scaleX = 1f / scale
                            scaleY = 1f / scale
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val radius = 5.dp.toPx() // Diameter 10dp
                        val gap = 1.5.dp.toPx()  // Gap between dot and beak
                        val center = Offset(size.width / 2, size.height / 2)

                        // 1. Path for the central dot
                        val dotPath = androidx.compose.ui.graphics.Path().apply {
                            addOval(androidx.compose.ui.geometry.Rect(
                                center.x - radius, center.y - radius,
                                center.x + radius, center.y + radius
                            ))
                        }

                        // 2. Path for the detached beak (pointing UP)
                        val beakPath = androidx.compose.ui.graphics.Path().apply {
                            // Start beak slightly above the dot + gap
                            val beakBottomY = center.y - radius - gap
                            // Shrink beak by ~15%: width 0.7f -> 0.6f, height 1.5f -> 1.3f
                            moveTo(center.x - radius * 0.6f, beakBottomY)
                            lineTo(center.x, beakBottomY - radius * 1.3f) // Tip of the beak
                            lineTo(center.x + radius * 0.6f, beakBottomY)
                            close()
                        }

                        // 3. Draw WHITE halo/outline for BOTH parts first
                        val strokeWidth = 3.dp.toPx()
                        drawPath(
                            path = dotPath,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            )
                        )
                        drawPath(
                            path = beakPath,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            )
                        )

                        // 4. Fill BOTH parts with MAGENTA
                        drawPath(path = dotPath, color = Color.Magenta)
                        drawPath(path = beakPath, color = Color.Magenta)
                    }
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
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Settings Button (Gear icon)
            var showSettingsMenu by remember { mutableStateOf(false) }

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
                        text = { Text(stringResource(R.string.reset_map)) },
                        onClick = {
                            scale = 1f
                            rotation = 0f
                            offset = Offset.Zero
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.SettingsBackupRestore, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_pdf_map)) },
                        onClick = {
                            onOpenNewPdfMap()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_kmz_map)) },
                        onClick = {
                            onOpenNewKmzMap()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recent_maps)) },
                        onClick = {
                            showRecentMapsDialog = true
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.app_settings)) },
                        onClick = {
                            onOpenAppSettings()
                            showSettingsMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.SettingsSuggest, contentDescription = null) }
                    )
                }
            }

            if (georeference != null) {
                if (isRecordingEnabled) {
                    GpxMenuFab(
                        viewModel = viewModel,
                        onExportClick = { createGpxLauncher.launch("orion_track.gpx") },
                        onLoadClick = { loadGpxLauncher.launch(arrayOf("*/*")) }
                    )
                }

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
                    Icon(icon, contentDescription = stringResource(R.string.cycle_gps_mode))
                }

                if (gpsMode == GpsMode.FOLLOW && isTrackingSuspended) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.setTrackingSuspended(false) },
                        modifier = Modifier.size(40.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.recenter))
                    }
                }
            }

            val modeAll = stringResource(R.string.mode_all_gestures)
            val modeRot = stringResource(R.string.mode_rotation_locked)
            val modeZoom = stringResource(R.string.mode_zoom_locked)

            FloatingActionButton(
                onClick = {
                    gestureMode = when (gestureMode) {
                        GestureMode.ALL -> GestureMode.LOCK_ROTATION
                        GestureMode.LOCK_ROTATION -> GestureMode.LOCK_ZOOM
                        GestureMode.LOCK_ZOOM -> GestureMode.ALL
                    }
                    triggerNotification(when (gestureMode) {
                        GestureMode.ALL -> modeAll
                        GestureMode.LOCK_ROTATION -> modeRot
                        GestureMode.LOCK_ZOOM -> modeZoom
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
                Icon(icon, contentDescription = stringResource(R.string.toggle_gesture_mode))
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
    }
}

@Composable
fun GpxMenuFab(
    viewModel: MapViewModel,
    onExportClick: () -> Unit,
    onLoadClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteWarning by remember { mutableStateOf(false) }

    val isRecording by viewModel.isRecording.collectAsState()
    val showRecordedTrack by viewModel.showRecordedTrack.collectAsState()
    val hasRecordedData by viewModel.hasRecordedData.collectAsState()
    val isTrackSaved by viewModel.isTrackSaved.collectAsState()

    Box {
        FloatingActionButton(
            onClick = { expanded = true },
            modifier = Modifier.size(44.dp),
            containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        ) {
            Icon(
                if (isRecording) Icons.Default.RadioButtonChecked else Icons.Default.Route,
                contentDescription = stringResource(R.string.gpx_menu),
                tint = if (isRecording) Color.Red else LocalContentColor.current
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (isRecording) stringResource(R.string.stop_recording) else stringResource(R.string.start_recording)) },
                onClick = {
                    if (isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                    expanded = false
                },
                leadingIcon = {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = if (isRecording) Color.Red else Color.Unspecified
                    )
                }
            )
            
            DropdownMenuItem(
                text = { Text(if (showRecordedTrack) stringResource(R.string.hide_track) else stringResource(R.string.show_track)) },
                onClick = {
                    viewModel.setShowRecordedTrack(!showRecordedTrack)
                    expanded = false
                },
                enabled = hasRecordedData || isRecording,
                leadingIcon = {
                    Icon(
                        if (showRecordedTrack) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_track)) },
                onClick = {
                    onExportClick()
                    expanded = false
                },
                enabled = !isRecording && hasRecordedData,
                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.load_gpx)) },
                onClick = {
                    onLoadClick()
                    expanded = false
                },
                enabled = !isRecording,
                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.clear_track)) },
                onClick = {
                    if (isTrackSaved) {
                        viewModel.clearRecordedTrack()
                    } else {
                        showDeleteWarning = true
                    }
                    expanded = false
                },
                enabled = !isRecording && hasRecordedData,
                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) }
            )
        }
    }

    if (showDeleteWarning) {
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            title = { Text(stringResource(R.string.unsaved_track_title)) },
            text = { Text(stringResource(R.string.unsaved_track_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearRecordedTrack()
                        showDeleteWarning = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
                    text = stringResource(R.string.app_settings),
                    style = MaterialTheme.typography.headlineSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.keep_screen_on), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.keep_screen_on_desc),
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
                        Text(text = stringResource(R.string.show_over_lock_screen), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.show_over_lock_screen_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showOverLockScreen,
                        onCheckedChange = { viewModel.setShowOverLockScreen(it) }
                    )
                }

                val isRecordingEnabled by viewModel.isRecordingEnabled.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.track_recording), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.track_recording_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isRecordingEnabled,
                        onCheckedChange = { viewModel.setRecordingEnabled(it) }
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.close))
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
                        text = stringResource(R.string.recent_maps),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    IconButton(onClick = { viewModel.clearRecentMaps() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.clear_list))
                    }
                }

                if (recentMaps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_recent_maps),
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
                                            R.string.error_opening_map,
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
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
