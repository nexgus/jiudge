package io.github.nexgus.jiudge.feature.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
        val arm = 20.dp.toPx()
        val gap = 6.dp.toPx()
        val dark = 2.dp.toPx()
        val halo = 4.dp.toPx()
        val dotR = 3.dp.toPx()
        fun cross(color: Color, width: Float) {
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
 * Bottom action bar for planning mode: add a waypoint at the crosshair, drop the last one, save,
 * or cancel. Save is disabled until there is a routed path (>= 2 waypoints); while a route is
 * computing the add/remove/save actions are disabled and a spinner shows.
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
    Surface(
        modifier = modifier,
        color = Color.White,
        contentColor = Color.Black,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onAdd, enabled = !busy) { Text("+", fontSize = 20.sp) }
            Button(onClick = onRemove, enabled = !busy && waypointCount > 0) { Text("−", fontSize = 20.sp) }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onSave, enabled = !busy && waypointCount >= 2) { Text("儲存") }
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            }
        }
    }
}

/** Map-view mode controls: clear the displayed route, or open the planning entry. */
@Composable
fun MapViewControls(
    canClear: Boolean,
    onClear: () -> Unit,
    onPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClear, enabled = canClear) { Text("清除軌跡") }
        Button(onClick = onPlan) { Text("規劃路徑") }
    }
}

/**
 * Route-view mode controls: re-enter editing, or leave to map-view (the route stays on the map).
 * Editing is where "+"/"-"/儲存 live.
 */
@Composable
fun RouteViewControls(onEdit: () -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onEdit) { Text("編輯") }
        OutlinedButton(onClick = onLeave) { Text("離開") }
    }
}

/** Planning entry: start a fresh plan, load a saved one, or cancel. */
@Composable
fun PlanEntryChooser(onNew: () -> Unit, onLoad: () -> Unit, onCancel: () -> Unit) {
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
fun SaveRouteDialog(initialName: String = "", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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

/** Lists saved routes for loading; tapping one displays it on the map. */
@Composable
fun LoadRouteDialog(
    summaries: List<io.github.nexgus.jiudge.data.route.RouteStore.Summary>,
    onPick: (io.github.nexgus.jiudge.data.route.RouteStore.Summary) -> Unit,
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(s) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${dateFormat.format(Date(s.createdAtEpochMs))} - ${s.waypointCount} 個經過點",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}
