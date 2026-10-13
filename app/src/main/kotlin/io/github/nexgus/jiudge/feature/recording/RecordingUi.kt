package io.github.nexgus.jiudge.feature.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.feature.planning.MapPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom action bar for the 錄製中 layer of the recording state machine: "停止" pauses the session
 * (staging file and points are kept - see [Recorder.pause]) and "放棄" pauses the same way and then
 * opens the discard confirmation on top of the 已停止 layer, per spec B - so the confirmation always
 * faces a paused session and cancelling it leaves the user paused, one 繼續錄製 away from resuming.
 *
 * [statusText] is the first-point convergence readout (docs/gating.md §3.5): shown while the
 * session's first point is still a provisional, so the user knows why no track has appeared yet.
 */
@Composable
fun RecordingBottomBar(
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (statusText != null) {
            Text(statusText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
        }
        MapPill(text = "停止", onClick = onStop, primary = true)
        MapPill(text = "放棄", onClick = onDiscard)
    }
}

/**
 * Bottom action bar for the 已停止 layer: "儲存" opens the save dialog, "放棄" opens the discard
 * confirmation, "繼續錄製" resumes the same session (same staging file, new fixes appended after a
 * legitimate time gap - spec G). "儲存" is disabled for a brand-new recording with no points yet
 * (spec F); a continuation is never disabled here since it always carries at least the source
 * track's own points. The "已停止" text is the explicit status cue spec A calls for.
 */
@Composable
fun PausedBottomBar(
    canSave: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已停止", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 4.dp))
        MapPill(text = "儲存", onClick = onSave, primary = true, enabled = canSave)
        MapPill(text = "放棄", onClick = onDiscard)
        MapPill(text = "繼續錄製", onClick = onResume, primary = true)
    }
}

/**
 * Confirms throwing away an unsaved recording, reachable from both the 錄製中 and 已停止 layers
 * (spec B; the 錄製中 path pauses the session before this dialog opens, so it always faces a paused
 * session). The wording shifts by [continuationName]: a fresh recording is gone for good when
 * discarded; a continuation only loses its newly added segment, with the original track left intact.
 * "取消" simply closes this dialog, leaving the user on the 已停止 layer to 儲存 / 放棄 / 繼續錄製.
 */
@Composable
fun DiscardRecordingDialog(
    continuationName: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message =
        if (continuationName == null) {
            "確定不儲存本次的錄製? 此操作會丟棄目前錄製的軌跡, 無法復原."
        } else {
            "確定不儲存本次新增的延伸? 原本的軌跡 \"$continuationName\" 仍會保留, 但這次新加的點將被丟棄."
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("不儲存軌跡") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("丟棄") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Default save name for a brand-new recording: local-time stamp, e.g. `2026-06-29_074723`. */
fun defaultRecordingName(startEpochMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
    return fmt.format(Date(startEpochMs))
}

/**
 * Explains why background location is needed before triggering the system permission flow. Tapping
 * confirm hands off to the caller's [ActivityResultContracts.RequestPermission] launcher for
 * `ACCESS_BACKGROUND_LOCATION`: Android 10 surfaces an inline "Allow all the time" dialog, while
 * Android 11+ jumps the user to the App's location-permission page (a far shallower target than the
 * App-details page). The dialog is informational only - the launcher call lives in the caller.
 */
@Composable
fun BackgroundLocationRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要背景定位權限") },
        text = {
            Text(
                "為了在螢幕關閉或 App 進入背景時繼續錄製軌跡, 需要 \"一律允許\" 的定位權限. " +
                    "點選 \"繼續\" 後系統會跳出權限選擇, 請選擇 \"一律允許\". " +
                    "授權完成回到本 App 後會自動開始錄製.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("繼續") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Explains why the battery-optimization exemption matters before firing the system
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog. Unlike the background-location rationale,
 * this gate is advisory: recording starts either way (the caller proceeds both when the system
 * dialog returns and on 略過), because the only cost of refusing is possible fix loss once Doze
 * kicks in - screen off plus a long stationary stretch, such as an overnight camp. Shown on every
 * recording start while unexempted, with no "do not ask again" state: the exemption is a system
 * setting the user can flip back at any time, so remembering a past refusal could go stale.
 */
@Composable
fun BatteryExemptionRationaleDialog(
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("建議解除電池用量限制") },
        text = {
            Text(
                "螢幕關閉且手機長時間靜止時 (例如中途休息), 系統省電機制可能暫停錄製, 使軌跡出現缺口. " +
                    "點選 \"前往允許\" 後, 請在系統對話框選擇 \"允許\", 讓錄製不受省電限制. " +
                    "點選 \"略過\" 仍會開始錄製.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("前往允許") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("略過") } },
    )
}
