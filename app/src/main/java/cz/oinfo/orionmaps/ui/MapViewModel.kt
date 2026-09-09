package cz.oinfo.orionmaps.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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

data class RecentMap(val uriString: String, val name: String)

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

    private fun getFileName(uri: Uri): String? {
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearRecentMaps() {
        _recentMaps.value = emptyList()
        sharedPreferences.edit().remove("maps_json").apply()
    }

    private suspend fun renderPdfFirstPage(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                if (renderer.pageCount > 0) {
                    page = renderer.openPage(0)
                    
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
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                page?.close()
                renderer?.close()
                pfd?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        null
    }
}

sealed class MapUiState {
    object Empty : MapUiState()
    object Loading : MapUiState()
    data class Success(val bitmap: Bitmap) : MapUiState()
    data class Error(val message: String) : MapUiState()
}
