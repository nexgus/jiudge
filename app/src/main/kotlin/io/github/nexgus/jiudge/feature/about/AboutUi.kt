package io.github.nexgus.jiudge.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nexgus.jiudge.BuildConfig

// The map screen's overflow ("⋮") menu and its "關於" dialog. The button mirrors the other floating
// map controls (a FloatingActionButton with a glyph, no icon dependency). The "檢查地圖更新" entry is
// a placeholder until the Phase 3 update mechanism lands.

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
    FloatingActionButton(
        onClick = { expanded = true },
        modifier = modifier,
    ) {
        Text(text = "⋮", fontSize = 24.sp)
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
        title = { Text("關於 Jiudge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutRow(label = "應用程式版本", value = appVersionLabel())
                AboutRow(label = "地圖資料版本", value = mapVersion?.let { "v$it" } ?: "未知")
            }
        },
    )
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
