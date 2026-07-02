package io.github.nexgus.jiudge.feature.map

import android.content.Context
import android.view.Choreographer
import android.view.View
import org.mapsforge.core.mapelements.MapElementContainer
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.labels.LabelStore
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.util.LayerUtil
import kotlin.math.pow
import android.graphics.Canvas as AndroidCanvas

/**
 * Draws mapsforge labels (text + POI icons) in screen space, on top of [MapView]. Solves the
 * problem that mapsforge's frame buffer applies a single matrix scale to its drawing bitmap during
 * fractional zoom - which would stretch labels along with tiles up to 2x before the next integer
 * zoom triggers a re-raster.
 *
 * The trick: each [MapElementContainer]'s `draw(canvas, origin, matrix, rotation)` computes its
 * own position as `xy - origin` then `translate`s the canvas by that offset before invoking
 * `drawText`/`drawBitmap` with a fixed-size paint. By passing `origin = element.xy` we make the
 * element believe it is drawing at the canvas origin (with a small font-padding adjustment), then
 * we pre-translate the underlying Android canvas to the screen position we computed ourselves,
 * applying the fractional [scaleRatio] only to the **position** while leaving the paint's text
 * size and the symbol bitmap's pixel size untouched.
 *
 * The view must be stacked above [MapView] (e.g. via a Compose `Box`) and should not consume
 * touches - the default View behaviour (non-clickable, `onTouchEvent` returns false) lets gestures
 * fall through to [MapView] below.
 *
 * Caveats:
 * - mapsforge populates the label store with **unfiltered raw labels** when `renderLabels = false`
 *   on the tile renderer; we run `LayerUtil.collisionFreeOrdered` here to mirror what the built-in
 *   `LabelLayer` does.
 * - The collision rects are computed at the integer zoom level; in fractional zoom the apparent
 *   label spacing grows, so some labels that visually have room may still be rejected. Matches the
 *   built-in `LabelLayer` behaviour.
 * - `MapElementContainer.xy` is `protected`; we read it via reflection (field handle cached). If
 *   mapsforge upgrades rename or remove it, the overlay will stop rendering - the field is then
 *   logged once and skipped silently to avoid crashing the map.
 * - Map rotation is not supported (the `origin = xy` trick moves the rotation pivot). The app does
 *   not enable map rotation today; revisit if it ever does.
 */
class LabelOverlayView(
    context: Context,
    private val mapView: MapView,
) : View(context) {
    private val labelStore: LabelStore? =
        mapView.layerManager.layers
            .asSequence()
            .filterIsInstance<TileRendererLayer>()
            .firstOrNull()
            ?.labelStore

    private val matrix = AndroidGraphicFactory.INSTANCE.createMatrix()

    private val observer =
        object : Observer {
            override fun onChange() {
                postInvalidateOnAnimation()
            }
        }

    // Last version of the label store this overlay drew from. LabelStore has no observer interface;
    // it only exposes a monotonically increasing version bumped whenever tile rendering populates
    // new labels. mapsforge's built-in LabelLayer is driven every frame by LayerManager and reads
    // the version there; this overlay is a plain Android View with no such driver, so we poll the
    // version via Choreographer instead (see [versionPoll] below). Without this, jumping the map to
    // a fresh area (e.g. picking a distant peak in the search dialog) shows no labels until the
    // user pans / zooms - the tile render finishes but the overlay is never invalidated.
    private var lastLabelStoreVersion: Int = Int.MIN_VALUE

    private val versionPoll =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!isAttachedToWindow) return
                val store = labelStore
                if (store != null) {
                    val v = store.getVersion()
                    if (v != lastLabelStoreVersion) {
                        lastLabelStoreVersion = v
                        postInvalidateOnAnimation()
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    init {
        // Transparent overlay; default View is non-clickable so touches fall through to MapView.
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mapView.model.mapViewPosition.addObserver(observer)
        Choreographer.getInstance().postFrameCallback(versionPoll)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mapView.model.mapViewPosition.removeObserver(observer)
        Choreographer.getInstance().removeFrameCallback(versionPoll)
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        val store = labelStore ?: return
        val viewW = width
        val viewH = height
        if (viewW == 0 || viewH == 0) return

        val mapViewPosition = mapView.model.mapViewPosition
        val center = mapViewPosition.center ?: return
        val zoomLevel = mapViewPosition.zoomLevel
        val scaleFactor = mapViewPosition.scaleFactor
        val baseScale = 2.0.pow(zoomLevel.toInt())
        // In [1, 2): how much the frame buffer is being stretched at the current fractional zoom.
        // Our job is to apply this only to label positions, never to their pixel sizes.
        val scaleRatio = if (baseScale > 0) scaleFactor / baseScale else 1.0

        val tileSize = mapView.model.displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize)
        val centerPixelX = MercatorProjection.longitudeToPixelX(center.longitude, mapSize)
        val centerPixelY = MercatorProjection.latitudeToPixelY(center.latitude, mapSize)

        val halfWInZoomPixels = (viewW / 2.0) / scaleRatio
        val halfHInZoomPixels = (viewH / 2.0) / scaleRatio
        val topLeftPixelX = centerPixelX - halfWInZoomPixels
        val topLeftPixelY = centerPixelY - halfHInZoomPixels
        val bottomRightPixelX = centerPixelX + halfWInZoomPixels
        val bottomRightPixelY = centerPixelY + halfHInZoomPixels

        // Clamp the visible rect to the map's pixel bounds before converting back to lat/long, so
        // the BoundingBox stays inside Mercator's defined range (avoids NaN near poles / dateline).
        val clampedMinX = topLeftPixelX.coerceIn(0.0, mapSize.toDouble())
        val clampedMinY = topLeftPixelY.coerceIn(0.0, mapSize.toDouble())
        val clampedMaxX = bottomRightPixelX.coerceIn(0.0, mapSize.toDouble())
        val clampedMaxY = bottomRightPixelY.coerceIn(0.0, mapSize.toDouble())
        if (clampedMaxX <= clampedMinX || clampedMaxY <= clampedMinY) return
        val minLong = MercatorProjection.pixelXToLongitude(clampedMinX, mapSize)
        val maxLong = MercatorProjection.pixelXToLongitude(clampedMaxX, mapSize)
        val maxLat = MercatorProjection.pixelYToLatitude(clampedMinY, mapSize)
        val minLat = MercatorProjection.pixelYToLatitude(clampedMaxY, mapSize)
        if (minLat > maxLat || minLong > maxLong) return
        val boundingBox = BoundingBox(minLat, minLong, maxLat, maxLong)

        val upperLeft = LayerUtil.getUpperLeft(boundingBox, zoomLevel, tileSize)
        val lowerRight = LayerUtil.getLowerRight(boundingBox, zoomLevel, tileSize)

        val raw = store.getVisibleItems(upperLeft, lowerRight) ?: return
        if (raw.isEmpty()) return
        val ordered = LayerUtil.collisionFreeOrdered(raw, Rotation.NULL_ROTATION, false)
        if (ordered.isEmpty()) return

        val mapsforgeCanvas = AndroidGraphicFactory.createGraphicContext(canvas)
        try {
            for (element in ordered) {
                val xy: Point = MapElementContainerXyAccessor.read(element) ?: continue
                val screenX = (xy.x - topLeftPixelX) * scaleRatio
                val screenY = (xy.y - topLeftPixelY) * scaleRatio
                canvas.save()
                canvas.translate(screenX.toFloat(), screenY.toFloat())
                element.draw(mapsforgeCanvas, xy, matrix, Rotation.NULL_ROTATION)
                canvas.restore()
            }
        } finally {
            mapsforgeCanvas.destroy()
        }
    }
}

/**
 * Reflective accessor for `MapElementContainer.xy` (protected). Cached lazily so we pay the field
 * lookup once per process. Returns null if the field is missing (e.g. mapsforge upgrade renamed
 * it), allowing the overlay to skip rendering gracefully.
 */
private object MapElementContainerXyAccessor {
    private val field: java.lang.reflect.Field? =
        runCatching {
            MapElementContainer::class.java.getDeclaredField("xy").apply { isAccessible = true }
        }.getOrNull()

    fun read(element: MapElementContainer): Point? = field?.get(element) as? Point
}
