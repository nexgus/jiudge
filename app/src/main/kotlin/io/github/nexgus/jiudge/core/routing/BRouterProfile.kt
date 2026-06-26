package io.github.nexgus.jiudge.core.routing

import android.content.Context
import java.io.File

/**
 * Installs the app's bundled BRouter routing profiles into the location [BRouterEngine] reads.
 *
 * The profiles are part of the app - our own hiking rulesets (pure shortest distance, with the
 * strict one blocking RudyMap's "艱難路線" and the companion one allowing it per leg), not RudyMap
 * data we download - so they ship inside the APK under `assets/` (one `.brf` each) and are copied into
 * `<brouterDir>/profiles2/`. The copy runs on every launch: each is tiny (~3 KB) and overwriting
 * guarantees an APK update always carries its matching profiles, with no download and no stale copy.
 *
 * Note this is independent of the BRouter `.rd5` segments and `lookups.dat`, which are still
 * downloaded on first run (see `MapDataCatalog`).
 */
object BRouterProfile {
    private val PROFILES = listOf(BRouterEngine.DEFAULT_PROFILE, BRouterEngine.TOUGH_PROFILE)

    fun install(
        context: Context,
        brouterDir: File,
    ) {
        val profilesDir = File(brouterDir, "profiles2")
        profilesDir.mkdirs()
        for (profile in PROFILES) {
            val name = "$profile.brf"
            context.assets.open(name).use { input ->
                File(profilesDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
