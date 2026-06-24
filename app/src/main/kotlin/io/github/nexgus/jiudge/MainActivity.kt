package io.github.nexgus.jiudge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.nexgus.jiudge.core.mapdata.DownloadService
import io.github.nexgus.jiudge.core.mapdata.DownloadState
import io.github.nexgus.jiudge.core.mapdata.MapDataCatalog
import io.github.nexgus.jiudge.core.mapdata.MapDataDownload
import io.github.nexgus.jiudge.core.mapdata.MapVersion
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.storage.AppPaths
import io.github.nexgus.jiudge.data.route.DuplicateRouteNameException
import io.github.nexgus.jiudge.data.route.PlannedRoute
import io.github.nexgus.jiudge.data.route.RouteStore
import io.github.nexgus.jiudge.feature.about.AboutDialog
import io.github.nexgus.jiudge.feature.about.MainMenuButton
import io.github.nexgus.jiudge.feature.identify.IdentifyBar
import io.github.nexgus.jiudge.feature.identify.IdentifyChooser
import io.github.nexgus.jiudge.feature.identify.IdentifyHint
import io.github.nexgus.jiudge.feature.identify.IdentifyResultCard
import io.github.nexgus.jiudge.feature.identify.SymbolIdentifier
import io.github.nexgus.jiudge.feature.identify.SymbolTable
import io.github.nexgus.jiudge.feature.map.RudyMapView
import io.github.nexgus.jiudge.feature.mapdata.DownloadScreen
import io.github.nexgus.jiudge.feature.planning.CrosshairOverlay
import io.github.nexgus.jiudge.feature.planning.LoadRouteDialog
import io.github.nexgus.jiudge.feature.planning.MapViewControls
import io.github.nexgus.jiudge.feature.planning.PlanEntryChooser
import io.github.nexgus.jiudge.feature.planning.PlanningBottomBar
import io.github.nexgus.jiudge.feature.planning.RoutePlanner
import io.github.nexgus.jiudge.feature.planning.RouteViewControls
import io.github.nexgus.jiudge.feature.planning.RouteViewer
import io.github.nexgus.jiudge.feature.planning.SaveRouteDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.model.common.Observer
import java.io.File

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = AppPaths(this)
        val catalog = MapDataCatalog(paths)
        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    val downloadState by MapDataDownload.state.collectAsState()
                    // Re-check the disk whenever the download state changes (so finishing flips us to the map).
                    val requiredReady = remember(downloadState) { catalog.missing(includeOptional = false).isEmpty() }
                    val showDownload =
                        downloadState is DownloadState.Running ||
                            downloadState is DownloadState.Failed ||
                            !requiredReady
                    if (showDownload) {
                        DownloadScreen(
                            state = downloadState,
                            totalBytes = catalog.totalDownloadBytes,
                            onStart = startDownload(),
                            onCancel = { DownloadService.cancel(this@MainActivity) },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding),
                        )
                    } else {
                        MapScreen(
                            mapDir = paths.mapDir,
                            engine = remember { BRouterEngine(paths.brouterDir) },
                            routeStore = remember { RouteStore() },
                            snackbarHostState = snackbarHostState,
                            onMapCreated = { mapView = it },
                            modifier =
                                Modifier
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
}

/** The three route-planning UI modes (see docs/ui.md). */
private enum class PlanMode { MAP_VIEW, ROUTE_EDIT, ROUTE_VIEW }

@Composable
private fun MapScreen(
    mapDir: File,
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

    // Main menu / "關於": the installed map version is read off the main thread once, so the dialog
    // can show it immediately when opened (null until loaded, or if no theme is installed).
    var aboutOpen by remember { mutableStateOf(false) }
    var mapVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mapDir) {
        mapVersion = withContext(Dispatchers.IO) { MapVersion.installed(mapDir) }
    }

    // Symbol-identify mode: "?" toggles a centre crosshair; "辨識" names the symbol under it. Aiming
    // by panning (not by tapping a tiny icon) keeps it precise regardless of finger size.
    var identifyMode by remember { mutableStateOf(false) }
    var identifyBusy by remember { mutableStateOf(false) }
    var identifyResult by remember { mutableStateOf<SymbolIdentifier.Match?>(null) }
    // Candidates awaiting a choice when several features share the crosshair spot.
    var identifyCandidates by remember { mutableStateOf<List<SymbolIdentifier.Match>?>(null) }
    val appContext = LocalContext.current.applicationContext
    val symbolTable = remember { SymbolTable.load(appContext) }
    val identifier =
        remember(mapDir, symbolTable) {
            SymbolIdentifier(File(mapDir, RudyMapView.BASEMAP_NAME), symbolTable)
        }
    DisposableEffect(identifier) { onDispose { identifier.close() } }

    fun runIdentify() {
        val mapView = map.value ?: return
        val center = mapView.model.mapViewPosition.center
        val zoom = mapView.model.mapViewPosition.zoomLevel
        val tileSize = mapView.model.displayModel.tileSize
        scope.launch {
            identifyBusy = true
            val matches = withContext(Dispatchers.IO) { identifier.identify(center, zoom, tileSize) }
            identifyBusy = false
            when {
                matches.isEmpty() -> snackbarHostState.showSnackbar("準心處沒有可辨識的符號")
                matches.size == 1 -> identifyResult = matches.first()
                else -> identifyCandidates = matches
            }
        }
    }

    var showChooser by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var loadList by remember { mutableStateOf<List<RouteStore.Summary>?>(null) }
    // Route currently drawn by the viewer: shown in ROUTE_VIEW, and kept in MAP_VIEW after 離開.
    var displayedRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    // What ROUTE_EDIT "取消" reverts to: set when entering edit, refreshed on save.
    var editBaseline by remember { mutableStateOf<PlannedRoute?>(null) }
    // Raised on 新增, lowered after the first save: a brand-new route must not reuse an existing
    // name, but re-saving an edited route may keep its own name.
    var isNewRoute by remember { mutableStateOf(false) }
    // Name kept across a rejected-duplicate save so the reopened dialog prefills it for editing.
    var saveNameDraft by remember { mutableStateOf<String?>(null) }

    // Storage-access gate: saving/loading routes writes the fixed public folder Documents/Jiudge,
    // which needs All files access on Android 11+ (a Settings toggle) or legacy WRITE_EXTERNAL_STORAGE
    // below. We defer the action behind a rationale dialog, then send the user to grant it; the action
    // runs once access is held, or is dropped if denied.
    val context = LocalContext.current
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showStorageRationale by remember { mutableStateOf(false) }

    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun runOrDropPending(granted: Boolean) {
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) {
            action?.invoke()
        } else {
            scope.launch { snackbarHostState.showSnackbar("未取得存取權, 無法儲存或讀取路線") }
        }
    }

    val allFilesAccessLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            runOrDropPending(hasStorageAccess())
        }
    val writePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            runOrDropPending(granted)
        }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesAccessLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        } else {
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun withStorageAccess(action: () -> Unit) {
        if (hasStorageAccess()) {
            action()
        } else {
            pendingStorageAction = action
            showStorageRationale = true
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                RudyMapView.create(ctx, mapDir).also {
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

        // Top-start controls, opposite the zoom column: the overflow ("⋮") menu on top, then the
        // identify ("?") toggle. Both persist across all modes.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MainMenuButton(
                onAbout = { aboutOpen = true },
                onCheckUpdate = {
                    // TODO(spec Phase 3): wire to the map-data update check/download mechanism.
                    scope.launch { snackbarHostState.showSnackbar("地圖更新功能尚未推出 (Phase 3)") }
                },
            )
            // Identify toggle: highlighted when on, with a centre crosshair for aiming.
            FloatingActionButton(
                onClick = {
                    identifyMode = !identifyMode
                    if (!identifyMode) {
                        identifyResult = null
                        identifyCandidates = null
                    }
                },
                containerColor =
                    if (identifyMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        FloatingActionButtonDefaults.containerColor
                    },
            ) {
                Text(text = "?", fontSize = 24.sp)
            }
        }

        // Aim with the centre crosshair, then "辨識" reads the symbol there. Hidden once a result
        // card or chooser is up so the reticle does not sit over the answer.
        if (identifyMode && identifyResult == null && identifyCandidates == null) {
            CrosshairOverlay(modifier = Modifier.fillMaxSize())
            IdentifyHint(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
            )
            IdentifyBar(
                busy = identifyBusy,
                onIdentify = { runIdentify() },
                onCancel = {
                    identifyMode = false
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
            )
        }

        // A result card or a chooser is up: lock the map and highlight the relevant point(s).
        if (identifyResult != null || identifyCandidates != null) {
            // Lock the map: consume all touches so it cannot pan or zoom while choosing/reading.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
            )
            // Highlight: a translucent yellow disc on the chosen feature; small dots on the other
            // candidates while choosing, so the user sees where each one sits.
            map.value?.let { mapView ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    identifyCandidates?.forEach { candidate ->
                        val p = mapView.mapViewProjection.toPixels(candidate.position) ?: return@forEach
                        drawCircle(Color(0x66000000), 5.dp.toPx(), Offset(p.x.toFloat(), p.y.toFloat()))
                    }
                    identifyResult?.let { result ->
                        val p = mapView.mapViewProjection.toPixels(result.position)
                        if (p != null) {
                            drawCircle(Color(0x80FFEB3B), 16.dp.toPx(), Offset(p.x.toFloat(), p.y.toFloat()))
                        }
                    }
                }
            }
            identifyResult?.let { result ->
                IdentifyResultCard(
                    themeDir = mapDir,
                    table = symbolTable,
                    result = result,
                    onDismiss = { identifyResult = null },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // Lift the card clear of the system navigation bar so all content shows.
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp),
                )
            }
        }

        // Zoom controls persist across all modes (map controls, independent of the action bar).
        ZoomButtons(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            zoomLevel = zoomLevel.value,
            onZoomIn = {
                map.value
                    ?.model
                    ?.mapViewPosition
                    ?.zoomIn()
            },
            onZoomOut = {
                map.value
                    ?.model
                    ?.mapViewPosition
                    ?.zoomOut()
            },
        )

        // Hide the bottom mode controls during identify (its own bar/card owns the bottom).
        if (!identifyMode && identifyResult == null) {
            when (mode) {
                PlanMode.MAP_VIEW ->
                    MapViewControls(
                        modifier =
                            Modifier
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
                        modifier =
                            Modifier
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

                PlanMode.ROUTE_VIEW ->
                    RouteViewControls(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                        onEdit = {
                            val route = displayedRoute
                            if (route != null && planner != null) {
                                viewer?.clear()
                                planner.loadFrom(route)
                                editBaseline = route
                                isNewRoute = false
                                mode = PlanMode.ROUTE_EDIT
                            }
                        },
                        onLeave = { mode = PlanMode.MAP_VIEW },
                    )
            }
        }
    }

    identifyCandidates?.let { candidates ->
        IdentifyChooser(
            themeDir = mapDir,
            candidates = candidates,
            onPick = { picked ->
                identifyResult = picked
                identifyCandidates = null
            },
            onDismiss = { identifyCandidates = null },
        )
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
                    isNewRoute = true
                    mode = PlanMode.ROUTE_EDIT
                } else {
                    scope.launch {
                        snackbarHostState.showSnackbar("BRouter 路由資料未就緒, 無法規劃路徑")
                    }
                }
            },
            onLoad = {
                showChooser = false
                withStorageAccess {
                    scope.launch {
                        try {
                            loadList = withContext(Dispatchers.IO) { routeStore.list() }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("讀取清單失敗: ${e.message}")
                        }
                    }
                }
            },
            onCancel = { showChooser = false },
        )
    }

    if (showSave) {
        SaveRouteDialog(
            initialName = saveNameDraft ?: editBaseline?.name ?: "",
            onConfirm = { name ->
                val p = planner
                showSave = false
                if (p != null) {
                    val saved = p.toPlannedRoute(name, System.currentTimeMillis())
                    withStorageAccess {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { routeStore.save(saved, checkDuplicate = isNewRoute) }
                                // Keep the route on screen: leave editing for view mode, not cleared.
                                p.clear()
                                viewer?.show(saved)
                                displayedRoute = saved
                                editBaseline = saved
                                isNewRoute = false
                                saveNameDraft = null
                                mode = PlanMode.ROUTE_VIEW
                                snackbarHostState.showSnackbar("已儲存規劃路徑: $name")
                            } catch (e: DuplicateRouteNameException) {
                                // Keep the typed name and reopen so the user can rename in place.
                                saveNameDraft = name
                                showSave = true
                                snackbarHostState.showSnackbar("已有同名路線 \"${e.routeName}\", 請改用其他名稱")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("儲存失敗: ${e.message}")
                            }
                        }
                    }
                }
            },
            onDismiss = {
                showSave = false
                saveNameDraft = null
            },
        )
    }

    if (showStorageRationale) {
        AlertDialog(
            onDismissRequest = {
                showStorageRationale = false
                pendingStorageAction = null
            },
            title = { Text("需要 \"所有檔案存取權\"") },
            text = {
                Text(
                    "Jiudge 會將你規劃的路線存放在 \"文件/Jiudge\" 資料夾. 為了讓這些路線在解除安裝後仍然保留, " +
                        "重新安裝後也能直接讀回, app 需要系統的 \"所有檔案存取權\". 此權限僅用於讀寫本 app 自己的路線檔.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStorageRationale = false
                    requestStorageAccess()
                }) { Text("前往設定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStorageRationale = false
                    pendingStorageAction = null
                }) { Text("稍後再說") }
            },
        )
    }

    loadList?.let { summaries ->
        LoadRouteDialog(
            summaries = summaries,
            onPick = { summary ->
                loadList = null
                // Opening a saved file shows it in view mode (per docs/ui.md); "編輯" enters editing.
                scope.launch {
                    try {
                        val route = withContext(Dispatchers.IO) { routeStore.load(summary.file) }
                        planner?.clear()
                        viewer?.show(route)
                        displayedRoute = route
                        mode = PlanMode.ROUTE_VIEW
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("載入失敗: ${e.message}")
                    }
                }
            },
            onDismiss = { loadList = null },
        )
    }

    if (aboutOpen) {
        AboutDialog(mapVersion = mapVersion, onDismiss = { aboutOpen = false })
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

/**
 * Builds the "start download" action: on Android 13+ it first requests POST_NOTIFICATIONS (so the
 * progress notification is visible) and starts the service from the result callback; otherwise it
 * starts immediately. The download proceeds whether or not the permission is granted.
 */
@Composable
private fun startDownload(): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { DownloadService.start(context) }
    return {
        val needsNotifPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotifPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DownloadService.start(context)
        }
    }
}
