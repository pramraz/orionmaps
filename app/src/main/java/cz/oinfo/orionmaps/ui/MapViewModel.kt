package cz.oinfo.orionmaps.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Empty)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    fun loadPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = MapUiState.Loading
            val bitmap = renderPdfFirstPage(uri)
            if (bitmap != null) {
                _uiState.value = MapUiState.Success(bitmap)
            } else {
                _uiState.value = MapUiState.Error("Failed to render PDF")
            }
        }
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
                    
                    // Calculate dimensions for a high-resolution bitmap
                    // Aiming for a reasonable size while balancing memory (e.g., 2048px on the longest side)
                    val scaleFactor = 2048f / maxOf(page.width, page.height).coerceAtLeast(1)
                    val width = (page.width * scaleFactor).toInt()
                    val height = (page.height * scaleFactor).toInt()

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
