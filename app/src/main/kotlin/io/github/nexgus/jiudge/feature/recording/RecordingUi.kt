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
 * Bottom action bar for an active recording session. Currently exposes only "停止"; manual pause is
 * intentionally absent (gui-redesign §5.3) since the statistics page subtracts rest periods via
 * automatic stationary-segment detection. The "統計" entry called for in §5.3 is deferred until the
 * statistics screen (§10.2) is built.
 */
@Composable
fun RecordingBottomBar(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapPill(text = "停止", onClick = onStop, primary = true)
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
 * Confirms throwing away an unsaved recording. The wording shifts by [continuationName]: a fresh
 * recording is gone for good when discarded; a continuation only loses its newly added segment,
 * with the original track left intact. Saying so explicitly keeps "取消" from feeling destructive.
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("回到儲存") } },
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
