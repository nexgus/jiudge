package io.github.nexgus.jiudge.core.mapdata

import io.github.nexgus.jiudge.core.storage.AppPaths
import io.github.nexgus.jiudge.feature.map.RudyMapView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MapUpdateCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lm = "Thu, 02 Jul 2026 02:53:58 GMT"
    private val newerLm = "Thu, 09 Jul 2026 03:00:00 GMT"

    private lateinit var paths: AppPaths
    private lateinit var catalog: MapDataCatalog
    private lateinit var store: MapDataVersionStore

    private fun setUp(installDem: Boolean = true) {
        paths = AppPaths(tmp.root)
        catalog = MapDataCatalog(paths)
        store = MapDataVersionStore(paths.mapDir)
        paths.mapDir.mkdirs()
        File(paths.mapDir, RudyMapView.BASEMAP_NAME).writeText("map")
        File(paths.mapDir, RudyMapView.THEME_NAME).writeText("theme")
        if (installDem) File(paths.mapDir, RudyMapView.DEM_DIR).mkdirs()
    }

    /** Every URL answers with the same [RemoteInfo]; records which URLs were probed. */
    private class FakeProbe(
        private val answer: (String) -> MapUpdateChecker.RemoteInfo,
    ) : MapUpdateChecker.RemoteProbe {
        val probed = mutableListOf<String>()

        override fun head(url: String): MapUpdateChecker.RemoteInfo {
            probed.add(url)
            return answer(url)
        }
    }

    private fun statusOf(
        result: MapUpdateChecker.Result,
        id: String,
    ): MapUpdateChecker.Status = result.checks.first { it.asset.id == id }.status

    @Test
    fun `matching record is up to date`() {
        setUp()
        store.put("basemap", MapDataVersionStore.Record(100L, lm))
        val probe = FakeProbe { MapUpdateChecker.RemoteInfo(lm, 100L) }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertEquals(MapUpdateChecker.Status.UpToDate, statusOf(result, "basemap"))
        assertTrue(result.updatableIds.isEmpty())
    }

    @Test
    fun `different last modified means update available`() {
        setUp()
        store.put("basemap", MapDataVersionStore.Record(100L, lm))
        val probe = FakeProbe { MapUpdateChecker.RemoteInfo(newerLm, 100L) }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertTrue(statusOf(result, "basemap") is MapUpdateChecker.Status.UpdateAvailable)
    }

    @Test
    fun `different size with same last modified means update available`() {
        setUp()
        store.put("basemap", MapDataVersionStore.Record(100L, lm))
        val probe = FakeProbe { MapUpdateChecker.RemoteInfo(lm, 101L) }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertTrue(statusOf(result, "basemap") is MapUpdateChecker.Status.UpdateAvailable)
    }

    @Test
    fun `installed asset without a record is unknown`() {
        setUp()
        val probe = FakeProbe { MapUpdateChecker.RemoteInfo(lm, 100L) }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertTrue(statusOf(result, "basemap") is MapUpdateChecker.Status.Unknown)
        assertEquals(listOf("basemap", "dem", "theme"), result.unknownIds)
    }

    @Test
    fun `absent dem is not installed and never probed`() {
        setUp(installDem = false)
        val probe = FakeProbe { MapUpdateChecker.RemoteInfo(lm, 100L) }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertEquals(MapUpdateChecker.Status.NotInstalled, statusOf(result, "dem"))
        assertTrue(probe.probed.none { it.contains("hgtmix") })
    }

    @Test
    fun `failing mirror falls through to the next`() {
        setUp()
        store.put("basemap", MapDataVersionStore.Record(100L, lm))
        val probe =
            FakeProbe { url ->
                if (url.contains("kcwu")) throw IOException("mirror down")
                MapUpdateChecker.RemoteInfo(lm, 100L)
            }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertEquals(MapUpdateChecker.Status.UpToDate, statusOf(result, "basemap"))
        assertTrue(probe.probed.any { it.contains("happyman") })
    }

    @Test
    fun `all mirrors failing throws`() {
        setUp()
        val probe = FakeProbe { throw IOException("offline") }
        try {
            runBlocking { MapUpdateChecker(catalog, store, probe).check() }
            fail("expected IOException")
        } catch (expected: IOException) {
            assertEquals("offline", expected.message)
        }
    }

    @Test
    fun `updatable ids keep install order with theme last`() {
        setUp()
        for (id in listOf("basemap", "dem", "theme")) {
            store.put(id, MapDataVersionStore.Record(100L, lm))
        }
        val probe = FakeProbe { MapUpdateChecker.RemoteInfo(newerLm, 100L) }
        val result = runBlocking { MapUpdateChecker(catalog, store, probe).check() }
        assertEquals(listOf("basemap", "dem", "theme"), result.updatableIds)
    }
}
