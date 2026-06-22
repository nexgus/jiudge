package io.github.nexgus.jiudge.core.mapdata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable state of the one-shot first-run map-data download. [DownloadService] writes it; the
 * first-run UI observes it. Held process-wide (in [MapDataDownload]) so the screen can follow
 * progress without binding to the service.
 */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** [fraction] is 0..1 across all assets, weighted by download size. */
    data class Running(
        val fraction: Float,
        val currentName: String,
        val phase: InstallPhase,
        val assetsDone: Int,
        val assetsTotal: Int,
    ) : DownloadState

    data object Done : DownloadState

    data class Failed(
        val message: String,
    ) : DownloadState
}

/** Process-wide holder for [DownloadState] so the UI need not bind to [DownloadService]. */
object MapDataDownload {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    internal fun set(value: DownloadState) {
        _state.value = value
    }
}
