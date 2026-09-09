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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

data class RecentMap(val uriString: String, val name: String)

data class MapGeoreference(val north: Double, val south: Double, val east: Double, val west: Double, val rotation: Double = 0.0)

enum class GpsMode {
    HIDDEN, FREE, FOLLOW
}

class MapViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sharedPreferences = application.getSharedPreferences("recent_maps", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Empty)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _recentMaps = MutableStateFlow<List<RecentMap>>(emptyList())
    val recentMaps = _recentMaps.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(sharedPreferences.getBoolean("keep_screen_on", true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _showOverLockScreen = MutableStateFlow(sharedPreferences.getBoolean("show_over_lock_screen", true))
    val showOverLockScreen: StateFlow<Boolean> = _showOverLockScreen.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _gpsMode = MutableStateFlow(GpsMode.HIDDEN)
    val gpsMode: StateFlow<GpsMode> = _gpsMode.asStateFlow()

    private val _isTrackingSuspended = MutableStateFlow(false)
    val isTrackingSuspended: StateFlow<Boolean> = _isTrackingSuspended.asStateFlow()

    private val _compassBearing = MutableStateFlow(0f)
    val compassBearing: StateFlow<Float> = _compassBearing.asStateFlow()

    private val _isRecordingEnabled = MutableStateFlow(sharedPreferences.getBoolean("recording_enabled", false))
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

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var smoothedSin = 0.0
    private var smoothedCos = 0.0
    private val ALPHA = 0.2f

    init {
        loadRecentMaps()
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
            sharedPreferences.edit().putBoolean("is_loading_map", true).commit()

            val bitmap = renderPdfFirstPage(uri)
            
            // Clear loading flag
            sharedPreferences.edit().remove("is_loading_map").apply()

            if (bitmap != null) {
                _uiState.value = MapUiState.Success(bitmap)
                addRecentMap(uri)
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
            sharedPreferences.edit().putBoolean("is_loading_map", true).commit()
            
            withContext(Dispatchers.IO) {
                try {
                    val contentResolver = getApplication<Application>().contentResolver
                    
                    // First pass: Find KML and parse tile structure
                    var kmlContent: String? = null
                    contentResolver.openInputStream(uri)?.use { inputStream ->
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
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipInputStream ->
                            var entry = zipInputStream.nextEntry
                            while (entry != null) {
                                val normalizedName = entry.name.replace("\\", "/")
                                if (overlays.any { it.href.contains(normalizedName) || normalizedName.contains(it.href) }) {
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
                        withContext(Dispatchers.Main) {
                            addRecentMap(uri)
                        }
                    } else if (_uiState.value !is MapUiState.Error) {
                        _uiState.value = MapUiState.Error("Failed to stitch map tiles")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _uiState.value = MapUiState.Error("Failed to load KMZ: ${e.message}")
                } finally {
                    // Clear loading flag
                    sharedPreferences.edit().remove("is_loading_map").apply()
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
        val overlayRegex = "<GroundOverlay>([\\s\\S]*?)</GroundOverlay>".toRegex()
        val hrefRegex = "<href>([\\s\\S]*?)</href>".toRegex()
        
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
        val firstBmp = tileBitmaps.entries.find { firstOverlay.href.contains(it.key) || it.key.contains(firstOverlay.href) }?.value 
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
                val bmp = tileBitmaps.entries.find { overlay.href.contains(it.key) || it.key.contains(overlay.href) }?.value
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
        val regex = "<$tag>\\s*([-+]?[0-9]*\\.?[0-9]+)\\s*</$tag>".toRegex()
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
        sharedPreferences.edit().putString("maps_json", jsonArray.toString()).apply()
    }

    private fun loadRecentMaps() {
        val jsonString = sharedPreferences.getString("maps_json", null)
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                val maps = mutableListOf<RecentMap>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    maps.add(RecentMap(obj.getString("uri"), obj.getString("name")))
                }
                _recentMaps.value = maps
                
                // Requirement: Open last opened map
                if (maps.isNotEmpty()) {
                    // Safety: Check if previous load crashed - CRITICAL: use .commit() to clear it
                    if (sharedPreferences.getBoolean("is_loading_map", false)) {
                        sharedPreferences.edit().remove("is_loading_map").commit()
                        _uiState.value = MapUiState.Error("Previous load failed. This map might be too large.")
                    } else {
                        val lastUri = Uri.parse(maps[0].uriString)
                        val fileName = getFileName(lastUri) ?: ""
                        if (fileName.endsWith(".kmz", ignoreCase = true)) {
                            loadKmz(lastUri)
                        } else {
                            loadPdf(lastUri)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearRecentMaps() {
        _recentMaps.value = emptyList()
        sharedPreferences.edit().remove("maps_json").apply()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
        sharedPreferences.edit().putBoolean("keep_screen_on", enabled).apply()
    }

    fun setShowOverLockScreen(enabled: Boolean) {
        _showOverLockScreen.value = enabled
        sharedPreferences.edit().putBoolean("show_over_lock_screen", enabled).apply()
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
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopLocationUpdates() {
        if (fusedLocationProviderClient == null) return
        fusedLocationProviderClient?.removeLocationUpdates(locationCallback)
        fusedLocationProviderClient = null
        _currentLocation.value = null
        sensorManager.unregisterListener(this)
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
            GpsMode.HIDDEN -> GpsMode.FREE
            GpsMode.FREE -> GpsMode.FOLLOW
            GpsMode.FOLLOW -> GpsMode.HIDDEN
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
        sharedPreferences.edit().putBoolean("recording_enabled", enabled).apply()
    }

    fun startRecording() {
        _isRecording.value = true
        _isTrackSaved.value = false
        _recordedTrack.value = emptyList()
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
}

sealed class MapUiState {
    object Empty : MapUiState()
    object Loading : MapUiState()
    data class Success(val bitmap: Bitmap, val georeference: MapGeoreference? = null) : MapUiState()
    data class Error(val message: String) : MapUiState()
}
