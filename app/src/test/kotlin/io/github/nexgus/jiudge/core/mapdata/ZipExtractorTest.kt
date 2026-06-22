package io.github.nexgus.jiudge.core.mapdata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExtractorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipWith(entries: List<Pair<String, String>>): File {
        val zip = tmp.newFile("in.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `extracts only kept entries, including nested ones`() =
        runBlocking {
            val zip = zipWith(listOf("keep.txt" to "A", "skip.poi" to "B", "sub/deep.txt" to "C"))
            val dest = tmp.newFolder("out")
            ZipExtractor.extract(zip, dest, keep = { !it.endsWith(".poi") })
            assertEquals("A", File(dest, "keep.txt").readText())
            assertEquals("C", File(dest, "sub/deep.txt").readText())
            assertFalse(File(dest, "skip.poi").exists())
        }

    @Test
    fun `reports cumulative uncompressed bytes`() =
        runBlocking {
            val zip = zipWith(listOf("a.txt" to "12345"))
            val dest = tmp.newFolder("out")
            var last = 0L
            ZipExtractor.extract(zip, dest, keep = { true }) { written -> last = written }
            assertEquals(5L, last)
        }

    @Test(expected = IOException::class)
    fun `rejects entries that escape the target dir`(): Unit =
        runBlocking {
            val zip = zipWith(listOf("../evil.txt" to "X"))
            val dest = tmp.newFolder("out")
            ZipExtractor.extract(zip, dest, keep = { true })
        }
}
