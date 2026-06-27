package io.github.nexgus.jiudge.core.index

import io.github.nexgus.jiudge.feature.map.RudyMapView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide entry point for the summit-position index that backs (future) peak-name search.
 *
 * Lifecycle: on every launch [ensureUpToDate] compares the stored index's stamp against the live
 * basemap. Missing, stale (map re-installed/updated), or corrupt -> rebuild via [PeakIndexBuilder];
 * matching -> nothing to do. The rebuild runs off the main thread and publishes progress through
 * [state] so the UI can show a non-blocking "building" banner without gating the usable map.
 */
object PeakIndex {
    private val _state = MutableStateFlow<PeakIndexState>(PeakIndexState.Idle)
    val state: StateFlow<PeakIndexState> = _state.asStateFlow()

    /**
     * Ensures the index matches the installed basemap, building it if needed. Call from a background
     * coroutine ([kotlinx.coroutines.Dispatchers.IO]); it performs blocking file/scan work directly.
     * Safe to call when no map is installed yet (it simply returns [PeakIndexState.Idle]).
     */
    fun ensureUpToDate(mapDir: File) {
        val basemap = File(mapDir, RudyMapView.BASEMAP_NAME)
        if (!basemap.isFile) {
            _state.value = PeakIndexState.Idle
            return
        }
        val current = PeakIndexStore.stampOf(basemap)
        if (PeakIndexStore.readStamp(mapDir) == current) {
            _state.value = PeakIndexState.Ready
            return
        }
        _state.value = PeakIndexState.Building(0f)
        try {
            val peaks =
                PeakIndexBuilder.build(basemap) { done, total ->
                    _state.value = PeakIndexState.Building(done.toFloat() / total)
                }
            PeakIndexStore.write(mapDir, current, peaks)
            _state.value = PeakIndexState.Ready
        } catch (e: Exception) {
            _state.value = PeakIndexState.Failed
        }
    }

    /**
     * Loads the persisted peaks for search, or null when the index is absent/unbuilt. Does not
     * trigger a build - callers use this only once [state] is [PeakIndexState.Ready].
     */
    fun load(mapDir: File): List<Peak>? = PeakIndexStore.readAll(mapDir)
}
