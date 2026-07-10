package io.github.nexgus.jiudge.feature.stats

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nexgus.jiudge.core.elevation.SlopeScale
import io.github.nexgus.jiudge.core.stats.TraceStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Ascent/descent accent colours reuse the slope scale's moderate band (warm uphill, cool downhill)
// so the data grid, the map route line and the profile chart share one colour language.
private val ASCENT_COLOR = Color(SlopeScale.MODERATE_UP_COLOR)
private val DESCENT_COLOR = Color(SlopeScale.MODERATE_DOWN_COLOR)

/**
 * Full-screen stats overlay for a planned route or recorded track: an elevation-profile chart
 * ("高度剖面圖" section) plus grouped stat cards ("統計數據" section: distance/elevation, time,
 * speed), shown over the map (caller passes a `fillMaxSize` modifier). Layout swaps between a
 * stacked (portrait) and side-by-side (landscape) arrangement, but the chart and data section are
 * the same composables in both orientations - see [ElevationProfileChart] and [StatsDataSection].
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (stats != null) {
                        Text(
                            summaryLine(stats),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val groups = remember(stats, isTrack) { buildStatGroups(stats, isTrack) }

                if (isLandscape) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                            SectionHeader("高度剖面圖", modifier = Modifier.padding(start = 16.dp))
                            ElevationProfileChart(
                                profile = stats.profile,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                            )
                        }
                        StatsDataSection(
                            groups = groups,
                            modifier = Modifier.weight(0.42f).fillMaxHeight(),
                        )
                    }
                } else {
                    // The chart keeps a wide-short aspect ratio instead of filling the remaining
                    // height: a tall portrait plot pushes the vertical exaggeration of long flat
                    // traces to absurd levels (docs/stats.md §7). The data section takes the rest,
                    // scrolling when the grouped cards do not fit.
                    val chartHeight = (configuration.screenWidthDp * 0.6f).coerceAtMost(configuration.screenHeightDp * 0.5f).dp
                    Column(modifier = Modifier.fillMaxSize()) {
                        SectionHeader("高度剖面圖", modifier = Modifier.padding(start = 16.dp))
                        ElevationProfileChart(
                            profile = stats.profile,
                            modifier = Modifier.fillMaxWidth().height(chartHeight).padding(8.dp),
                        )
                        StatsDataSection(
                            groups = groups,
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

/** Small primary-coloured section header ("高度剖面圖" / "統計數據"). */
@Composable
private fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/**
 * One label/value cell in a stat group card. [value] is the big figure ("-" when missing), [unit]
 * a small dim suffix, [prefix] an optional accent glyph (↑/↓) tinted [prefixColor]. [small] drops
 * the value to a smaller style for long values (timestamps) that would not fit at display size.
 */
private data class StatItem(
    val label: String,
    val value: String,
    val unit: String? = null,
    val prefix: String? = null,
    val prefixColor: Color? = null,
    val small: Boolean = false,
)

/** A titled group of related [StatItem]s rendered as one hairline-grid card. */
private data class StatGroup(
    val title: String,
    val items: List<StatItem>,
)

/** "統計數據" section: header plus one hairline-grid card per stat group, scrolling as a whole. */
@Composable
private fun StatsDataSection(
    groups: List<StatGroup>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("統計數據")
        groups.forEach { StatGroupCard(it) }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** One stat group: dim group title over a rounded card of 2-column cells separated by hairlines. */
@Composable
private fun StatGroupCard(
    group: StatGroup,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            group.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        // Hairline grid without per-cell borders: the container carries the line colour, cells sit
        // on top with 1 dp gaps (and a 1 dp container inset as the outer border).
        val lineColor = MaterialTheme.colorScheme.outlineVariant
        val cellColor = MaterialTheme.colorScheme.surface
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(lineColor)
                    .padding(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            group.items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    rowItems.forEach { item ->
                        StatCell(item, modifier = Modifier.weight(1f).fillMaxHeight().background(cellColor))
                    }
                    if (rowItems.size == 1) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(cellColor))
                    }
                }
            }
        }
    }
}

/** One cell: small dim label over the big value with optional accent prefix and small unit suffix. */
@Composable
private fun StatCell(
    item: StatItem,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            item.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            buildAnnotatedString {
                if (item.prefix != null) {
                    withStyle(SpanStyle(color = item.prefixColor ?: dimColor)) { append("${item.prefix} ") }
                }
                append(item.value)
                if (item.unit != null) {
                    withStyle(SpanStyle(fontSize = 13.sp, color = dimColor)) { append(" ${item.unit}") }
                }
            },
            style = if (item.small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Top-bar subtitle: total distance plus colour-accented ascent/descent, e.g. "37.33 km · ↑ 772 m · ↓ 780 m". */
private fun summaryLine(stats: TraceStats): AnnotatedString =
    buildAnnotatedString {
        val (distanceValue, distanceUnit) = distanceParts(stats.distanceM)
        append("$distanceValue $distanceUnit")
        stats.ascentM?.let {
            append(" · ")
            withStyle(SpanStyle(color = ASCENT_COLOR)) { append("↑ ") }
            append("${Math.round(it)} m")
        }
        stats.descentM?.let {
            append(" · ")
            withStyle(SpanStyle(color = DESCENT_COLOR)) { append("↓ ") }
            append("${Math.round(it)} m")
        }
    }

/**
 * Builds the stat groups for the data section per the fixed spec: the distance/elevation group for
 * every trace, plus the time and speed groups for a recorded track. Missing values (no DEM, no time
 * stats) render as "-" rather than being omitted, so the layout shape stays stable.
 */
private fun buildStatGroups(
    stats: TraceStats,
    isTrack: Boolean,
): List<StatGroup> {
    val time = stats.time
    val groups = mutableListOf<StatGroup>()

    val elevationDiff =
        if (stats.maxElevationM != null && stats.minElevationM != null) {
            stats.maxElevationM - stats.minElevationM
        } else {
            null
        }
    groups +=
        StatGroup(
            "距離與高程",
            listOf(
                distanceItem("距離", stats.distanceM),
                StatItem("高低差", elevationDiff?.let { "${Math.round(it)}" } ?: "-", unit = elevationDiff?.let { "m" }),
                distanceItem("上坡里程", stats.ascentDistanceM, prefix = "↑", prefixColor = ASCENT_COLOR),
                distanceItem("下坡里程", stats.descentDistanceM, prefix = "↓", prefixColor = DESCENT_COLOR),
                elevationItem("累計爬升", stats.ascentM?.toFloat(), prefix = "↑", prefixColor = ASCENT_COLOR),
                elevationItem("累計下降", stats.descentM?.toFloat(), prefix = "↓", prefixColor = DESCENT_COLOR),
                elevationItem("最高海拔", stats.maxElevationM),
                elevationItem("最低海拔", stats.minElevationM),
            ),
        )

    if (isTrack) {
        groups +=
            StatGroup(
                "時間",
                listOf(
                    StatItem("開始時刻", time?.startEpochMs?.let(::formatEpoch) ?: "-", small = true),
                    StatItem("結束時刻", time?.endEpochMs?.let(::formatEpoch) ?: "-", small = true),
                    StatItem("總時間", time?.totalMs?.let(::formatDuration) ?: "-"),
                    StatItem("移動時間", time?.movingMs?.let(::formatDuration) ?: "-"),
                    StatItem("停止時間", time?.stoppedMs?.let(::formatDuration) ?: "-"),
                ),
            )
        groups +=
            StatGroup(
                "速度",
                listOf(
                    speedItem("平均速率", time?.avgSpeedMps),
                    speedItem("移動均速", time?.movingAvgSpeedMps),
                    speedItem("最高速率", time?.maxSpeedMps),
                ),
            )
    }
    return groups
}

/** Distance value/unit pair: metres below 1 km, otherwise kilometres to two decimals. */
private fun distanceParts(meters: Double): Pair<String, String> =
    if (meters >= 1000.0) {
        String.format(Locale.getDefault(), "%.2f", meters / 1000.0) to "km"
    } else {
        String.format(Locale.getDefault(), "%.0f", meters) to "m"
    }

/** Distance [StatItem] with optional accent prefix; "-" without a value. */
private fun distanceItem(
    label: String,
    meters: Double?,
    prefix: String? = null,
    prefixColor: Color? = null,
): StatItem {
    if (meters == null) return StatItem(label, "-")
    val (value, unit) = distanceParts(meters)
    return StatItem(label, value, unit, prefix, prefixColor)
}

/** Elevation [StatItem], rounded to the nearest metre; "-" without a value. */
private fun elevationItem(
    label: String,
    meters: Float?,
    prefix: String? = null,
    prefixColor: Color? = null,
): StatItem {
    if (meters == null) return StatItem(label, "-")
    return StatItem(label, "${Math.round(meters)}", "m", prefix, prefixColor)
}

/** Speed [StatItem] in m/s rendered as km/h to one decimal; "-" without a value. */
private fun speedItem(
    label: String,
    mps: Double?,
): StatItem {
    if (mps == null) return StatItem(label, "-")
    return StatItem(label, String.format(Locale.getDefault(), "%.1f", mps * 3.6), "km/h")
}

/** Duration as H:mm:ss, with unpadded total hours (may exceed 24). */
private fun formatDuration(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
}

/** Epoch millis formatted as a local MM-dd HH:mm timestamp (year omitted to fit the cell). */
private fun formatEpoch(epochMs: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
