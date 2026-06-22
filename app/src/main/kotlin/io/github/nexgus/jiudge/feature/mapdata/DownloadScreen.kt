package io.github.nexgus.jiudge.feature.mapdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.core.mapdata.DownloadState
import io.github.nexgus.jiudge.core.mapdata.InstallPhase

/**
 * First-run screen for downloading the offline map data. Stateless: it renders whatever
 * [DownloadState] it is given (intro, live progress, or a failure with retry) and reports the
 * user's intent through [onStart]/[onCancel] - the actual work runs in `DownloadService`.
 */
@Composable
fun DownloadScreen(
    state: DownloadState,
    totalBytes: Long,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "下載離線地圖資料",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        when (state) {
            is DownloadState.Running -> RunningBody(state, onCancel)
            is DownloadState.Failed -> FailedBody(state, onStart)
            else -> IdleBody(totalBytes, onStart)
        }
    }
}

private fun approxMb(bytes: Long): String = "約 ${bytes / 1_000_000} MB"

@Composable
private fun IdleBody(
    totalBytes: Long,
    onStart: () -> Unit,
) {
    Text(
        text =
            "首次使用需下載台灣登山地圖 (RudyMap Taiwan TOPO), 立體陰影與路徑規劃資料, 共 ${approxMb(totalBytes)}. " +
                "建議在 Wi-Fi 下進行; 下載可在背景或螢幕關閉時繼續.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onStart) {
        Text("開始下載 (${approxMb(totalBytes)})")
    }
}

@Composable
private fun RunningBody(
    state: DownloadState.Running,
    onCancel: () -> Unit,
) {
    LinearProgressIndicator(
        progress = { state.fraction },
        modifier = Modifier.fillMaxWidth(),
    )
    val verb = if (state.phase == InstallPhase.DOWNLOADING) "下載" else "安裝"
    Text(
        text = "$verb ${state.currentName}",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "${(state.fraction * 100).toInt()}%   (${state.assetsDone + 1}/${state.assetsTotal})",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onCancel) {
        Text("取消")
    }
}

@Composable
private fun FailedBody(
    state: DownloadState.Failed,
    onRetry: () -> Unit,
) {
    Text(
        text = "下載失敗: ${state.message}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "已下載的部分會保留, 重試會從中斷處續傳.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry) {
        Text("重試")
    }
}
