package io.github.nexgus.jiudge.feature.search

import io.github.nexgus.jiudge.core.index.Peak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakSearchTest {
    private fun peak(
        name: String,
        ele: Int? = null,
    ) = Peak(name = name, eleMeters = ele, latitude = 0.0, longitude = 0.0)

    private val peaks =
        listOf(
            peak("玉山, 3952m", 3952),
            peak("玉山前峰, 3239m", 3239),
            peak("南玉山, 3383m", 3383),
            peak("雪山主峰, 3886m", 3886),
        )

    @Test
    fun `blank query returns nothing`() {
        assertEquals(emptyList<Peak>(), PeakSearch.search(peaks, "   "))
    }

    @Test
    fun `substring matches anywhere in the name`() {
        val names = PeakSearch.search(peaks, "玉山").map { it.name }
        assertEquals(setOf("玉山, 3952m", "玉山前峰, 3239m", "南玉山, 3383m"), names.toSet())
    }

    @Test
    fun `prefix matches rank above interior matches`() {
        val results = PeakSearch.search(peaks, "玉山")
        // 南玉山 only matches in the interior, so it must sort after the two 玉山-prefixed peaks.
        assertEquals("南玉山, 3383m", results.last().name)
    }

    @Test
    fun `among equal-prefix matches the higher summit comes first`() {
        val results = PeakSearch.search(peaks, "玉山")
        assertEquals("玉山, 3952m", results.first().name)
        assertTrue(results.indexOf(results.first { it.name.startsWith("玉山前峰") }) > 0)
    }

    @Test
    fun `no match yields an empty list`() {
        assertEquals(emptyList<Peak>(), PeakSearch.search(peaks, "合歡"))
    }
}
