package io.github.nexgus.jiudge.data.route

import android.os.Environment
import java.io.File

/**
 * The fixed public folders trace files live in, under `Documents/Jiudge/` (see CLAUDE.md storage
 * rationale). The layout is defined here in one place so callers like [RouteStore] never spell out
 * the paths themselves.
 *
 * Reaching these paths needs `MANAGE_EXTERNAL_STORAGE` (Android 11+) or legacy
 * `WRITE_EXTERNAL_STORAGE`; callers must hold it before touching the filesystem.
 */
internal object RoutePaths {
    const val APP_DIR = "Jiudge"
    const val PLANS_DIR = "plans"

    /** `Documents/Jiudge/`, created on demand. */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory still works under All files access.
    fun appDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            APP_DIR,
        ).apply { mkdirs() }

    /** `Documents/Jiudge/plans/`, created on demand. */
    fun plansDir(): File = File(appDir(), PLANS_DIR).apply { mkdirs() }
}
