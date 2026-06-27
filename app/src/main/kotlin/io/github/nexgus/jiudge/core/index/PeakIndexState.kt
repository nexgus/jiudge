package io.github.nexgus.jiudge.core.index

/**
 * Observable state of the on-device peak-index build. The map is usable regardless of this state -
 * only the (future) peak-name search depends on the index - so the UI surfaces it as a small
 * non-blocking banner, never a gate.
 */
sealed interface PeakIndexState {
    /** No build needed or none attempted yet (e.g. map data not installed). */
    data object Idle : PeakIndexState

    /** The index is present and matches the installed basemap; search can use it. */
    data object Ready : PeakIndexState

    /** A build is in progress; [fraction] is 0..1 over the tiles scanned. */
    data class Building(
        val fraction: Float,
    ) : PeakIndexState

    /** The build failed; it will be retried on the next launch. */
    data object Failed : PeakIndexState
}
