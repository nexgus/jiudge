package io.github.nexgus.jiudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nexgus.jiudge.feature.map.RudyMapView
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.model.common.Observer
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
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            val map = remember { mutableStateOf<MapView?>(null) }
                            val zoomLevel = remember { mutableStateOf<Byte?>(null) }
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    RudyMapView.create(ctx, dataDir).also {
                                        mapView = it
                                        map.value = it
                                    }
                                },
                            )
                            // Keep the on-screen zoom-level readout in sync with the map.
                            DisposableEffect(map.value) {
                                val position = map.value?.model?.mapViewPosition
                                if (position == null) {
                                    onDispose {}
                                } else {
                                    zoomLevel.value = position.zoomLevel
                                    val observer = Observer { zoomLevel.value = position.zoomLevel }
                                    position.addObserver(observer)
                                    onDispose { position.removeObserver(observer) }
                                }
                            }
                            // On the emulator pinch gestures are unavailable, so expose explicit
                            // zoom buttons (mirrors the Flutter prototype's ZoomOverlay).
                            ZoomButtons(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                zoomLevel = zoomLevel.value,
                                onZoomIn = { map.value?.model?.mapViewPosition?.zoomIn() },
                                onZoomOut = { map.value?.model?.mapViewPosition?.zoomOut() },
                            )
                        }
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
private fun ZoomButtons(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    zoomLevel: Byte?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Permanent zoom-level readout (the scale bar is hidden - it is not precise).
        Surface(
            color = Color.White,
            contentColor = Color.Black,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 6.dp,
        ) {
            Text(
                text = "${zoomLevel ?: "-"}",
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        FloatingActionButton(onClick = onZoomIn) {
            Text(text = "+", fontSize = 24.sp)
        }
        FloatingActionButton(onClick = onZoomOut) {
            Text(text = "−", fontSize = 24.sp) // minus sign
        }
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
