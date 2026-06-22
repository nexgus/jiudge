package io.github.nexgus.jiudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.data.route.PlannedRoute
import io.github.nexgus.jiudge.data.route.RouteStore
import io.github.nexgus.jiudge.feature.map.RudyMapView
import io.github.nexgus.jiudge.feature.planning.CrosshairOverlay
import io.github.nexgus.jiudge.feature.planning.LoadRouteDialog
import io.github.nexgus.jiudge.feature.planning.MapViewControls
import io.github.nexgus.jiudge.feature.planning.PlanEntryChooser
import io.github.nexgus.jiudge.feature.planning.PlanningBottomBar
import io.github.nexgus.jiudge.feature.planning.RoutePlanner
import io.github.nexgus.jiudge.feature.planning.RouteViewControls
import io.github.nexgus.jiudge.feature.planning.RouteViewer
import io.github.nexgus.jiudge.feature.planning.SaveRouteDialog
import kotlinx.coroutines.launch
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.model.common.Observer
import java.io.File

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataDir = File(DATA_DIR)
        val routesDir = File(filesDir, "routes")
        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    if (File(dataDir, RudyMapView.BASEMAP_NAME).exists()) {
                        MapScreen(
                            dataDir = dataDir,
                            engine = remember { BRouterEngine(dataDir) },
                            routeStore = remember { RouteStore(routesDir) },
                            snackbarHostState = snackbarHostState,
                            onMapCreated = { mapView = it },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
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

/** The three route-planning UI modes (see docs/ui.md). */
private enum class PlanMode { MAP_VIEW, ROUTE_EDIT, ROUTE_VIEW }

@Composable
private fun MapScreen(
    dataDir: File,
    engine: BRouterEngine,
    routeStore: RouteStore,
    snackbarHostState: SnackbarHostState,
    onMapCreated: (MapView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val map = remember { mutableStateOf<MapView?>(null) }
    val zoomLevel = remember { mutableStateOf<Byte?>(null) }

    // One planner / viewer per live MapView; recreated if the view is.
    val planner = remember(map.value) { map.value?.let { RoutePlanner(it, engine) } }
    val viewer = remember(map.value) { map.value?.let { RouteViewer(it) } }

    var mode by remember { mutableStateOf(PlanMode.MAP_VIEW) }
    var busy by remember { mutableStateOf(false) }
    var showChooser by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var loadList by remember { mutableStateOf<List<RouteStore.Summary>?>(null) }
    // Route currently drawn by the viewer: shown in ROUTE_VIEW, and kept in MAP_VIEW after 離開.
    var displayedRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    // What ROUTE_EDIT "取消" reverts to: set when entering edit, refreshed on save.
    var editBaseline by remember { mutableStateOf<PlannedRoute?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                RudyMapView.create(ctx, dataDir).also {
                    onMapCreated(it)
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

        // Zoom controls persist across all modes (map controls, independent of the action bar).
        ZoomButtons(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            zoomLevel = zoomLevel.value,
            onZoomIn = { map.value?.model?.mapViewPosition?.zoomIn() },
            onZoomOut = { map.value?.model?.mapViewPosition?.zoomOut() },
        )

        when (mode) {
            PlanMode.MAP_VIEW -> MapViewControls(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                canClear = displayedRoute != null,
                onClear = {
                    viewer?.clear()
                    displayedRoute = null
                },
                onPlan = { showChooser = true },
            )

            PlanMode.ROUTE_EDIT -> {
                CrosshairOverlay(modifier = Modifier.fillMaxSize())
                PlanningBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    waypointCount = planner?.waypoints?.size ?: 0,
                    busy = busy,
                    onAdd = {
                        val p = planner ?: return@PlanningBottomBar
                        scope.launch {
                            busy = true
                            val error = p.addWaypointAtCenter()
                            busy = false
                            if (error != null) snackbarHostState.showSnackbar("無法連到此點: $error")
                        }
                    },
                    onRemove = { planner?.removeLastWaypoint() },
                    onSave = { showSave = true },
                    onCancel = {
                        planner?.clear()
                        val baseline = editBaseline
                        if (baseline != null) {
                            viewer?.show(baseline)
                            displayedRoute = baseline
                            mode = PlanMode.ROUTE_VIEW
                        } else {
                            displayedRoute = null
                            mode = PlanMode.MAP_VIEW
                        }
                    },
                )
            }

            PlanMode.ROUTE_VIEW -> RouteViewControls(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                onEdit = {
                    val route = displayedRoute
                    if (route != null && planner != null) {
                        viewer?.clear()
                        planner.loadFrom(route)
                        editBaseline = route
                        mode = PlanMode.ROUTE_EDIT
                    }
                },
                onLeave = { mode = PlanMode.MAP_VIEW },
            )
        }
    }

    if (showChooser) {
        PlanEntryChooser(
            onNew = {
                showChooser = false
                if (engine.isReady()) {
                    viewer?.clear()
                    planner?.clear()
                    displayedRoute = null
                    editBaseline = null
                    mode = PlanMode.ROUTE_EDIT
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar("BRouter 路由資料未就緒, 無法規劃路徑")
                    }
                }
            },
            onLoad = {
                showChooser = false
                loadList = routeStore.list()
            },
            onCancel = { showChooser = false },
        )
    }

    if (showSave) {
        SaveRouteDialog(
            initialName = editBaseline?.name ?: "",
            onConfirm = { name ->
                val p = planner
                if (p != null) {
                    val saved = p.toPlannedRoute(name, System.currentTimeMillis())
                    routeStore.save(saved)
                    // Keep the route on screen: leave editing for view mode rather than clearing.
                    p.clear()
                    viewer?.show(saved)
                    displayedRoute = saved
                    editBaseline = saved
                    mode = PlanMode.ROUTE_VIEW
                }
                showSave = false
                scope.launch { snackbarHostState.showSnackbar("已儲存規劃路徑: $name") }
            },
            onDismiss = { showSave = false },
        )
    }

    loadList?.let { summaries ->
        LoadRouteDialog(
            summaries = summaries,
            onPick = { summary ->
                loadList = null
                // Opening a saved file shows it in view mode (per docs/ui.md); "編輯" enters editing.
                val route = routeStore.load(summary.file)
                planner?.clear()
                viewer?.show(route)
                displayedRoute = route
                mode = PlanMode.ROUTE_VIEW
            },
            onDismiss = { loadList = null },
        )
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
