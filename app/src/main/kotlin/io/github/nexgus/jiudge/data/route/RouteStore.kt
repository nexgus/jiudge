package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import java.io.File

/**
 * Stores [PlannedRoute]s as individual JSON files under [dir] (the app's private storage). One
 * file per route keeps plans shareable and loadable without a database (see CLAUDE.md storage
 * decision). [list] reads only each file's metadata so the picker need not parse full geometry.
 */
class RouteStore(
    private val dir: File,
) {
    /** Lightweight listing entry - enough to render the load picker without reading geometry. */
    data class Summary(
        val file: File,
        val name: String,
        val createdAtEpochMs: Long,
        val waypointCount: Int,
    )

    /** Persists [route] to a uniquely-named file and returns it. */
    fun save(route: PlannedRoute): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${slug(route.name)}-${route.createdAtEpochMs}${PlannedRoute.FILE_SUFFIX}")
        file.writeText(route.toJson().toString())
        return file
    }

    /** Lists saved routes newest-first; files that fail to parse are skipped rather than thrown. */
    fun list(): List<Summary> {
        val files =
            dir.listFiles { f -> f.isFile && f.name.endsWith(PlannedRoute.FILE_SUFFIX) }
                ?: return emptyList()
        return files
            .mapNotNull { f ->
                runCatching {
                    val json = JSONObject(f.readText())
                    Summary(
                        file = f,
                        name = json.getString("name"),
                        createdAtEpochMs = json.getLong("createdAtEpochMs"),
                        waypointCount = json.getJSONArray("waypoints").length(),
                    )
                }.getOrNull()
            }.sortedByDescending { it.createdAtEpochMs }
    }

    fun load(file: File): PlannedRoute = PlannedRoute.fromJson(JSONObject(file.readText()))

    // Keep CJK and word characters; collapse everything else so the filename stays filesystem-safe.
    private fun slug(name: String): String =
        name
            .trim()
            .ifEmpty { "route" }
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .take(40)
}
