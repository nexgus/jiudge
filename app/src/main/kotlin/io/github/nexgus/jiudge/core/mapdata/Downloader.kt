package io.github.nexgus.jiudge.core.mapdata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Resumable HTTP downloader (built-in [HttpURLConnection]; no third-party client). Writes to a
 * sibling `.part` file and only renames it into place on a complete transfer, so the target file
 * is never seen half-written. Tries the given URLs in order (RudyMap mirror fallback) and resumes
 * an interrupted `.part` via a `Range` request guarded by `If-Range`: the server's `ETag` (or
 * `Last-Modified`) from the first fetch is persisted to a `.part.meta` sidecar, so resuming a
 * `.part` that was started against a now-superseded server-side file silently restarts from zero
 * instead of stitching old bytes onto new bytes. Cancellable: honours coroutine cancellation
 * between reads, leaving the `.part` so a later run can continue.
 */
class Downloader(
    private val bufferSize: Int = 64 * 1024,
    private val timeoutMs: Int = 30_000,
) {
    /** Whole-file progress: [downloaded] of [total] bytes ([total] <= 0 when the server omits it). */
    fun interface Progress {
        fun onProgress(
            downloaded: Long,
            total: Long,
        )
    }

    /**
     * Post-download integrity check run against the completed `.part` before it is renamed into
     * place. Throw [VerifyException] (or any [IOException]) to reject the file; the [Downloader]
     * will then discard the `.part`/sidecar and try the next mirror.
     */
    fun interface Verifier {
        @Throws(IOException::class)
        fun verify(file: File)
    }

    /** Raised by a [Verifier] to mean "the downloaded bytes are not what we asked for". */
    class VerifyException(
        message: String,
    ) : IOException(message)

    suspend fun download(
        urls: List<String>,
        target: File,
        progress: Progress,
        verifier: Verifier = Verifier {},
    ) = withContext(Dispatchers.IO) {
        require(urls.isNotEmpty()) { "no URLs for ${target.name}" }
        val part = File(target.parentFile, target.name + ".part")
        val meta = File(target.parentFile, target.name + ".part.meta")
        target.parentFile?.mkdirs()
        // A .part left by an older app version (or one whose sidecar is gone) carries no validator
        // we could send as If-Range, so we cannot prove the server's bytes still match its prefix.
        // Restart cleanly rather than blindly appending to bytes of unknown lineage.
        if (part.exists() && readValidator(meta) == null) {
            part.delete()
            meta.delete()
        }
        var lastError: IOException? = null
        for (url in urls) {
            try {
                fetch(url, part, meta, progress)
                verifier.verify(part)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("cannot move ${part.name} into place")
                meta.delete()
                return@withContext
            } catch (e: VerifyException) {
                // The bytes we got do not survive the integrity check. Throwing them away (rather
                // than keeping them for the next mirror to resume into) is the whole point.
                part.delete()
                meta.delete()
                lastError = e
            } catch (e: IOException) {
                lastError = e // try the next mirror; keep .part so the next attempt resumes
            }
        }
        throw lastError ?: IOException("no URL succeeded for ${target.name}")
    }

    private suspend fun fetch(
        url: String,
        part: File,
        meta: File,
        progress: Progress,
    ) {
        val have = if (part.exists()) part.length() else 0L
        val ifRange = if (have > 0) readValidator(meta) else null
        val conn = open(url, have, ifRange)
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Either the first fetch, or the server saw If-Range mismatch and is sending
                    // the new full file. Restart cleanly and refresh the validator.
                    writeValidator(meta, conn)
                    stream(conn, part, resuming = false, startAt = 0L, progress = progress)
                }

                HttpURLConnection.HTTP_PARTIAL ->
                    // If-Range matched (or absent on first fetch with no .part). Resume; the stored
                    // validator stays valid because the server confirmed the underlying file did.
                    stream(conn, part, resuming = have > 0, startAt = have, progress = progress)

                416 -> {
                    // Range no longer valid (resource changed, or .part already complete): start over.
                    part.delete()
                    meta.delete()
                    val fresh = open(url, 0L, null)
                    try {
                        if (fresh.responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("HTTP ${fresh.responseCode} for $url")
                        }
                        writeValidator(meta, fresh)
                        stream(fresh, part, resuming = false, startAt = 0L, progress = progress)
                    } finally {
                        fresh.disconnect()
                    }
                }

                else -> throw IOException("HTTP $code for $url")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(
        url: String,
        haveBytes: Long,
        ifRange: String?,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (haveBytes > 0) {
                setRequestProperty("Range", "bytes=$haveBytes-")
                if (!ifRange.isNullOrEmpty()) setRequestProperty("If-Range", ifRange)
            }
            connect()
        }

    private suspend fun stream(
        conn: HttpURLConnection,
        part: File,
        resuming: Boolean,
        startAt: Long,
        progress: Progress,
    ) {
        // Content-Length here is the *remaining* bytes; the total file is what we already have plus it.
        val remaining = conn.contentLengthLong
        val total = if (remaining >= 0) startAt + remaining else -1L
        conn.inputStream.use { input ->
            // append == resuming: false truncates any stale .part the server chose to resend in full.
            FileOutputStream(part, resuming).use { out ->
                var written = startAt
                val buf = ByteArray(bufferSize)
                progress.onProgress(written, total)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    progress.onProgress(written, total)
                }
                if (total > 0 && written != total) {
                    throw IOException(
                        "truncated transfer for ${part.name}: $written / $total bytes",
                    )
                }
            }
        }
    }

    private fun readValidator(meta: File): String? = if (meta.exists()) meta.readText().trim().ifEmpty { null } else null

    private fun writeValidator(
        meta: File,
        conn: HttpURLConnection,
    ) {
        val validator = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
        if (!validator.isNullOrEmpty()) meta.writeText(validator) else meta.delete()
    }
}
