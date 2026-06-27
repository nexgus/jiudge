package io.github.nexgus.jiudge.core.mapdata

import io.github.nexgus.jiudge.core.storage.AppPaths
import java.io.File
import java.io.IOException

/** Where an asset is in its install: downloading bytes, unpacking, then publishing into place. */
enum class InstallPhase { DOWNLOADING, EXTRACTING, PUBLISHING }

/** Progress for one asset: [bytes] of [totalBytes] in the current [phase] ([totalBytes] <= 0 = unknown). */
data class InstallProgress(
    val phase: InstallPhase,
    val bytes: Long,
    val totalBytes: Long,
)

/**
 * Installs one [MapDataAsset] end to end: download (resumable, mirror fallback) -> for a zip,
 * extract the kept entries into a staging dir -> publish atomically into the live data dir. All
 * scratch work happens under [AppPaths.stagingDir]; the live `map/`//`brouter/` dirs only ever see
 * a finished asset, so an interrupted or cancelled run leaves no half-written data the app can read.
 *
 * Idempotent: an already-installed asset (its marker present) is skipped, so a resumed download run
 * simply continues with whatever is still missing.
 */
class MapDataInstaller(
    private val paths: AppPaths,
    private val downloader: Downloader = Downloader(),
) {
    suspend fun install(
        asset: MapDataAsset,
        onProgress: (InstallProgress) -> Unit,
    ) {
        if (asset.isInstalled) return
        paths.stagingDir.mkdirs()
        when (val plan = asset.install) {
            is InstallPlan.Raw -> installRaw(asset, plan, onProgress)
            is InstallPlan.Unzip -> installZip(asset, plan, onProgress)
        }
    }

    private suspend fun installRaw(
        asset: MapDataAsset,
        plan: InstallPlan.Raw,
        onProgress: (InstallProgress) -> Unit,
    ) {
        // Downloader writes a sibling .part and renames into place, so the file is atomic by itself.
        // A plan-supplied verifier (e.g. a `.rd5` sanity check) runs against the .part before the
        // rename, so a corrupt download never becomes the marker file the catalog treats as installed.
        downloader.download(
            urls = asset.urls,
            target = plan.destFile,
            progress = { done, total ->
                onProgress(InstallProgress(InstallPhase.DOWNLOADING, done, total))
            },
            verifier = plan.verify ?: Downloader.Verifier {},
        )
    }

    private suspend fun installZip(
        asset: MapDataAsset,
        plan: InstallPlan.Unzip,
        onProgress: (InstallProgress) -> Unit,
    ) {
        val zip = File(paths.stagingDir, "${asset.id}.zip")
        val stage = File(paths.stagingDir, "${asset.id}.tmp")
        try {
            downloader.download(
                urls = asset.urls,
                target = zip,
                progress = { done, total ->
                    onProgress(InstallProgress(InstallPhase.DOWNLOADING, done, total))
                },
            )
            if (stage.exists()) stage.deleteRecursively()
            ZipExtractor.extract(zip, stage, plan.keepEntry) { written ->
                onProgress(InstallProgress(InstallPhase.EXTRACTING, written, -1L))
            }
            onProgress(InstallProgress(InstallPhase.PUBLISHING, 0L, -1L))
            publish(stage, plan)
        } finally {
            zip.delete()
            stage.deleteRecursively()
        }
    }

    /** Moves the staged output into the live data dir, flipping the marker into place last. */
    private fun publish(
        stage: File,
        plan: InstallPlan.Unzip,
    ) {
        val destDir = plan.destDir
        if (plan.marker == destDir) {
            // The asset owns the whole dir (e.g. hgt/): one atomic rename of the staged tree.
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.parentFile?.mkdirs()
            if (!stage.renameTo(destDir)) throw IOException("cannot publish ${destDir.name}")
            return
        }
        // Shared dir (map/ holds several assets): move children in, the marker file last so a
        // partial move never looks installed.
        destDir.mkdirs()
        val children = stage.listFiles()?.toList() ?: emptyList()
        val markerName = plan.marker.name
        val ordered = children.filter { it.name != markerName } + children.filter { it.name == markerName }
        for (src in ordered) {
            val dst = File(destDir, src.name)
            if (dst.exists()) dst.deleteRecursively()
            if (!src.renameTo(dst)) throw IOException("cannot move ${src.name} into ${destDir.name}")
        }
    }
}
