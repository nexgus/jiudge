package io.github.nexgus.jiudge.data.route

import java.io.File
import java.io.IOException

/** Thrown by [TrackStore.save] when a new track reuses an existing track's name. */
class DuplicateTrackNameException(
    val trackName: String,
) : Exception("track name already exists: $trackName")

/**
 * Stores [RecordedTrack]s as individual JSONL trace files under a fixed public folder,
 * `Documents/Jiudge/tracks/` - one file per track, so tracks survive uninstall and a reinstalled app
 * re-reads them once the user re-grants storage access. Mirrors [RouteStore] for plans.
 *
 * Live recording writes to a per-session staging file under the same folder, prefixed with a leading
 * dot ([STAGING_PREFIX]). The dot keeps it off the picker even before it is finalised, so a session
 * that dies mid-recording (crash, task swiped away) leaves a hidden remnant rather than a
 * half-written track in the picker. Remnants are swept by [cleanupStaleRecordings]; a recording may
 * be live while the sweep runs (the session outlives the activity that triggers sweeping), so the
 * caller that knows which staging file is live - RecordingController - passes it via `keep` and the
 * sweep never touches it. Finalising via [finalizeNew] / [finalizeContinuation] rewrites the staging
 * file as a published trace under its display name.
 *
 * [tracksDir] exists so tests can point the store at a temp folder; production uses the default.
 *
 * Every method does blocking file I/O - call off the main thread. Reaching the public folder needs
 * `MANAGE_EXTERNAL_STORAGE` (Android 11+) or `WRITE_EXTERNAL_STORAGE` (Android 10 and below);
 * callers must hold it before saving/loading, otherwise the I/O fails.
 */
class TrackStore(
    private val tracksDir: () -> File = { RoutePaths.tracksDir() },
) {
    /** Lightweight listing entry - identifies a saved track file and its summary fields. */
    data class Summary(
        val file: File,
        val name: String,
        val createdAtEpochMs: Long,
        val distanceMeters: Double,
    )

    /**
     * Allocates a fresh empty staging file for a recording session that starts now, returning the
     * file already populated with the trace header. The dot prefix keeps it out of [list] until it
     * is finalised. Caller appends `pt` records as fixes arrive via [appendPoint].
     */
    fun startNewStaging(
        startEpochMs: Long,
        displayName: String,
    ): File {
        val file = File(tracksDir(), stagingFileName(startEpochMs))
        // Truncate to a single header line; never carry over stale lines if the same epoch is somehow reused.
        val header = TraceHeader(type = Trace.TYPE_TRACK, name = displayName, createdAtEpochMs = startEpochMs)
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
     */
    fun startContinuationStaging(source: File): File {
        val parsed = Trace.read(source) ?: error("not a track file: ${source.name}")
        val file = File(tracksDir(), stagingFileName(parsed.header.createdAtEpochMs))
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
        // flushes at most one short line. spec §3: append-only line layer.
        java.io.FileWriter(staging, true).use { writer ->
            writer.append(RecordedTrack.pointRecord(latitude, longitude, timeMs, src)).append('\n')
        }
    }

    /**
     * Finalises a new recording: renames [staging] into a published JSONL trace file under [newName],
     * checking for duplicates. Returns the finalised file.
     *
     * Both the file name's slug and the header's name reflect [newName]; the header is rewritten via
     * the atomic [Trace.write], so the published file is either complete or absent at every moment.
     */
    fun finalizeNew(
        staging: File,
        newName: String,
    ): File {
        val parsed = Trace.read(staging) ?: error("staging trace empty: ${staging.name}")
        val target = newName.trim().ifEmpty { "未命名軌跡" }
        if (list().any { it.name.trim() == target }) throw DuplicateTrackNameException(target)
        val header = parsed.header.copy(name = target)
        val out = File(tracksDir(), publishedFileName(target, header.createdAtEpochMs))
        Trace.write(out, header, parsed.records)
        staging.delete()
        return out
    }

    /**
     * Finalises a continuation recording: rewrites the new (longer) trace as the published file
     * named [newName], and removes the original [original] when it would otherwise collide on disk.
     *
     * The continuation may keep the original name (default) or rename: when [newName] differs from
     * any other existing track it succeeds; when it collides with a *different* track (one that is
     * neither [original] nor [staging]), [DuplicateTrackNameException] is thrown.
     */
    fun finalizeContinuation(
        staging: File,
        original: File,
        newName: String,
    ): File {
        val parsed = Trace.read(staging) ?: error("staging trace empty: ${staging.name}")
        val target = newName.trim().ifEmpty { "未命名軌跡" }
        if (list().any { it.file != original && it.name.trim() == target }) {
            throw DuplicateTrackNameException(target)
        }
        val header = parsed.header.copy(name = target)
        val out = File(tracksDir(), publishedFileName(target, header.createdAtEpochMs))
        Trace.write(out, header, parsed.records)
        if (out != original) original.delete()
        staging.delete()
        return out
    }

    /**
     * Saves a complete [track] (e.g. one imported from an external GPX) as a published trace file,
     * mirroring [RouteStore.save]: rejects a name already used by another track by throwing
     * [DuplicateTrackNameException], then writes atomically via [Trace.write]. Returns the file.
     * Recording sessions do not use this - they append to a staging file and finalise instead.
     */
    fun save(track: RecordedTrack): File {
        val target = track.name.trim().ifEmpty { "未命名軌跡" }
        if (list().any { it.name.trim() == target }) throw DuplicateTrackNameException(target)
        val named = track.copy(name = target)
        val out = File(tracksDir(), publishedFileName(target, named.createdAtEpochMs))
        Trace.write(out, named.header(), named.toRecords())
        return out
    }

    /** Discards the in-progress staging file. Caller invokes this on cancel-save to keep the folder clean. */
    fun discardStaging(staging: File): Boolean = staging.delete()

    /**
     * Removes every leftover staging file (`tracks/.recording-*.jsonl`) except [keep], so a crash
     * or swiped-away task does not leave noise behind in the public folder. [keep] is the live
     * session's staging file (null when no session is live): stale remnants and the file being
     * recorded right now share the same name pattern, and only the caller can tell them apart.
     */
    fun cleanupStaleRecordings(keep: File? = null) {
        (tracksDir().listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(STAGING_PREFIX) && it.name.endsWith(Trace.FILE_SUFFIX) }
            .filterNot { it == keep }
            .forEach { it.delete() }
    }

    /** Lists saved tracks newest-first; files that fail to parse or are still staging are skipped. */
    fun list(): List<Summary> =
        (tracksDir().listFiles() ?: emptyArray())
            .filter {
                it.isFile &&
                    it.name.endsWith(Trace.FILE_SUFFIX) &&
                    !it.name.startsWith(STAGING_PREFIX)
            }.mapNotNull { file ->
                runCatching {
                    val parsed = Trace.read(file) ?: return@runCatching null
                    val track = RecordedTrack.fromTrace(parsed)
                    Summary(
                        file = file,
                        name = track.name,
                        createdAtEpochMs = track.createdAtEpochMs,
                        distanceMeters = track.distanceMeters,
                    )
                }.getOrNull()
            }.sortedByDescending { it.createdAtEpochMs }

    fun load(file: File): RecordedTrack = RecordedTrack.fromTrace(Trace.read(file) ?: error("not a trace file: ${file.name}"))

    /** Deletes a saved track file; returns false if it was already gone. */
    fun delete(file: File): Boolean = file.delete()

    /**
     * Renames a saved track to [newName], returning the updated [Summary]. Mirrors [RouteStore.rename]
     * - the displayed name lives in the file's header, so renaming rewrites the whole file via the
     * atomic [Trace.write], shifting the file path's slug to match. Rejects a name already used by
     * another track by throwing [DuplicateTrackNameException].
     */
    fun rename(
        file: File,
        newName: String,
    ): Summary {
        val target = newName.trim().ifEmpty { "未命名軌跡" }
        if (list().any { it.file != file && it.name.trim() == target }) {
            throw DuplicateTrackNameException(target)
        }
        val track = load(file).copy(name = target)
        val newFile = File(tracksDir(), publishedFileName(target, track.createdAtEpochMs))
        Trace.write(newFile, track.header(), track.toRecords())
        if (newFile != file) file.delete()
        return Summary(
            file = newFile,
            name = track.name,
            createdAtEpochMs = track.createdAtEpochMs,
            distanceMeters = track.distanceMeters,
        )
    }

    private fun publishedFileName(
        name: String,
        createdAtEpochMs: Long,
    ): String = "${slug(name)}-$createdAtEpochMs${Trace.FILE_SUFFIX}"

    private fun stagingFileName(startEpochMs: Long): String = "$STAGING_PREFIX$startEpochMs${Trace.FILE_SUFFIX}"

    // Keep CJK and word characters; collapse everything else so the file name stays valid.
    private fun slug(name: String): String =
        name
            .trim()
            .ifEmpty { "track" }
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .take(40)

    private companion object {
        const val STAGING_PREFIX = ".recording-"
    }
}
