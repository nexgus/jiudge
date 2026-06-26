package io.github.nexgus.jiudge.core.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToughPathDetectorTest {
    @Test
    fun `tough sac_scale values are tough`() {
        assertTrue(ToughPathDetector.isTough(mapOf("sac_scale" to "demanding_alpine_hiking")))
        assertTrue(ToughPathDetector.isTough(mapOf("sac_scale" to "difficult_alpine_hiking")))
    }

    @Test
    fun `easier sac_scale values are not tough`() {
        for (v in listOf("hiking", "mountain_hiking", "demanding_mountain_hiking", "alpine_hiking")) {
            assertFalse(v, ToughPathDetector.isTough(mapOf("sac_scale" to v)))
        }
    }

    @Test
    fun `poor trail_visibility is tough`() {
        for (v in listOf("bad", "horrible", "no")) {
            assertTrue(v, ToughPathDetector.isTough(mapOf("trail_visibility" to v)))
        }
    }

    @Test
    fun `good trail_visibility is not tough`() {
        for (v in listOf("excellent", "good", "intermediate")) {
            assertFalse(v, ToughPathDetector.isTough(mapOf("trail_visibility" to v)))
        }
    }

    @Test
    fun `either condition alone makes a way tough`() {
        // Poor visibility on an otherwise easy trail.
        assertTrue(
            ToughPathDetector.isTough(mapOf("sac_scale" to "hiking", "trail_visibility" to "no")),
        )
    }

    @Test
    fun `a plain trail with no difficulty tags is not tough`() {
        assertFalse(ToughPathDetector.isTough(mapOf("highway" to "path")))
        assertFalse(ToughPathDetector.isTough(emptyMap()))
    }
}
