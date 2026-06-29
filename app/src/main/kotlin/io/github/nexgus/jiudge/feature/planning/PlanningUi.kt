package io.github.nexgus.jiudge.feature.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
    ) {
        Text(text, fontSize = fontSize)
    }
}

/**
 * Bottom action bar for planning mode: add a waypoint at the crosshair, drop the last one, save,
 * or cancel. Save is disabled until there is a routed path (>= 2 waypoints); while a route is
 * computing the add/remove/save actions are disabled and a spinner shows. The buttons disable in
 * place (rather than hide) so the row keeps a stable width as the waypoint count changes.
 */
@Composable
fun PlanningBottomBar(
    waypointCount: Int,
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
        MapPill("−", onRemove, primary = true, enabled = !busy && waypointCount > 0, fontSize = 20.sp)
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp))
        }
        MapPill("儲存", onSave, enabled = !busy && waypointCount >= 2)
        MapPill("取消", onCancel, enabled = !busy)
    }
}

/**
 * Map-view mode controls: open a recording session, open the planning entry, or clear the displayed
 * route. "錄製軌跡" stays in place but is disabled when location permission is not yet held - the
 * recording feature is meaningless without a fix, and the user picks the permission up via the
 * existing "我位置" entry rather than from a disabled pill.
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
            MapPill("清除軌跡", onClear)
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
 * Route-view mode controls: re-enter editing, or leave to map-view (the route stays on the map).
 * Editing is where "+"/"-"/儲存 live.
 */
@Composable
fun RouteViewControls(
    onEdit: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapPill("編輯", onEdit, primary = true)
        MapPill("離開", onLeave)
    }
}

/** Planning entry: start a fresh plan, load a saved one, or cancel. */
@Composable
fun PlanEntryChooser(
    onNew: () -> Unit,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("規劃路徑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("新規劃") }
                OutlinedButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text("載入已存檔") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/** Prompts for a route name on save (prefilled with [initialName]). Blank falls back to a default. */
@Composable
fun SaveRouteDialog(
    initialName: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("儲存規劃路徑") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("路線名稱") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { "未命名路線" }) }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
 * Lists saved routes for loading; tapping a row displays it on the map. Each row's "⋮" menu offers
 * rename and delete, delegated upward via [onRename]/[onDelete] (the caller confirms and persists).
 */
@Composable
fun LoadRouteDialog(
    summaries: List<io.github.nexgus.jiudge.data.route.RouteStore.Summary>,
    onPick: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
    onRename: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
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
                                RouteRowMenu(onRename = { onRename(s) }, onDelete = { onDelete(s) })
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

/** Per-row "⋮" overflow menu offering rename and delete for a saved route. */
@Composable
private fun RouteRowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("⋮", fontSize = 20.sp) }
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

/** Prompts for a new route name on rename (prefilled with [initialName]). Blank falls back to a default. */
@Composable
fun RenameRouteDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("路線改名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("路線名稱") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { "未命名路線" }) }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
