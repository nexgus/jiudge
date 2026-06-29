package io.github.nexgus.jiudge.feature.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.nexgus.jiudge.BuildConfig

// The map screen's overflow ("⋮") menu and its "關於" dialog. The button mirrors the other floating
// map controls (a SmallFloatingActionButton with a Material icon). The "檢查地圖更新" entry is a
// placeholder until the Phase 3 update mechanism lands.

/** App version as `<versionName>+<gitHash>` (semver build metadata), suffixed `-dirty` if built from an unclean tree. */
private fun appVersionLabel(): String {
    val base = "${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_HASH}"
    return if (BuildConfig.GIT_DIRTY) "$base-dirty" else base
}

@Composable
fun MainMenuButton(
    onAbout: () -> Unit,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // FAB 與 DropdownMenu 包在同一個 Box, 讓 popup 以 FAB 為錨從原位往下展開, 蓋過下方按鈕;
    // 否則它們會成為父層 Column 的兩個 slot, 被 Arrangement.spacedBy 撐到搜尋鍵下方.
    Box(modifier = modifier) {
        SmallFloatingActionButton(
            onClick = { expanded = true },
        ) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "主選單")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("關於") },
                onClick = {
                    expanded = false
                    onAbout()
                },
            )
            DropdownMenuItem(
                text = { Text("檢查地圖更新") },
                onClick = {
                    expanded = false
                    onCheckUpdate()
                },
            )
        }
    }
}

/**
 * "關於" dialog: the app build (version + commit) and the installed map-data version. [mapVersion]
 * is the `vYYYY.MM.DD` token from the installed theme, or null when none could be read.
 */
@Composable
fun AboutDialog(
    mapVersion: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIconImage(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "有關於 Jiudge",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "極簡風格路徑規劃與軌跡錄製應用程式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutRow(label = "版本", value = appVersionLabel())
                AboutRow(label = "圖資", value = mapVersion?.let { "v$it" } ?: "未知")
            }
        },
    )
}

// Renders the current launcher icon (the one manifest's android:icon resolves to). Goes through
// PackageManager rather than referencing R.mipmap.ic_launcher_foreground / ic_launcher_background
// directly, so swapping the launcher icon (vector, single PNG, different adaptive layers, ...)
// updates this dialog automatically without code changes.
@Composable
private fun AppIconImage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val painter =
        remember(context, sizePx) {
            val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
            val bitmap = drawable.toBitmap(width = sizePx, height = sizePx)
            BitmapPainter(bitmap.asImageBitmap())
        }
    Image(painter = painter, contentDescription = null, modifier = modifier)
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
