package io.github.nexgus.jiudge.core.index

import org.mapsforge.core.model.LatLong

/**
 * One summit in the peak index: its raw RudyMap label, optional elevation, position, and a nearby
 * place name. The name is stored exactly as the basemap carries it (RudyMap bakes the height into the
 * board label, e.g. "玉山, 3952m"), so substring search over [name] still matches a bare "玉山" query.
 *
 * [eleMeters] is a best-effort height parse, kept for search ranking (not shown). [locality] is the
 * nearest named place (a 直轄市/鄉鎮/村里 settlement) used to tell same-named peaks apart in the list;
 * it is an approximate proximity hint (nearest point, not an authoritative administrative lookup),
 * null when no place was near.
 */
data class Peak(
    val name: String,
    val eleMeters: Int?,
    val latitude: Double,
    val longitude: Double,
    val locality: String? = null,
) {
    val position: LatLong get() = LatLong(latitude, longitude)
}
