package io.github.nexgus.jiudge.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeakIndexStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stamp = PeakIndexStore.Stamp(mapSizeBytes = 123_456L, mapModifiedMs = 1_700_000_000_000L)

    private val peaks =
        listOf(
            Peak(name = "玉山, 3952m", eleMeters = 3952, latitude = 23.47000, longitude = 120.95700, locality = "信義鄉"),
            Peak(name = "雪山主峰", eleMeters = 3886, latitude = 24.38400, longitude = 121.23000, locality = "和平區"),
            // A summit with no readable elevation and no nearby place: both optional columns round-trip blank.
            Peak(name = "無名山頭", eleMeters = null, latitude = 23.00000, longitude = 121.00000, locality = null),
        )

    @Test
    fun `write then readAll round-trips every peak`() {
        val dir = tmp.newFolder("map")
        PeakIndexStore.write(dir, stamp, peaks)
        assertEquals(peaks, PeakIndexStore.readAll(dir))
    }

    @Test
    fun `readStamp returns the stamp written in the header`() {
        val dir = tmp.newFolder("map")
        PeakIndexStore.write(dir, stamp, peaks)
        assertEquals(stamp, PeakIndexStore.readStamp(dir))
    }

    @Test
    fun `readStamp is null when no index exists`() {
        assertNull(PeakIndexStore.readStamp(tmp.newFolder("empty")))
    }

    @Test
    fun `readAll is null for a file missing the magic header`() {
        val dir = tmp.newFolder("map")
        PeakIndexStore.indexFile(dir).writeText("not-a-peak-index\nfoo\tbar\t1\t2\n")
        assertNull(PeakIndexStore.readAll(dir))
        assertNull(PeakIndexStore.readStamp(dir))
    }

    @Test
    fun `write is atomic - no leftover temp file remains`() {
        val dir = tmp.newFolder("map")
        PeakIndexStore.write(dir, stamp, peaks)
        val leftovers = dir.listFiles()?.map { it.name }?.filter { it.endsWith(".tmp") } ?: emptyList()
        assertEquals(emptyList<String>(), leftovers)
    }

    @Test
    fun `names containing tabs are sanitized so columns stay aligned`() {
        val dir = tmp.newFolder("map")
        val messy = listOf(Peak(name = "a\tb\tc", eleMeters = 100, latitude = 23.0, longitude = 121.0))
        PeakIndexStore.write(dir, stamp, messy)
        val loaded = PeakIndexStore.readAll(dir)!!
        assertEquals(1, loaded.size)
        assertEquals("a b c", loaded[0].name)
        assertEquals(100, loaded[0].eleMeters)
    }
}
