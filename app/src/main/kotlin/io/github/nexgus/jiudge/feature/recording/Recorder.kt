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
 * [TrackStore] will finalise (or discard) once the user is done. Pure state and file IO; no Android
 * dependency, no subscription to the location provider - the caller pushes fixes in via [onFix]. The
 * location subscription itself stays where it already lives (the map screen's foreground GPS), so we
 * do not duplicate provider work.
 *
 * v1 scope: the staging file is dot-prefixed so a crashed session leaves no published noise (cleaned
 * at next start by [TrackStore.cleanupStaleRecordings]); the foreground-service background recording
 * is out of scope - see CLAUDE.md "尚未建置: 背景軌跡錄製". Manual pause/resume ([pause]/[resume]) is
 * the three-layer UI state machine's "已停止" layer: the session and its staging file stay alive
 * across a pause, [points] is left untouched (the map overlay keeps showing the path so far), and
 * [onFix] silently drops fixes while paused rather than appending them - a deliberate simplification
 * over buffering/discarding at the source. Resuming appends to the same staging file, so a pause
 * leaves a legitimate time gap between two consecutive points; this does not change the trace file
 * format.
 */
class Recorder(
    private val store: TrackStore,
) {
    enum class State { IDLE, RECORDING, PAUSED }

    /** A live recording session that exists from [startNew]/[startContinuation] until finalised or discarded. */
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

    /** True iff there is a live session ([State.RECORDING] or [State.PAUSED]). */
    val active: Boolean get() = session != null

    /** The current session, or null when no recording is in progress. */
    fun currentSession(): Session? = session

    /**
     * Begins a fresh recording at [startEpochMs] (caller supplies wall-clock; tests can inject a
     * fixed value). Creates an empty staging file and enters [State.RECORDING]. [defaultSaveName] is
     * the name pre-filled in the save dialog once the session ends.
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
     * Pauses the session: further [onFix] calls are dropped until [resume]. The session, its staging
     * file, and the in-memory [points] polyline are all left untouched, so the overlay keeps showing
     * the path recorded so far and "繼續錄製" can pick up exactly where this left off. No-op when
     * there is no active session.
     */
    fun pause() {
        if (session == null) return
        _state.value = State.PAUSED
    }

    /** Resumes a [State.PAUSED] session, appending subsequent fixes to the same staging file. */
    fun resume() {
        if (session == null) return
        _state.value = State.RECORDING
    }

    /**
     * Ends the session (from either [State.RECORDING] or [State.PAUSED]) and returns it so the caller
     * can [finalize] or [discard] it. The staging file is left in place. After this call [active] is
     * false and the in-memory polyline is cleared - the layer will already be off screen by then.
     */
    fun end(): Session? {
        val s = session ?: return null
        session = null
        _state.value = State.IDLE
        _points.value = emptyList()
        return s
    }

    /**
     * Publishes [session] (returned by [end]) under [name], replacing the original on continuations.
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
     * Drops [session]'s staging file (returned by [end]) without publishing. On continuations the
     * original source file is left intact - the contract for "discard a continuation" is "throw
     * away what was newly recorded, keep the original".
     */
    fun discard(session: Session) {
        store.discardStaging(session.staging)
    }
}
