package io.github.nexgus.jiudge.feature.identify

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Fixed-size icon slot: renders the theme symbol if [svg] is set, otherwise an empty box of the
 * same size, so text next to it keeps a consistent left edge whether or not an icon exists. */
@Composable
private fun SymbolIconSlot(
    themeDir: File,
    svg: String?,
    size: Dp,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (svg != null) {
            val sizePx = with(LocalDensity.current) { size.roundToPx() }
            val bitmap: ImageBitmap? by rememberSymbol(themeDir, svg, sizePx)
            bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(size)) }
        }
    }
}

/** A small banner telling the user how to use identify mode (shown while the mode is on). */
@Composable
fun IdentifyHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = "移動地圖, 將符號對準中央準心, 再按辨識",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Bottom bar for identify mode: identify whatever sits under the centre crosshair, or leave. */
@Composable
fun IdentifyBar(
    busy: Boolean,
    onIdentify: () -> Unit,
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onIdentify, enabled = !busy) { Text("辨識") }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            }
        }
    }
}

/**
 * Chooser shown when several features share the crosshair spot (a dense summit, etc.). Each row is a
 * candidate; tapping one shows its card and moves the highlight to that feature.
 */
@Composable
fun IdentifyChooser(
    themeDir: File,
    candidates: List<SymbolIdentifier.Match>,
    onPick: (SymbolIdentifier.Match) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選擇符號 (${candidates.size})") },
        text = {
            Column {
                candidates.forEach { candidate ->
                    val tags = candidate.tags.toMap()
                    val name = candidate.info?.name ?: "未知符號"
                    val sub = tags["name"] ?: tags["ref"]
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate) }
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SymbolIconSlot(themeDir, candidate.info?.svg, 28.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            sub?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Bottom card showing an identify result: the symbol's Chinese name as the title (with its theme
 * icon when one exists), the feature's own name (or ref) as a subtitle, and surface + crosshair
 * distance as detail lines. Other raw tags are intentionally not shown.
 */
@Composable
fun IdentifyResultCard(
    themeDir: File,
    table: SymbolTable,
    result: SymbolIdentifier.Match,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tags = remember(result) { result.tags.toMap() }
    val subtitle = tags["name"] ?: tags["ref"]
    val surface = table.surfaceLabel(tags["surface"])
    Surface(
        modifier = modifier,
        color = Color.White,
        contentColor = Color.Black,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Fixed icon slot (shown if the symbol has one) so every card's text shares a left edge.
            SymbolIconSlot(themeDir, result.info?.svg, 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = result.info?.name ?: "未知符號",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
                surface?.let {
                    Text(
                        text = "路面: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
