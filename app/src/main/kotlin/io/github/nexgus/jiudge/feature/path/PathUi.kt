package io.github.nexgus.jiudge.feature.path

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.data.route.PathStore
import io.github.nexgus.jiudge.feature.planning.MapPill

/**
 * Entry controls of the plain map state: record (a new track, or onto an opened one), plan (a new
 * route, or edit an opened one), open the file manager, or take every opened path off the map.
 * "錄製" stays in place but is disabled while location permission is not held - recording is
 * meaningless without a fix, and the permission is picked up through the location banner or the
 * recenter button rather than from here. "清除" is omitted, not disabled, while no path is opened.
 */
@Composable
fun MapEntryControls(
    canRecord: Boolean,
    canClear: Boolean,
    onRecord: () -> Unit,
    onPlan: () -> Unit,
    onFiles: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapPill("錄製", onRecord, primary = true, enabled = canRecord)
        MapPill("規劃", onPlan, primary = true)
        MapPill("檔案", onFiles)
        if (canClear) {
            MapPill("清除", onClear)
        }
    }
}

/**
 * The map's distance-marker switch: a small round button that shows or hides the kilometre labels
 * on every path. [on] paints it in the theme's primary colour, as the identify toggle is when active.
 */
@Composable
fun DistanceMarkerSwitch(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PathSwitchButton(Icons.Filled.Straighten, "里程標記", on, onClick, modifier)
}

/**
 * The map's slope switch: a small round button that turns slope colouring on or off for every path.
 * [on] paints it in the theme's primary colour, as the identify toggle is when active.
 */
@Composable
fun SlopeSwitch(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PathSwitchButton(Icons.Filled.Terrain, "坡度顯示", on, onClick, modifier)
}

/** Shared look of the two map switches: the small FAB size of every other map control. */
@Composable
private fun PathSwitchButton(
    icon: ImageVector,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor =
            if (on) {
                MaterialTheme.colorScheme.primary
            } else {
                FloatingActionButtonDefaults.containerColor
            },
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * The chooser behind 規劃 and 錄製 while the map already shows paths of their kind: one row per
 * path in [paths], in the given order and labelled by [pathLabel], then a last row [newLabel] for
 * starting afresh. Tapping a row acts right away and is the only way to confirm; back or a tap
 * outside dismisses it. The rows scroll when they outgrow the dialog.
 */
@Composable
fun <T> OpenedPathChooserDialog(
    title: String,
    paths: List<T>,
    pathLabel: (T) -> String,
    newLabel: String,
    onPickPath: (T) -> Unit,
    onPickNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                paths.forEach { path ->
                    ChooserRow(pathLabel(path)) { onPickPath(path) }
                    HorizontalDivider()
                }
                ChooserRow(newLabel, onPickNew)
            }
        },
        confirmButton = {},
    )
}

/** One tappable full-width row of [OpenedPathChooserDialog]. */
@Composable
private fun ChooserRow(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
    )
}

/**
 * The single-field naming dialog behind every flow that gives a path a name: one text field
 * prefilled with [initialName]; confirming trims the input and falls back to
 * [PathStore.DEFAULT_NAME] when blank. A changed [initialName] replaces the field's text, so a
 * dialog reopened after a rejected name shows the name it is handed.
 */
@Composable
private fun PathNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("路徑名稱") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { PathStore.DEFAULT_NAME }) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Asks for the name a plan or a recording is saved under. */
@Composable
fun SavePathDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PathNameDialog(
        title = "儲存路徑",
        confirmLabel = "儲存",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Asks for a saved path's new name. */
@Composable
fun RenamePathDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PathNameDialog(
        title = "路徑改名",
        confirmLabel = "確定",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Asks for the name an imported GPX file is saved under. The name is all it asks: whether the file
 * becomes a track or a planned route follows from its contents.
 */
@Composable
fun ImportPathDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PathNameDialog(
        title = "匯入 GPX",
        confirmLabel = "匯入",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Asks for the name of the planned route a track is copied as. */
@Composable
fun CopyAsPlanDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PathNameDialog(
        title = "複製為規劃路徑",
        confirmLabel = "複製",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Confirms irreversible deletion of a saved path (the file lives in a public, uninstall-surviving
 * folder). [isOpened] adds a line telling the user the path is on the map and goes away with it.
 */
@Composable
fun DeletePathDialog(
    pathName: String,
    isOpened: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message =
        if (isOpened) {
            "確定要刪除 \"$pathName\" 嗎? 此路徑會一併自地圖移除. 此操作無法復原."
        } else {
            "確定要刪除 \"$pathName\" 嗎? 此操作無法復原."
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除路徑") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("刪除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
