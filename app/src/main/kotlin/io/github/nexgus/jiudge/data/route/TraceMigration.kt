package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import java.io.File

/**
 * One-shot upgrade of legacy single-object `.json` plans to the JSONL trace format (spec §13).
 *
 * Idempotent and re-entrant: each old file is converted by writing the new `.jsonl` first and
 * deleting the old `.json` only on success; the completion marker `.trace_meta.json` is written only
 * once every file is done. A crash mid-run leaves the marker unwritten, so the next launch simply
 * re-scans - at worst an already-converted file's old `.json` lingers, never lost data. Once the
 * marker records the current schema the scan short-circuits, so calling this on every launch is cheap.
 *
 * Only `plans/` is scanned: track recording is not yet built, so no legacy track files can exist.
 * Needs storage access (see [RoutePaths]); call off the main thread.
 */
class TraceMigration {
    /** Runs the upgrade if `.trace_meta.json` does not yet record the current schema version. */
    fun migrateIfNeeded() {
        val meta = RoutePaths.metaFile()
        val done =
            runCatching { JSONObject(meta.readText()).optInt("schema", 0) }.getOrDefault(0)
        if (done >= Trace.SCHEMA_VERSION) return

        val plans = RoutePaths.plansDir()
        val legacyFiles =
            (plans.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.endsWith(LEGACY_SUFFIX) }
        for (old in legacyFiles) {
            // Preserve the existing "{slug}-{createdAt}" stem; only the extension changes (spec §12).
            val newFile = File(plans, "${old.name.removeSuffix(LEGACY_SUFFIX)}${Trace.FILE_SUFFIX}")
            runCatching {
                val route = PlannedRoute.fromLegacyJson(JSONObject(old.readText()))
                Trace.write(newFile, route.header(), route.toRecords())
                old.delete()
            }
        }

        meta.writeText(JSONObject().put("schema", Trace.SCHEMA_VERSION).toString())
    }

    private companion object {
        const val LEGACY_SUFFIX = ".json"
    }
}
