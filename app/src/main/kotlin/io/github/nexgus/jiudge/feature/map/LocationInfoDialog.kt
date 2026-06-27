package io.github.nexgus.jiudge.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.core.geo.Twd97
import io.github.nexgus.jiudge.core.location.GnssSummary
import io.github.nexgus.jiudge.core.location.LocationFix
import java.util.Locale

/**
 * Connection-info popup raised by long-pressing the current-location marker. Shows whatever the
 * device can report about the live fix - link type and satellite signal, coordinates in both decimal
 * degrees and TWD97 TM2, GPS and DEM altitude, and the accuracy figures - with a dash standing in
 * for any value the current fix does not carry.
 *
 * [demAltitude] is the elevation looked up from the on-device DEM at the fix position (independent of
 * the GPS-reported altitude); [gnss] is null when satellite status is unavailable (no fine-location
 * grant or no reading yet).
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
        title = { Text("連線資訊") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoRow("連線狀態", connectionText(serviceEnabled, fix))
                InfoRow("信號強度", signalText(gnss))
                InfoRow("衛星 (使用/可見)", satelliteCountText(gnss))
                InfoRow("平均 C/N0", cn0Text(gnss))
                InfoRow("緯度", fix?.let { formatDegrees(it.latitude, isLat = true) } ?: DASH)
                InfoRow("經度", fix?.let { formatDegrees(it.longitude, isLat = false) } ?: DASH)
                InfoRow("TWD97 (二度分帶)", twd97Text(fix))
                InfoRow("GPS 海拔", altitudeText(fix?.altitudeMeters))
                InfoRow("DEM 海拔", altitudeText(demAltitude))
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
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(116.dp),
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
    val grade =
        when {
            cn0 >= 35f -> "良好"
            cn0 >= 25f -> "普通"
            else -> "微弱"
        }
    return grade
}

private fun satelliteCountText(gnss: GnssSummary?): String =
    if (gnss == null) DASH else "${gnss.satellitesUsedInFix} / ${gnss.satellitesInView}"

private fun cn0Text(gnss: GnssSummary?): String {
    val cn0 = gnss?.averageCn0DbHz ?: return DASH
    return String.format(Locale.US, "%.1f dB-Hz", cn0)
}

private fun twd97Text(fix: LocationFix?): String {
    if (fix == null) return DASH
    val c = Twd97.convert(fix.latitude, fix.longitude)
    return String.format(
        Locale.US,
        "E %,.0f N %,.0f (TM%d)",
        c.easting,
        c.northing,
        c.centralMeridianDeg,
    )
}

private fun altitudeText(meters: Double?): String = if (meters == null) DASH else String.format(Locale.US, "%.0f m", meters)

private fun accuracyText(meters: Float?): String = if (meters == null) DASH else String.format(Locale.US, "±%.0f m", meters)

private fun speedText(mps: Float?): String = if (mps == null) DASH else String.format(Locale.US, "%.1f m/s (%.1f km/h)", mps, mps * 3.6f)

/** Decimal-degree string with a hemisphere suffix, e.g. `24.094523° N`. */
private fun formatDegrees(
    value: Double,
    isLat: Boolean,
): String {
    val hemisphere =
        if (isLat) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }
    return String.format(Locale.US, "%.6f° %s", kotlin.math.abs(value), hemisphere)
}
