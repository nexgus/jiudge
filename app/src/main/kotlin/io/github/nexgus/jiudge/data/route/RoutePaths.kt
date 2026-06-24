package io.github.nexgus.jiudge.data.route

import android.os.Environment
import java.io.File

/**
 * The fixed public folders trace files live in, under `Documents/Jiudge/` (see CLAUDE.md storage
 * rationale). Shared by [RouteStore] and [TraceMigration] so the layout is defined in one place.
 *
 * Reaching these paths needs `MANAGE_EXTERNAL_STORAGE` (Android 11+) or legacy
 * `WRITE_EXTERNAL_STORAGE`; callers must hold it before touching the filesystem.
 */
internal object RoutePaths {
    const val APP_DIR = "Jiudge"
    const val PLANS_DIR = "plans"
    const val META_FILE = ".trace_meta.json"

    /** `Documents/Jiudge/`, created on demand. */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory still works under All files access.
    fun appDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            APP_DIR,
        ).apply { mkdirs() }

    /** `Documents/Jiudge/plans/`, created on demand. */
    fun plansDir(): File = File(appDir(), PLANS_DIR).apply { mkdirs() }

    /** `Documents/Jiudge/.trace_meta.json`; tracks the migration schema version (spec §13). */
    fun metaFile(): File = File(appDir(), META_FILE)
}
