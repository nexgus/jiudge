package io.github.nexgus.jiudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nexgus.jiudge.feature.map.RudyMapView
import org.mapsforge.map.android.view.MapView
import java.io.File

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataDir = File(DATA_DIR)
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    if (File(dataDir, RudyMapView.BASEMAP_NAME).exists()) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            factory = { ctx -> RudyMapView.create(ctx, dataDir).also { mapView = it } },
                        )
                    } else {
                        MissingDataMessage(
                            dataDir = dataDir.path,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        mapView?.destroyAll()
        mapView = null
        super.onDestroy()
    }

    companion object {
        // Phase 0: prototype data is pushed via `adb push <bundle> /data/local/tmp/rudymap`.
        // That path is world-readable; app-private dirs reject shell-owned files (errno 13).
        // Phase 1 replaces this with an in-app download into getExternalFilesDir.
        private const val DATA_DIR = "/data/local/tmp/rudymap"
    }
}

@Composable
private fun MissingDataMessage(dataDir: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "RudyMap data not found",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Expected ${RudyMapView.BASEMAP_NAME}, ${RudyMapView.THEME_NAME} and " +
                "${RudyMapView.DEM_DIR}/ under:\n$dataDir",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
