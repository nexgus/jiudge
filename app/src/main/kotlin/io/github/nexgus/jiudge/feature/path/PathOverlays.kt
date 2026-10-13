package io.github.nexgus.jiudge.feature.path

import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.core.elevation.SlopeSampler
import io.github.nexgus.jiudge.feature.map.CurrentLocationLayer
import io.github.nexgus.jiudge.feature.map.SearchPeakMarkerLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer

/**
 * Owns every path drawn over one [MapView]: any number of opened (saved) paths, the plan being
 * edited, and the track being recorded - one [PathLayer] each - and applies the shared
 * [PathDisplayOptions] to all of them.
 *
 * It also keeps the overlay stacking order, bottom to top:
 * 1. opened paths, in the order they were opened;
 * 2. the plan being edited;
 * 3. the track being recorded;
 * 4. the search-result marker;
 * 5. the current-location marker, above everything.
 *
 * The order never depends on which layer happens to be created first: every layer is inserted into
 * mapsforge's layer list by index, directly below the lowest layer that must stay above it. The two
 * marker layers are created by the caller and placed through [addMarkerLayer] so they take part in
 * the same ordering.
 *
 * Slope sampling for all layers runs on one background scope owned here; [close] cancels it. All
 * methods must be called from the main thread.
 *
 * [density] is screen pixels per dp. [dem] is the elevation source for slope colouring; with none,
 * a path shown with slope display on has no slope data and is drawn in the gentle-slope colour.
 */
class PathOverlays(
    private val mapView: MapView,
    private val density: Float,
    dem: DemElevation?,
    displayOptions: PathDisplayOptions,
) {
    private val samplingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // A DEM tile that cannot be read is treated as "no elevation here" - the sampler then holds the
    // gap from its neighbours - rather than letting an IO error escape on a background thread.
    private val sampler =
        SlopeSampler { latitude, longitude ->
            try {
                dem?.elevationAt(latitude, longitude)
            } catch (e: Exception) {
                null
            }
        }

    // Insertion-ordered: iteration order is opening order, which is also bottom-to-top draw order.
    private val opened = LinkedHashMap<Any, PathLayer>()
    private var editing: PathLayer? = null
    private var recording: PathLayer? = null

    /** The options currently applied to every path. */
    var displayOptions: PathDisplayOptions = displayOptions
        private set

    /** Applies [options] to every path on the map, and to any shown later. */
    fun setDisplayOptions(options: PathDisplayOptions) {
        displayOptions = options
        opened.values.forEach { it.setDisplayOptions(options) }
        editing?.setDisplayOptions(options)
        recording?.setDisplayOptions(options)
    }

    /**
     * Shows an opened path under [key], which identifies it for [removeOpened] (any object with
     * value equality, e.g. its file). [style] is [PathStyle.TRACK] or [PathStyle.PLAN]; [points] is
     * the whole line and [waypoints] a plan's user-placed points (empty for a track). A new key is
     * stacked above the paths opened before it. A key already shown keeps its place and only has its
     * content replaced - unless [style] differs, in which case it is re-opened on top.
     */
    fun showOpened(
        key: Any,
        style: PathStyle,
        points: List<LatLong>,
        waypoints: List<LatLong> = emptyList(),
    ) {
        require(style != PathStyle.RECORDING) { "an opened path is a track or a plan" }
        val existing = opened[key]
        if (existing != null && existing.style == style) {
            existing.setPath(points, waypoints)
            return
        }
        if (existing != null) removeOpened(key)
        val layer = newLayer(style)
        layer.setPath(points, waypoints)
        opened[key] = layer
        insert(layer, RANK_OPENED)
    }

    /** Removes the opened path shown under [key]; no-op when there is none. */
    fun removeOpened(key: Any) {
        opened.remove(key)?.let { detach(it) }
    }

    /** Removes every opened path. The plan being edited and the recording are not affected. */
    fun clearOpened() {
        opened.values.forEach { detach(it) }
        opened.clear()
    }

    /** Draws (or redraws) the plan being edited: [points] is its routed line, [waypoints] its placed points. */
    fun setEditing(
        points: List<LatLong>,
        waypoints: List<LatLong>,
    ) {
        val layer =
            editing ?: newLayer(PathStyle.PLAN).also {
                editing = it
                insert(it, RANK_EDITING)
            }
        layer.setPath(points, waypoints)
    }

    /** Removes the plan being edited from the map; no-op when there is none. */
    fun clearEditing() {
        editing?.let { detach(it) }
        editing = null
    }

    /** Draws the points written so far by the recording. An empty list clears the line. */
    fun setRecording(points: List<LatLong>) {
        if (points.isEmpty() && recording == null) return
        recordingLayer().setPath(points)
    }

    /**
     * Moves (or with null, hides) the marker-side end of the recording's rubber band - the current
     * location while actively recording.
     */
    fun setRecordingLiveTip(tip: LatLong?) {
        if (tip == null && recording == null) return
        recordingLayer().setLiveTip(tip)
    }

    /**
     * Moves (or with null, drops) the rubber band's fixed end used before the recording's first point
     * is written.
     */
    fun setRecordingProvisionalStart(start: LatLong?) {
        if (start == null && recording == null) return
        recordingLayer().setProvisionalStart(start)
    }

    /**
     * Adds a marker layer that must stay above every path - the search-result marker
     * ([SearchPeakMarkerLayer]) or the current-location marker ([CurrentLocationLayer]) - at its place
     * in the stacking order. The layer remains the caller's: [close] does not remove it.
     */
    fun addMarkerLayer(layer: Layer) {
        val rank = rankOf(layer)
        require(rank > RANK_RECORDING) { "not a marker layer: ${layer.javaClass.simpleName}" }
        insert(layer, rank)
    }

    /**
     * Cancels all slope sampling and removes this object's path layers from the map (a no-op for
     * layers the map view has already dropped while being destroyed). The instance must not be used
     * afterwards.
     */
    fun close() {
        samplingScope.cancel()
        clearOpened()
        clearEditing()
        recording?.let { detach(it) }
        recording = null
    }

    private fun recordingLayer(): PathLayer =
        recording ?: newLayer(PathStyle.RECORDING).also {
            recording = it
            insert(it, RANK_RECORDING)
        }

    private fun newLayer(style: PathStyle): PathLayer = PathLayer(style, density, sampler, samplingScope, displayOptions)

    /**
     * Inserts [layer] directly below the lowest layer that ranks above it, or on top when there is
     * none. Among layers of equal rank the new one therefore lands above the existing ones, which
     * is what keeps opened paths in opening order.
     */
    private fun insert(
        layer: Layer,
        rank: Int,
    ) {
        val layers = mapView.layerManager.layers
        var index = layers.size()
        for (i in 0 until layers.size()) {
            if (rankOf(layers.get(i)) > rank) {
                index = i
                break
            }
        }
        layers.add(index, layer)
    }

    private fun detach(layer: Layer) {
        mapView.layerManager.layers.remove(layer)
    }

    /** Where [layer] belongs in the stacking order; a higher rank is drawn above a lower one. */
    private fun rankOf(layer: Layer): Int =
        when {
            layer === recording -> RANK_RECORDING
            layer === editing -> RANK_EDITING
            layer is PathLayer -> RANK_OPENED
            layer is SearchPeakMarkerLayer -> RANK_SEARCH_MARKER
            layer is CurrentLocationLayer -> RANK_LOCATION_MARKER
            else -> RANK_BASE
        }

    private companion object {
        // The basemap and any layer this class does not know stay underneath the paths.
        const val RANK_BASE = 0
        const val RANK_OPENED = 1
        const val RANK_EDITING = 2
        const val RANK_RECORDING = 3
        const val RANK_SEARCH_MARKER = 4
        const val RANK_LOCATION_MARKER = 5
    }
}
