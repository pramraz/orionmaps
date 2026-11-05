package cz.oinfo.orionmaps

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type "location") that records the GPX track.
 *
 * Started by the user tapping "Start recording" (app in foreground), so the
 * while-in-use location permission is sufficient - no background location permission.
 * While it runs, location updates keep arriving at full rate even with the screen off
 * or the app in the background. Points are stored in [TrackRepository].
 *
 * Stopping: either [TrackRepository.setRecording] (false) from the app - the service
 * observes the flag - or the "Stop" action in the notification.
 */
class TrackRecordingService : Service() {

    companion object {
        const val ACTION_START = "cz.oinfo.orionmaps.action.START_RECORDING"
        const val ACTION_STOP = "cz.oinfo.orionmaps.action.STOP_RECORDING"

        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 2000L

        fun start(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fusedClient: FusedLocationProviderClient? = null
    private var isRunning = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!TrackRepository.isRecording.value) return
            for (location in result.locations) {
                TrackRepository.addPoint(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        TrackRepository.init(this)

        // Stop the service as soon as the app switches recording off
        scope.launch {
            TrackRepository.isRecording.collect { recording ->
                if (!recording && isRunning) stopRecordingService()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!startRecording()) {
                    TrackRepository.setRecording(false)
                    stopRecordingService()
                }
            }
            ACTION_STOP -> {
                TrackRepository.setRecording(false)
                stopRecordingService()
            }
            else -> stopRecordingService()
        }
        // Do not let the system restart the service on its own: a restart from the
        // background would not get location access. The track is persisted anyway.
        return START_NOT_STICKY
    }

    private fun startRecording(): Boolean {
        if (isRunning) return true
        if (!hasLocationPermission(this)) return false

        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException or SecurityException
            e.printStackTrace()
            return false
        }
        isRunning = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS).build()
        val client = LocationServices.getFusedLocationProviderClient(this)
        fusedClient = client
        return try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    private fun stopRecordingService() {
        fusedClient?.removeLocationUpdates(locationCallback)
        fusedClient = null
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrackRecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPending)
            .addAction(0, getString(R.string.recording_notification_stop), stopPending)
            .build()
    }

    override fun onDestroy() {
        fusedClient?.removeLocationUpdates(locationCallback)
        fusedClient = null
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
