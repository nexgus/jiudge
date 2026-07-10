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
 * Covers the staging-file lifecycle guarantees (the sweep must never delete the live session's
 * staging file, and the append layer must never silently recreate a deleted one) plus the direct
 * [TrackStore.save] path used by GPX track import (serialisation runs against the real org.json
 * on the test classpath - the android.jar stub would throw).
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

    private fun sampleTrack(name: String): RecordedTrack =
        RecordedTrack(
            name = name,
            createdAtEpochMs = 1_000_000L,
            points =
                listOf(
                    RecordedTrack.Point(24.0, 121.0, 1_000_000L),
                    RecordedTrack.Point(24.001, 121.001, 1_010_000L),
                ),
        )

    @Test
    fun `save writes a published file that loads back identically`() {
        setUpStore()

        val file = store.save(sampleTrack("Imported Hike"))

        assertTrue(file.exists())
        assertFalse(file.name.startsWith("."))
        val loaded = store.load(file)
        assertEquals("Imported Hike", loaded.name)
        assertEquals(2, loaded.points.size)
        assertEquals(1_000_000L, loaded.points[0].timeMs)
        assertEquals(listOf(store.list().single().name), listOf("Imported Hike"))
    }

    @Test
    fun `save rejects a duplicate name`() {
        setUpStore()
        store.save(sampleTrack("Same Name"))

        assertThrows(DuplicateTrackNameException::class.java) {
            store.save(sampleTrack("Same Name"))
        }
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
