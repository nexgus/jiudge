package io.github.nexgus.jiudge.feature.mapdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.core.mapdata.MapUpdateChecker
import io.github.nexgus.jiudge.core.mapdata.MapUpdateState
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The "地圖更新" dialog: checks the RudyMap mirrors as soon as it opens, lists what each installed
 * asset's check found, and offers one update action. The download itself runs in `DownloadService`
 * (update mode) with progress on the map-screen banner, so the dialog closes when the update
 * starts; [updateState] keeps a reopened dialog honest while a run is active or awaiting apply.
 */
@Composable
fun MapUpdateDialog(
    installedVersion: String?,
    checker: MapUpdateChecker,
    stagingDir: File,
    updateState: MapUpdateState,
    onStartUpdate: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var check by remember { mutableStateOf<CheckUiState>(CheckUiState.Checking) }
    var attempt by remember { mutableIntStateOf(0) }
    var spaceError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt) {
        check = CheckUiState.Checking
        check =
            try {
                CheckUiState.Ready(checker.check())
            } catch (e: IOException) {
                CheckUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
    }

    // Unverifiable assets ride along with a normal update, so one run leaves every asset with a
    // fresh version record; when nothing is verifiable (legacy install) the same list is offered
    // as a forced update.
    val ready = check as? CheckUiState.Ready
    val updateIds = ready?.result?.let { it.updatableIds + it.unknownIds }.orEmpty()
    val busy = updateState !is MapUpdateState.Idle

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("地圖更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "目前圖資版本: ${installedVersion?.let { "v$it" } ?: "無法讀取"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    updateState is MapUpdateState.Running ->
                        Text("更新進行中, 進度顯示於地圖上方橫幅.", style = MaterialTheme.typography.bodyMedium)
                    updateState is MapUpdateState.Done ->
                        Text("新圖資已下載完成, 請點選地圖上方橫幅套用.", style = MaterialTheme.typography.bodyMedium)
                    check is CheckUiState.Checking ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在檢查更新...", style = MaterialTheme.typography.bodyMedium)
                        }
                    check is CheckUiState.Failed ->
                        Text(
                            text = "檢查失敗: ${(check as CheckUiState.Failed).message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    ready != null -> {
                        for (assetCheck in ready.result.checks) {
                            Text(
                                text = "${assetCheck.asset.displayName}: ${statusLabel(assetCheck.status)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (updateIds.isEmpty()) {
                            Text(
                                text = "所有圖資皆為最新版本.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        spaceError?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                busy -> Unit
                check is CheckUiState.Failed ->
                    TextButton(onClick = { attempt++ }) { Text("重試") }
                ready != null && updateIds.isNotEmpty() -> {
                    val label = if (ready.result.updatableIds.isNotEmpty()) "更新" else "強制更新"
                    val downloadBytes = downloadEstimate(ready.result, updateIds)
                    TextButton(
                        onClick = {
                            // Peak transient usage is roughly 3x the download: the zip, its
                            // extracted content in staging, and the old files' deleted-but-open
                            // inodes all coexist until the update is applied. mkdirs() first:
                            // usableSpace is 0 (read as "unknown", skipping the check) until the
                            // directory exists.
                            val needed = downloadBytes * 3
                            stagingDir.mkdirs()
                            if (stagingDir.usableSpace in 1 until needed) {
                                spaceError =
                                    "儲存空間不足: 更新過程約需 ${approxMb(needed)}, " +
                                    "目前僅剩 ${approxMb(stagingDir.usableSpace)}."
                            } else {
                                onStartUpdate(updateIds)
                            }
                        },
                    ) { Text("$label (${approxMb(downloadBytes)})") }
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
    )
}

private sealed interface CheckUiState {
    data object Checking : CheckUiState

    data class Failed(
        val message: String,
    ) : CheckUiState

    data class Ready(
        val result: MapUpdateChecker.Result,
    ) : CheckUiState
}

private fun statusLabel(status: MapUpdateChecker.Status): String =
    when (status) {
        MapUpdateChecker.Status.UpToDate -> "已是最新"
        is MapUpdateChecker.Status.UpdateAvailable -> "有新版${remoteDate(status.remote)?.let { " ($it)" } ?: ""}"
        is MapUpdateChecker.Status.Unknown -> "無法確認版本"
        MapUpdateChecker.Status.NotInstalled -> "未安裝, 不更新"
    }

/** The mirror's RFC-1123 `Last-Modified` as a local date, or null when absent/unparsable. */
private fun remoteDate(remote: MapUpdateChecker.RemoteInfo): String? =
    remote.lastModified?.let { lm ->
        runCatching {
            ZonedDateTime
                .parse(lm, DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }.getOrNull()
    }

/** Sum of the selected assets' remote sizes, falling back to the catalog estimate when unknown. */
private fun downloadEstimate(
    result: MapUpdateChecker.Result,
    ids: List<String>,
): Long =
    result.checks
        .filter { it.asset.id in ids }
        .sumOf { assetCheck ->
            val remote =
                when (val s = assetCheck.status) {
                    is MapUpdateChecker.Status.UpdateAvailable -> s.remote.contentLength
                    is MapUpdateChecker.Status.Unknown -> s.remote.contentLength
                    else -> -1L
                }
            if (remote > 0) remote else assetCheck.asset.approxSizeBytes
        }

private fun approxMb(bytes: Long): String = "約 ${bytes / 1_000_000} MB"
