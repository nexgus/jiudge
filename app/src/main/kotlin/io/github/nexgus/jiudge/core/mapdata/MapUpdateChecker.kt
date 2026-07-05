package io.github.nexgus.jiudge.core.mapdata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Answers "does a mirror hold newer map data than what is installed?" for every RudyMap asset.
 *
 * One HEAD per asset, tried down the mirror chain like the download does; the first mirror that
 * answers wins. The remote `Last-Modified` + size are compared against what [MapDataVersionStore]
 * recorded when the installed bytes were downloaded - `Last-Modified` is identical across the
 * mirrors (rsync preserves mtime), so any mirror's answer is authoritative. An installed asset
 * with no local record (installed before version recording existed) is reported as [Status.Unknown]
 * rather than guessed at; the UI offers a forced update for those.
 */
class MapUpdateChecker(
    private val catalog: MapDataCatalog,
    private val store: MapDataVersionStore,
    private val probe: RemoteProbe = HttpHeadProbe(),
) {
    /** What a HEAD against a mirror reported for one asset's file. */
    data class RemoteInfo(
        val lastModified: String?,
        val contentLength: Long,
    )

    /** Injectable HEAD transport so the comparison logic is unit-testable without a server. */
    fun interface RemoteProbe {
        @Throws(IOException::class)
        fun head(url: String): RemoteInfo
    }

    sealed interface Status {
        /** The mirror's file matches the recorded download; nothing to fetch. */
        data object UpToDate : Status

        /** The mirror holds a different (newer) file than the one installed. */
        data class UpdateAvailable(
            val remote: RemoteInfo,
        ) : Status

        /** Installed, but with no local version record - freshness cannot be determined. */
        data class Unknown(
            val remote: RemoteInfo,
        ) : Status

        /** Not on disk (the optional DEM); the update flow leaves it alone. */
        data object NotInstalled : Status
    }

    data class AssetCheck(
        val asset: MapDataAsset,
        val status: Status,
    )

    data class Result(
        val checks: List<AssetCheck>,
    ) {
        /** Assets a normal update run should fetch, in catalog (install) order. */
        val updatableIds: List<String> get() = checks.filter { it.status is Status.UpdateAvailable }.map { it.asset.id }

        /** Assets only a forced update fetches (installed but unverifiable), in install order. */
        val unknownIds: List<String> get() = checks.filter { it.status is Status.Unknown }.map { it.asset.id }
    }

    /**
     * HEADs every installed RudyMap asset. Throws [IOException] when an asset gets no answer from
     * any mirror (typically: offline) - partial answers are not useful for an update decision.
     */
    suspend fun check(): Result =
        withContext(Dispatchers.IO) {
            val records = store.readAll()
            Result(
                catalog.rudyMapAssets.map { asset ->
                    coroutineContext.ensureActive()
                    AssetCheck(asset, statusOf(asset, records[asset.id]))
                },
            )
        }

    private fun statusOf(
        asset: MapDataAsset,
        record: MapDataVersionStore.Record?,
    ): Status {
        if (!asset.isInstalled) return Status.NotInstalled
        val remote = headFirstReachable(asset.urls)
        return when {
            record == null -> Status.Unknown(remote)
            record.lastModified == remote.lastModified && record.sizeBytes == remote.contentLength ->
                Status.UpToDate
            else -> Status.UpdateAvailable(remote)
        }
    }

    private fun headFirstReachable(urls: List<String>): RemoteInfo {
        var lastError: IOException? = null
        for (url in urls) {
            try {
                return probe.head(url)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no mirror answered")
    }

    /** Real transport: one HEAD, redirects followed automatically (rudymap.tw 302s to a mirror). */
    class HttpHeadProbe(
        private val timeoutMs: Int = 15_000,
    ) : RemoteProbe {
        override fun head(url: String): RemoteInfo {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "HEAD"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${conn.responseCode} for $url")
                }
                return RemoteInfo(
                    lastModified = conn.getHeaderField("Last-Modified"),
                    contentLength = conn.contentLengthLong,
                )
            } finally {
                conn.disconnect()
            }
        }
    }
}
