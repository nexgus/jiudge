package io.github.nexgus.jiudge.feature.path

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.data.route.PathKind
import io.github.nexgus.jiudge.data.route.PathStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Diameter of the kind-colour dot leading each row.
private val KIND_DOT_SIZE = 12.dp

// The tabs, left to right.
private val TAB_KINDS = listOf(PathKind.TRACK, PathKind.PLAN)

/**
 * Full-screen file manager: the saved paths - one kind at a time, tracks or planned routes, chosen
 * with the tabs under the top bar - in the order [summaries] is given (the store lists newest
 * first), with the actions on them. It is the one place saved paths are opened, renamed, deleted,
 * exported, copied and imported from.
 *
 * Each tab label carries the number of paths of its kind in [summaries]. The screen does not keep
 * the selection itself: it shows [selectedKind] and calls [onSelectKind] when the other tab is
 * tapped, so the caller decides where the choice is kept.
 *
 * Shown over the map the same way the stats screen is: the caller passes a `fillMaxSize` modifier,
 * and the opaque surface keeps map content and touches from bleeding through. The close icon and
 * the system back button both call [onClose].
 *
 * Tapping a row calls [onOpen]. Everything else is in the row's "更多" menu and is only delegated
 * upward - the caller confirms, persists and hands back a fresh [summaries]. [onCopyAsPlan] is
 * offered for tracks only.
 */
@Composable
fun PathManagerScreen(
    summaries: List<PathStore.Summary>,
    selectedKind: PathKind,
    onSelectKind: (PathKind) -> Unit,
    onOpen: (PathStore.Summary) -> Unit,
    onStats: (PathStore.Summary) -> Unit,
    onRename: (PathStore.Summary) -> Unit,
    onExport: (PathStore.Summary) -> Unit,
    onCopyAsPlan: (PathStore.Summary) -> Unit,
    onDelete: (PathStore.Summary) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "檔案管理",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImport) { Text("匯入 GPX") }
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "關閉檔案管理")
                }
            }

            TabRow(selectedTabIndex = TAB_KINDS.indexOf(selectedKind)) {
                TAB_KINDS.forEach { kind ->
                    Tab(
                        selected = kind == selectedKind,
                        onClick = { onSelectKind(kind) },
                        text = { Text("${tabName(kind)} (${summaries.count { it.kind == kind }})") },
                    )
                }
            }

            val shown = remember(summaries, selectedKind) { summaries.filter { it.kind == selectedKind } }
            if (shown.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "尚無已儲存的${tabName(selectedKind)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Keyed by the tab so each tab's list starts at the top rather than inheriting the
                // other one's scroll position.
                key(selectedKind) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(shown) { summary ->
                            PathRow(
                                summary = summary,
                                subtitle =
                                    "${dateFormat.format(Date(summary.createdAtEpochMs))} - " +
                                        formatDistance(summary.distanceMeters),
                                onOpen = { onOpen(summary) },
                                onStats = { onStats(summary) },
                                onRename = { onRename(summary) },
                                onExport = { onExport(summary) },
                                onCopyAsPlan = { onCopyAsPlan(summary) },
                                onDelete = { onDelete(summary) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Name of a kind as shown on its tab and in the empty-list message. */
private fun tabName(kind: PathKind): String = if (kind == PathKind.TRACK) "軌跡" else "規劃路徑"

/** Path length for the list: metres below 1 km, otherwise kilometres to one decimal. */
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) {
        String.format(Locale.getDefault(), "%.1f 公里", meters / 1000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f 公尺", meters)
    }

/**
 * One list row: a dot in the path's kind colour (the colour its line has on the map), the name over
 * [subtitle], and the "更多" menu. The dot and the texts together are the tap target that opens the
 * path.
 */
@Composable
private fun PathRow(
    summary: PathStore.Summary,
    subtitle: String,
    onOpen: () -> Unit,
    onStats: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onCopyAsPlan: () -> Unit,
    onDelete: () -> Unit,
) {
    val style = if (summary.kind == PathKind.TRACK) PathStyle.TRACK else PathStyle.PLAN
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(KIND_DOT_SIZE)
                        .background(Color(style.kindColor), CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(summary.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PathRowMenu(
            showCopyAsPlan = summary.kind == PathKind.TRACK,
            onStats = onStats,
            onRename = onRename,
            onExport = onExport,
            onCopyAsPlan = onCopyAsPlan,
            onDelete = onDelete,
        )
    }
}

/** Per-row "更多" overflow menu. [showCopyAsPlan] adds the copy action, which only a track has. */
@Composable
private fun PathRowMenu(
    showCopyAsPlan: Boolean,
    onStats: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onCopyAsPlan: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(end = 4.dp)) {
        TextButton(onClick = { expanded = true }) { Text("更多") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("統計") },
                onClick = {
                    expanded = false
                    onStats()
                },
            )
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
            if (showCopyAsPlan) {
                DropdownMenuItem(
                    text = { Text("複製為規劃路徑") },
                    onClick = {
                        expanded = false
                        onCopyAsPlan()
                    },
                )
            }
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
