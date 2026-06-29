package io.github.nexgus.jiudge.feature.recording

import io.github.nexgus.jiudge.data.route.TrackStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mapsforge.core.model.LatLong
import java.io.File
import java.io.IOException

/**
 * Stateful coordinator for one recording session: holds the in-memory point list that drives the live
 * red-chevron overlay, gates writes by the recording state, and owns the staging file that
 * [TrackStore] will finalise (or discard) on stop. Pure state and file IO; no Android dependency, no
 * subscription to the location provider - the caller pushes fixes in via [onFix]. The location
 * subscription itself stays where it already lives (the map screen's foreground GPS), so we do not
 * duplicate provider work.
 *
 * v1 scope: the staging file is dot-prefixed so a crashed session leaves no published noise (cleaned
 * at next start by [TrackStore.cleanupStaleRecordings]); the foreground-service background recording
 * is out of scope - see CLAUDE.md "尚未建置: 背景軌跡錄製".
 */
class Recorder(
    private val store: TrackStore,
) {
    enum class State { IDLE, RECORDING, PAUSED }

    /** A live recording session that exists from [startNew]/[startContinuation] until [stop]. */
    data class Session(
        val startEpochMs: Long,
        val staging: File,
        val isContinuation: Boolean,
        val source: File?,
        val defaultName: String,
    )

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _points = MutableStateFlow<List<LatLong>>(emptyList())

    /** The accumulated polyline so far. Layer renderers collect this to drive the overlay. */
    val points: StateFlow<List<LatLong>> = _points.asStateFlow()

    private var session: Session? = null

    /** True iff there is an active session (RECORDING or PAUSED). */
    val active: Boolean get() = session != null

    /** The current session, or null when no recording is in progress. */
    fun currentSession(): Session? = session

    /**
     * Begins a fresh recording at [startEpochMs] (caller supplies wall-clock; tests can inject a
     * fixed value). Creates an empty staging file and enters [State.RECORDING]. [defaultSaveName] is
     * the name pre-filled in the save dialog on stop.
     */
    fun startNew(
        startEpochMs: Long,
        defaultSaveName: String,
    ) {
        val staging = store.startNewStaging(startEpochMs, defaultSaveName)
        session =
            Session(
                startEpochMs = startEpochMs,
                staging = staging,
                isContinuation = false,
                source = null,
                defaultName = defaultSaveName,
            )
        _points.value = emptyList()
        _state.value = State.RECORDING
    }

    /**
     * Continues an existing saved track. Copies its header and points into a fresh staging file,
     * seeds the live polyline with the prior points so the overlay shows the existing path, and
     * enters [State.RECORDING]. The original [source] is left untouched until [finalize] commits.
     */
    fun startContinuation(source: File) {
        val staging = store.startContinuationStaging(source)
        val seeded = store.load(staging)
        session =
            Session(
                startEpochMs = seeded.createdAtEpochMs,
                staging = staging,
                isContinuation = true,
                source = source,
                defaultName = seeded.name,
            )
        _points.value = seeded.polyline
        _state.value = State.RECORDING
    }

    /** Pauses writing; new fixes are dropped until [resume]. No-op outside [State.RECORDING]. */
    fun pause() {
        if (_state.value == State.RECORDING) _state.value = State.PAUSED
    }

    /** Resumes writing after [pause]. No-op outside [State.PAUSED]. */
    fun resume() {
        if (_state.value == State.PAUSED) _state.value = State.RECORDING
    }

    /**
     * Push one fix in. When [State.RECORDING] the fix is appended to the staging file and the
     * in-memory polyline; otherwise it is dropped. Returns the IO error if the append failed, so the
     * caller can surface it and stop the session.
     */
    fun onFix(
        latitude: Double,
        longitude: Double,
        timeMs: Long,
    ): IOException? {
        if (_state.value != State.RECORDING) return null
        val s = session ?: return null
        return try {
            store.appendPoint(s.staging, latitude, longitude, timeMs)
            _points.value = _points.value + LatLong(latitude, longitude)
            null
        } catch (e: IOException) {
            e
        }
    }

    /**
     * Ends the session and returns it so the caller can run the save dialog and call [finalize] or
     * [discard]. The staging file is left in place; both states ([State.RECORDING] and
     * [State.PAUSED]) are valid stops. After this call [active] is false and the in-memory polyline
     * is cleared - the layer will already be off screen by then.
     */
    fun stop(): Session? {
        val s = session ?: return null
        session = null
        _state.value = State.IDLE
        _points.value = emptyList()
        return s
    }

    /**
     * Publishes [session] (returned by [stop]) under [name], replacing the original on continuations.
     * Throws [io.github.nexgus.jiudge.data.route.DuplicateTrackNameException] on a name collision.
     */
    fun finalize(
        session: Session,
        name: String,
    ): File =
        if (session.isContinuation && session.source != null) {
            store.finalizeContinuation(session.staging, session.source, name)
        } else {
            store.finalizeNew(session.staging, name)
        }

    /**
     * Drops [session]'s staging file (returned by [stop]) without publishing. On continuations the
     * original source file is left intact - the contract for "discard a continuation" is "throw
     * away what was newly recorded, keep the original".
     */
    fun discard(session: Session) {
        store.discardStaging(session.staging)
    }
}
