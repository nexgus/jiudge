package io.github.nexgus.jiudge.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.core.geo.Twd97
import io.github.nexgus.jiudge.core.location.GnssSummary
import io.github.nexgus.jiudge.core.location.LocationFix
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Location-info popup raised by long-pressing the current-location marker. The dialog is split into
 * two sections separated by a horizontal divider: coordinates (WGS84 with DD and DMS sub-rows,
 * TWD97 TM2 easting / northing, and altitude combining DEM and GPS readings) above, and link /
 * signal diagnostics below. A dash stands in for any field the current fix cannot supply.
 *
 * Each coordinate value is split onto two lines with a hard `\n` so the layout never depends on
 * implicit text wrapping - WGS84 DD/DMS show latitude above longitude, TWD97 shows easting above
 * northing.
 *
 * [demAltitude] is the elevation looked up from the on-device DEM at the fix position (independent
 * of the GPS-reported altitude); [gnss] is null when satellite status is unavailable (no fine-
 * location grant or no reading yet).
 */
@Composable
fun LocationInfoDialog(
    fix: LocationFix?,
    gnss: GnssSummary?,
    serviceEnabled: Boolean,
    demAltitude: Double?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定位資訊") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoSectionHeader("WGS84")
                InfoRow("DD", wgs84DdText(fix))
                InfoRow("DMS", wgs84DmsText(fix))
                InfoRow("TWD97", twd97Text(fix))
                InfoRow("海拔", altitudeText(demAltitude, fix?.altitudeMeters))
                HorizontalDivider()
                InfoRow("連線狀態", connectionText(serviceEnabled, fix))
                InfoRow("信號強度", signalText(gnss))
                InfoRow("衛星數量", satelliteCountText(gnss))
                InfoRow("平均 C/N0", cn0Text(gnss))
                InfoRow("水平精度", accuracyText(fix?.accuracyMeters))
                InfoRow("垂直精度", accuracyText(fix?.verticalAccuracyMeters))
                InfoRow("速度", speedText(fix?.speedMps))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
    )
}

@Composable
private fun InfoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val DASH = "--"

private fun connectionText(
    serviceEnabled: Boolean,
    fix: LocationFix?,
): String =
    when {
        !serviceEnabled -> "定位服務已關閉"
        fix == null -> "尚未定位"
        fix.fromGps -> "GPS 衛星定位"
        else -> "網路定位 (WiFi/行動網路)"
    }

// Qualitative signal grade from the mean C/N0 of the satellites used in the fix, a common rule of
// thumb: above ~35 dB-Hz is a strong open-sky signal, the mid 20s-30s is usable, below is weak.
private fun signalText(gnss: GnssSummary?): String {
    val cn0 = gnss?.averageCn0DbHz ?: return DASH
    return when {
        cn0 >= 35f -> "良好"
        cn0 >= 25f -> "普通"
        else -> "微弱"
    }
}

private fun satelliteCountText(gnss: GnssSummary?): String =
    if (gnss == null) DASH else "使用 ${gnss.satellitesUsedInFix} / 可見 ${gnss.satellitesInView}"

private fun cn0Text(gnss: GnssSummary?): String {
    val cn0 = gnss?.averageCn0DbHz ?: return DASH
    return String.format(Locale.US, "%.1fdB-Hz", cn0)
}

private fun wgs84DdText(fix: LocationFix?): String {
    if (fix == null) return DASH
    return "${formatDecimalDegrees(fix.latitude, isLat = true)}\n${formatDecimalDegrees(fix.longitude, isLat = false)}"
}

private fun wgs84DmsText(fix: LocationFix?): String {
    if (fix == null) return DASH
    return "${formatDms(fix.latitude, isLat = true)}\n${formatDms(fix.longitude, isLat = false)}"
}

private fun twd97Text(fix: LocationFix?): String {
    if (fix == null) return DASH
    val c = Twd97.convert(fix.latitude, fix.longitude)
    return String.format(Locale.US, "E %,.0f\nN %,.0f", c.easting, c.northing)
}

private fun altitudeText(
    dem: Double?,
    gps: Double?,
): String {
    if (dem == null && gps == null) return DASH
    val parts = mutableListOf<String>()
    if (dem != null) parts += String.format(Locale.US, "DEM %.0fm", dem)
    if (gps != null) parts += String.format(Locale.US, "GPS %.0fm", gps)
    return parts.joinToString(", ")
}

private fun accuracyText(meters: Float?): String = if (meters == null) DASH else String.format(Locale.US, "±%.0fm", meters)

private fun speedText(mps: Float?): String = if (mps == null) DASH else String.format(Locale.US, "%.1fm/s (%.1fkm/h)", mps, mps * 3.6f)

/** Decimal-degree string with a hemisphere suffix, e.g. `24.094523°N`. */
private fun formatDecimalDegrees(
    value: Double,
    isLat: Boolean,
): String {
    val hemisphere = hemisphereOf(value, isLat)
    return String.format(Locale.US, "%.6f°%s", abs(value), hemisphere)
}

/**
 * Degrees / minutes / seconds string with a hemisphere suffix, e.g. `24°05'40.28"N`. Minutes and
 * the integer part of seconds are zero-padded to width 2 so the DMS line aligns vertically.
 */
private fun formatDms(
    value: Double,
    isLat: Boolean,
): String {
    val hemisphere = hemisphereOf(value, isLat)
    val abs = abs(value)
    val deg = floor(abs).toInt()
    val minFloat = (abs - deg) * 60.0
    val min = floor(minFloat).toInt()
    val sec = (minFloat - min) * 60.0
    return String.format(Locale.US, "%d°%02d'%05.2f\"%s", deg, min, sec, hemisphere)
}

private fun hemisphereOf(
    value: Double,
    isLat: Boolean,
): String =
    if (isLat) {
        if (value >= 0) "N" else "S"
    } else {
        if (value >= 0) "E" else "W"
    }
