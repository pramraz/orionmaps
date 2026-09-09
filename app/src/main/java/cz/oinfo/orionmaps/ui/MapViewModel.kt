package cz.oinfo.orionmaps.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

data class RecentMap(val uriString: String, val name: String)

data class MapGeoreference(val north: Double, val south: Double, val east: Double, val west: Double)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("recent_maps", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Empty)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _recentMaps = MutableStateFlow<List<RecentMap>>(emptyList())
    val recentMaps = _recentMaps.asStateFlow()

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
            val bitmap = renderPdfFirstPage(uri)
            if (bitmap != null) {
                _uiState.value = MapUiState.Success(bitmap)
                addRecentMap(uri)
            } else {
                _uiState.value = MapUiState.Error("Failed to render PDF")
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
            
            withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                        ZipInputStream(inputStream).use { zipInputStream ->
                            var bitmap: Bitmap? = null
                            var georeference: MapGeoreference? = null
                            
                            var entry = zipInputStream.nextEntry
                            while (entry != null) {
                                when {
                                    entry.name.endsWith(".png", ignoreCase = true) || entry.name.endsWith(".jpg", ignoreCase = true) -> {
                                        bitmap = BitmapFactory.decodeStream(zipInputStream)
                                    }
                                    entry.name.endsWith(".kml", ignoreCase = true) -> {
                                        val kmlContent = zipInputStream.bufferedReader().readText()
                                        georeference = parseKmlGeoreference(kmlContent)
                                    }
                                }
                                entry = zipInputStream.nextEntry
                            }

                            if (bitmap != null) {
                                _uiState.value = MapUiState.Success(bitmap, georeference)
                                withContext(Dispatchers.Main) {
                                    addRecentMap(uri)
                                }
                            } else {
                                _uiState.value = MapUiState.Error("No image found in KMZ")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _uiState.value = MapUiState.Error("Failed to load KMZ: ${e.message}")
                }
            }
        }
    }

    private fun parseKmlGeoreference(kml: String): MapGeoreference? {
        return try {
            val north = extractCoord(kml, "north")
            val south = extractCoord(kml, "south")
            val east = extractCoord(kml, "east")
            val west = extractCoord(kml, "west")
            
            if (north != null && south != null && east != null && west != null) {
                MapGeoreference(north, south, east, west)
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
                    val lastUri = Uri.parse(maps[0].uriString)
                    val fileName = getFileName(lastUri) ?: ""
                    if (fileName.endsWith(".kmz", ignoreCase = true)) {
                        loadKmz(lastUri)
                    } else {
                        loadPdf(lastUri)
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

    fun clearMap() {
        _uiState.value = MapUiState.Empty
    }

    override fun onCleared() {
        super.onCleared()
        clearMap()
    }

    private suspend fun renderPdfFirstPage(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            // Requirement 3: High-Resolution Rendering
                            // Using a 4.0x scale factor for crisp rendering
                            val scaleFactor = 4.0f
                            val width = (page.width * scaleFactor).toInt()
                            val height = (page.height * scaleFactor).toInt()

                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                            // Ensure white background (Requirement 1 - rendering level)
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(Color.WHITE)

                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            return@withContext bitmap
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
