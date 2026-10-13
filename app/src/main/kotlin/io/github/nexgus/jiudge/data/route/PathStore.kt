package io.github.nexgus.jiudge.data.route

import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException

/** Thrown by [PathStore] when a save, finalise or rename would reuse another saved path's name. */
class DuplicatePathNameException(
    val pathName: String,
) : Exception("path name already exists: $pathName")

/**
 * Stores every [SavedPath] - planned routes and recorded tracks alike - as individual JSONL trace
 * files under one fixed public folder, `Documents/Jiudge/paths/`: one file per path, so paths survive
 * uninstall and a reinstalled app re-reads them once the user re-grants storage access. A file's
 * [PathKind] is derived from its records, never stored; a file with neither `seg` nor `pt` records
 * is not a path file and is neither listed nor written.
 *
 * Names are unique across the whole store regardless of kind (trimmed exact match): every operation
 * that gives a path a name throws [DuplicatePathNameException] when another path already has it. A
 * blank name always becomes [DEFAULT_NAME].
 *
 * Live recording writes to a per-session staging file in the same folder, prefixed with a leading
 * dot ([STAGING_PREFIX]). The dot keeps it off [list] even before it is finalised, so a session that
 * dies mid-recording (crash, task swiped away) leaves a hidden remnant rather than a half-written
 * track in the picker. Remnants are swept by [cleanupStaleRecordings]; a recording may be live while
 * the sweep runs (the session outlives the activity that triggers sweeping), so the caller that
 * knows which staging file is live - RecordingController - passes it via `keep` and the sweep never
 * touches it. Finalising via [finalizeNew] / [finalizeContinuation] rewrites the staging file as a
 * published trace under its display name.
 *
 * Earlier builds kept plans and tracks in two separate folders (`plans/`, `tracks/`). Their files
 * are moved over by [migrateLegacy], which every method except [appendPoint] runs first; whatever
 * could not be moved stays where it is and is still listed, so a failed move never hides a path. A
 * file reference handed out before a move stays usable: methods taking one follow it to its new
 * location.
 *
 * [pathsDir] and [legacyDirs] exist so tests can point the store at temp folders; production uses
 * the defaults.
 *
 * Every method does blocking file I/O - call off the main thread ([appendPoint], one short line per
 * fix, is the exception the recording service relies on). Reaching the public folder needs
 * `MANAGE_EXTERNAL_STORAGE` (Android 11+) or `WRITE_EXTERNAL_STORAGE` (Android 10 and below);
 * callers must hold it before saving/loading, otherwise the I/O fails.
 */
class PathStore(
    private val pathsDir: () -> File = { RoutePaths.pathsDir() },
    private val legacyDirs: () -> List<File> = { RoutePaths.legacyDirs() },
) {
    /** Lightweight listing entry - identifies a saved path file and its summary fields. */
    data class Summary(
        val file: File,
        val name: String,
        val createdAtEpochMs: Long,
        val distanceMeters: Double,
        val kind: PathKind,
    )

    /**
     * Lists saved paths of both kinds newest-first. Covers the path folder plus whatever
     * [migrateLegacy] had to leave behind in a legacy folder; files that fail to parse, are not
     * valid path files, or are still staging are skipped, not thrown.
     *
     * Runs under the migration lock so a move in progress on another thread can neither show a file
     * twice nor make it vanish from the snapshot.
     */
    fun list(): List<Summary> =
        synchronized(MIGRATION_LOCK) {
            migrateLegacy()
            (listOf(pathsDir()) + existingLegacyDirs())
                .flatMap { it.listFiles()?.asList().orEmpty() }
                .filter { isPathFile(it) }
                .mapNotNull { file -> runCatching { summarize(file, read(file)) }.getOrNull() }
                .sortedByDescending { it.createdAtEpochMs }
        }

    /** Reads one saved path file. Throws when [file] is missing or is not a valid path file. */
    fun load(file: File): SavedPath {
        migrateLegacy()
        return read(resolve(file))
    }

    /**
     * Persists [route] as a new JSONL trace file in the path folder, returning the written file
     * (callers keep it as the reference for later export/rename bookkeeping).
     *
     * [replacing] is the file an edited route was loaded from: it is left out of the duplicate-name
     * check, so keeping the origin's name is fine while renaming onto a different existing path is
     * rejected, and it is deleted once the new file is written, so an edit-then-save never leaves
     * both copies behind. The new file is written before the old one is deleted (matching
     * [rename]), so a crash mid-save leaves a duplicate rather than losing the route. Pass null for
     * a brand-new route; nothing is ever deleted then.
     */
    fun save(
        route: PlannedRoute,
        replacing: File? = null,
    ): File {
        migrateLegacy()
        val source = replacing?.let { resolve(it) }
        val named = route.copy(name = normalizedName(route.name))
        return publish(named, except = source).also { file ->
            if (source != null && source != file) source.delete()
        }
    }

    /**
     * Saves a complete [track] (e.g. one imported from an external GPX) as a published trace file,
     * returning it. Recording sessions do not use this - they append to a staging file and finalise
     * instead.
     */
    fun save(track: RecordedTrack): File {
        migrateLegacy()
        return publish(track.copy(name = normalizedName(track.name)), except = null)
    }

    /**
     * Renames a saved path to [newName], returning the updated [Summary].
     *
     * The displayed name lives in the file's header, so renaming rewrites the whole file (JSONL has
     * no in-place line edit). The file name's slug is cosmetic but kept in sync, so the new name may
     * shift the path; the new file is written before the old one is deleted, so a crash mid-rename
     * leaves a duplicate rather than losing the path. When the slug and timestamp are unchanged the
     * path is identical and [Trace.write] atomically replaces it via tmp+rename, so an interrupted
     * write still preserves the prior contents.
     *
     * [file] itself is left out of the duplicate-name check.
     */
    fun rename(
        file: File,
        newName: String,
    ): Summary {
        migrateLegacy()
        val source = resolve(file)
        val target = normalizedName(newName)
        val renamed =
            when (val path = read(source)) {
                is PlannedRoute -> path.copy(name = target)
                is RecordedTrack -> path.copy(name = target)
            }
        val newFile = publish(renamed, except = source)
        if (newFile != source) source.delete()
        return summarize(newFile, renamed)
    }

    /** Deletes a saved path file; returns false if it was already gone. */
    fun delete(file: File): Boolean {
        migrateLegacy()
        return resolve(file).delete()
    }

    /**
     * Allocates a fresh empty staging file for a recording session that starts now, returning the
     * file already populated with the trace header. The dot prefix keeps it out of [list] until it
     * is finalised. Caller appends `pt` records as fixes arrive via [appendPoint].
     */
    fun startNewStaging(
        startEpochMs: Long,
        displayName: String,
    ): File {
        migrateLegacy()
        val file = File(pathsDir(), stagingFileName(startEpochMs))
        // Truncate to a single header line; never carry over stale lines if the same epoch is somehow reused.
        val header = TraceHeader(name = displayName, createdAtEpochMs = startEpochMs)
        file.bufferedWriter().use { writer ->
            writer.append(header.toLine()).append('\n')
        }
        return file
    }

    /**
     * Allocates a staging file for continuing the existing recording in [source], returning the file
     * already populated with the source's header and every parseable record. Subsequent fixes are
     * appended to this staging file via [appendPoint]; the original [source] is left untouched until
     * the user either finalises (replacing it) or discards (keeping it intact).
     *
     * Throws when [source] is not a [PathKind.TRACK]: `pt` records appended after a plan's `seg`
     * records would be invisible, the file still reading as a plan.
     */
    fun startContinuationStaging(source: File): File {
        migrateLegacy()
        val parsed = Trace.read(resolve(source))
        if (parsed == null || PathKind.of(parsed.records) != PathKind.TRACK) error("not a track file: ${source.name}")
        val file = File(pathsDir(), stagingFileName(parsed.header.createdAtEpochMs))
        Trace.write(file, parsed.header, parsed.records)
        return file
    }

    /**
     * Appends one `pt` record (serialised by [RecordedTrack.pointRecord]) to [staging] as one line.
     *
     * Throws [IOException] when [staging] no longer exists: this layer only ever extends a file the
     * session created, never (re)creates one - a silently recreated file would lack the header line
     * and fail at finalise, long after whatever deleted it. Failing on the very next fix lets the
     * caller stop the session immediately instead.
     *
     * The one method that skips [migrateLegacy]: it runs once per fix on the recording service's
     * main thread and only ever touches the session's own staging file.
     */
    fun appendPoint(
        staging: File,
        latitude: Double,
        longitude: Double,
        timeMs: Long,
        src: String? = null,
    ) {
        if (!staging.exists()) throw IOException("staging file missing: ${staging.name}")
        // FileWriter(append = true) is the simplest reliable append; flushed on close so each call
        // flushes at most one short line.
        FileWriter(staging, true).use { writer ->
            writer.append(RecordedTrack.pointRecord(latitude, longitude, timeMs, src)).append('\n')
        }
    }

    /**
     * Finalises a new recording: rewrites [staging] as a published JSONL trace file under [newName]
     * and removes the staging file. Returns the finalised file.
     *
     * Both the file name's slug and the header's name reflect [newName]; the header is rewritten via
     * the atomic [Trace.write], so the published file is either complete or absent at every moment.
     */
    fun finalizeNew(
        staging: File,
        newName: String,
    ): File {
        migrateLegacy()
        val out = publishStaging(staging, newName, except = null)
        staging.delete()
        return out
    }

    /**
     * Finalises a continuation recording: rewrites the new (longer) trace as the published file
     * named [newName], and removes [original] unless the new file landed on the very same path.
     *
     * The continuation may keep the original name (default) or rename: [original] is left out of
     * the duplicate-name check, any other path holding [newName] rejects it.
     */
    fun finalizeContinuation(
        staging: File,
        original: File,
        newName: String,
    ): File {
        migrateLegacy()
        val source = resolve(original)
        val out = publishStaging(staging, newName, except = source)
        if (out != source) source.delete()
        staging.delete()
        return out
    }

    /** Discards the in-progress staging file. Caller invokes this on cancel-save to keep the folder clean. */
    fun discardStaging(staging: File): Boolean {
        migrateLegacy()
        return staging.delete()
    }

    /**
     * Removes every leftover staging file (`paths/.recording-*.jsonl`) except [keep], so a crash or
     * swiped-away task does not leave noise behind in the public folder. [keep] is the live
     * session's staging file (null when no session is live): stale remnants and the file being
     * recorded right now share the same name pattern, and only the caller can tell them apart.
     * Remnants in a legacy folder are removed by the [migrateLegacy] pass this starts with.
     */
    fun cleanupStaleRecordings(keep: File? = null) {
        migrateLegacy()
        (pathsDir().listFiles() ?: emptyArray())
            .filter { isStagingFile(it) }
            .filterNot { it == keep }
            .forEach { it.delete() }
    }

    /**
     * Moves the path files of the legacy `plans/` and `tracks/` folders into the path folder, and
     * returns at once when neither legacy folder exists. Each file moves on its own: its bytes are
     * copied to a temporary file in the path folder, the temporary file is renamed to the original
     * file name, and only then is the original deleted - so the file's name, contents and listed
     * date are unchanged, and no instant leaves the path without a complete copy.
     *
     * Nothing is ever overwritten: when the path folder already holds a file of that name the
     * original stays in the legacy folder. When any step fails (storage access not granted yet
     * included) the temporary file is removed and the original stays too, to be retried on the next
     * call. Staging remnants in a legacy folder are deleted outright, as [cleanupStaleRecordings]
     * does for the path folder - a session can only be live in the path folder. A legacy folder
     * left empty is removed.
     *
     * Serialised process-wide: the UI and the recording controller each hold a store, and two
     * passes moving the same file at once could publish a truncated copy.
     */
    fun migrateLegacy() {
        if (existingLegacyDirs().isEmpty()) return
        synchronized(MIGRATION_LOCK) {
            for (dir in existingLegacyDirs()) {
                runCatching {
                    val target = pathsDir()
                    for (file in dir.listFiles() ?: emptyArray()) {
                        when {
                            isStagingFile(file) -> file.delete()
                            isPathFile(file) -> moveInto(file, target)
                        }
                    }
                    if (dir.listFiles()?.isEmpty() == true) dir.delete()
                }
            }
        }
    }

    /** Moves one legacy [source] into [targetDir], or leaves it exactly where and as it is. */
    private fun moveInto(
        source: File,
        targetDir: File,
    ) {
        val dest = File(targetDir, source.name)
        if (dest.exists()) return
        val tmp = File(targetDir, source.name + MIGRATION_TMP_SUFFIX)
        try {
            source.inputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                    // Reach stable storage before the only other copy is deleted. Best-effort: a
                    // filesystem that refuses to sync must not keep the file from moving at all.
                    runCatching { output.fd.sync() }
                }
            }
            if (tmp.length() != source.length()) throw IOException("incomplete copy: ${source.name}")
            if (!tmp.renameTo(dest)) throw IOException("cannot publish migrated file: ${source.name}")
            if (!source.delete() && source.exists()) {
                // The original could not be removed: take the published copy back so the path is
                // not listed twice, and so the next pass retries the move instead of skipping it
                // as a name clash. Only while the original is still there - never the last copy.
                dest.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    /**
     * Writes [path] under its (already normalised) name, rejecting a name another path holds.
     *
     * Also refuses a path whose records would not read back as its own kind (a plan with no
     * segment, a track with no point): by the kind rule such a file is not a path file, so [list]
     * would never show what was just "saved".
     */
    private fun publish(
        path: SavedPath,
        except: File?,
    ): File {
        val records = path.toRecords()
        if (PathKind.of(records) != path.kind) throw IOException("nothing to save: ${path.name}")
        rejectDuplicate(path.name, except)
        val file = File(pathsDir(), publishedFileName(path.name, path.createdAtEpochMs))
        Trace.write(file, path.header(), records)
        return file
    }

    /**
     * Publishes a recording's [staging] trace under [newName]; the staging file itself is kept. A
     * recording with no point yet is refused for the same reason [publish] refuses an empty path.
     */
    private fun publishStaging(
        staging: File,
        newName: String,
        except: File?,
    ): File {
        val parsed = Trace.read(staging) ?: error("staging trace empty: ${staging.name}")
        val target = normalizedName(newName)
        if (PathKind.of(parsed.records) != PathKind.TRACK) throw IOException("nothing to save: $target")
        rejectDuplicate(target, except)
        val header = parsed.header.copy(name = target)
        val out = File(pathsDir(), publishedFileName(target, header.createdAtEpochMs))
        Trace.write(out, header, parsed.records)
        return out
    }

    private fun rejectDuplicate(
        name: String,
        except: File?,
    ) {
        if (list().any { it.file != except && it.name.trim() == name }) throw DuplicatePathNameException(name)
    }

    private fun read(file: File): SavedPath = Trace.read(file)?.let { SavedPath.fromTrace(it) } ?: error("not a path file: ${file.name}")

    private fun summarize(
        file: File,
        path: SavedPath,
    ): Summary =
        Summary(
            file = file,
            name = path.name,
            createdAtEpochMs = path.createdAtEpochMs,
            distanceMeters = path.distanceMeters,
            kind = path.kind,
        )

    /**
     * Follows a reference handed out before [migrateLegacy] moved the file: a missing [file] whose
     * name now exists in the path folder is that same file, since a move never renames.
     */
    private fun resolve(file: File): File {
        if (file.exists()) return file
        return File(pathsDir(), file.name).takeIf { it.exists() } ?: file
    }

    private fun existingLegacyDirs(): List<File> = legacyDirs().filter { it.isDirectory }

    private fun isStagingFile(file: File): Boolean =
        file.isFile && file.name.startsWith(STAGING_PREFIX) && file.name.endsWith(Trace.FILE_SUFFIX)

    private fun isPathFile(file: File): Boolean =
        file.isFile && file.name.endsWith(Trace.FILE_SUFFIX) && !file.name.startsWith(STAGING_PREFIX)

    private fun normalizedName(name: String): String = name.trim().ifEmpty { DEFAULT_NAME }

    private fun publishedFileName(
        name: String,
        createdAtEpochMs: Long,
    ): String = "${slug(name)}-$createdAtEpochMs${Trace.FILE_SUFFIX}"

    private fun stagingFileName(startEpochMs: Long): String = "$STAGING_PREFIX$startEpochMs${Trace.FILE_SUFFIX}"

    // Keep CJK and word characters; collapse everything else so the file name stays valid.
    private fun slug(name: String): String = name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40)

    companion object {
        /** The name a path gets when the user leaves the name field blank. */
        const val DEFAULT_NAME = "未命名路徑"

        private const val STAGING_PREFIX = ".recording-"

        // Deliberately not Trace.write's ".tmp": a migration temp must never collide with the temp
        // of a path being saved under the same file name at that moment.
        private const val MIGRATION_TMP_SUFFIX = ".migrating"

        private val MIGRATION_LOCK = Any()
    }
}
