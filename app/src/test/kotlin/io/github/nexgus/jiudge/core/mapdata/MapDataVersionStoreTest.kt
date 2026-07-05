package io.github.nexgus.jiudge.core.mapdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MapDataVersionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lastModified = "Thu, 02 Jul 2026 02:53:58 GMT"

    private fun store(): MapDataVersionStore = MapDataVersionStore(tmp.root)

    private fun file(): File = File(tmp.root, "mapdata_versions.tsv")

    @Test
    fun `missing file reads as empty`() {
        assertTrue(store().readAll().isEmpty())
    }

    @Test
    fun `put then readAll roundtrips`() {
        val s = store()
        s.put("basemap", MapDataVersionStore.Record(354_528_540L, lastModified))
        s.put("theme", MapDataVersionStore.Record(1_700_270L, "Sat, 04 Jul 2026 08:23:39 GMT"))
        assertEquals(
            mapOf(
                "basemap" to MapDataVersionStore.Record(354_528_540L, lastModified),
                "theme" to MapDataVersionStore.Record(1_700_270L, "Sat, 04 Jul 2026 08:23:39 GMT"),
            ),
            store().readAll(),
        )
    }

    @Test
    fun `put overwrites an existing record`() {
        val s = store()
        s.put("basemap", MapDataVersionStore.Record(1L, lastModified))
        s.put("basemap", MapDataVersionStore.Record(2L, "Thu, 09 Jul 2026 03:00:00 GMT"))
        assertEquals(
            mapOf("basemap" to MapDataVersionStore.Record(2L, "Thu, 09 Jul 2026 03:00:00 GMT")),
            s.readAll(),
        )
    }

    @Test
    fun `remove drops only the named record`() {
        val s = store()
        s.put("basemap", MapDataVersionStore.Record(1L, lastModified))
        s.put("dem", MapDataVersionStore.Record(2L, lastModified))
        s.remove("basemap")
        assertEquals(setOf("dem"), s.readAll().keys)
    }

    @Test
    fun `bad header reads as empty`() {
        file().writeText("#some-other-file\t1\nbasemap\t1\t$lastModified\n")
        assertTrue(store().readAll().isEmpty())
    }

    @Test
    fun `bad data line is skipped and the rest kept`() {
        file().writeText(
            "#jiudge-mapdata-versions\t1\n" +
                "basemap\tnot-a-number\t$lastModified\n" +
                "truncated-line\n" +
                "dem\t42\t$lastModified\n",
        )
        assertEquals(
            mapOf("dem" to MapDataVersionStore.Record(42L, lastModified)),
            store().readAll(),
        )
    }

    @Test
    fun `last modified with spaces survives the tsv`() {
        store().put("basemap", MapDataVersionStore.Record(7L, lastModified))
        assertEquals(lastModified, store().readAll()["basemap"]?.lastModified)
    }

    @Test
    fun `stray tmp file does not affect reads`() {
        store().put("basemap", MapDataVersionStore.Record(7L, lastModified))
        File(tmp.root, "mapdata_versions.tsv.tmp").writeText("garbage")
        assertEquals(setOf("basemap"), store().readAll().keys)
    }
}
