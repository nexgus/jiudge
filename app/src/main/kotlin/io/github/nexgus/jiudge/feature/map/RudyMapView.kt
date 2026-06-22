package io.github.nexgus.jiudge.feature.map

import android.content.Context
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.hills.DemFolderFS
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.map.layer.hills.SimpleShadingAlgorithm
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import java.io.File

/**
 * Builds a mapsforge [MapView] that renders RudyMap's `.map` with its bundled theme and
 * directional hillshade from the `.hgt` DEM. This is the Phase 0 prototype milestone
 * ("prototype shows RudyMap map") rebuilt natively after dropping the Flutter renderer.
 *
 * The data directory is expected to hold the unzipped RudyMap bundle:
 *   `<dir>/MOI_OSM_Taiwan_TOPO_Rudy.map`   the mapsforge basemap
 *   `<dir>/MOI_OSM.xml`                     the bundled render theme (refs `moiosmhs_res/`)
 *   `<dir>/hgt/` (.hgt tiles)               the DEM tiles (RudyMap hgtmix)
 */
object RudyMapView {
    const val BASEMAP_NAME = "MOI_OSM_Taiwan_TOPO_Rudy.map"
    const val THEME_NAME = "MOI_OSM.xml"
    const val DEM_DIR = "hgt"

    // Guanyinshan main peak (Yinghanling), Bali - 616 m. Mirrors the Phase 0 prototype camera.
    private const val INITIAL_LAT = 25.1363861
    private const val INITIAL_LNG = 121.4275306
    private const val INITIAL_ZOOM: Byte = 15

    fun create(context: Context, dataDir: File): MapView {
        val mapView = MapView(context).apply {
            isClickable = true
            // Scale bar hidden: it snaps to coarse 1/2/5 buckets so it is not a precise
            // indicator (e.g. zoom 15 and 16 both read 200 m). The zoom-level readout in the
            // UI overlay is shown instead.
            mapScaleBar.isVisible = false
            setBuiltInZoomControls(false)
            setZoomLevelMin(0)
            setZoomLevelMax(21) // RudyMap's `.map` tops out around zoom 21.
        }

        val tileCache = AndroidUtil.createTileCache(
            context,
            "rudymap",
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor,
        )

        val tileRendererLayer = TileRendererLayer(
            tileCache,
            MapFile(File(dataDir, BASEMAP_NAME)),
            mapView.model.mapViewPosition,
            false, // isTransparent
            true, // renderLabels
            true, // cacheLabels
            AndroidGraphicFactory.INSTANCE,
            buildHillsConfig(dataDir),
        ).apply {
            setXmlRenderTheme(ExternalRenderTheme(File(dataDir, THEME_NAME)))
        }
        mapView.layerManager.layers.add(tileRendererLayer)

        mapView.model.mapViewPosition.mapPosition =
            MapPosition(LatLong(INITIAL_LAT, INITIAL_LNG), INITIAL_ZOOM)
        return mapView
    }

    /**
     * Directional hillshade from RudyMap's `.hgt` DEM - the capability the pure-Dart renderer
     * lacked and the reason for the native pivot. The theme's `<hillshading />` element only
     * places the shading in layer order; the actual relief comes from this config.
     *
     * Returns null when the DEM folder is absent so the basemap still renders (without relief)
     * rather than crashing - the Phase 0 data push may not include `hgt/` yet.
     */
    private fun buildHillsConfig(dataDir: File): HillsRenderConfig? {
        val demDir = File(dataDir, DEM_DIR)
        if (!demDir.isDirectory) return null
        val tileSource = MemoryCachingHgtReaderTileSource(
            DemFolderFS(demDir),
            SimpleShadingAlgorithm(),
            AndroidGraphicFactory.INSTANCE,
        )
        return HillsRenderConfig(tileSource).apply { indexOnThread() }
    }
}
