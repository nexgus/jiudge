package io.github.nexgus.jiudge.feature.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crosshair drawn at the exact screen center - the point "+" turns into a waypoint. A white halo
 * under a dark stroke keeps it legible over both light terrain and dark hillshade.
 */
@Composable
fun CrosshairOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = 34.dp.toPx()
        val gap = 6.dp.toPx()
        val dark = 2.dp.toPx()
        val halo = 4.dp.toPx()
        val dotR = 3.dp.toPx()

        fun cross(
            color: Color,
            width: Float,
        ) {
            drawLine(color, Offset(cx - arm, cy), Offset(cx - gap, cy), width)
            drawLine(color, Offset(cx + gap, cy), Offset(cx + arm, cy), width)
            drawLine(color, Offset(cx, cy - arm), Offset(cx, cy - gap), width)
            drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + arm), width)
            drawCircle(color, radius = dotR, center = Offset(cx, cy), style = Stroke(width = width))
        }
        cross(Color.White, halo)
        cross(Color(0xFF202124), dark)
    }
}

/**
 * Map-overlay action button: an opaque rounded pill that floats legibly over the coloured map
 * without a shared backdrop. Primary actions get the filled accent fill; secondary actions get a
 * white pill with accent text (grey text when disabled). The small shadow lifts each pill clear of
 * busy terrain. Callers hide unavailable actions by not emitting them, rather than showing them
 * disabled - except the editing panel, which keeps its buttons in place and disables them so the
 * row does not jump as the waypoint count changes.
 */
@Composable
internal fun MapPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val colors =
        if (primary) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = Color.White,
                disabledContentColor = Color(0xFF9E9E9E),
            )
        }
    Button(
        onClick = onClick,
        // Drop the Material3 minWidth (58 dp) so single-character pills like "+"/"−" do not get
        // padded out, and tight the horizontal contentPadding from the default 24 dp to 12 dp so the
        // pill row stays narrow on small screens (it otherwise overruns the bottom-end FAB column).
        modifier = modifier.defaultMinSize(minWidth = 0.dp),
        enabled = enabled,
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = fontSize)
    }
}

/**
 * Bottom action bar for planning mode: add a waypoint at the crosshair, drop the last one, save,
 * or cancel. Save is disabled until there is a routed path (>= 2 waypoints); "-" is disabled when
 * there is nothing to remove or the leg it would delete is imported GPX geometry ([canRemove], see
 * [RoutePlanner.canRemoveLast]); while a route is computing the add/remove/save actions are disabled
 * and a spinner shows. The buttons disable in place (rather than hide) so the row keeps a stable
 * width as the waypoint count changes.
 */
@Composable
fun PlanningBottomBar(
    waypointCount: Int,
    canRemove: Boolean,
    busy: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapPill("+", onAdd, primary = true, enabled = !busy, fontSize = 20.sp)
        MapPill("−", onRemove, primary = true, enabled = !busy && canRemove, fontSize = 20.sp)
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp))
        }
        MapPill("儲存", onSave, enabled = !busy && waypointCount >= 2)
        MapPill("取消", onCancel, enabled = !busy)
    }
}

/**
 * Map-view mode controls: open a recording session, open the planning entry, or clear the displayed
 * overlays. "錄製軌跡" stays in place but is disabled when location permission is not yet held - the
 * recording feature is meaningless without a fix, and the user picks the permission up via the
 * existing "我位置" entry rather than from a disabled pill. "清除軌跡/路徑" stands in for the eventual
 * "清除圖層" dialog (gui-redesign §5.5); it sweeps both the planned-route overlay and the loaded
 * history-track overlay in one tap, and hides itself when nothing is on the map.
 */
@Composable
fun MapViewControls(
    canRecord: Boolean,
    canClear: Boolean,
    onRecord: () -> Unit,
    onPlan: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapPill("錄製軌跡", onRecord, primary = true, enabled = canRecord)
        MapPill("規劃路徑", onPlan, primary = true)
        // Nothing to clear yet -> omit the button entirely instead of disabling it.
        if (canClear) {
            MapPill("清除軌跡/路徑", onClear)
        }
    }
}

/**
 * Controls shown after jumping to a searched peak: confirm the target (ending the search) or go back
 * to the result list to pick another. These take over the bottom-start row while a search target is
 * pending, in place of the mode-specific controls.
 */
@Composable
fun SearchTargetControls(
    onConfirm: () -> Unit,
    onBackToResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapPill("確認", onConfirm, primary = true)
        MapPill("回到搜尋結果", onBackToResults)
    }
}

/**
 * Route-view mode controls: re-enter editing, export the viewed route as GPX, or leave to map-view
 * (the route stays on the map). Editing is where "+"/"-"/儲存 live. [onExport] is null when the
 * viewed route has no backing file (e.g. it was just deleted from the load picker) - the pill is
 * omitted rather than disabled.
 */
@Composable
fun RouteViewControls(
    onEdit: () -> Unit,
    onExport: (() -> Unit)?,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapPill("編輯", onEdit, primary = true)
        if (onExport != null) {
            MapPill("匯出 GPX", onExport)
        }
        MapPill("離開", onLeave)
    }
}

/** Planning entry: start a fresh plan, load a saved one, import an external GPX track, or cancel. */
@Composable
fun PlanEntryChooser(
    onNew: () -> Unit,
    onLoad: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("規劃路徑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("新規劃") }
                OutlinedButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text("載入已存路徑") }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("匯入 GPX 軌跡") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/**
 * Prompts for the route name an imported GPX track is saved under (prefilled with the name found in
 * the file, or the file name). Blank falls back to a default, like [SaveRouteDialog].
 */
@Composable
fun ImportRouteDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    RouteNameDialog(
        title = "匯入 GPX 軌跡",
        confirmLabel = "匯入",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Shared single-field naming dialog behind [SaveRouteDialog], [RenameRouteDialog] and
 * [ImportRouteDialog]: one text field prefilled with [initialName]; confirming trims the input and
 * falls back to a default name when blank.
 */
@Composable
private fun RouteNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("路線名稱") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { "未命名路線" }) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Prompts for a route name on save (prefilled with [initialName]). Blank falls back to a default. */
@Composable
fun SaveRouteDialog(
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    RouteNameDialog(
        title = "儲存規劃路徑",
        confirmLabel = "儲存",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Route length for the picker: metres below 1 km, otherwise kilometres to one decimal. */
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) {
        String.format(Locale.getDefault(), "%.1f 公里", meters / 1000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f 公尺", meters)
    }

/**
 * Lists saved routes for loading; tapping a row displays it on the map. Each row's "更多" menu offers
 * rename, export to GPX, and delete, delegated upward via [onRename]/[onExport]/[onDelete] (the
 * caller confirms and persists).
 */
@Composable
fun LoadRouteDialog(
    summaries: List<io.github.nexgus.jiudge.data.route.RouteStore.Summary>,
    onPick: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
    onRename: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
    onExport: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
    onDelete: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("載入規劃路徑") },
        text = {
            if (summaries.isEmpty()) {
                Text("尚無已儲存的路線")
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
                                RouteRowMenu(
                                    onRename = { onRename(s) },
                                    onExport = { onExport(s) },
                                    onDelete = { onDelete(s) },
                                )
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

/** Per-row "更多" overflow menu offering rename, GPX export, and delete for a saved route. */
@Composable
private fun RouteRowMenu(
    onRename: () -> Unit,
    onExport: () -> Unit,
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
                text = { Text("匯出 GPX") },
                onClick = {
                    expanded = false
                    onExport()
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

/** Prompts for a new route name on rename (prefilled with [initialName]). Blank falls back to a default. */
@Composable
fun RenameRouteDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    RouteNameDialog(
        title = "路線改名",
        confirmLabel = "確定",
        initialName = initialName,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Confirms irreversible deletion of a saved route (the file lives in a public, uninstall-surviving folder). */
@Composable
fun DeleteRouteDialog(
    routeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除規劃路徑") },
        text = { Text("確定要刪除 \"$routeName\" 嗎? 此操作無法復原.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("刪除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
