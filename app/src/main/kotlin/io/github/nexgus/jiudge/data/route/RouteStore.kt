package io.github.nexgus.jiudge.data.route

import android.os.Environment
import org.json.JSONObject
import java.io.File

/** Thrown by [RouteStore.save] when a new route reuses an existing route's name. */
class DuplicateRouteNameException(
    val routeName: String,
) : Exception("route name already exists: $routeName")

/**
 * Stores [PlannedRoute]s as individual JSON files under a fixed public folder, `Documents/Jiudge/
 * plans/` - one file per route, so plans survive uninstall and a reinstalled app re-reads them once
 * the user re-grants storage access. [list] parses each file to fill the picker; routes are few and
 * the files tiny.
 *
 * Every method does blocking file I/O - call off the main thread. Reaching the public folder needs
 * `MANAGE_EXTERNAL_STORAGE` (Android 11+) or `WRITE_EXTERNAL_STORAGE` (Android 10 and below);
 * callers must hold it before saving/loading, otherwise the I/O fails.
 */
class RouteStore {
    /** Lightweight listing entry - identifies a saved route file and its summary fields. */
    data class Summary(
        val file: File,
        val name: String,
        val createdAtEpochMs: Long,
        val waypointCount: Int,
    )

    /**
     * Persists [route] as a new uniquely-named JSON file under `plans/`.
     *
     * When [checkDuplicate] is true (a brand-new route's first save), this rejects a name that
     * already exists - trimmed exact match against existing plans - by throwing
     * [DuplicateRouteNameException], so the picker never lists two indistinguishable entries.
     * Re-saving an edited route passes false, since its name intentionally matches its origin.
     */
    fun save(
        route: PlannedRoute,
        checkDuplicate: Boolean,
    ) {
        val plans = plansDir()
        if (checkDuplicate) {
            val target = route.name.trim()
            if (list().any { it.name.trim() == target }) throw DuplicateRouteNameException(route.name)
        }
        val file = File(plans, "${slug(route.name)}-${route.createdAtEpochMs}${PlannedRoute.FILE_SUFFIX}")
        file.writeText(route.toJson().toString())
    }

    /** Lists saved routes newest-first; files that fail to parse are skipped, not thrown. */
    fun list(): List<Summary> =
        (plansDir().listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(PlannedRoute.FILE_SUFFIX) }
            .mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    Summary(
                        file = file,
                        name = json.getString("name"),
                        createdAtEpochMs = json.getLong("createdAtEpochMs"),
                        waypointCount = json.getJSONArray("waypoints").length(),
                    )
                }.getOrNull()
            }.sortedByDescending { it.createdAtEpochMs }

    fun load(file: File): PlannedRoute = PlannedRoute.fromJson(JSONObject(file.readText()))

    /** The fixed `Documents/Jiudge/plans/` folder, created on demand. */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory still works under All files access.
    private fun plansDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "$APP_DIR/$PLANS_DIR",
        ).apply { mkdirs() }

    // Keep CJK and word characters; collapse everything else so the file name stays valid.
    private fun slug(name: String): String =
        name
            .trim()
            .ifEmpty { "route" }
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .take(40)

    private companion object {
        const val APP_DIR = "Jiudge"
        const val PLANS_DIR = "plans"
    }
}
