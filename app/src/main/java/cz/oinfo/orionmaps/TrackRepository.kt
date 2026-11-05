package cz.oinfo.orionmaps

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide holder of the recorded GPX track.
 *
 * - The track and the recording state live here (not in the ViewModel), so they survive
 *   Activity / ViewModel recreation while [TrackRecordingService] records in the background.
 * - Every point is appended to a file in internal storage, so an unsaved track also
 *   survives the process being killed. After such a restart the track is restored
 *   in the "paused" state (recording off) and the user can resume or save it.
 */
object TrackRepository {

    private const val PREFS_NAME = "track_recording"
    private const val KEY_TRACK_SAVED = "track_saved"
    private const val TRACK_FILE_NAME = "current_track.csv"
    private const val SEPARATOR = ";"

    private val _track = MutableStateFlow<List<Location>>(emptyList())
    val track: StateFlow<List<Location>> = _track.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isTrackSaved = MutableStateFlow(true)
    val isTrackSaved: StateFlow<Boolean> = _isTrackSaved.asStateFlow()

    private val lock = Any()
    private var appContext: Context? = null

    /** Idempotent. Call from ViewModel and Service before using the repository. */
    fun init(context: Context?) {
        if (context == null) return
        synchronized(lock) {
            if (appContext != null) return
            val ctx = (try { context.applicationContext } catch (e: Throwable) { null }) ?: context
            appContext = ctx

            val restored = loadTrack(ctx)
            _track.value = restored
            _isTrackSaved.value = restored.isEmpty() || (prefs(ctx)?.getBoolean(KEY_TRACK_SAVED, true) ?: true)

            // A fresh process never has a running recording service, so a recording that
            // was active when the process died is restored as paused.
            _isRecording.value = false
        }
    }

    fun setRecording(recording: Boolean) {
        synchronized(lock) {
            _isRecording.value = recording
            if (recording) setSaved(false)
        }
    }

    fun addPoint(location: Location) {
        synchronized(lock) {
            _track.value = _track.value + location
            appContext?.let { ctx ->
                try {
                    trackFile(ctx)?.appendText(serialize(location) + "\n")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /** Replaces the whole track, e.g. after a GPX import. */
    fun replaceTrack(points: List<Location>, saved: Boolean) {
        synchronized(lock) {
            _track.value = points
            appContext?.let { ctx ->
                try {
                    trackFile(ctx)?.writeText(points.joinToString(separator = "") { serialize(it) + "\n" })
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            setSaved(saved)
        }
    }

    /** Call only after the GPX file has been successfully written. */
    fun markSaved() {
        synchronized(lock) { setSaved(true) }
    }

    fun clear() {
        synchronized(lock) {
            _track.value = emptyList()
            appContext?.let { ctx ->
                try {
                    trackFile(ctx)?.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            setSaved(true)
        }
    }

    private fun setSaved(saved: Boolean) {
        _isTrackSaved.value = saved
        appContext?.let { prefs(it)?.edit()?.putBoolean(KEY_TRACK_SAVED, saved)?.apply() }
    }

    private fun prefs(ctx: Context): SharedPreferences? = try {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Throwable) {
        null
    }

    private fun trackFile(ctx: Context): File? = try {
        File(ctx.filesDir, TRACK_FILE_NAME)
    } catch (e: Throwable) {
        null
    }

    // Format per line: lat;lon;altitude;timeMillis;accuracy  (altitude/accuracy may be empty)
    private fun serialize(loc: Location): String = listOf(
        loc.latitude.toString(),
        loc.longitude.toString(),
        if (loc.hasAltitude()) loc.altitude.toString() else "",
        loc.time.toString(),
        if (loc.hasAccuracy()) loc.accuracy.toString() else ""
    ).joinToString(SEPARATOR)

    private fun deserialize(line: String): Location? {
        val parts = line.split(SEPARATOR)
        if (parts.size < 4) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return Location("track").apply {
            latitude = lat
            longitude = lon
            parts[2].toDoubleOrNull()?.let { altitude = it }
            time = parts[3].toLongOrNull() ?: 0L
            parts.getOrNull(4)?.toFloatOrNull()?.let { accuracy = it }
        }
    }

    private fun loadTrack(ctx: Context): List<Location> {
        return try {
            val file = trackFile(ctx)
            if (file == null || !file.exists()) emptyList()
            else file.readLines().mapNotNull { if (it.isBlank()) null else deserialize(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
