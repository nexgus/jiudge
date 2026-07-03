package io.github.nexgus.jiudge.core.recording

import io.github.nexgus.jiudge.data.route.TrackStore
import io.github.nexgus.jiudge.feature.recording.Recorder
import kotlinx.coroutines.flow.StateFlow
import org.mapsforge.core.model.LatLong
import java.io.File
import java.io.IOException

/**
 * Process-wide bridge between [RecordingService] (the foreground GPS owner that survives Doze) and
 * the UI (which renders the live polyline and runs the save / discard dialogs). The service drives
 * the underlying [Recorder] through [handleStartNew] / [handleStartContinuation] / [handleFix] /
 * [handlePause] / [handleResume] / [handleEnd]; the UI observes [state] / [points] for the overlay
 * and drives the save / discard flow directly off `state == PAUSED` (the "已停止" layer of the
 * three-layer state machine) rather than a separate "pending session" signal.
 *
 * The singleton scope is deliberate: the recording session outlives any single composable scope and
 * may even outlive the activity (the service keeps writing with the screen off and the app paused).
 * [Recorder] itself remains the source of truth for the staging file and in-memory polyline -
 * everything here is plumbing.
 *
 * [finalize] and [discard] stay public because the save / discard dialogs are owned by the UI, which
 * commits or drops the staged session directly; the UI then tells the service to finish up (remove
 * the notification, stop the foreground service) once that completes.
 */
object RecordingController {
    private val store = TrackStore()
    private val recorder = Recorder(store)

    /** The recorder's live state - drives whether the recording / paused bottom bar shows. */
    val state: StateFlow<Recorder.State> = recorder.state

    /** The accumulated polyline so far - drives the red-chevron overlay. */
    val points: StateFlow<List<LatLong>> = recorder.points

    /** True iff a recording session is currently live ([Recorder.State.RECORDING] or [Recorder.State.PAUSED]). */
    val active: Boolean get() = recorder.active

    /** The current session, or null when no recording is in progress. Used by the UI to drive the save / discard dialogs while paused. */
    fun currentSession(): Recorder.Session? = recorder.currentSession()

    /**
     * Sweeps leftover staging files (`tracks/.recording-*.jsonl`) from crashed or swiped-away
     * sessions, never touching the live session's file. Owned here rather than by the UI because
     * this singleton is the only place that knows whether a session is live: the activity that
     * triggers the sweep may be destroyed and recreated while the service keeps recording, so
     * "sweeping at UI start" and "a recording in progress" legitimately coexist. Synchronised with
     * session start so a sweep can never race a brand-new staging file. Blocking file IO - call off
     * the main thread. Safe to call at any time, any number of times.
     */
    @Synchronized
    fun sweepStaleStaging() {
        store.cleanupStaleRecordings(keep = recorder.currentSession()?.staging)
    }

    /** Service-only: begin a fresh recording. */
    @Synchronized
    internal fun handleStartNew(
        startEpochMs: Long,
        defaultSaveName: String,
    ) {
        recorder.startNew(startEpochMs, defaultSaveName)
    }

    /** Service-only: continue an existing track from [source]. */
    @Synchronized
    internal fun handleStartContinuation(source: File) {
        recorder.startContinuation(source)
    }

    /** Service-only: push one fix in. Returns the IO error if a staging-file append failed. */
    internal fun handleFix(
        latitude: Double,
        longitude: Double,
        timeMs: Long,
        accuracyMeters: Float?,
        speedMps: Float?,
    ): IOException? = recorder.onFix(latitude, longitude, timeMs, accuracyMeters, speedMps)

    /** Service-only: the fix stream went stale; drops the gate's pending fix (docs/gating.md rule 2). */
    internal fun handleFixStale() {
        recorder.onFixStale()
    }

    /** Service-only: pause the session (enters [Recorder.State.PAUSED] - the "已停止" layer). */
    internal fun handlePause() {
        recorder.pause()
    }

    /** Service-only: resume a paused session (back to [Recorder.State.RECORDING]). */
    internal fun handleResume() {
        recorder.resume()
    }

    /** Service-only: end the session once the UI has finalised or discarded it, returning to [Recorder.State.IDLE]. */
    internal fun handleEnd() {
        recorder.end()
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
}
