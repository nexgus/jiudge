package io.github.nexgus.jiudge.feature.map

import android.content.Context
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.util.Parameters
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
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu
import java.io.File

/**
 * Builds a mapsforge [MapView] that renders RudyMap's `.map` with its bundled theme and
 * directional hillshade from the `.hgt` DEM. This is the Phase 0 prototype milestone
 * ("prototype shows RudyMap map") rebuilt natively after dropping the Flutter renderer.
 *
 * The map directory ([mapDir], the app's `map/` data folder - see `AppPaths`) holds the unzipped
 * RudyMap bundle:
 *   `<mapDir>/MOI_OSM_Taiwan_TOPO_Rudy.map`   the mapsforge basemap
 *   `<mapDir>/MOI_OSM.xml`                     the bundled render theme (refs `moiosmhs_res/`)
 *   `<mapDir>/hgt/` (.hgt tiles)              the DEM tiles (RudyMap hgtmix)
 */
object RudyMapView {
    const val BASEMAP_NAME = "MOI_OSM_Taiwan_TOPO_Rudy.map"
    const val THEME_NAME = "MOI_OSM.xml"
    const val DEM_DIR = "hgt"

    // Guanyinshan main peak (Yinghanling), Bali - 616 m. Mirrors the Phase 0 prototype camera.
    private const val INITIAL_LAT = 25.1363861
    private const val INITIAL_LNG = 121.4275306
    private const val INITIAL_ZOOM: Byte = 15

    // The render theme is RudyMap's `<stylemenu>` theme: a base layer (`elmt-hiking`) plus
    // overlays, each carrying an `enabled` flag and a set of categories. mapsforge only honours
    // those flags when a menu callback is supplied; with none, it renders *every* rule regardless
    // of `enabled`, so author-disabled overlays (air-defense shelters, giant trees, base stations,
    // some borders) leak onto the map. This callback applies the theme's own intent: take the
    // default layer's categories plus those of its enabled overlays, and let mapsforge drop the
    // rest. Returning null would fall back to "render everything", so we keep that as the
    // defensive path if the expected layer is missing.
    private val styleMenuCallback =
        XmlRenderThemeMenuCallback { style: XmlRenderThemeStyleMenu ->
            val baseLayer = style.getLayer(style.defaultValue) ?: return@XmlRenderThemeMenuCallback null
            val categories = baseLayer.categories
            for (overlay in baseLayer.overlays) {
                if (overlay.isEnabled) {
                    categories.addAll(overlay.categories)
                }
            }
            categories
        }

    fun create(
        context: Context,
        mapDir: File,
    ): MapView {
        // Snap zoom to integer levels on pinch release. mapsforge composites every layer into a
        // single frame buffer bitmap that is then stretched by the current fractional scale; with
        // FRACTIONAL_ZOOM enabled the bitmap (and the text baked or drawn into it) would keep that
        // stretch indefinitely, so labels appear to grow with the map until the next integer zoom
        // is crossed. Disabling it preserves the smooth fractional animation during the gesture
        // and lets mapsforge re-render the basemap at the new integer level on release, restoring
        // theme-rule text sizes - the behaviour RudyMap and OruxMaps use.
        Parameters.FRACTIONAL_ZOOM = false

        val mapView =
            MapView(context).apply {
                isClickable = true
                // Scale bar hidden: it snaps to coarse 1/2/5 buckets so it is not a precise
                // indicator (e.g. zoom 15 and 16 both read 200 m).
                mapScaleBar.isVisible = false
                setBuiltInZoomControls(false)
                setZoomLevelMin(0)
                // `.map` detail tops out around zoom 21; beyond that mapsforge over-zooms
                // (vector geometry and labels are scaled up, no new detail). Allow one
                // over-zoom level (22) to match RudyMap's reach.
                setZoomLevelMax(22)
            }

        val tileCache =
            AndroidUtil.createTileCache(
                context,
                "rudymap",
                mapView.model.displayModel.tileSize,
                1f,
                mapView.model.frameBufferModel.overdrawFactor,
            )

        val tileRendererLayer =
            TileRendererLayer(
                tileCache,
                MapFile(File(mapDir, BASEMAP_NAME)),
                mapView.model.mapViewPosition,
                false, // isTransparent
                true, // renderLabels
                true, // cacheLabels
                AndroidGraphicFactory.INSTANCE,
                buildHillsConfig(mapDir),
            ).apply {
                setXmlRenderTheme(ExternalRenderTheme(File(mapDir, THEME_NAME), styleMenuCallback))
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
    private fun buildHillsConfig(mapDir: File): HillsRenderConfig? {
        val demDir = File(mapDir, DEM_DIR)
        if (!demDir.isDirectory) return null
        val tileSource =
            MemoryCachingHgtReaderTileSource(
                DemFolderFS(demDir),
                SimpleShadingAlgorithm(),
                AndroidGraphicFactory.INSTANCE,
            )
        return HillsRenderConfig(tileSource).apply { indexOnThread() }
    }
}
