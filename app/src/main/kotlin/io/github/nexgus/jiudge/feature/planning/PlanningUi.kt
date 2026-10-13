package io.github.nexgus.jiudge.feature.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
