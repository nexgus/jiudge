package io.github.nexgus.jiudge.core.mapdata

import com.sun.net.httpserver.HttpServer
import io.github.nexgus.jiudge.core.storage.AppPaths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class MapDataInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lastModified = "Thu, 02 Jul 2026 02:53:58 GMT"
    private val body = "fresh bytes".toByteArray()

    private lateinit var server: HttpServer
    private val requests = AtomicInteger(0)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/asset") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Last-Modified", lastModified)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun rawAsset(destFile: File): MapDataAsset =
        MapDataAsset(
            id = "raw-test",
            displayName = "raw test asset",
            urls = listOf("http://127.0.0.1:${server.address.port}/asset"),
            approxSizeBytes = body.size.toLong(),
            optional = false,
            install = InstallPlan.Raw(destFile),
        )

    @Test
    fun `installed asset is skipped without a request unless forced`() {
        val paths = AppPaths(tmp.root)
        val dest = File(paths.mapDir, "asset.bin")
        dest.parentFile.mkdirs()
        dest.writeText("already installed")

        val result =
            runBlocking {
                MapDataInstaller(paths).install(rawAsset(dest)) { }
            }

        assertNull(result)
        assertEquals(0, requests.get())
        assertEquals("already installed", dest.readText())
    }

    @Test
    fun `forced install re-downloads over an installed asset and reports metadata`() {
        val paths = AppPaths(tmp.root)
        val dest = File(paths.mapDir, "asset.bin")
        dest.parentFile.mkdirs()
        dest.writeText("stale bytes")

        val result =
            runBlocking {
                MapDataInstaller(paths).install(rawAsset(dest), force = true) { }
            }

        assertNotNull(result)
        assertEquals(lastModified, result?.lastModified)
        assertEquals(body.size.toLong(), result?.sizeBytes)
        assertEquals(String(body), dest.readText())
        assertEquals(1, requests.get())
    }
}
