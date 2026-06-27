package io.github.nexgus.jiudge.data.route

import java.io.File

/** Thrown by [RouteStore.save] when a new route reuses an existing route's name. */
class DuplicateRouteNameException(
    val routeName: String,
) : Exception("route name already exists: $routeName")

/**
 * Stores [PlannedRoute]s as individual JSONL trace files under a fixed public folder,
 * `Documents/Jiudge/plans/` - one file per route, so plans survive uninstall and a reinstalled app
 * re-reads them once the user re-grants storage access. [list] parses each file to fill the picker;
 * routes are few and the files tiny.
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
        val distanceMeters: Double,
    )

    /**
     * Persists [route] as a new uniquely-named JSONL trace file under `plans/`.
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
        val plans = RoutePaths.plansDir()
        if (checkDuplicate) {
            val target = route.name.trim()
            if (list().any { it.name.trim() == target }) throw DuplicateRouteNameException(route.name)
        }
        val file = File(plans, "${slug(route.name)}-${route.createdAtEpochMs}${Trace.FILE_SUFFIX}")
        Trace.write(file, route.header(), route.toRecords())
    }

    /** Lists saved routes newest-first; files that fail to parse are skipped, not thrown. */
    fun list(): List<Summary> =
        (RoutePaths.plansDir().listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(Trace.FILE_SUFFIX) }
            .mapNotNull { file ->
                runCatching {
                    val parsed = Trace.read(file) ?: return@runCatching null
                    val route = PlannedRoute.fromTrace(parsed)
                    Summary(
                        file = file,
                        name = route.name,
                        createdAtEpochMs = route.createdAtEpochMs,
                        distanceMeters = route.distanceMeters,
                    )
                }.getOrNull()
            }.sortedByDescending { it.createdAtEpochMs }

    fun load(file: File): PlannedRoute = PlannedRoute.fromTrace(Trace.read(file) ?: error("not a trace file: ${file.name}"))

    /** Deletes a saved route file; returns false if it was already gone. */
    fun delete(file: File): Boolean = file.delete()

    /**
     * Renames a saved route to [newName], returning the updated [Summary].
     *
     * The displayed name lives in the file's header, so renaming rewrites the whole file (JSONL has
     * no in-place line edit). The file name's slug is cosmetic but kept in sync, so the new name may
     * shift the path; the new file is written before the old one is deleted, so a crash mid-rename
     * leaves a duplicate rather than losing the route. When the slug and timestamp are unchanged the
     * path is identical and [Trace.write] atomically replaces it via tmp+rename, so an interrupted
     * write still preserves the prior contents.
     *
     * Rejects a name already used by another route (trimmed exact match, excluding [file] itself) by
     * throwing [DuplicateRouteNameException], matching [save]'s duplicate guard.
     */
    fun rename(
        file: File,
        newName: String,
    ): Summary {
        val target = newName.trim().ifEmpty { "未命名路線" }
        if (list().any { it.file != file && it.name.trim() == target }) {
            throw DuplicateRouteNameException(target)
        }
        val route = load(file).copy(name = target)
        val newFile = File(RoutePaths.plansDir(), "${slug(target)}-${route.createdAtEpochMs}${Trace.FILE_SUFFIX}")
        Trace.write(newFile, route.header(), route.toRecords())
        if (newFile != file) file.delete()
        return Summary(
            file = newFile,
            name = route.name,
            createdAtEpochMs = route.createdAtEpochMs,
            distanceMeters = route.distanceMeters,
        )
    }

    // Keep CJK and word characters; collapse everything else so the file name stays valid.
    private fun slug(name: String): String =
        name
            .trim()
            .ifEmpty { "route" }
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .take(40)
}
