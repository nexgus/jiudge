package io.github.nexgus.jiudge.data.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mapsforge.core.model.LatLong
import java.io.File

class RouteStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: RouteStore

    private fun setUpStore(): RouteStore {
        dir = tmp.newFolder("plans")
        store = RouteStore(plansDir = { dir })
        return store
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

    // Reproduces the pre-fix edit-then-save flow: re-saving with a fresh createdAt but no
    // [replacing] leaves both copies behind, so the picker listed the same name twice. Kept to
    // pin down that save() itself never deletes anything unless explicitly asked to.
    @Test
    fun `re-save without replacing leaves both copies on disk`() {
        setUpStore()
        val original = store.save(route("縱走", 1L), checkDuplicate = true)
        val edited = store.load(original).copy(createdAtEpochMs = 2L)

        store.save(edited, checkDuplicate = false)

        assertEquals(2, dir.listFiles()!!.size)
        assertEquals(listOf("縱走", "縱走"), store.list().map { it.name })
    }

    @Test
    fun `re-save with replacing removes the edit source`() {
        setUpStore()
        val original = store.save(route("縱走", 1L), checkDuplicate = true)
        val edited = store.load(original).copy(createdAtEpochMs = 2L)

        val saved = store.save(edited, checkDuplicate = false, replacing = original)

        assertFalse(original.exists())
        assertTrue(saved.exists())
        val listed = store.list().single()
        assertEquals("縱走", listed.name)
        assertEquals(2L, listed.createdAtEpochMs)
    }

    @Test
    fun `re-save with replacing under a new name removes the old file`() {
        setUpStore()
        val original = store.save(route("縱走", 1L), checkDuplicate = true)
        val renamed = store.load(original).copy(name = "橫斷", createdAtEpochMs = 2L)

        val saved = store.save(renamed, checkDuplicate = false, replacing = original)

        assertFalse(original.exists())
        assertTrue(saved.exists())
        assertEquals(listOf("橫斷"), store.list().map { it.name })
    }

    @Test
    fun `replacing the same path keeps the freshly written file`() {
        setUpStore()
        val original = store.save(route("縱走", 1L), checkDuplicate = true)
        val edited = store.load(original)

        val saved = store.save(edited, checkDuplicate = false, replacing = original)

        assertEquals(original, saved)
        assertTrue(saved.exists())
        assertEquals(listOf("縱走"), store.list().map { it.name })
    }

    @Test
    fun `re-save renamed onto another route's name is rejected`() {
        setUpStore()
        val original = store.save(route("縱走", 1L), checkDuplicate = true)
        val other = store.save(route("橫斷", 2L), checkDuplicate = true)
        val renamed = store.load(original).copy(name = "橫斷", createdAtEpochMs = 3L)

        assertThrows(DuplicateRouteNameException::class.java) {
            store.save(renamed, checkDuplicate = false, replacing = original)
        }
        // The guard runs before anything is written: both routes must survive untouched.
        assertTrue(original.exists())
        assertTrue(other.exists())
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `re-save renamed onto another route's padded name is rejected`() {
        setUpStore()
        val original = store.save(route("縱走", 1L), checkDuplicate = true)
        store.save(route("橫斷", 2L), checkDuplicate = true)
        val renamed = store.load(original).copy(name = " 橫斷 ", createdAtEpochMs = 3L)

        assertThrows(DuplicateRouteNameException::class.java) {
            store.save(renamed, checkDuplicate = false, replacing = original)
        }
    }

    @Test
    fun `first save rejects a duplicate name`() {
        setUpStore()
        store.save(route("縱走", 1L), checkDuplicate = true)

        assertThrows(DuplicateRouteNameException::class.java) {
            store.save(route("縱走", 2L), checkDuplicate = true)
        }
        assertEquals(1, dir.listFiles()!!.size)
    }
}
