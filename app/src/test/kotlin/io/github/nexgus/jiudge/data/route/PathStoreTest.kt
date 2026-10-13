package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.LatLong
import java.io.File
import java.io.IOException

/**
 * Covers the unified path store: saving / renaming / replacing for both kinds, the store-wide
 * duplicate-name rule, kind detection from records, version-1 header reading, the staging-file
 * lifecycle guarantees (the sweep must never delete the live session's staging file, and the append
 * layer must never silently recreate a deleted one), and the move out of the legacy `plans/` and
 * `tracks/` folders. Serialisation runs against the real org.json on the test classpath - the
 * android.jar stub would throw.
 */
class PathStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var paths: File
    private lateinit var plans: File
    private lateinit var tracks: File
    private lateinit var store: PathStore

    // The legacy folders are deliberately not created here: a test that needs one makes it, and
    // every other test doubles as a check that the store never brings them back.
    @Before
    fun setUpStore() {
        val root = tmp.newFolder("Jiudge")
        paths = File(root, "paths")
        plans = File(root, "plans")
        tracks = File(root, "tracks")
        store = PathStore(pathsDir = { paths.apply { mkdirs() } }, legacyDirs = { listOf(plans, tracks) })
    }

    private fun route(
        name: String,
        createdAtEpochMs: Long,
    ): PlannedRoute {
        val a = LatLong(24.1, 121.1)
        val b = LatLong(24.2, 121.2)
        return PlannedRoute(
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            waypoints = listOf(PlannedRoute.Waypoint(a), PlannedRoute.Waypoint(b)),
            segments = listOf(PlannedRoute.Segment(points = listOf(a, b))),
        )
    }

    private fun track(
        name: String,
        createdAtEpochMs: Long = 1_000_000L,
    ): RecordedTrack =
        RecordedTrack(
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            points =
                listOf(
                    RecordedTrack.Point(24.0, 121.0, 1_000_000L),
                    RecordedTrack.Point(24.001, 121.001, 1_010_000L),
                ),
        )

    // Writes [lines] as a JSONL file named [fileName] in [dir], creating the folder as needed.
    private fun traceFile(
        dir: File,
        fileName: String,
        vararg lines: String,
    ): File = File(dir.apply { mkdirs() }, fileName).apply { writeText(lines.joinToString(separator = "\n", postfix = "\n")) }

    private fun stagingFile(
        dir: File,
        epochMs: Long,
    ): File = traceFile(dir, ".recording-$epochMs.jsonl", """{"v":1,"type":"track","name":"t","createdAt":$epochMs,"app":"jiudge"}""")

    // A plan file exactly as a build before the folders were merged wrote it: version 1, with `type`.
    private fun legacyPlan(
        dir: File,
        name: String,
        createdAtEpochMs: Long,
    ): File =
        traceFile(
            dir,
            "$name-$createdAtEpochMs.jsonl",
            """{"v":1,"type":"plan","name":"$name","createdAt":$createdAtEpochMs,"app":"jiudge"}""",
            WPT_0,
            WPT_1,
            SEG_0,
        )

    // The track counterpart of [legacyPlan]: two fixes ten seconds apart, the first at the creation time.
    private fun legacyTrack(
        dir: File,
        name: String,
        createdAtEpochMs: Long,
    ): File =
        traceFile(
            dir,
            "$name-$createdAtEpochMs.jsonl",
            """{"v":1,"type":"track","name":"$name","createdAt":$createdAtEpochMs,"app":"jiudge"}""",
            """{"k":"pt","lat":24.0,"lon":121.0,"t":$createdAtEpochMs}""",
            """{"k":"pt","lat":24.001,"lon":121.001,"t":${createdAtEpochMs + 10_000L}}""",
        )

    private fun headerOf(file: File): JSONObject = JSONObject(file.readLines().first())

    // Saving never deletes anything unless a file to replace is named.
    @Test
    fun `save without replacing leaves the edit source on disk`() {
        val original = store.save(route("縱走", 1L))
        val edited = (store.load(original) as PlannedRoute).copy(name = "橫斷", createdAtEpochMs = 2L)

        store.save(edited)

        assertTrue(original.exists())
        assertEquals(2, paths.listFiles()!!.size)
        assertEquals(listOf("橫斷", "縱走"), store.list().map { it.name })
    }

    // Without the edit source to exclude, a re-save under its own name is just another duplicate.
    @Test
    fun `re-save under the same name without replacing is rejected`() {
        val original = store.save(route("縱走", 1L))
        val edited = (store.load(original) as PlannedRoute).copy(createdAtEpochMs = 2L)

        assertThrows(DuplicatePathNameException::class.java) { store.save(edited) }
        assertEquals(listOf(original), paths.listFiles()!!.toList())
    }

    @Test
    fun `re-save with replacing removes the edit source`() {
        val original = store.save(route("縱走", 1L))
        val edited = (store.load(original) as PlannedRoute).copy(createdAtEpochMs = 2L)

        val saved = store.save(edited, replacing = original)

        assertFalse(original.exists())
        assertTrue(saved.exists())
        val listed = store.list().single()
        assertEquals("縱走", listed.name)
        assertEquals(2L, listed.createdAtEpochMs)
    }

    @Test
    fun `re-save with replacing under a new name removes the old file`() {
        val original = store.save(route("縱走", 1L))
        val renamed = (store.load(original) as PlannedRoute).copy(name = "橫斷", createdAtEpochMs = 2L)

        val saved = store.save(renamed, replacing = original)

        assertFalse(original.exists())
        assertTrue(saved.exists())
        assertEquals(listOf("橫斷"), store.list().map { it.name })
    }

    @Test
    fun `replacing the same path keeps the freshly written file`() {
        val original = store.save(route("縱走", 1L))
        val edited = store.load(original) as PlannedRoute

        val saved = store.save(edited, replacing = original)

        assertEquals(original, saved)
        assertTrue(saved.exists())
        assertEquals(listOf("縱走"), store.list().map { it.name })
    }

    @Test
    fun `re-save renamed onto another route's name is rejected`() {
        val original = store.save(route("縱走", 1L))
        val other = store.save(route("橫斷", 2L))
        val renamed = (store.load(original) as PlannedRoute).copy(name = "橫斷", createdAtEpochMs = 3L)

        assertThrows(DuplicatePathNameException::class.java) {
            store.save(renamed, replacing = original)
        }
        // The guard runs before anything is written: both routes must survive untouched.
        assertTrue(original.exists())
        assertTrue(other.exists())
        assertEquals(2, paths.listFiles()!!.size)
    }

    @Test
    fun `re-save renamed onto another route's padded name is rejected`() {
        val original = store.save(route("縱走", 1L))
        store.save(route("橫斷", 2L))
        val renamed = (store.load(original) as PlannedRoute).copy(name = " 橫斷 ", createdAtEpochMs = 3L)

        assertThrows(DuplicatePathNameException::class.java) {
            store.save(renamed, replacing = original)
        }
    }

    @Test
    fun `first save rejects a duplicate name`() {
        store.save(route("縱走", 1L))

        assertThrows(DuplicatePathNameException::class.java) {
            store.save(route("縱走", 2L))
        }
        assertEquals(1, paths.listFiles()!!.size)
    }

    @Test
    fun `saving a track writes a published file that loads back identically`() {
        val file = store.save(track("Imported Hike"))

        assertTrue(file.exists())
        assertEquals(paths, file.parentFile)
        assertFalse(file.name.startsWith("."))
        val loaded = store.load(file) as RecordedTrack
        assertEquals("Imported Hike", loaded.name)
        assertEquals(2, loaded.points.size)
        assertEquals(1_000_000L, loaded.points[0].timeMs)
        assertEquals(listOf("Imported Hike"), store.list().map { it.name })
    }

    @Test
    fun `saving a track rejects a duplicate name`() {
        store.save(track("Same Name"))

        assertThrows(DuplicatePathNameException::class.java) {
            store.save(track("Same Name"))
        }
    }

    @Test
    fun `cleanup keeps the live staging file and removes the rest`() {
        val stale1 = stagingFile(paths, 1L)
        val stale2 = stagingFile(paths, 2L)
        val live = stagingFile(paths, 3L)

        store.cleanupStaleRecordings(keep = live)

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
        assertTrue(live.exists())
    }

    @Test
    fun `cleanup with no live session removes every staging file`() {
        val stale1 = stagingFile(paths, 1L)
        val stale2 = stagingFile(paths, 2L)

        store.cleanupStaleRecordings()

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
    }

    @Test
    fun `cleanup leaves published paths untouched`() {
        val published = File(paths.apply { mkdirs() }, "some_track-42.jsonl").apply { writeText("published\n") }
        stagingFile(paths, 1L)

        store.cleanupStaleRecordings()

        assertTrue(published.exists())
        assertEquals(listOf(published), paths.listFiles()!!.toList())
    }

    @Test
    fun `appendPoint throws instead of recreating a missing staging file`() {
        val missing = File(paths.apply { mkdirs() }, ".recording-99.jsonl")

        assertThrows(IOException::class.java) {
            store.appendPoint(missing, latitude = 25.0, longitude = 121.5, timeMs = 99L)
        }
        assertFalse(missing.exists())
    }

    @Test
    fun `kind is a plan when any seg record is present`() {
        assertEquals(PathKind.PLAN, PathKind.of(listOf(JSONObject(WPT_0), JSONObject(SEG_0))))
        // A seg record decides the kind even next to pt records.
        assertEquals(PathKind.PLAN, PathKind.of(listOf(JSONObject(PT), JSONObject(SEG_0))))
    }

    @Test
    fun `kind is a track when pt records are present and no seg record is`() {
        assertEquals(PathKind.TRACK, PathKind.of(listOf(JSONObject(PT))))
        assertEquals(PathKind.TRACK, PathKind.of(listOf(JSONObject(WPT_0), JSONObject(PT))))
    }

    @Test
    fun `records with neither seg nor pt have no kind`() {
        val unknown = JSONObject("""{"k":"photo","lat":24.1,"lon":121.1}""")

        assertNull(PathKind.of(emptyList()))
        assertNull(PathKind.of(listOf(JSONObject(WPT_0), unknown)))
    }

    @Test
    fun `list reports each path's kind and sorts both kinds newest-first`() {
        store.save(route("規劃", 2L))
        store.save(track("軌跡甲", createdAtEpochMs = 3L))
        store.save(track("軌跡乙", createdAtEpochMs = 1L))

        val listed = store.list()

        assertEquals(listOf("軌跡甲", "規劃", "軌跡乙"), listed.map { it.name })
        assertEquals(listOf(PathKind.TRACK, PathKind.PLAN, PathKind.TRACK), listed.map { it.kind })
    }

    @Test
    fun `a file with neither seg nor pt records is not listed and does not load`() {
        val headerOnly = traceFile(paths, "empty-1.jsonl", """{"v":2,"name":"empty","createdAt":1,"app":"jiudge"}""")
        val waypointsOnly = traceFile(paths, "dots-2.jsonl", """{"v":2,"name":"dots","createdAt":2,"app":"jiudge"}""", WPT_0)

        assertTrue(store.list().isEmpty())
        assertThrows(IllegalStateException::class.java) { store.load(headerOnly) }
        assertThrows(IllegalStateException::class.java) { store.load(waypointsOnly) }
        // Not being a path file never costs the file itself.
        assertTrue(headerOnly.exists())
        assertTrue(waypointsOnly.exists())
    }

    @Test
    fun `saving a path with nothing to draw is refused`() {
        val noSegments =
            PlannedRoute(
                name = "空規劃",
                createdAtEpochMs = 1L,
                waypoints = listOf(PlannedRoute.Waypoint(LatLong(24.1, 121.1))),
                segments = emptyList(),
            )
        val noPoints = RecordedTrack(name = "空軌跡", createdAtEpochMs = 2L, points = emptyList())

        assertThrows(IOException::class.java) { store.save(noSegments) }
        assertThrows(IOException::class.java) { store.save(noPoints) }
        assertTrue(paths.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a track may not take a plan's name`() {
        store.save(route("玉山", 1L))

        assertThrows(DuplicatePathNameException::class.java) { store.save(track("玉山")) }
        assertThrows(DuplicatePathNameException::class.java) { store.save(track(" 玉山 ")) }
        assertEquals(listOf(PathKind.PLAN), store.list().map { it.kind })
    }

    @Test
    fun `a plan may not take a track's name`() {
        store.save(track("玉山"))

        assertThrows(DuplicatePathNameException::class.java) { store.save(route("玉山", 1L)) }
        assertEquals(listOf(PathKind.TRACK), store.list().map { it.kind })
    }

    @Test
    fun `rename onto a name held by the other kind is rejected`() {
        val plan = store.save(route("玉山", 1L))
        val recorded = store.save(track("雪山"))

        val e = assertThrows(DuplicatePathNameException::class.java) { store.rename(recorded, "玉山") }
        assertEquals("玉山", e.pathName)
        assertThrows(DuplicatePathNameException::class.java) { store.rename(plan, "雪山") }
        assertEquals(setOf("玉山", "雪山"), store.list().map { it.name }.toSet())
    }

    @Test
    fun `a recording may not be finalised under a plan's name`() {
        store.save(route("玉山", 1L))
        val staging = store.startNewStaging(startEpochMs = 5L, displayName = "2026-10-13_080000")
        store.appendPoint(staging, latitude = 24.0, longitude = 121.0, timeMs = 5L)

        assertThrows(DuplicatePathNameException::class.java) { store.finalizeNew(staging, "玉山") }
        // The session must survive the rejection so the user can retry under another name.
        assertTrue(staging.exists())
    }

    @Test
    fun `rename keeps a path's own name, kind and contents`() {
        val recorded = store.save(track("雪山"))

        val same = store.rename(recorded, " 雪山 ")
        val renamed = store.rename(same.file, "雪山主東")

        assertEquals("雪山", same.name)
        assertEquals("雪山主東", renamed.name)
        assertEquals(PathKind.TRACK, renamed.kind)
        assertFalse(recorded.exists())
        assertEquals(listOf(renamed.file), paths.listFiles()!!.toList())
        assertEquals(track("雪山主東"), store.load(renamed.file))
    }

    @Test
    fun `a blank name always becomes the default name`() {
        val plan = store.save(route("  ", 1L))
        assertEquals("未命名路徑", store.load(plan).name)
        assertEquals("未命名路徑", store.rename(plan, "").name)
        store.rename(plan, "規劃")

        val recorded = store.save(track(""))
        assertEquals("未命名路徑", store.load(recorded).name)
        store.rename(recorded, "軌跡")

        val staging = store.startNewStaging(startEpochMs = 5L, displayName = "x")
        store.appendPoint(staging, latitude = 24.0, longitude = 121.0, timeMs = 5L)
        assertEquals("未命名路徑", store.load(store.finalizeNew(staging, "   ")).name)
    }

    @Test
    fun `a new header is version 2 and carries no type`() {
        val plan = headerOf(store.save(route("規劃", 1L)))
        val recorded = headerOf(store.save(track("軌跡")))
        val staging = headerOf(store.startNewStaging(startEpochMs = 5L, displayName = "錄製"))

        for (header in listOf(plan, recorded, staging)) {
            assertEquals(2, header.getInt("v"))
            assertFalse(header.has("type"))
            assertEquals("jiudge", header.getString("app"))
        }
        assertEquals("規劃", plan.getString("name"))
        assertEquals(1L, plan.getLong("createdAt"))
    }

    @Test
    fun `a version 1 file with a type field reads like any other`() {
        val plan = legacyPlan(paths, "舊規劃", 10L)
        val recorded = legacyTrack(paths, "舊軌跡", 20L)

        val listed = store.list()

        assertEquals(listOf("舊軌跡", "舊規劃"), listed.map { it.name })
        assertEquals(listOf(PathKind.TRACK, PathKind.PLAN), listed.map { it.kind })
        assertEquals(listOf(20L, 10L), listed.map { it.createdAtEpochMs })
        assertEquals(1, Trace.read(plan)!!.header.version)
        assertEquals(2, (store.load(plan) as PlannedRoute).waypoints.size)
        assertEquals(listOf(20L, 10_020L), store.load(recorded).pointTimesMs)
    }

    @Test
    fun `the type field of a version 1 header is ignored in favour of the records`() {
        // Labelled a track, but its records are a plan's.
        val mislabelled =
            traceFile(
                paths,
                "x-1.jsonl",
                """{"v":1,"type":"track","name":"x","createdAt":1,"app":"jiudge"}""",
                WPT_0,
                WPT_1,
                SEG_0,
            )

        assertEquals(PathKind.PLAN, store.list().single().kind)
        assertTrue(store.load(mislabelled) is PlannedRoute)
    }

    @Test
    fun `rewriting a version 1 file drops its type and stamps version 2`() {
        val plan = legacyPlan(paths, "舊規劃", 10L)
        val recorded = legacyTrack(paths, "舊軌跡", 20L)

        val renamedPlan = headerOf(store.rename(plan, "新規劃").file)
        val staging = store.startContinuationStaging(recorded)
        val staged = headerOf(staging)
        val continued = headerOf(store.finalizeContinuation(staging, recorded, "舊軌跡"))

        for (header in listOf(renamedPlan, staged, continued)) {
            assertEquals(2, header.getInt("v"))
            assertFalse(header.has("type"))
        }
        // Only the header layout is upgraded; the path keeps its creation time.
        assertEquals(10L, renamedPlan.getLong("createdAt"))
        assertEquals(20L, continued.getLong("createdAt"))
    }

    @Test
    fun `a new recording is staged in the path folder and stays off the list until finalised`() {
        val staging = store.startNewStaging(startEpochMs = 1_700_000_000_000L, displayName = "2026-10-13_080000")

        assertEquals(File(paths, ".recording-1700000000000.jsonl"), staging)
        store.appendPoint(staging, latitude = 24.0, longitude = 121.0, timeMs = 1_700_000_000_000L)
        store.appendPoint(staging, latitude = 24.001, longitude = 121.001, timeMs = 1_700_000_010_000L, src = "net")
        assertTrue(store.list().isEmpty())

        val published = store.finalizeNew(staging, "晨走")

        assertFalse(staging.exists())
        assertEquals(File(paths, "晨走-1700000000000.jsonl"), published)
        val listed = store.list().single()
        assertEquals("晨走", listed.name)
        assertEquals(PathKind.TRACK, listed.kind)
        val loaded = store.load(published) as RecordedTrack
        assertEquals(listOf(1_700_000_000_000L, 1_700_000_010_000L), loaded.pointTimesMs)
        assertEquals(listOf(null, "net"), loaded.points.map { it.src })
    }

    @Test
    fun `a recording with no point cannot be finalised`() {
        val staging = store.startNewStaging(startEpochMs = 5L, displayName = "x")

        assertThrows(IOException::class.java) { store.finalizeNew(staging, "空的") }
        assertTrue(staging.exists())
        assertEquals(listOf(staging), paths.listFiles()!!.toList())
    }

    @Test
    fun `a continuation may keep its own name and replaces the original`() {
        val original = store.save(track("縱走", createdAtEpochMs = 1_000_000L))

        val staging = store.startContinuationStaging(original)
        assertEquals(File(paths, ".recording-1000000.jsonl"), staging)
        store.appendPoint(staging, latitude = 24.002, longitude = 121.002, timeMs = 1_020_000L)
        // The original is untouched until the continuation is finalised.
        assertEquals(2, (store.load(original) as RecordedTrack).points.size)

        val published = store.finalizeContinuation(staging, original, "縱走")

        assertFalse(staging.exists())
        assertEquals(original, published)
        assertEquals(3, (store.load(published) as RecordedTrack).points.size)
        assertEquals(listOf("縱走"), store.list().map { it.name })
    }

    @Test
    fun `a renamed continuation removes the original file`() {
        val original = store.save(track("縱走"))
        val staging = store.startContinuationStaging(original)

        val published = store.finalizeContinuation(staging, original, "縱走全程")

        assertFalse(original.exists())
        assertEquals(listOf(published), paths.listFiles()!!.toList())
        assertEquals(listOf("縱走全程"), store.list().map { it.name })
    }

    @Test
    fun `a continuation may not take another path's name`() {
        val original = store.save(track("縱走"))
        store.save(route("橫斷", 2L))
        val staging = store.startContinuationStaging(original)

        assertThrows(DuplicatePathNameException::class.java) {
            store.finalizeContinuation(staging, original, "橫斷")
        }
        assertTrue(staging.exists())
        assertTrue(original.exists())
    }

    @Test
    fun `discarding a continuation keeps the original`() {
        val original = store.save(track("縱走"))
        val staging = store.startContinuationStaging(original)
        store.appendPoint(staging, latitude = 24.002, longitude = 121.002, timeMs = 1_020_000L)

        assertTrue(store.discardStaging(staging))

        assertFalse(staging.exists())
        assertEquals(2, (store.load(original) as RecordedTrack).points.size)
    }

    @Test
    fun `a plan cannot be continued as a recording`() {
        val plan = store.save(route("規劃", 1L))

        assertThrows(IllegalStateException::class.java) { store.startContinuationStaging(plan) }
        assertEquals(listOf(plan), paths.listFiles()!!.toList())
    }

    @Test
    fun `delete removes the file and reports a missing one`() {
        val plan = store.save(route("規劃", 1L))

        assertTrue(store.delete(plan))
        assertFalse(store.delete(plan))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `legacy files move into the path folder byte for byte under their own names`() {
        val plan = legacyPlan(plans, "舊規劃", 10L)
        val recorded = legacyTrack(tracks, "舊軌跡", 20L)
        val planBytes = plan.readBytes()
        val trackBytes = recorded.readBytes()

        val listed = store.list()

        assertFalse(plans.exists())
        assertFalse(tracks.exists())
        assertEquals(setOf("舊規劃-10.jsonl", "舊軌跡-20.jsonl"), paths.list()!!.toSet())
        assertArrayEquals(planBytes, File(paths, plan.name).readBytes())
        assertArrayEquals(trackBytes, File(paths, recorded.name).readBytes())
        // Moved files keep their name and date; neither is rewritten on the way.
        assertEquals(listOf("舊軌跡", "舊規劃"), listed.map { it.name })
        assertEquals(listOf(20L, 10L), listed.map { it.createdAtEpochMs })
        assertEquals(listOf(PathKind.TRACK, PathKind.PLAN), listed.map { it.kind })
        assertTrue(listed.all { it.file.parentFile == paths })
    }

    @Test
    fun `moved files are not renamed even when two end up with the same name`() {
        legacyPlan(plans, "玉山", 10L)
        legacyTrack(tracks, "玉山", 20L)

        val listed = store.list()

        assertEquals(listOf("玉山", "玉山"), listed.map { it.name })
        assertEquals(listOf(PathKind.TRACK, PathKind.PLAN), listed.map { it.kind })
        assertFalse(plans.exists())
        assertFalse(tracks.exists())
    }

    @Test
    fun `a legacy file whose name is taken stays put and nothing is overwritten`() {
        val existing = legacyTrack(paths, "同名", 10L)
        val existingBytes = existing.readBytes()
        val legacy = legacyPlan(plans, "同名", 10L)
        val legacyBytes = legacy.readBytes()
        assertEquals(existing.name, legacy.name)

        val listed = store.list()

        assertArrayEquals(existingBytes, existing.readBytes())
        assertTrue(legacy.exists())
        assertArrayEquals(legacyBytes, legacy.readBytes())
        assertEquals(listOf(existing.name), paths.list()!!.toList())
        // Both remain listed: the one left behind is still a path the user owns.
        assertEquals(setOf(existing, legacy), listed.map { it.file }.toSet())
        assertEquals(setOf(PathKind.TRACK, PathKind.PLAN), listed.map { it.kind }.toSet())
    }

    @Test
    fun `a file left in a legacy folder moves once its name is free`() {
        val existing = legacyTrack(paths, "同名", 10L)
        val legacy = legacyPlan(plans, "同名", 10L)
        store.list()
        assertTrue(legacy.exists())

        store.delete(existing)
        val listed = store.list().single()

        assertFalse(plans.exists())
        assertEquals(File(paths, legacy.name), listed.file)
        assertEquals(PathKind.PLAN, listed.kind)
    }

    @Test
    fun `staging remnants in a legacy folder are deleted, not moved`() {
        val remnant = stagingFile(tracks, 1L)
        val recorded = legacyTrack(tracks, "舊軌跡", 20L)

        store.cleanupStaleRecordings()

        assertFalse(remnant.exists())
        assertFalse(tracks.exists())
        assertEquals(listOf(recorded.name), paths.list()!!.toList())
    }

    @Test
    fun `the live staging file survives a pass that clears legacy remnants`() {
        val live = store.startNewStaging(startEpochMs = 5L, displayName = "x")
        stagingFile(tracks, 1L)

        store.cleanupStaleRecordings(keep = live)

        assertTrue(live.exists())
        assertFalse(tracks.exists())
    }

    @Test
    fun `an empty legacy folder is removed`() {
        plans.mkdirs()
        tracks.mkdirs()

        store.list()

        assertFalse(plans.exists())
        assertFalse(tracks.exists())
    }

    @Test
    fun `a legacy folder holding anything else is kept, contents untouched`() {
        legacyPlan(plans, "舊規劃", 10L)
        val note = File(plans, "notes.txt").apply { writeText("mine\n") }

        store.list()

        assertEquals(listOf(note), plans.listFiles()!!.toList())
        assertEquals("mine\n", note.readText())
        assertTrue(File(paths, "舊規劃-10.jsonl").exists())
    }

    @Test
    fun `a file that cannot be moved stays listed from its legacy folder`() {
        val legacy = legacyPlan(plans, "舊規劃", 10L)
        val legacyBytes = legacy.readBytes()
        // A path folder that cannot be created or written to, as when storage access is missing.
        val unusable = File(tmp.newFile("blocker"), "paths")
        val blocked = PathStore(pathsDir = { unusable }, legacyDirs = { listOf(plans, tracks) })

        val listed = blocked.list()

        assertTrue(legacy.exists())
        assertArrayEquals(legacyBytes, legacy.readBytes())
        assertEquals(listOf(legacy), listed.map { it.file })
        assertEquals("舊規劃", listed.single().name)
        // The retry on a later access, now that the path folder works, completes the move.
        assertEquals(listOf(File(paths, legacy.name)), store.list().map { it.file })
        assertFalse(plans.exists())
    }

    @Test
    fun `a leftover temporary file from an interrupted move does not block the next one`() {
        val legacy = legacyPlan(plans, "舊規劃", 10L)
        val legacyBytes = legacy.readBytes()
        File(paths.apply { mkdirs() }, "${legacy.name}.migrating").writeText("half a fi")

        store.list()

        assertEquals(listOf(legacy.name), paths.list()!!.toList())
        assertArrayEquals(legacyBytes, File(paths, legacy.name).readBytes())
        assertFalse(plans.exists())
    }

    @Test
    fun `a reference taken before the move still loads, renames and deletes the path`() {
        val plan = legacyPlan(plans, "舊規劃", 10L)
        val recorded = legacyTrack(tracks, "舊軌跡", 20L)

        // Every access migrates, so the first load already moves both files out from under the
        // references the caller is holding.
        assertEquals("舊規劃", store.load(plan).name)
        assertFalse(plan.exists())
        assertFalse(recorded.exists())

        assertEquals("舊軌跡改", store.rename(recorded, "舊軌跡改").name)
        assertTrue(store.delete(plan))
        assertEquals(listOf("舊軌跡改"), store.list().map { it.name })
    }

    @Test
    fun `a continuation started from a pre-move reference replaces the moved original`() {
        val recorded = legacyTrack(tracks, "舊軌跡", 20L)

        val staging = store.startContinuationStaging(recorded)
        store.appendPoint(staging, latitude = 24.002, longitude = 121.002, timeMs = 30_000L)
        val published = store.finalizeContinuation(staging, recorded, "舊軌跡")

        assertEquals(listOf(published), paths.listFiles()!!.toList())
        assertEquals(3, (store.load(published) as RecordedTrack).points.size)
        assertFalse(tracks.exists())
    }

    @Test
    fun `saving and recording never bring the legacy folders back`() {
        val plan = store.save(route("規劃", 1L))
        store.save(track("軌跡"))
        store.rename(plan, "規劃改")
        val staging = store.startNewStaging(startEpochMs = 5L, displayName = "x")
        store.appendPoint(staging, latitude = 24.0, longitude = 121.0, timeMs = 5L)
        store.finalizeNew(staging, "錄製")
        store.cleanupStaleRecordings()

        assertFalse(plans.exists())
        assertFalse(tracks.exists())
        assertEquals(3, store.list().size)
    }

    @Test
    fun `with no legacy folder the migration returns before touching the path folder`() {
        var pathsDirCalls = 0
        val counting =
            PathStore(
                pathsDir = {
                    pathsDirCalls++
                    paths
                },
                legacyDirs = { listOf(plans, tracks) },
            )

        counting.migrateLegacy()

        assertEquals(0, pathsDirCalls)
        assertFalse(paths.exists())
    }

    private companion object {
        const val WPT_0 = """{"k":"wpt","i":0,"lat":24.1,"lon":121.1}"""
        const val WPT_1 = """{"k":"wpt","i":1,"lat":24.2,"lon":121.2}"""
        const val SEG_0 = """{"k":"seg","i":0,"pts":[[24.1,121.1],[24.2,121.2]]}"""
        const val PT = """{"k":"pt","lat":24.0,"lon":121.0,"t":1}"""
    }
}
