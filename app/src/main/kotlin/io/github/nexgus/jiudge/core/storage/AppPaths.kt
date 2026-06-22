package io.github.nexgus.jiudge.core.storage

import android.content.Context
import java.io.File

/**
 * Single source of truth for the app's on-device file locations.
 *
 * Downloadable RudyMap/BRouter data lives under [getExternalFilesDir][Context.getExternalFilesDir]
 * - it is wiped on uninstall, which is fine because it re-downloads. User-created routes are NOT
 * here: they are precious and must survive uninstall, so they live in a SAF folder the user picks
 * (handled separately via DocumentFile, not java.io.File). See the storage-layout decision.
 *
 * Layout under the external files root:
 * ```
 * <root>/map/        RudyMap basemap .map + MOI_OSM.xml theme + moiosmhs_res/ + hgt/ DEM
 * <root>/brouter/    BRouter routing data: segments4/ + profiles2/
 * <root>/.staging/   in-progress downloads, promoted into place only after full verify
 * ```
 */
class AppPaths internal constructor(
    private val root: File,
) {
    // getExternalFilesDir is null only when external storage is unmounted (essentially never on
    // these devices); fall back to internal storage so path resolution cannot crash. The File-root
    // constructor is internal so unit tests can point the layout at a temp directory.
    constructor(context: Context) : this(
        context.getExternalFilesDir(null) ?: File(context.filesDir, "external"),
    )

    /** RudyMap rendering data: basemap, theme + `moiosmhs_res/` symbols, and `hgt/` DEM. */
    val mapDir: File = File(root, "map")

    /** BRouter routing data root, holding `segments4/` and `profiles2/`. */
    val brouterDir: File = File(root, "brouter")

    /** Staging area for in-progress downloads (Step 2); promoted into place after verify. */
    val stagingDir: File = File(root, ".staging")
}
