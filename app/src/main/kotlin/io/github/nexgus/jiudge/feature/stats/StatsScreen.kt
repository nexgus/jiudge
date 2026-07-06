package io.github.nexgus.jiudge.feature.stats

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.core.stats.TraceStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Full-screen stats overlay for a planned route or recorded track: an elevation-profile chart plus
 * a two-column data grid, shown over the map (caller passes a `fillMaxSize` modifier). Layout swaps
 * between a stacked (portrait) and side-by-side (landscape) arrangement, but the chart and data grid
 * are the same composables in both orientations - see [ElevationProfileChart] and the data-grid
 * section below.
 *
 * `stats == null` renders only a loading spinner under the top bar (docs contract: stats are
 * computed off the UI thread and may not be ready yet when the screen first appears).
 *
 * [onExportPng], when non-null, drives the "匯出 PNG" top-bar action (D4, docs/stats.md): the caller
 * (MainActivity) owns the SAF picker flow and does the actual render+write off the main thread, this
 * screen only shows the button and defers to the callback. The button itself is only shown when the
 * profile has drawable data, mirroring [ElevationProfileChart]'s own no-data fallback.
 */
@Composable
fun StatsScreen(
    name: String,
    isTrack: Boolean,
    stats: TraceStats?,
    onClose: () -> Unit,
    onExportPng: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val hasChartData = stats != null && stats.profile.any { it.elevationM != null }

    // Opaque surface: this screen is shown on top of the map and must not let map content or
    // touches bleed through.
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
                    "$name - 統計",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onExportPng != null && hasChartData) {
                    IconButton(onClick = onExportPng) {
                        Icon(imageVector = Icons.Filled.SaveAlt, contentDescription = "匯出 PNG")
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "關閉統計")
                }
            }

            if (stats == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val isLandscape =
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val items = remember(stats, isTrack) { buildStatItems(stats, isTrack) }

                if (isLandscape) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ElevationProfileChart(
                            profile = stats.profile,
                            modifier = Modifier.weight(0.58f).fillMaxSize().padding(8.dp),
                        )
                        StatsDataGrid(
                            items = items,
                            modifier = Modifier.weight(0.42f).fillMaxSize(),
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ElevationProfileChart(
                            profile = stats.profile,
                            modifier = Modifier.weight(0.35f).fillMaxWidth().padding(8.dp),
                        )
                        StatsDataGrid(
                            items = items,
                            modifier = Modifier.weight(0.65f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** One label/value cell in the stats data grid. */
private data class StatItem(
    val label: String,
    val value: String,
)

/** Two-column, independently-scrollable grid of [StatItem]s: label (small) over value (medium). */
@Composable
private fun StatsDataGrid(
    items: List<StatItem>,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
    ) {
        items(items) { item ->
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(item.value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * Builds the ordered stat rows for the data grid per the fixed spec: distance-only fields for a
 * planned route, the full time+distance+elevation set for a recorded track. Missing values (no DEM,
 * no time stats) render as "-" rather than being omitted, so the grid shape stays stable.
 */
private fun buildStatItems(
    stats: TraceStats,
    isTrack: Boolean,
): List<StatItem> {
    val time = stats.time
    val items = mutableListOf<StatItem>()
    items += StatItem("距離", formatDistance(stats.distanceM))
    if (isTrack) {
        items += StatItem("總時間", time?.totalMs?.let(::formatDuration) ?: "-")
        items += StatItem("移動時間", time?.movingMs?.let(::formatDuration) ?: "-")
        items += StatItem("停止時間", time?.stoppedMs?.let(::formatDuration) ?: "-")
        items += StatItem("均速", time?.avgSpeedMps?.let(::formatSpeed) ?: "-")
        items += StatItem("移動均速", time?.movingAvgSpeedMps?.let(::formatSpeed) ?: "-")
        items += StatItem("最大速度", time?.maxSpeedMps?.let(::formatSpeed) ?: "-")
    }
    items += StatItem("累計爬升", stats.ascentM?.let(::formatAscent) ?: "-")
    items += StatItem("累計下降", stats.descentM?.let(::formatDescent) ?: "-")
    items += StatItem("最高海拔", stats.maxElevationM?.let(::formatElevation) ?: "-")
    items += StatItem("最低海拔", stats.minElevationM?.let(::formatElevation) ?: "-")
    if (isTrack) {
        items += StatItem("開始時刻", time?.startEpochMs?.let(::formatEpoch) ?: "-")
        items += StatItem("結束時刻", time?.endEpochMs?.let(::formatEpoch) ?: "-")
    }
    return items
}

/** Distance: metres below 1 km, otherwise kilometres to one decimal - matches PlanningUi's formatDistance. */
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) {
        String.format(Locale.getDefault(), "%.1f 公里", meters / 1000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f 公尺", meters)
    }

/** Duration as H:mm:ss, with unpadded total hours (may exceed 24). */
private fun formatDuration(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
}

/** Speed in m/s rendered as km/h to one decimal. */
private fun formatSpeed(mps: Double): String = String.format(Locale.getDefault(), "%.1f km/h", mps * 3.6)

/** Cumulative ascent, rounded to the nearest metre with an explicit "+" sign. */
private fun formatAscent(meters: Double): String = "+${Math.round(meters)} m"

/** Cumulative descent, rounded to the nearest metre with an explicit "-" sign. */
private fun formatDescent(meters: Double): String = "-${Math.round(meters)} m"

/** A single elevation reading, rounded to the nearest metre. */
private fun formatElevation(meters: Float): String = "${Math.round(meters)} m"

/** Epoch millis formatted as a local yyyy-MM-dd HH:mm timestamp. */
private fun formatEpoch(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
