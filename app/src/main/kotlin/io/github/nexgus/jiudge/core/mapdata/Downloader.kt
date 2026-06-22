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
 * an interrupted `.part` via a `Range` request. Cancellable: honours coroutine cancellation
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

    suspend fun download(
        urls: List<String>,
        target: File,
        progress: Progress,
    ) = withContext(Dispatchers.IO) {
        require(urls.isNotEmpty()) { "no URLs for ${target.name}" }
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        var lastError: IOException? = null
        for (url in urls) {
            try {
                fetch(url, part, progress)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("cannot move ${part.name} into place")
                return@withContext
            } catch (e: IOException) {
                lastError = e // try the next mirror; keep .part so the next attempt resumes
            }
        }
        throw lastError ?: IOException("no URL succeeded for ${target.name}")
    }

    private suspend fun fetch(
        url: String,
        part: File,
        progress: Progress,
    ) {
        val have = if (part.exists()) part.length() else 0L
        val conn = open(url, have)
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    stream(conn, part, resuming = false, startAt = 0L, progress = progress)

                HttpURLConnection.HTTP_PARTIAL ->
                    stream(conn, part, resuming = have > 0, startAt = have, progress = progress)

                416 -> {
                    // Range no longer valid (resource changed, or .part already complete): start over.
                    part.delete()
                    val fresh = open(url, 0L)
                    try {
                        if (fresh.responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("HTTP ${fresh.responseCode} for $url")
                        }
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
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            if (haveBytes > 0) setRequestProperty("Range", "bytes=$haveBytes-")
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
            }
        }
    }
}
