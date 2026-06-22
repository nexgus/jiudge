package io.github.nexgus.jiudge.core.mapdata

import java.io.File

/**
 * A single downloadable map-data asset (a basemap, the theme, the DEM, or one BRouter file) plus
 * how to put it on disk. The download layer is uniform - try [urls] in order (mirror fallback),
 * resume to a `.part` file - and [install] decides what the bytes become once downloaded.
 *
 * [marker] is a path that exists on disk *only* once this asset is fully installed; it is both the
 * "already have it, skip" check ([isInstalled]) and what the installer publishes last, so an
 * interrupted install is simply re-run rather than mistaken for complete.
 */
data class MapDataAsset(
    val id: String,
    val displayName: String,
    val urls: List<String>,
    val approxSizeBytes: Long,
    val optional: Boolean,
    val install: InstallPlan,
) {
    val marker: File get() = install.marker

    val isInstalled: Boolean get() = marker.exists()
}

/** How a downloaded asset is turned into its on-disk form. */
sealed interface InstallPlan {
    /** The path that proves this asset is installed (published last; also the skip check). */
    val marker: File

    /**
     * The download is a zip; extract every entry for which [keepEntry] is true into [destDir]
     * (other entries - e.g. the basemap's unused `.poi` - are skipped without ever being written).
     */
    data class Unzip(
        val destDir: File,
        val keepEntry: (String) -> Boolean,
        override val marker: File,
    ) : InstallPlan

    /** The download is placed verbatim at [destFile] (e.g. a BRouter `.rd5`/`.brf`/`lookups.dat`). */
    data class Raw(
        val destFile: File,
    ) : InstallPlan {
        override val marker: File get() = destFile
    }
}
