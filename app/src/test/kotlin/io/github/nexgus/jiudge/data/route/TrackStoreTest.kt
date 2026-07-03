package io.github.nexgus.jiudge.data.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Covers the staging-file lifecycle guarantees that do not go through JSON serialisation (org.json
 * is an Android framework class, unavailable to plain JVM tests): the sweep must never delete the
 * live session's staging file, and the append layer must never silently recreate a deleted one.
 */
class TrackStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: TrackStore

    private fun setUpStore(): TrackStore {
        dir = tmp.newFolder("tracks")
        store = TrackStore(tracksDir = { dir })
        return store
    }

    private fun stagingFile(epochMs: Long): File =
        File(dir, ".recording-$epochMs.jsonl").apply {
            writeText("{\"v\":1,\"type\":\"track\",\"name\":\"t\",\"createdAt\":$epochMs,\"app\":\"jiudge\"}\n")
        }

    @Test
    fun `cleanup keeps the live staging file and removes the rest`() {
        setUpStore()
        val stale1 = stagingFile(1L)
        val stale2 = stagingFile(2L)
        val live = stagingFile(3L)

        store.cleanupStaleRecordings(keep = live)

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
        assertTrue(live.exists())
    }

    @Test
    fun `cleanup with no live session removes every staging file`() {
        setUpStore()
        val stale1 = stagingFile(1L)
        val stale2 = stagingFile(2L)

        store.cleanupStaleRecordings()

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
    }

    @Test
    fun `cleanup leaves published tracks untouched`() {
        setUpStore()
        val published = File(dir, "some_track-42.jsonl").apply { writeText("published\n") }
        stagingFile(1L)

        store.cleanupStaleRecordings()

        assertTrue(published.exists())
        assertEquals(listOf(published), dir.listFiles()!!.toList())
    }

    @Test
    fun `appendPoint throws instead of recreating a missing staging file`() {
        setUpStore()
        val missing = File(dir, ".recording-99.jsonl")

        assertThrows(IOException::class.java) {
            store.appendPoint(missing, latitude = 25.0, longitude = 121.5, timeMs = 99L)
        }
        assertFalse(missing.exists())
    }
}
