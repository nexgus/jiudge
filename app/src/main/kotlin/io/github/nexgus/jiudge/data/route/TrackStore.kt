package io.github.nexgus.jiudge.data.route

import java.io.File

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
 * dot ([STAGING_PREFIX]). The dot keeps it off the picker even before it is finalised, so a crash
 * mid-recording leaves a hidden remnant that the next launch cleans up via [cleanupStaleRecordings]
 * rather than presenting a half-written track to the user. v1 does not recover from staging files -
 * see CLAUDE.md "尚未建置: 背景軌跡錄製". Finalising via [finalize] / [finalizeAsCopyOf] rewrites the
 * file as a published trace under its display name.
 *
 * Every method does blocking file I/O - call off the main thread. Reaching the public folder needs
 * `MANAGE_EXTERNAL_STORAGE` (Android 11+) or `WRITE_EXTERNAL_STORAGE` (Android 10 and below);
 * callers must hold it before saving/loading, otherwise the I/O fails.
 */
class TrackStore {
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
        val file = File(RoutePaths.tracksDir(), stagingFileName(startEpochMs))
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
        val file = File(RoutePaths.tracksDir(), stagingFileName(parsed.header.createdAtEpochMs))
        Trace.write(file, parsed.header, parsed.records)
        return file
    }

    /** Appends one `pt` record (serialised by [RecordedTrack.pointRecord]) to [staging] as one line. */
    fun appendPoint(
        staging: File,
        latitude: Double,
        longitude: Double,
        timeMs: Long,
    ) {
        // FileWriter(append = true) is the simplest reliable append; flushed on close so each call
        // flushes at most one short line. spec §3: append-only line layer.
        java.io.FileWriter(staging, true).use { writer ->
            writer.append(RecordedTrack.pointRecord(latitude, longitude, timeMs)).append('\n')
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
        val out = File(RoutePaths.tracksDir(), publishedFileName(target, header.createdAtEpochMs))
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
        val out = File(RoutePaths.tracksDir(), publishedFileName(target, header.createdAtEpochMs))
        Trace.write(out, header, parsed.records)
        if (out != original) original.delete()
        staging.delete()
        return out
    }

    /** Discards the in-progress staging file. Caller invokes this on cancel-save to keep the folder clean. */
    fun discardStaging(staging: File): Boolean = staging.delete()

    /**
     * Removes every leftover staging file (`tracks/.recording-*.jsonl`). Run on app start so a
     * crash mid-recording does not leave noise behind in the public folder.
     */
    fun cleanupStaleRecordings() {
        (RoutePaths.tracksDir().listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(STAGING_PREFIX) && it.name.endsWith(Trace.FILE_SUFFIX) }
            .forEach { it.delete() }
    }

    /** Lists saved tracks newest-first; files that fail to parse or are still staging are skipped. */
    fun list(): List<Summary> =
        (RoutePaths.tracksDir().listFiles() ?: emptyArray())
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
        val newFile = File(RoutePaths.tracksDir(), publishedFileName(target, track.createdAtEpochMs))
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
