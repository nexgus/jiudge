package io.github.nexgus.jiudge.core.recording

import io.github.nexgus.jiudge.data.route.TrackStore
import io.github.nexgus.jiudge.feature.recording.Recorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mapsforge.core.model.LatLong
import java.io.File
import java.io.IOException

/**
 * Process-wide bridge between [RecordingService] (the foreground GPS owner that survives Doze) and
 * the UI (which renders the live polyline and runs the save / discard dialogs). The service drives
 * the underlying [Recorder] through [handleStartNew] / [handleStartContinuation] / [handleFix] /
 * [handleStop]; the UI observes [state] / [points] for the overlay and [pendingSession] to bring up
 * the save dialog after the service has stopped.
 *
 * The singleton scope is deliberate: the recording session outlives any single composable scope and
 * may even outlive the activity (the service keeps writing with the screen off and the app paused).
 * [Recorder] itself remains the source of truth for the staging file and in-memory polyline -
 * everything here is plumbing.
 *
 * [finalize] and [discard] stay public because they fire after [RecordingService] has already
 * stopped: the save dialog is owned by the UI, so the UI commits or drops the staged session.
 */
object RecordingController {
    private val recorder = Recorder(TrackStore())

    /** The recorder's live state - drives whether the recording bottom bar shows. */
    val state: StateFlow<Recorder.State> = recorder.state

    /** The accumulated polyline so far - drives the red-chevron overlay. */
    val points: StateFlow<List<LatLong>> = recorder.points

    private val _pendingSession = MutableStateFlow<Recorder.Session?>(null)

    /** Set by [handleStop] once the service stops mid-recording. UI watches this to open the save dialog. */
    val pendingSession: StateFlow<Recorder.Session?> = _pendingSession.asStateFlow()

    private val _lastSessionPoints = MutableStateFlow<List<LatLong>>(emptyList())

    /**
     * The polyline captured at the instant the service stopped, kept so the history-view overlay
     * can render the just-recorded track under the save dialog even when the stop was triggered
     * from the notification (where the UI never had a chance to snapshot [points] itself).
     */
    val lastSessionPoints: StateFlow<List<LatLong>> = _lastSessionPoints.asStateFlow()

    /** True iff a recording session is currently live. */
    val active: Boolean get() = recorder.active

    /** Service-only: begin a fresh recording. */
    internal fun handleStartNew(
        startEpochMs: Long,
        defaultSaveName: String,
    ) {
        recorder.startNew(startEpochMs, defaultSaveName)
    }

    /** Service-only: continue an existing track from [source]. */
    internal fun handleStartContinuation(source: File) {
        recorder.startContinuation(source)
    }

    /** Service-only: push one fix in. Returns the IO error if the staging-file append failed. */
    internal fun handleFix(
        latitude: Double,
        longitude: Double,
        timeMs: Long,
    ): IOException? = recorder.onFix(latitude, longitude, timeMs)

    /**
     * Service-only: stop the session and stage it in [pendingSession] for the UI to save or discard.
     * No-op when no session is active (e.g. the user already pressed stop and the dialog is up).
     * Snapshots [points] into [lastSessionPoints] before clearing so the UI can still render the
     * just-recorded polyline under the save dialog when the stop arrived from the notification.
     */
    internal fun handleStop() {
        val pointsSnapshot = recorder.points.value
        val session = recorder.stop() ?: return
        _lastSessionPoints.value = pointsSnapshot
        _pendingSession.value = session
    }

    /** Publishes [session] under [name], replacing the original on continuations. */
    fun finalize(
        session: Recorder.Session,
        name: String,
    ): File = recorder.finalize(session, name)

    /** Drops [session]'s staging file. On continuations the original source is left intact. */
    fun discard(session: Recorder.Session) {
        recorder.discard(session)
    }

    /** Clears [pendingSession] (and the [lastSessionPoints] snapshot) once the save / discard dialog has run its course. */
    fun clearPending() {
        _pendingSession.value = null
        _lastSessionPoints.value = emptyList()
    }
}
