package io.github.nexgus.jiudge.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.github.nexgus.jiudge.core.index.Peak

/**
 * Peak-name search: a single dialog that filters the loaded index live as the user types and lists
 * the matches; tapping a row hands the chosen [Peak] back via [onPick] (the caller centres the map).
 * Same-named neighbours (前峰/主峰...) are common, so the user picks from the hit list rather than
 * the search guessing one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PeakSearchDialog(
    peaks: List<Peak>,
    initialQuery: String,
    onQueryChange: (String) -> Unit,
    onPick: (Peak) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val results = remember(query) { PeakSearch.search(peaks, query) }
    val shown = results.take(PeakSearch.MAX_RESULTS)

    // Focus the field and raise the keyboard as soon as the dialog appears, so the user can type
    // straight away without an extra tap.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜尋山頭") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQueryChange(it)
                    },
                    label = { Text("山名關鍵字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                when {
                    query.isBlank() ->
                        Hint("請輸入山名關鍵字, 例如 \"玉山\"")

                    shown.isEmpty() ->
                        Hint("找不到符合的山頭")

                    else ->
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(shown) { peak ->
                                PeakRow(peak = peak, onClick = { onPick(peak) })
                                HorizontalDivider()
                            }
                            if (results.size > shown.size) {
                                item {
                                    Hint("符合的山頭超過 ${PeakSearch.MAX_RESULTS} 筆, 請輸入更精確的關鍵字")
                                }
                            }
                        }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

@Composable
private fun PeakRow(
    peak: Peak,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
    ) {
        Text(peak.name, style = MaterialTheme.typography.bodyLarge)
        peak.locality?.let { locality ->
            // Approximate nearby place (直轄市/鄉鎮/村里) - tells same-named peaks apart without coords.
            Text(
                "鄰近 $locality",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}
