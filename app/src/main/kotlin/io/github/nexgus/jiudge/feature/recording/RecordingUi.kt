package io.github.nexgus.jiudge.feature.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.data.route.TrackStore
import io.github.nexgus.jiudge.feature.planning.MapPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom action bar for the 錄製中 layer of the recording state machine: "停止" pauses the session
 * (staging file and points are kept - see [Recorder.pause]) and "放棄" pauses the same way and then
 * opens the discard confirmation on top of the 已停止 layer, per spec B - so the confirmation always
 * faces a paused session and cancelling it leaves the user paused, one 繼續錄製 away from resuming.
 */
@Composable
fun RecordingBottomBar(
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
 * Bottom action bar for the history-track viewing sub-mode: continue recording on top of the loaded
 * track, or leave the sub-mode (the track stays on the map until the user explicitly clears it).
 * Both pills share a min-width so they read as a symmetric pair regardless of CJK character count -
 * width is set to comfortably fit "繼續錄製" (the wider label), and "離開" stretches up to match.
 */
@Composable
fun HistoryTrackViewControls(
    onContinue: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pillModifier = Modifier.widthIn(min = 110.dp)
        MapPill(text = "繼續錄製", onClick = onContinue, primary = true, modifier = pillModifier)
        MapPill(text = "離開", onClick = onLeave, modifier = pillModifier)
    }
}

/**
 * Recording entry chooser - matches the planning entry's shape so the user's mental model is the
 * same: a primary "new" path and a secondary "load saved" path, plus 取消. The load path opens the
 * saved track for browsing first (history-view sub-mode); "繼續錄製" lives inside that sub-mode, not
 * here, so the chooser stays a clean two-option fork.
 */
@Composable
fun RecordEntryChooser(
    onNew: () -> Unit,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("錄製軌跡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("新軌跡") }
                OutlinedButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text("載入已存軌跡") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/**
 * Prompts for a track name on stop (prefilled with [initialName]). Blank falls back to a default,
 * matching the planning save flow.
 */
@Composable
fun SaveTrackDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("儲存軌跡") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("軌跡名稱") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { "未命名軌跡" }) }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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

/** Track length for the picker: metres below 1 km, otherwise kilometres to one decimal. */
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) {
        String.format(Locale.getDefault(), "%.1f 公里", meters / 1000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f 公尺", meters)
    }

/**
 * Lists saved tracks for loading; tapping a row picks it. Each row's "更多" menu offers rename and
 * delete, delegated upward via [onRename]/[onDelete] (the caller confirms and persists). Mirrors
 * [io.github.nexgus.jiudge.feature.planning.LoadRouteDialog]. The dialog itself is purpose-neutral -
 * the caller decides what "pick" means (load into the history viewer); 繼續錄製 lives in the
 * history-view sub-mode after a pick, not on this dialog.
 */
@Composable
fun LoadTrackDialog(
    summaries: List<TrackStore.Summary>,
    onPick: (TrackStore.Summary) -> Unit,
    onRename: (TrackStore.Summary) -> Unit,
    onDelete: (TrackStore.Summary) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("載入軌跡") },
        text = {
            if (summaries.isEmpty()) {
                Text("尚無已儲存的軌跡")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(summaries) { s ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clickable { onPick(s) }
                                            .padding(vertical = 10.dp),
                                ) {
                                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${dateFormat.format(Date(s.createdAtEpochMs))} - ${formatDistance(s.distanceMeters)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TrackRowMenu(onRename = { onRename(s) }, onDelete = { onDelete(s) })
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

/** Per-row "更多" overflow menu offering rename and delete for a saved track. */
@Composable
private fun TrackRowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("更多") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("改名") },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("刪除") },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** Prompts for a new track name on rename (prefilled with [initialName]). Blank falls back to a default. */
@Composable
fun RenameTrackDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("軌跡改名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("軌跡名稱") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { "未命名軌跡" }) }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Confirms irreversible deletion of a saved track (the file lives in a public, uninstall-surviving folder). */
@Composable
fun DeleteTrackDialog(
    trackName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除軌跡") },
        text = { Text("確定要刪除 \"$trackName\" 嗎? 此操作無法復原.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("刪除") } },
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
