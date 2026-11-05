package cz.oinfo.orionmaps

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cz.oinfo.orionmaps.ui.MapScreen
import cz.oinfo.orionmaps.ui.MapViewModel
import cz.oinfo.orionmaps.ui.theme.OrionMapsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Handle intent when activity is first created
        handleIntent(intent)
        
        setContent {
            val showOverLockScreen by viewModel.showOverLockScreen.collectAsState()

            LaunchedEffect(showOverLockScreen) {
                updateLockScreenFlags(showOverLockScreen)
            }

            OrionMapsTheme {
                MapScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle intent when activity is already running
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                val fileName = viewModel.getFileName(uri) ?: ""
                if (fileName.endsWith(".kmz", ignoreCase = true)) {
                    viewModel.loadKmz(uri)
                } else if (fileName.endsWith(".pdf", ignoreCase = true)) {
                    viewModel.loadPdf(uri)
                } else {
                    // Try to load based on mime type if extension is missing
                    val mimeType = contentResolver.getType(uri)
                    if (mimeType == "application/vnd.google-earth.kmz") {
                        viewModel.loadKmz(uri)
                    } else if (mimeType == "application/pdf") {
                        viewModel.loadPdf(uri)
                    }
                }
            }
        }
    }

    private fun updateLockScreenFlags(showOverLockScreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(showOverLockScreen)
            setTurnScreenOn(showOverLockScreen)
        } else {
            @Suppress("DEPRECATION")
            if (showOverLockScreen) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }
}
