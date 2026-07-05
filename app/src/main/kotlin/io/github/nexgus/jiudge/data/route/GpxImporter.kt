package io.github.nexgus.jiudge.data.route

import org.mapsforge.core.model.LatLong
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/**
 * Thrown when a GPX file cannot be parsed: either the XML itself is malformed, or an I/O error
 * occurred while reading it.
 */
class GpxParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Streaming (SAX) GPX importer. Namespace-insensitive: accepts GPX 1.0, GPX 1.1, and documents
 * with no namespace at all. Deliberately avoids DOM (files can hold tens of thousands of points)
 * and avoids android.util.Xml / XmlPullParser (unavailable in plain-JVM unit tests).
 */
object GpxImporter {
    /**
     * @param name resolved display name, see priority rules in [GpxHandler].
     * @param segments one entry per `<trkseg>` or `<rte>`, in document order; each inner list is
     *   that segment's points in document order. Segments with fewer than 2 usable points are
     *   dropped before being added here.
     */
    data class Result(
        val name: String?,
        val segments: List<List<LatLong>>,
    )

    fun parse(input: InputStream): Result {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        val handler = GpxHandler()
        try {
            val parser = factory.newSAXParser()
            parser.parse(input, handler)
        } catch (e: SAXException) {
            throw GpxParseException("Malformed GPX XML", e)
        } catch (e: ParserConfigurationException) {
            throw GpxParseException("Could not configure XML parser", e)
        } catch (e: IOException) {
            throw GpxParseException("I/O error while reading GPX", e)
        }
        return Result(name = handler.resolvedName(), segments = handler.segments())
    }
}

/**
 * SAX content handler implementing the GPX geometry and name-resolution rules.
 *
 * Name priority: first `<trk>`'s direct child `<name>`; else `<metadata>`'s direct child `<name>`;
 * else the `<gpx>` element's direct child `<name>` (GPX 1.0 style); else null. Names inside `<wpt>`,
 * `<rte>`, `<rtept>`, `<trkpt>` or any other element are ignored. "Direct child" is tracked via an
 * explicit element stack so a `<name>` nested deeper (e.g. inside an extension) is not picked up.
 */
private class GpxHandler : DefaultHandler() {
    private val elementStack = ArrayDeque<String>()
    private val segments = mutableListOf<List<LatLong>>()

    // Points accumulated for the segment currently open (trkseg or rte).
    private var currentSegment: MutableList<LatLong>? = null

    // Pending lat/lon attributes for the point element currently open (trkpt or rtept).
    private var pendingLat: Double? = null
    private var pendingLon: Double? = null
    private var insidePoint = false

    // Name capture state: which source is currently being read, and whether that source already
    // has a winning name captured (first trk wins; first metadata wins; first gpx-direct wins).
    private var trkName: String? = null
    private var metadataName: String? = null
    private var gpxName: String? = null
    private var trkNameCaptured = false
    private var metadataNameCaptured = false
    private var gpxNameCaptured = false
    private var capturingNameFor: NameSource? = null
    private val nameTextBuilder = StringBuilder()

    private enum class NameSource { TRK, METADATA, GPX }

    private fun localName(
        qName: String,
        localName: String,
    ): String = if (localName.isNotEmpty()) localName else qName.substringAfter(':')

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes?,
    ) {
        val name = localName(qName.orEmpty(), localName.orEmpty())
        val parent = elementStack.lastOrNull()

        when (name) {
            "trkseg" -> currentSegment = mutableListOf()
            "rte" -> currentSegment = mutableListOf()
            "trkpt", "rtept" -> {
                insidePoint = true
                pendingLat = attributes?.getValue("lat")?.toDoubleOrNull()
                pendingLon = attributes?.getValue("lon")?.toDoubleOrNull()
            }
            "name" -> {
                if (!insidePoint) {
                    capturingNameFor =
                        when {
                            parent == "trk" && !trkNameCaptured -> NameSource.TRK
                            parent == "metadata" && !metadataNameCaptured -> NameSource.METADATA
                            parent == "gpx" && !gpxNameCaptured -> NameSource.GPX
                            else -> null
                        }
                    if (capturingNameFor != null) {
                        nameTextBuilder.setLength(0)
                    }
                }
            }
        }

        elementStack.addLast(name)
    }

    override fun characters(
        ch: CharArray?,
        start: Int,
        length: Int,
    ) {
        if (capturingNameFor != null && ch != null) {
            nameTextBuilder.append(ch, start, length)
        }
    }

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String?,
    ) {
        val name = localName(qName.orEmpty(), localName.orEmpty())
        elementStack.removeLastOrNull()

        when (name) {
            // Only the FIRST <trk>'s name may win: once it closes - named or not - later tracks'
            // names must fall through to the metadata/gpx sources instead.
            "trk" -> trkNameCaptured = true
            "trkseg", "rte" -> {
                currentSegment?.let { seg ->
                    if (seg.size >= 2) segments.add(seg)
                }
                currentSegment = null
            }
            "trkpt", "rtept" -> {
                val lat = pendingLat
                val lon = pendingLon
                if (lat != null && lon != null) {
                    currentSegment?.add(LatLong(lat, lon))
                }
                pendingLat = null
                pendingLon = null
                insidePoint = false
            }
            "name" -> {
                val source = capturingNameFor
                if (source != null) {
                    val text = nameTextBuilder.toString().trim().ifEmpty { null }
                    when (source) {
                        NameSource.TRK -> {
                            trkNameCaptured = true
                            trkName = text
                        }
                        NameSource.METADATA -> {
                            metadataNameCaptured = true
                            metadataName = text
                        }
                        NameSource.GPX -> {
                            gpxNameCaptured = true
                            gpxName = text
                        }
                    }
                    capturingNameFor = null
                }
            }
        }
    }

    fun segments(): List<List<LatLong>> = segments

    fun resolvedName(): String? = trkName ?: metadataName ?: gpxName
}
