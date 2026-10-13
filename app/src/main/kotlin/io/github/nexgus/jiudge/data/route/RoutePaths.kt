package io.github.nexgus.jiudge.data.route

import android.os.Environment
import java.io.File

/**
 * The fixed public folders path files live in, under `Documents/Jiudge/` (see CLAUDE.md storage
 * rationale). The layout is defined here in one place so callers like [PathStore] never spell out
 * the paths themselves.
 *
 * Every path file now lives in the single [PATHS_DIR]. [PLANS_DIR] and [TRACKS_DIR] are the legacy
 * per-kind folders of earlier builds: [PathStore] moves their files over and removes them, so they
 * are only ever looked up here, never created.
 *
 * Reaching these paths needs `MANAGE_EXTERNAL_STORAGE` (Android 11+) or legacy
 * `WRITE_EXTERNAL_STORAGE`; callers must hold it before touching the filesystem.
 */
internal object RoutePaths {
    const val APP_DIR = "Jiudge"
    const val PATHS_DIR = "paths"
    const val PLANS_DIR = "plans"
    const val TRACKS_DIR = "tracks"

    /** `Documents/Jiudge/`, created on demand. */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory still works under All files access.
    fun appDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            APP_DIR,
        ).apply { mkdirs() }

    /** `Documents/Jiudge/paths/`, created on demand. */
    fun pathsDir(): File = File(appDir(), PATHS_DIR).apply { mkdirs() }

    /** The legacy `Documents/Jiudge/plans/` if it still exists, else null. Never created. */
    fun plansDir(): File? = existingDir(PLANS_DIR)

    /** The legacy `Documents/Jiudge/tracks/` if it still exists, else null. Never created. */
    fun tracksDir(): File? = existingDir(TRACKS_DIR)

    /** The legacy folders that still exist; empty once the migration has removed both. */
    fun legacyDirs(): List<File> = listOfNotNull(plansDir(), tracksDir())

    private fun existingDir(name: String): File? = File(appDir(), name).takeIf { it.isDirectory }
}
