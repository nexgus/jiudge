package io.github.nexgus.jiudge.core.mapdata

import io.github.nexgus.jiudge.core.storage.AppPaths
import io.github.nexgus.jiudge.feature.map.RudyMapView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MapDataCatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun catalog(): Pair<AppPaths, MapDataCatalog> {
        val paths = AppPaths(tmp.root)
        return paths to MapDataCatalog(paths)
    }

    @Test
    fun `has all expected assets`() {
        val (_, cat) = catalog()
        assertEquals(
            setOf(
                "basemap",
                "theme",
                "dem",
                "brouter-E120_N20.rd5",
                "brouter-E120_N25.rd5",
                "brouter-profile",
                "brouter-lookups",
            ),
            cat.assets.map { it.id }.toSet(),
        )
    }

    @Test
    fun `only the dem is optional`() {
        val (_, cat) = catalog()
        assertEquals(listOf("dem"), cat.optional.map { it.id })
        assertFalse(cat.required.any { it.optional })
        assertEquals(cat.assets.size - 1, cat.required.size)
    }

    @Test
    fun `basemap keeps the map and skips the poi`() {
        val (paths, cat) = catalog()
        val basemap = cat.assets.first { it.id == "basemap" }
        val plan = basemap.install as InstallPlan.Unzip
        assertTrue(plan.keepEntry(RudyMapView.BASEMAP_NAME))
        assertFalse(plan.keepEntry("MOI_OSM_Taiwan_TOPO_Rudy.poi"))
        assertEquals(File(paths.mapDir, RudyMapView.BASEMAP_NAME), basemap.marker)
    }

    @Test
    fun `rudymap assets try the kcwu mirror first`() {
        val (_, cat) = catalog()
        val basemap = cat.assets.first { it.id == "basemap" }
        assertEquals(3, basemap.urls.size)
        assertTrue(basemap.urls.first().contains("moi.kcwu.csie.org"))
    }

    @Test
    fun `missing reflects markers present on disk`() {
        val (paths, cat) = catalog()
        assertEquals(cat.assets.size, cat.missing().size)

        paths.mapDir.mkdirs()
        File(paths.mapDir, RudyMapView.BASEMAP_NAME).writeText("x")
        assertFalse(cat.missing().any { it.id == "basemap" })

        // Excluding optionals hides the still-absent DEM.
        assertFalse(cat.missing(includeOptional = false).any { it.id == "dem" })
    }
}
