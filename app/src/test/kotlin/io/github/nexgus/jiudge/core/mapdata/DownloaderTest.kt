package io.github.nexgus.jiudge.core.mapdata

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/**
 * Downloader against a local JDK HttpServer, focused on the [Downloader.Completed] metadata the
 * update flow persists (the resume/If-Range behaviour itself long predates these tests).
 */
class DownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lastModified = "Thu, 02 Jul 2026 02:53:58 GMT"
    private val body = "0123456789abcdef".toByteArray()

    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    /** Serves [body] whole (200) or from a `Range` offset (206), optionally stamping Last-Modified. */
    private fun serve(
        path: String,
        stampLastModified: Boolean,
    ) {
        server.createContext(path) { exchange ->
            if (stampLastModified) exchange.responseHeaders.add("Last-Modified", lastModified)
            val range = exchange.requestHeaders.getFirst("Range")
            val from =
                range
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toLongOrNull()
                    ?.toInt()
            val slice = if (from != null) body.copyOfRange(from, body.size) else body
            exchange.sendResponseHeaders(if (from != null) 206 else 200, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        }
    }

    private fun download(target: File): Downloader.Completed =
        runBlocking {
            Downloader().download(listOf(url("/file")), target, { _, _ -> })
        }

    @Test
    fun `completed download reports last modified and size`() {
        serve("/file", stampLastModified = true)
        val target = File(tmp.root, "file.bin")
        val completed = download(target)
        assertEquals(lastModified, completed.lastModified)
        assertEquals(body.size.toLong(), completed.sizeBytes)
        assertArrayEquals(body, target.readBytes())
    }

    @Test
    fun `server without last modified yields null`() {
        serve("/file", stampLastModified = false)
        val completed = download(File(tmp.root, "file.bin"))
        assertNull(completed.lastModified)
        assertEquals(body.size.toLong(), completed.sizeBytes)
    }

    @Test
    fun `resumed download still reports last modified and the full size`() {
        serve("/file", stampLastModified = true)
        val target = File(tmp.root, "file.bin")
        // A previous run got half the bytes; the sidecar holds the validator sent as If-Range.
        File(tmp.root, "file.bin.part").writeBytes(body.copyOfRange(0, 8))
        File(tmp.root, "file.bin.part.meta").writeText(lastModified)
        val completed = download(target)
        assertEquals(lastModified, completed.lastModified)
        assertEquals(body.size.toLong(), completed.sizeBytes)
        assertArrayEquals(body, target.readBytes())
    }
}
