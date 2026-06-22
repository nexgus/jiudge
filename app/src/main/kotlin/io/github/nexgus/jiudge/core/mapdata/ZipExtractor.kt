package io.github.nexgus.jiudge.core.mapdata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Extracts selected entries from a zip into a directory. Streams with [ZipInputStream] (constant
 * memory regardless of archive size) and writes only entries [keep] accepts - the basemap's unused
 * 236 MB `.poi` is filtered out before a byte of it touches disk. Guards against zip-slip (entries
 * whose path escapes the target). Cancellable between reads.
 */
object ZipExtractor {
    /** Cumulative uncompressed bytes written so far. */
    fun interface Bytes {
        fun onBytes(written: Long)
    }

    suspend fun extract(
        zipFile: File,
        destDir: File,
        keep: (String) -> Boolean,
        onBytes: Bytes = Bytes {},
    ) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val root = destDir.canonicalFile.path
        var written = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory || !keep(entry.name)) {
                    zin.closeEntry()
                    continue
                }
                val out = File(destDir, entry.name).canonicalFile
                if (out.path != root && !out.path.startsWith(root + File.separator)) {
                    throw IOException("zip entry escapes target: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = zin.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        written += n
                        onBytes.onBytes(written)
                    }
                }
                zin.closeEntry()
            }
        }
    }
}
