package io.github.nexgus.jiudge.core.mapdata

import io.github.nexgus.jiudge.feature.map.RudyMapView
import java.io.File

/**
 * Reads the installed map-data version from the on-disk render theme. RudyMap stamps the release
 * date as a `vYYYY.MM.DD` token inside `MOI_OSM.xml` (e.g. `<name ... value="Hiking v2026.06.18"/>`)
 * - the same token `scripts/gen_symbols.py` parses - so this reports the version of the *installed*
 * theme, not the table bundled in the APK. Fully offline: no network, no extra metadata file.
 */
object MapVersion {
    private val VERSION = Regex("""v(\d{4}\.\d{2}\.\d{2})""")

    // The token sits in the theme's <name> block near the top; cap the scan so a theme that somehow
    // lacks it never costs a full read of the multi-thousand-line file.
    private const val MAX_LINES_SCANNED = 2_000

    /** The installed map version (e.g. `2026.06.18`), or null when the theme is absent or unstamped. */
    fun installed(mapDir: File): String? {
        val theme = File(mapDir, RudyMapView.THEME_NAME)
        if (!theme.isFile) return null
        return runCatching {
            theme.useLines { lines ->
                lines
                    .take(MAX_LINES_SCANNED)
                    .firstNotNullOfOrNull { VERSION.find(it)?.groupValues?.get(1) }
            }
        }.getOrNull()
    }
}
