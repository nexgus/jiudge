package io.github.nexgus.jiudge.core.mapdata

import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.storage.AppPaths
import io.github.nexgus.jiudge.feature.map.RudyMapView
import java.io.File

/**
 * The fixed set of map-data assets the app downloads on first run, and where each lands on disk
 * (via [AppPaths]). Filenames/dirs are taken from [RudyMapView]/[BRouterEngine] so the catalog and
 * the consumers agree on exactly one layout.
 *
 * RudyMap mirror paths differ per host (kcwu serves from the root, happyman from `/rudy/`,
 * rudymap.tw from `/v1/` with a redirect); each asset therefore lists full candidate URLs tried in
 * order. There is no published checksum, so integrity rests on the HTTP byte count plus a clean
 * unzip (see [Downloader]/[ZipExtractor]). Sizes are the *download* (compressed) bytes, approximate
 * and only used for the UI estimate - the real length comes from each response.
 */
class MapDataCatalog(
    paths: AppPaths,
) {
    private val mapDir = paths.mapDir
    private val brouterDir = paths.brouterDir

    val assets: List<MapDataAsset> =
        buildList {
            add(
                MapDataAsset(
                    id = "basemap",
                    displayName = "底圖 (Taiwan TOPO)",
                    urls = rudyUrls("MOI_OSM_Taiwan_TOPO_Rudy.map.zip"),
                    approxSizeBytes = 354_528_540L,
                    optional = false,
                    // The zip also holds a 236 MB `.poi` the renderer never uses; keep only the `.map`.
                    install =
                        InstallPlan.Unzip(
                            destDir = mapDir,
                            keepEntry = { it == RudyMapView.BASEMAP_NAME },
                            marker = File(mapDir, RudyMapView.BASEMAP_NAME),
                        ),
                ),
            )
            add(
                MapDataAsset(
                    id = "theme",
                    displayName = "圖徵樣式",
                    urls = rudyUrls("MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip"),
                    approxSizeBytes = 1_700_270L,
                    optional = false,
                    // Small bundle (xml + moiosmhs_res/ symbols + a few docs); take all of it.
                    install =
                        InstallPlan.Unzip(
                            destDir = mapDir,
                            keepEntry = { true },
                            marker = File(mapDir, RudyMapView.THEME_NAME),
                        ),
                ),
            )
            add(
                MapDataAsset(
                    id = "dem",
                    displayName = "立體陰影 DEM (30 m)",
                    urls = rudyUrls("hgtmix.zip"),
                    approxSizeBytes = 45_727_094L,
                    optional = true,
                    // Zip holds bare `N**E***.hgt` tiles; extract them into the renderer's hgt/ folder.
                    // marker == destDir -> the installer publishes via one atomic dir rename.
                    install =
                        InstallPlan.Unzip(
                            destDir = File(mapDir, RudyMapView.DEM_DIR),
                            keepEntry = { it.endsWith(".hgt") },
                            marker = File(mapDir, RudyMapView.DEM_DIR),
                        ),
                ),
            )
            addAll(brouterAssets())
        }

    val required: List<MapDataAsset> get() = assets.filterNot { it.optional }
    val optional: List<MapDataAsset> get() = assets.filter { it.optional }

    /** Approximate total download size of every asset, for the first-run UI estimate. */
    val totalDownloadBytes: Long get() = assets.sumOf { it.approxSizeBytes }

    /** Assets whose output is not yet on disk (what a download run still needs to fetch). */
    fun missing(includeOptional: Boolean = true): List<MapDataAsset> =
        assets.filter { (includeOptional || !it.optional) && !it.isInstalled }

    private fun brouterAssets(): List<MapDataAsset> {
        val segments = File(brouterDir, "segments4")
        val profiles = File(brouterDir, "profiles2")
        return listOf(
            brouterSegment("E120_N20.rd5", 21_872_135L, segments),
            brouterSegment("E120_N25.rd5", 12_362_502L, segments),
            MapDataAsset(
                id = "brouter-profile",
                displayName = "路徑規劃設定",
                // Official generic trekking profile, installed under the name BRouterEngine expects.
                // A hiking-tuned profile (poutnik) can replace it later without touching the path.
                urls = listOf("https://raw.githubusercontent.com/abrensch/brouter/master/misc/profiles2/trekking.brf"),
                approxSizeBytes = 17_852L,
                optional = false,
                install = InstallPlan.Raw(File(profiles, "${BRouterEngine.DEFAULT_PROFILE}.brf")),
            ),
            MapDataAsset(
                id = "brouter-lookups",
                displayName = "路徑規劃標籤表",
                // lookups.dat must match the brouter-core version (v1.7.9); pin the tag accordingly.
                urls = listOf("https://raw.githubusercontent.com/abrensch/brouter/v1.7.9/misc/profiles2/lookups.dat"),
                approxSizeBytes = 30_000L,
                optional = false,
                install = InstallPlan.Raw(File(profiles, "lookups.dat")),
            ),
        )
    }

    private fun brouterSegment(
        name: String,
        approxSize: Long,
        segmentsDir: File,
    ) = MapDataAsset(
        id = "brouter-$name",
        displayName = "路網 $name",
        urls = listOf("https://brouter.de/brouter/segments4/$name"),
        approxSizeBytes = approxSize,
        optional = false,
        install = InstallPlan.Raw(File(segmentsDir, name)),
    )

    private companion object {
        // RudyMap mirrors, tried in order. kcwu is direct (no /v1/, no redirect, Accept-Ranges);
        // the others are fallbacks. Each asset URL is base + the shared filename.
        val RUDY_MIRRORS =
            listOf(
                "https://moi.kcwu.csie.org/",
                "https://map.happyman.idv.tw/rudy/",
                "https://rudymap.tw/v1/",
            )

        fun rudyUrls(fileName: String): List<String> = RUDY_MIRRORS.map { it + fileName }
    }
}
