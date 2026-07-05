package io.github.nexgus.jiudge.core.mapdata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of a map-data *update* run ([DownloadService] in update mode). Deliberately a
 * separate flow from [MapDataDownload]: the first-run flow drives the full-screen [DownloadScreen]
 * takeover in MainActivity, whereas an update runs behind the live map and surfaces as a banner.
 *
 * [Done] means the new data is fully on disk but the map on screen still renders the old files
 * (their handles stay valid after the rename-over); it persists until the user applies the update
 * (map rebuild), which resets the state to [Idle]. Unapplied data simply loads on the next launch.
 */
sealed interface MapUpdateState {
    data object Idle : MapUpdateState

    /** [fraction] is 0..1 across the assets being updated, weighted by download size. */
    data class Running(
        val fraction: Float,
        val currentName: String,
        val phase: InstallPhase,
        val assetsDone: Int,
        val assetsTotal: Int,
    ) : MapUpdateState

    /** Downloaded and installed; waiting for the user to apply (rebuild the map). */
    data object Done : MapUpdateState

    data class Failed(
        val message: String,
    ) : MapUpdateState
}

/** Process-wide holder for [MapUpdateState] so the UI need not bind to [DownloadService]. */
object MapUpdate {
    private val _state = MutableStateFlow<MapUpdateState>(MapUpdateState.Idle)
    val state: StateFlow<MapUpdateState> = _state.asStateFlow()

    internal fun set(value: MapUpdateState) {
        _state.value = value
    }

    /** Acknowledges a terminal state (applied update or dismissed failure). */
    fun reset() {
        _state.value = MapUpdateState.Idle
    }
}
