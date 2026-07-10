package io.github.nexgus.jiudge.data.route

import org.mapsforge.core.model.LatLong
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.util.Locale

/**
 * Streaming GPX 1.1 exporter for [PlannedRoute] and [RecordedTrack]. Writes elements as it goes
 * rather than building a DOM or accumulating the whole document in memory, since a recorded track
 * can hold tens of thousands of points (see docs/trace_spec.md §11).
 *
 * Elevation is never taken from a stored value - per spec §8, (lat, lon) is the only source of
 * truth for position and elevation is always re-derived from the on-device DEM at export time.
 * Callers pass [elevationAt] to look that up; a null lambda, or a lambda returning null for a
 * given point, means no DEM elevation is available and the `<ele>` element is omitted for that
 * point.
 *
 * Pure Kotlin/JVM: no `android.*` references, so this can run under plain JVM unit tests.
 */
object GpxExporter {
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"

    /**
     * Writes [route] as GPX 1.1: `<metadata>`, then one `<wpt>` per *named* waypoint, then a single
     * `<rte>` whose `<rtept>` sequence is [PlannedRoute.polyline] (spec §11).
     *
     * Unnamed waypoints are deliberately omitted. A GPX `<wpt>` means a point of interest, and apps
     * that render them (Wadi, OruxMaps) label an unnamed one with a placeholder - so exporting every
     * routing control point buries the route under anonymous pins. The route's shape is carried by
     * `<rte>` regardless, and the waypoints stay in the trace file, so nothing is lost.
     *
     * Writes to [out] via a buffered UTF-8 [Writer] and calls [Writer.flush] at the end, but does
     * **not** close [out] - the caller owns the stream's lifecycle (typically via `use`).
     */
    fun write(
        route: PlannedRoute,
        out: OutputStream,
        elevationAt: ((Double, Double) -> Float?)?,
    ) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writeHeader(writer)
        writeMetadata(writer, route.name, route.createdAtEpochMs)
        for (waypoint in route.waypoints) {
            val label = waypoint.name ?: continue
            writePoint(writer, "wpt", waypoint.point, elevationAt, label = label)
        }
        if (route.polyline.isNotEmpty()) {
            writer.append("  <rte>\n")
            writer.append("    <name>").append(escape(route.name)).append("</name>\n")
            for (point in route.polyline) {
                writePoint(writer, "rtept", point, elevationAt, indent = "    ")
            }
            writer.append("  </rte>\n")
        }
        writeFooter(writer)
        writer.flush()
    }

    /**
     * Writes [track] as GPX 1.1: `<metadata>`, then a single `<trk>`/`<trkseg>` whose `<trkpt>`
     * sequence is [RecordedTrack.points] in their existing (time-ascending) order, each with a
     * mandatory `<time>` and an optional `<ele>` (spec §11).
     *
     * Writes to [out] via a buffered UTF-8 [Writer] and calls [Writer.flush] at the end, but does
     * **not** close [out] - the caller owns the stream's lifecycle (typically via `use`).
     */
    fun write(
        track: RecordedTrack,
        out: OutputStream,
        elevationAt: ((Double, Double) -> Float?)?,
    ) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writeHeader(writer)
        writeMetadata(writer, track.name, track.createdAtEpochMs)
        writer.append("  <trk>\n")
        writer.append("    <name>").append(escape(track.name)).append("</name>\n")
        writer.append("    <trkseg>\n")
        for (point in track.points) {
            writeTrackPoint(writer, point, elevationAt)
        }
        writer.append("    </trkseg>\n")
        writer.append("  </trk>\n")
        writeFooter(writer)
        writer.flush()
    }

    private fun writeHeader(writer: Writer) {
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.append("<gpx version=\"1.1\" creator=\"jiudge\" xmlns=\"$GPX_NAMESPACE\">\n")
    }

    private fun writeFooter(writer: Writer) {
        writer.append("</gpx>\n")
    }

    private fun writeMetadata(
        writer: Writer,
        name: String,
        createdAtEpochMs: Long,
    ) {
        writer.append("  <metadata>\n")
        writer.append("    <name>").append(escape(name)).append("</name>\n")
        writer.append("    <time>").append(Instant.ofEpochMilli(createdAtEpochMs).toString()).append("</time>\n")
        writer.append("  </metadata>\n")
    }

    /**
     * Writes a `<wpt>` or `<rtept>` element, with an `<ele>` child if a DEM elevation is available
     * and a `<name>` child if [label] is given. GPX 1.1's `wptType` fixes the child order, so `<ele>`
     * must precede `<name>`.
     */
    private fun writePoint(
        writer: Writer,
        tag: String,
        point: LatLong,
        elevationAt: ((Double, Double) -> Float?)?,
        indent: String = "  ",
        label: String? = null,
    ) {
        val lat = Trace.coord(point.latitude)
        val lon = Trace.coord(point.longitude)
        val ele = elevationAt?.invoke(point.latitude, point.longitude)
        writer
            .append(indent)
            .append("<")
            .append(tag)
            .append(" lat=\"")
            .append(lat.toString())
            .append("\" lon=\"")
            .append(lon.toString())
            .append("\"")
        if (ele == null && label == null) {
            writer.append(" />\n")
            return
        }
        writer.append(">\n")
        if (ele != null) {
            writer
                .append(indent)
                .append("  <ele>")
                .append(String.format(Locale.US, "%.1f", ele))
                .append("</ele>\n")
        }
        if (label != null) {
            writer
                .append(indent)
                .append("  <name>")
                .append(escape(label))
                .append("</name>\n")
        }
        writer
            .append(indent)
            .append("</")
            .append(tag)
            .append(">\n")
    }

    /** Writes a `<trkpt>` element: optional `<ele>` first, then the mandatory `<time>`. */
    private fun writeTrackPoint(
        writer: Writer,
        point: RecordedTrack.Point,
        elevationAt: ((Double, Double) -> Float?)?,
    ) {
        val lat = Trace.coord(point.latitude)
        val lon = Trace.coord(point.longitude)
        val ele = elevationAt?.invoke(point.latitude, point.longitude)
        writer
            .append("      <trkpt lat=\"")
            .append(lat.toString())
            .append("\" lon=\"")
            .append(lon.toString())
            .append("\">\n")
        if (ele != null) {
            writer.append("        <ele>").append(String.format(Locale.US, "%.1f", ele)).append("</ele>\n")
        }
        writer.append("        <time>").append(Instant.ofEpochMilli(point.timeMs).toString()).append("</time>\n")
        writer.append("      </trkpt>\n")
    }

    /** XML-escapes text content (and is safe for the numeric attribute values used here too). */
    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
