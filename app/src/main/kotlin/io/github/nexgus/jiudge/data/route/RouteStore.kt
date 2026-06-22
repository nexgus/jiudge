package io.github.nexgus.jiudge.data.route

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/**
 * Stores [PlannedRoute]s as individual JSON documents under the user-picked SAF folder
 * ([RouteFolder]), inside a `plans/` subdirectory - one document per route, so plans stay
 * shareable and survive uninstall (the folder is in public storage). [list] reads each document's
 * JSON to fill the picker; routes are few and the files tiny.
 *
 * Every method does blocking I/O through the [android.content.ContentResolver] - call off the main
 * thread. Callers must have a folder set ([RouteFolder.current] non-null) before saving/loading;
 * [save] throws otherwise, [list] returns empty.
 */
class RouteStore(
    private val context: Context,
) {
    /** Lightweight listing entry - identifies a saved route document and its summary fields. */
    data class Summary(
        val uri: Uri,
        val name: String,
        val createdAtEpochMs: Long,
        val waypointCount: Int,
    )

    /** Persists [route] as a new uniquely-named JSON document under `plans/`. */
    fun save(route: PlannedRoute) {
        val plans = plansDir() ?: error("no route folder selected")
        val displayName = "${slug(route.name)}-${route.createdAtEpochMs}"
        val doc = plans.createFile(MIME_JSON, displayName) ?: error("cannot create route document")
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(route.toJson().toString().toByteArray())
        } ?: error("cannot write route document")
    }

    /** Lists saved routes newest-first; documents that fail to parse are skipped, not thrown. */
    fun list(): List<Summary> {
        val plans = plansDir() ?: return emptyList()
        return plans
            .listFiles()
            .filter { it.isFile && it.name?.endsWith(PlannedRoute.FILE_SUFFIX) == true }
            .mapNotNull { doc ->
                runCatching {
                    val json = JSONObject(readText(doc.uri))
                    Summary(
                        uri = doc.uri,
                        name = json.getString("name"),
                        createdAtEpochMs = json.getLong("createdAtEpochMs"),
                        waypointCount = json.getJSONArray("waypoints").length(),
                    )
                }.getOrNull()
            }.sortedByDescending { it.createdAtEpochMs }
    }

    fun load(uri: Uri): PlannedRoute = PlannedRoute.fromJson(JSONObject(readText(uri)))

    /** The `plans/` subdir of the picked folder, created on demand; null if no folder is set. */
    private fun plansDir(): DocumentFile? {
        val treeUri = RouteFolder.current(context) ?: return null
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return tree.findFile(PLANS_DIR) ?: tree.createDirectory(PLANS_DIR)
    }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
            ?: error("cannot read route document")

    // Keep CJK and word characters; collapse everything else so the document name stays valid.
    private fun slug(name: String): String =
        name
            .trim()
            .ifEmpty { "route" }
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .take(40)

    private companion object {
        const val PLANS_DIR = "plans"
        const val MIME_JSON = "application/json"
    }
}
