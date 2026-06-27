package io.github.nexgus.jiudge.feature.map

import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer

/**
 * Marks a single search target on the map with a translucent yellow disc, so a peak jumped to from
 * the search dialog stays visible while the user decides whether it is the one they wanted. The
 * marker is shown via [show] and removed via [clear]; nothing is drawn until a target is set.
 *
 * [density] is screen pixels per dp, so the disc keeps a constant physical size across screens.
 */
class SearchPeakMarkerLayer(
    private val density: Float,
) : Layer() {
    @Volatile
    private var target: LatLong? = null

    private val fill: Paint =
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(MARKER_ARGB)
            setStyle(Style.FILL)
        }

    fun show(position: LatLong) {
        target = position
        requestRedraw()
    }

    fun clear() {
        target = null
        requestRedraw()
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val position = target ?: return
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        val cx = MercatorProjection.longitudeToPixelX(position.longitude, mapSize) - topLeftPoint.x
        val cy = MercatorProjection.latitudeToPixelY(position.latitude, mapSize) - topLeftPoint.y
        val radius = (RADIUS_DP * density).toInt()
        canvas.drawCircle(cx.toInt(), cy.toInt(), radius, fill)
    }

    private companion object {
        const val RADIUS_DP = 14f

        // Translucent yellow (ARGB), no border.
        const val MARKER_ARGB = 0x80FFEB3B.toInt()
    }
}
