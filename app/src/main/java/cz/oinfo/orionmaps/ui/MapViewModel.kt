package cz.oinfo.orionmaps.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

data class RecentMap(val uriString: String, val name: String)

data class MapGeoreference(val north: Double, val south: Double, val east: Double, val west: Double, val rotation: Double = 0.0)

data class NavigationInfo(val distance: Float, val bearing: Float, val isOffMap: Boolean)

enum class GpsMode {
    HIDDEN, FREE, FOLLOW, COMPASS_ONLY
}

class MapViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sharedPreferences = try {
        application.getSharedPreferences("recent_maps", Context.MODE_PRIVATE)
    } catch (e: Throwable) {
        null
    }

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Empty)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _recentMaps = MutableStateFlow<List<RecentMap>>(emptyList())
    val recentMaps = _recentMaps.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(sharedPreferences?.getBoolean("keep_screen_on", true) ?: true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _showOverLockScreen = MutableStateFlow(sharedPreferences?.getBoolean("show_over_lock_screen", true) ?: true)
    val showOverLockScreen: StateFlow<Boolean> = _showOverLockScreen.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _gpsMode = MutableStateFlow(GpsMode.HIDDEN)
    val gpsMode: StateFlow<GpsMode> = _gpsMode.asStateFlow()

    private val _isTrackingSuspended = MutableStateFlow(false)
    val isTrackingSuspended: StateFlow<Boolean> = _isTrackingSuspended.asStateFlow()

    private val _compassBearing = MutableStateFlow(0f)
    val compassBearing: StateFlow<Float> = _compassBearing.asStateFlow()

    private val _isRecordingEnabled = MutableStateFlow(sharedPreferences?.getBoolean("recording_enabled", false) ?: false)
    val isRecordingEnabled: StateFlow<Boolean> = _isRecordingEnabled.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _showRecordedTrack = MutableStateFlow(true)
    val showRecordedTrack: StateFlow<Boolean> = _showRecordedTrack.asStateFlow()

    private val _recordedTrack = MutableStateFlow<List<Location>>(emptyList())
    val recordedTrack: StateFlow<List<Location>> = _recordedTrack.asStateFlow()

    val hasRecordedData: StateFlow<Boolean> = _recordedTrack
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val userNavigationInfo: StateFlow<NavigationInfo?> = combine(_currentLocation, _uiState) { location, state ->
        if (location != null && state is MapUiState.Success && state.georeference != null) {
            val geo = state.georeference
            val mapCenterLat = (geo.north + geo.south) / 2.0
            val mapCenterLon = (geo.east + geo.west) / 2.0

            val results = FloatArray(2)
            Location.distanceBetween(
                mapCenterLat, mapCenterLon,
                location.latitude, location.longitude,
                results
            )
            NavigationInfo(
                distance = results[0],
                bearing = (results[1] + 360f) % 360f,
                isOffMap = isLocationOutside(location, geo)
            )
        } else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun calculateNavigation(userLoc: Location, targetLat: Double, targetLon: Double): NavigationInfo {
        val results = FloatArray(2)
        Location.distanceBetween(
            userLoc.latitude, userLoc.longitude,
            targetLat, targetLon,
            results
        )
        return NavigationInfo(
            distance = results[0],
            bearing = (results[1] + 360f) % 360f,
            isOffMap = true // This is now handled by screen visibility
        )
    }

    private fun isLocationOutside(loc: Location, geo: MapGeoreference): Boolean {
        return loc.latitude > geo.north || loc.latitude < geo.south ||
                loc.longitude > geo.east || loc.longitude < geo.west
    }

    fun formatDistance(distance: Float): String {
        return if (distance < 1000) {
            "${distance.roundToInt()} m"
        } else {
            "%.1f km".format(java.util.Locale.US, distance / 1000f)
        }
    }

    private val _isTrackSaved = MutableStateFlow(true)
    val isTrackSaved: StateFlow<Boolean> = _isTrackSaved.asStateFlow()

    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation
            _currentLocation.value = location
            
            if (_isRecording.value && location != null) {
                _recordedTrack.value = _recordedTrack.value + location
            }
        }
    }

    private val sensorManager = try { application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager } catch (e: Throwable) { null }
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private val ALPHA = 0.2f

    init {
        loadRecentMaps()
    }

    suspend fun copyToInternalStorage(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            if (uri.scheme == "file") {
                val existingFile = File(uri.path ?: "")
                if (existingFile.exists() && existingFile.parentFile == context.filesDir) {
                    return@withContext existingFile
                }
            }

            val originalName = getFileName(uri) ?: "map_${System.currentTimeMillis()}"
            var destFile = File(context.filesDir, originalName)
            if (destFile.exists()) {
                val nameWithoutExt = originalName.substringBeforeLast('.', originalName)
                val ext = originalName.substringAfterLast('.', "")
                val newName = if (ext.isNotEmpty() && ext != originalName) "${nameWithoutExt}_${System.currentTimeMillis()}.$ext" else "${nameWithoutExt}_${System.currentTimeMillis()}"
                destFile = File(context.filesDir, newName)
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                destFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun copyAssetToInternalStorage(context: Context, assetName: String): File? = withContext(Dispatchers.IO) {
        try {
            val destFile = File(context.filesDir, assetName)
            if (destFile.exists() && destFile.length() > 0) {
                return@withContext destFile
            }

            context.assets.open(assetName).use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                destFile
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadDemoMap(assetName: String) {
        viewModelScope.launch {
            _uiState.value = MapUiState.Loading
            sharedPreferences?.edit()?.putBoolean("is_loading_map", true)?.commit()

            val localFile = copyAssetToInternalStorage(getApplication(), assetName)
            if (localFile == null) {
                sharedPreferences?.edit()?.remove("is_loading_map")?.apply()
                _uiState.value = MapUiState.Error("Failed to load demo map")
                return@launch
            }

            val localUri = Uri.fromFile(localFile)
            if (assetName.endsWith(".kmz", ignoreCase = true)) {
                loadKmz(localUri)
            } else {
                loadPdf(localUri)
            }
        }
    }

    fun loadPdf(uri: Uri) {
        viewModelScope.launch {
            try {
                // Persist URI permissions
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            _uiState.value = MapUiState.Loading
            
            // CRITICAL: Use .commit() to ensure the flag is written to disk IMMEDIATELY
            sharedPreferences?.edit()?.putBoolean("is_loading_map", true)?.commit()

            val localFile = copyToInternalStorage(getApplication(), uri)
            if (localFile == null) {
                sharedPreferences?.edit()?.remove("is_loading_map")?.apply()
                _uiState.value = MapUiState.Error("Failed to copy PDF to internal storage")
                return@launch
            }

            val localUri = Uri.fromFile(localFile)
            val bitmap = renderPdfFirstPage(localUri)
            val geo = withContext(Dispatchers.IO) { extractPdfGeoreference(localFile) }
            
            // Clear loading flag
            sharedPreferences?.edit()?.remove("is_loading_map")?.apply()

            if (bitmap != null) {
                _uiState.value = MapUiState.Success(bitmap, geo)
                if (geo != null) {
                    _gpsMode.value = GpsMode.FREE
                    _isTrackingSuspended.value = false
                }
                addRecentMap(localUri)
            } else {
                if (_uiState.value !is MapUiState.Error) {
                    _uiState.value = MapUiState.Error("Failed to render PDF")
                }
            }
        }
    }

    fun loadKmz(uri: Uri) {
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            _uiState.value = MapUiState.Loading

            // CRITICAL: Use .commit() to ensure the flag is written to disk IMMEDIATELY
            sharedPreferences?.edit()?.putBoolean("is_loading_map", true)?.commit()
            
            withContext(Dispatchers.IO) {
                try {
                    val localFile = copyToInternalStorage(getApplication(), uri)
                    if (localFile == null) {
                        _uiState.value = MapUiState.Error("Failed to copy KMZ to internal storage")
                        return@withContext
                    }

                    val localUri = Uri.fromFile(localFile)
                    val contentResolver = getApplication<Application>().contentResolver
                    
                    // First pass: Find KML and parse tile structure
                    var kmlContent: String? = null
                    contentResolver.openInputStream(localUri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipInputStream ->
                            var entry = zipInputStream.nextEntry
                            while (entry != null) {
                                if (entry.name.endsWith(".kml", ignoreCase = true)) {
                                    kmlContent = zipInputStream.bufferedReader().readText()
                                    break
                                }
                                entry = zipInputStream.nextEntry
                            }
                        }
                    }

                    if (kmlContent == null) {
                        _uiState.value = MapUiState.Error("No KML found in KMZ")
                        return@withContext
                    }

                    val overlays = parseKmlGroundOverlays(kmlContent!!)
                    if (overlays.isEmpty()) {
                        _uiState.value = MapUiState.Error("No map overlays found in KML")
                        return@withContext
                    }

                    // Second pass: Load images and stitch
                    val tileBitmaps = mutableMapOf<String, Bitmap>()
                    contentResolver.openInputStream(localUri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipInputStream ->
                            var entry = zipInputStream.nextEntry
                            while (entry != null) {
                                val normalizedName = entry.name.replace("\\", "/")
                                if (overlays.any { overlay ->
                                    val cleanHref = overlay.href.substringAfterLast("/")
                                    val cleanEntry = normalizedName.substringAfterLast("/")
                                    overlay.href.contains(normalizedName) || normalizedName.contains(overlay.href) || cleanHref.equals(cleanEntry, ignoreCase = true)
                                }) {
                                    try {
                                        // REQUIREMENT 2: KMZ Image Downsampling
                                        val bytes = zipInputStream.readBytes()
                                        
                                        val options = BitmapFactory.Options().apply {
                                            inJustDecodeBounds = true
                                        }
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                        
                                        val MAX_TILE_DIMENSION = 4096
                                        var inSampleSize = 1
                                        if (options.outHeight > MAX_TILE_DIMENSION || options.outWidth > MAX_TILE_DIMENSION) {
                                            val halfHeight = options.outHeight / 2
                                            val halfWidth = options.outWidth / 2
                                            while (halfHeight / inSampleSize >= MAX_TILE_DIMENSION || halfWidth / inSampleSize >= MAX_TILE_DIMENSION) {
                                                inSampleSize *= 2
                                            }
                                        }
                                        
                                        options.inJustDecodeBounds = false
                                        options.inSampleSize = inSampleSize
                                        
                                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                        if (bmp != null) {
                                            tileBitmaps[normalizedName] = bmp
                                        }
                                    } catch (e: OutOfMemoryError) {
                                        _uiState.value = MapUiState.Error("Memory limit reached while loading tiles")
                                        return@withContext
                                    }
                                }
                                entry = zipInputStream.nextEntry
                            }
                        }
                    }

                    if (tileBitmaps.isEmpty()) {
                        _uiState.value = MapUiState.Error("No images found in KMZ for the specified overlays")
                        return@withContext
                    }

                    val result = stitchTiles(overlays, tileBitmaps)
                    if (result != null) {
                        _uiState.value = MapUiState.Success(result.first, result.second)
                        // Requirement: Default to "Free" (Pouze poloha) mode when KMZ is loaded
                        _gpsMode.value = GpsMode.FREE
                        _isTrackingSuspended.value = false

                        withContext(Dispatchers.Main) {
                            addRecentMap(localUri)
                        }
                    } else if (_uiState.value !is MapUiState.Error) {
                        _uiState.value = MapUiState.Error("Failed to stitch map tiles")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _uiState.value = MapUiState.Error("Failed to load KMZ: ${e.message}")
                } finally {
                    // Clear loading flag
                    sharedPreferences?.edit()?.remove("is_loading_map")?.apply()
                }
            }
        }
    }

    private data class GroundOverlay(
        val href: String,
        val north: Double,
        val south: Double,
        val east: Double,
        val west: Double,
        val rotation: Double
    )

    private fun parseKmlGroundOverlays(kml: String): List<GroundOverlay> {
        val overlays = mutableListOf<GroundOverlay>()
        val overlayRegex = "<GroundOverlay\\b[^>]*>([\\s\\S]*?)</GroundOverlay>".toRegex()
        val hrefRegex = "<href\\b[^>]*>([\\s\\S]*?)</href>".toRegex()
        
        overlayRegex.findAll(kml).forEach { match ->
            val content = match.groupValues[1]
            val href = hrefRegex.find(content)?.groupValues?.get(1)?.trim()?.replace("\\", "/") ?: ""
            val north = extractCoord(content, "north")
            val south = extractCoord(content, "south")
            val east = extractCoord(content, "east")
            val west = extractCoord(content, "west")
            val rotation = extractCoord(content, "rotation") ?: 0.0

            if (href.isNotEmpty() && north != null && south != null && east != null && west != null) {
                overlays.add(GroundOverlay(href, north, south, east, west, rotation))
            }
        }
        return overlays
    }

    private fun stitchTiles(
        overlays: List<GroundOverlay>,
        tileBitmaps: Map<String, Bitmap>
    ): Pair<Bitmap, MapGeoreference>? {
        // Calculate overall bounds
        val minNorth = overlays.minOf { it.north }
        val maxNorth = overlays.maxOf { it.north }
        val minSouth = overlays.minOf { it.south }
        val maxSouth = overlays.maxOf { it.south }
        val minEast = overlays.minOf { it.east }
        val maxEast = overlays.maxOf { it.east }
        val minWest = overlays.minOf { it.west }
        val maxWest = overlays.maxOf { it.west }

        val overallNorth = maxNorth
        val overallSouth = minSouth
        val overallEast = maxEast
        val overallWest = minWest
        
        // Use rotation from first overlay (assuming all have same/similar rotation for stitching)
        val rotation = overlays.first().rotation

        // Map relative positions
        // Since OCAD tiles might have different sizes or overlaps, we use geographic interpolation
        // but to keep it simple and high-res, we'll try to find a base resolution (pixels per degree)
        val firstOverlay = overlays.first()
        val firstBmp = tileBitmaps.entries.find { entry ->
            val cleanHref = firstOverlay.href.substringAfterLast("/")
            val cleanEntry = entry.key.substringAfterLast("/")
            firstOverlay.href.contains(entry.key) || entry.key.contains(firstOverlay.href) || cleanHref.equals(cleanEntry, ignoreCase = true)
        }?.value 
            ?: return null
            
        val pixelsPerLat = firstBmp.height / (firstOverlay.north - firstOverlay.south)
        val pixelsPerLon = firstBmp.width / (firstOverlay.east - firstOverlay.west)

        val totalWidth = ((overallEast - overallWest) * pixelsPerLon).toInt()
        val totalHeight = ((overallNorth - overallSouth) * pixelsPerLat).toInt()

        return try {
            val combinedBmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combinedBmp)
            canvas.drawColor(Color.WHITE)

            overlays.forEach { overlay ->
                val bmp = tileBitmaps.entries.find { entry ->
                    val cleanHref = overlay.href.substringAfterLast("/")
                    val cleanEntry = entry.key.substringAfterLast("/")
                    overlay.href.contains(entry.key) || entry.key.contains(overlay.href) || cleanHref.equals(cleanEntry, ignoreCase = true)
                }?.value
                if (bmp != null) {
                    val left = ((overlay.west - overallWest) * pixelsPerLon).toFloat()
                    val top = ((overallNorth - overlay.north) * pixelsPerLat).toFloat()
                    canvas.drawBitmap(bmp, left, top, null)
                }
            }

            Pair(combinedBmp, MapGeoreference(overallNorth, overallSouth, overallEast, overallWest, rotation))
        } catch (e: OutOfMemoryError) {
            _uiState.value = MapUiState.Error("OutOfMemory while stitching tiles")
            null
        }
    }

    private fun parseKmlGeoreference(kml: String): MapGeoreference? {
        return try {
            val north = extractCoord(kml, "north")
            val south = extractCoord(kml, "south")
            val east = extractCoord(kml, "east")
            val west = extractCoord(kml, "west")
            val rotation = extractCoord(kml, "rotation") ?: 0.0 // Výchozí hodnota je 0, pokud chybí

            if (north != null && south != null && east != null && west != null) {
                MapGeoreference(north, south, east, west, rotation)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractCoord(kml: String, tag: String): Double? {
        val regex = "<$tag\\b[^>]*>\\s*([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*</$tag>".toRegex()
        return regex.find(kml)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun addRecentMap(uri: Uri) {
        val name = getFileName(uri) ?: "Unknown Map"
        val newList = _recentMaps.value.toMutableList()
        // Remove if exists to move to top
        newList.removeAll { it.uriString == uri.toString() }
        newList.add(0, RecentMap(uri.toString(), name))
        // Keep only last 10
        if (newList.size > 10) {
            newList.removeAt(newList.size - 1)
        }
        _recentMaps.value = newList
        saveRecentMaps(newList)
    }

    fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun saveRecentMaps(maps: List<RecentMap>) {
        val jsonArray = JSONArray()
        maps.forEach {
            val jsonObject = JSONObject()
            jsonObject.put("uri", it.uriString)
            jsonObject.put("name", it.name)
            jsonArray.put(jsonObject)
        }
        sharedPreferences?.edit()?.putString("maps_json", jsonArray.toString())?.apply()
    }

    private fun loadRecentMaps() {
        val jsonString = sharedPreferences?.getString("maps_json", null)
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                val maps = mutableListOf<RecentMap>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    maps.add(RecentMap(obj.getString("uri"), obj.getString("name")))
                }
                _recentMaps.value = maps
                
                // Requirement: Open last opened map is now manual via UI
                // Safety: Check if previous load crashed - CRITICAL: use .commit() to clear it
                if (sharedPreferences?.getBoolean("is_loading_map", false) == true) {
                    sharedPreferences?.edit()?.remove("is_loading_map")?.commit()
                    _uiState.value = MapUiState.Error("Previous load failed. This map might be too large.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun openLastMap() {
        val maps = _recentMaps.value
        if (maps.isNotEmpty()) {
            val lastUri = Uri.parse(maps[0].uriString)
            val fileName = getFileName(lastUri) ?: ""
            if (fileName.endsWith(".kmz", ignoreCase = true)) {
                loadKmz(lastUri)
            } else {
                loadPdf(lastUri)
            }
        }
    }

    fun deleteRecentMap(map: RecentMap) {
        try {
            val uri = Uri.parse(map.uriString)
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val newList = _recentMaps.value.toMutableList()
        newList.removeAll { it.uriString == map.uriString }
        _recentMaps.value = newList
        saveRecentMaps(newList)
    }

    fun clearRecentMaps() {
        _recentMaps.value.forEach { map ->
            try {
                val uri = Uri.parse(map.uriString)
                if (uri.scheme == "file") {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _recentMaps.value = emptyList()
        sharedPreferences?.edit()?.remove("maps_json")?.apply()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        sharedPreferences?.edit()?.putBoolean("keep_screen_on", enabled)?.apply()
    }

    fun setShowOverLockScreen(enabled: Boolean) {
        _showOverLockScreen.value = enabled
        sharedPreferences?.edit()?.putBoolean("show_over_lock_screen", enabled)?.apply()
    }

    fun clearMap() {
        _uiState.value = MapUiState.Empty
    }

    fun startLocationUpdates(context: Context) {
        if (fusedLocationProviderClient != null) return

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()

        try {
            fusedLocationProviderClient?.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        rotationVectorSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopLocationUpdates() {
        if (fusedLocationProviderClient == null) return
        fusedLocationProviderClient?.removeLocationUpdates(locationCallback)
        fusedLocationProviderClient = null
        _currentLocation.value = null
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            val rawAzimuthRadians = orientation[0].toDouble()
            
            // Exponential Moving Average on vector components to handle 360/0 wrap-around
            smoothedSin = ALPHA * kotlin.math.sin(rawAzimuthRadians) + (1 - ALPHA) * smoothedSin
            smoothedCos = ALPHA * kotlin.math.cos(rawAzimuthRadians) + (1 - ALPHA) * smoothedCos
            
            val smoothedBearing = Math.toDegrees(kotlin.math.atan2(smoothedSin, smoothedCos)).toFloat()
            _compassBearing.value = (smoothedBearing + 360f) % 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun cycleGpsMode() {
        _gpsMode.value = when (_gpsMode.value) {
            GpsMode.COMPASS_ONLY -> GpsMode.HIDDEN
            GpsMode.HIDDEN -> GpsMode.FREE
            GpsMode.FREE -> GpsMode.FOLLOW
            GpsMode.FOLLOW -> GpsMode.COMPASS_ONLY
        }
        if (_gpsMode.value == GpsMode.FOLLOW) {
            _isTrackingSuspended.value = false
        }
    }

    fun setTrackingSuspended(suspended: Boolean) {
        _isTrackingSuspended.value = suspended
    }

    fun setRecordingEnabled(enabled: Boolean) {
        _isRecordingEnabled.value = enabled
        sharedPreferences?.edit()?.putBoolean("recording_enabled", enabled)?.apply()
    }

    fun startRecording() {
        _isRecording.value = true
        _isTrackSaved.value = false
    }

    fun stopRecording() {
        _isRecording.value = false
    }

    fun setShowRecordedTrack(show: Boolean) {
        _showRecordedTrack.value = show
    }

    fun clearRecordedTrack() {
        _recordedTrack.value = emptyList()
    }

    fun clearTrack() {
        _recordedTrack.value = emptyList()
    }

    enum class GpxImportResult {
        SUCCESS,
        OUTSIDE_MAP,
        ERROR
    }

    fun importGpx(uri: Uri, onResult: ((GpxImportResult) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                
                // Extract track points using Regex to find <trkpt lat="..." lon="...">
                val latLonRegex = "<trkpt\\s+lat=\"([-+]?[0-9]*\\.?[0-9]+)\"\\s+lon=\"([-+]?[0-9]*\\.?[0-9]+)\"".toRegex()
                val parsedList = latLonRegex.findAll(content).map { match ->
                    val lat = match.groupValues[1].toDouble()
                    val lon = match.groupValues[2].toDouble()
                    Location("gpx").apply {
                        latitude = lat
                        longitude = lon
                    }
                }.toList()

                if (parsedList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult?.invoke(GpxImportResult.ERROR)
                    }
                    return@launch
                }

                val state = _uiState.value as? MapUiState.Success
                val geo = state?.georeference

                if (geo != null) {
                    val hasPointInside = parsedList.any { loc ->
                        loc.latitude <= geo.north &&
                        loc.latitude >= geo.south &&
                        loc.longitude <= geo.east &&
                        loc.longitude >= geo.west
                    }

                    if (!hasPointInside) {
                        withContext(Dispatchers.Main) {
                            onResult?.invoke(GpxImportResult.OUTSIDE_MAP)
                        }
                        return@launch
                    }
                }

                withContext(Dispatchers.Main) {
                    _recordedTrack.value = parsedList
                    _showRecordedTrack.value = true
                    _isTrackSaved.value = true
                    onResult?.invoke(GpxImportResult.SUCCESS)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult?.invoke(GpxImportResult.ERROR)
                }
            }
        }
    }

    fun exportTrackToGpxString(): String {
        val track = _recordedTrack.value
        if (track.isEmpty()) return ""
        
        _isTrackSaved.value = true

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"OrionMaps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n")
        sb.append("    <name>Orion Track</name>\n")
        sb.append("    <trkseg>\n")
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        track.forEach { loc ->
            sb.append("      <trkpt lat=\"${loc.latitude}\" lon=\"${loc.longitude}\">\n")
            sb.append("        <ele>${loc.altitude}</ele>\n")
            sb.append("        <time>${dateFormat.format(java.util.Date(loc.time))}</time>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>")
        
        return sb.toString()
    }

    fun getSuggestedGpxFilename(): String {
        val currentMapName = _recentMaps.value.firstOrNull()?.name ?: "map"
        val stem = currentMapName.substringBeforeLast('.', currentMapName)
        
        val normalized = java.text.Normalizer.normalize(stem, java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        
        val sanitized = normalized
            .trim()
            .lowercase(java.util.Locale.US)
            .replace("[^a-z0-9_-]+".toRegex(), "-")
            .trim('-')
            .ifEmpty { "map" }

        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return "${sanitized}_${dateStr}.gpx"
    }

    fun getVersionName(): String {
        return try {
            val packageInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    fun getVersionCode(): Int {
        return try {
            val packageInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearMap()
        stopLocationUpdates()
    }

    private suspend fun renderPdfFirstPage(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            // REQUIREMENT 1: Dynamic PDF Scaling
                            val MAX_DIMENSION = 4096f
                            val scale = minOf(MAX_DIMENSION / page.width, MAX_DIMENSION / page.height)
                            
                            val width = (page.width * scale).toInt()
                            val height = (page.height * scale).toInt()

                            try {
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                                // Ensure white background (Requirement 1 - rendering level)
                                val canvas = Canvas(bitmap)
                                canvas.drawColor(Color.WHITE)

                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                return@withContext bitmap
                            } catch (e: OutOfMemoryError) {
                                _uiState.value = MapUiState.Error("PDF rendering failed: Out of memory")
                                return@withContext null
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private fun extractPdfGeoreference(file: File): MapGeoreference? {
        return try {
            val fileBytes = file.readBytes()
            var text = String(fileBytes, Charsets.ISO_8859_1)

            if (!text.contains("/GPTS")) {
                val decompressedBuilder = StringBuilder(text)
                var searchIdx = 0
                val streamToken = "stream"
                val endStreamToken = "endstream"
                while (searchIdx < fileBytes.size) {
                    val streamPos = text.indexOf(streamToken, searchIdx)
                    if (streamPos == -1) break

                    var dataStart = streamPos + streamToken.length
                    if (dataStart < fileBytes.size && fileBytes[dataStart] == '\r'.code.toByte()) dataStart++
                    if (dataStart < fileBytes.size && fileBytes[dataStart] == '\n'.code.toByte()) dataStart++

                    val endStreamPos = text.indexOf(endStreamToken, dataStart)
                    if (endStreamPos == -1) break

                    var dataEnd = endStreamPos
                    if (dataEnd > dataStart && fileBytes[dataEnd - 1] == '\n'.code.toByte()) dataEnd--
                    if (dataEnd > dataStart && fileBytes[dataEnd - 1] == '\r'.code.toByte()) dataEnd--

                    if (dataEnd > dataStart) {
                        val compressedBytes = fileBytes.copyOfRange(dataStart, dataEnd)
                        val decompressed = decompressFlateStream(compressedBytes)
                        if (decompressed != null) {
                            decompressedBuilder.append("\n").append(decompressed)
                        }
                    }

                    searchIdx = endStreamPos + endStreamToken.length
                }
                text = decompressedBuilder.toString()
            }

            val mediaBoxRegex = "/MediaBox\\s*\\[\\s*([-\\d.eE+]+)\\s+([-\\d.eE+]+)\\s+([-\\d.eE+]+)\\s+([-\\d.eE+]+)\\s*\\]".toRegex()
            val mediaMatch = mediaBoxRegex.find(text) ?: return null
            val m0 = mediaMatch.groupValues[1].toDoubleOrNull() ?: return null
            val m1 = mediaMatch.groupValues[2].toDoubleOrNull() ?: return null
            val m2 = mediaMatch.groupValues[3].toDoubleOrNull() ?: return null
            val m3 = mediaMatch.groupValues[4].toDoubleOrNull() ?: return null

            val mediaX0 = minOf(m0, m2)
            val mediaX1 = maxOf(m0, m2)
            val mediaY0 = minOf(m1, m3)
            val mediaY1 = maxOf(m1, m3)

            if (mediaX1 <= mediaX0 || mediaY1 <= mediaY0) return null

            val bboxRegex = "/BBox\\s*\\[\\s*([-\\d.eE+]+)\\s+([-\\d.eE+]+)\\s+([-\\d.eE+]+)\\s+([-\\d.eE+]+)\\s*\\]".toRegex()
            val bboxMatch = bboxRegex.find(text)
            val bxMin: Double
            val bxMax: Double
            val byMin: Double
            val byMax: Double

            if (bboxMatch != null) {
                val b0 = bboxMatch.groupValues[1].toDoubleOrNull()
                val b1 = bboxMatch.groupValues[2].toDoubleOrNull()
                val b2 = bboxMatch.groupValues[3].toDoubleOrNull()
                val b3 = bboxMatch.groupValues[4].toDoubleOrNull()
                if (b0 != null && b1 != null && b2 != null && b3 != null) {
                    bxMin = minOf(b0, b2)
                    bxMax = maxOf(b0, b2)
                    byMin = minOf(b1, b3)
                    byMax = maxOf(b1, b3)
                } else {
                    bxMin = mediaX0
                    bxMax = mediaX1
                    byMin = mediaY0
                    byMax = mediaY1
                }
            } else {
                bxMin = mediaX0
                bxMax = mediaX1
                byMin = mediaY0
                byMax = mediaY1
            }

            val gptsRegex = "/GPTS\\s*\\[\\s*([-\\d.eE+\\s]+)\\s*\\]".toRegex()
            val gptsMatch = gptsRegex.find(text) ?: return null
            val gpts = gptsMatch.groupValues[1].trim().split("\\s+".toRegex()).mapNotNull { it.toDoubleOrNull() }
            if (gpts.size < 8) return null

            val lptsRegex = "/LPTS\\s*\\[\\s*([-\\d.eE+\\s]+)\\s*\\]".toRegex()
            val lptsMatch = lptsRegex.find(text)
            val lpts = if (lptsMatch != null) {
                val parsed = lptsMatch.groupValues[1].trim().split("\\s+".toRegex()).mapNotNull { it.toDoubleOrNull() }
                if (parsed.size >= 8) parsed else listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0)
            } else {
                listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0)
            }

            val uArr = DoubleArray(4)
            val vArr = DoubleArray(4)
            val latArr = DoubleArray(4)
            val lonArr = DoubleArray(4)

            for (i in 0..3) {
                val lx = lpts[2 * i]
                val ly = lpts[2 * i + 1]
                val px = bxMin + lx * (bxMax - bxMin)
                val py = byMin + ly * (byMax - byMin)
                uArr[i] = (px - mediaX0) / (mediaX1 - mediaX0)
                vArr[i] = (mediaY1 - py) / (mediaY1 - mediaY0)
                latArr[i] = gpts[2 * i]
                lonArr[i] = gpts[2 * i + 1]
            }

            fun fitLinear(y: DoubleArray): DoubleArray? {
                var su = 0.0
                var sv = 0.0
                var suu = 0.0
                var svv = 0.0
                var suv = 0.0
                var sy = 0.0
                var suy = 0.0
                var svy = 0.0
                val n = 4.0

                for (i in 0..3) {
                    val u = uArr[i]
                    val v = vArr[i]
                    val yi = y[i]
                    su += u
                    sv += v
                    suu += u * u
                    svv += v * v
                    suv += u * v
                    sy += yi
                    suy += u * yi
                    svy += v * yi
                }

                val m00 = n
                val m01 = su
                val m02 = sv
                val m10 = su
                val m11 = suu
                val m12 = suv
                val m20 = sv
                val m21 = suv
                val m22 = svv

                val c00 = m11 * m22 - m12 * m21
                val c01 = m12 * m20 - m10 * m22
                val c02 = m10 * m21 - m11 * m20

                val c10 = m21 * m02 - m01 * m22
                val c11 = m00 * m22 - m02 * m20
                val c12 = m02 * m10 - m00 * m21

                val c20 = m01 * m12 - m02 * m11
                val c21 = m02 * m10 - m00 * m12
                val c22 = m00 * m11 - m01 * m10

                val det = m00 * c00 + m01 * c01 + m02 * c02
                if (kotlin.math.abs(det) < 1e-12) return null

                val beta0 = (c00 * sy + c10 * suy + c20 * svy) / det
                val beta1 = (c01 * sy + c11 * suy + c21 * svy) / det
                val beta2 = (c02 * sy + c12 * suy + c22 * svy) / det

                return doubleArrayOf(beta0, beta1, beta2)
            }

            val latCoeffs = fitLinear(latArr) ?: return null
            val lonCoeffs = fitLinear(lonArr) ?: return null

            val lat0 = latCoeffs[0]
            val dLat_du = latCoeffs[1]
            val dLat_dv = latCoeffs[2]

            val lon0 = lonCoeffs[0]
            val dLon_du = lonCoeffs[1]
            val dLon_dv = lonCoeffs[2]

            val midLat = lat0 + dLat_du * 0.5 + dLat_dv * 0.5
            val midLon = lon0 + dLon_du * 0.5 + dLon_dv * 0.5

            val aspect = kotlin.math.cos(Math.toRadians(midLat))

            val dLonAdj = dLon_du * aspect
            val dLat = dLat_du
            val rotation = Math.toDegrees(kotlin.math.atan2(dLat, dLonAdj))

            val spanLon = kotlin.math.hypot(dLon_du * aspect, dLat_du) / aspect
            val spanLat = kotlin.math.hypot(dLon_dv * aspect, dLat_dv)

            val north = midLat + spanLat / 2.0
            val south = midLat - spanLat / 2.0
            val east = midLon + spanLon / 2.0
            val west = midLon - spanLon / 2.0

            MapGeoreference(north, south, east, west, rotation)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decompressFlateStream(bytes: ByteArray): String? {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(bytes)
        val outputStream = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        return try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            outputStream.toByteArray().toString(Charsets.ISO_8859_1)
        } catch (e: Exception) {
            inflater.end()
            null
        }
    }
}

sealed class MapUiState {
    object Empty : MapUiState()
    object Loading : MapUiState()
    data class Success(val bitmap: Bitmap, val georeference: MapGeoreference? = null) : MapUiState()
    data class Error(val message: String) : MapUiState()
}
