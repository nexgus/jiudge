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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.core.index.Peak
import io.github.nexgus.jiudge.core.index.PeakIndex
import io.github.nexgus.jiudge.core.index.PeakIndexState
import io.github.nexgus.jiudge.core.location.HeadingProvider
import io.github.nexgus.jiudge.core.location.LocationProvider
import io.github.nexgus.jiudge.core.mapdata.DownloadService
import io.github.nexgus.jiudge.core.mapdata.DownloadState
import io.github.nexgus.jiudge.core.mapdata.MapDataCatalog
import io.github.nexgus.jiudge.core.mapdata.MapDataDownload
import io.github.nexgus.jiudge.core.mapdata.MapVersion
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.routing.BRouterProfile
import io.github.nexgus.jiudge.core.routing.ToughPathDetector
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
import io.github.nexgus.jiudge.feature.map.CurrentLocationLayer
import io.github.nexgus.jiudge.feature.map.LocationInfoDialog
import io.github.nexgus.jiudge.feature.map.RudyMapView
import io.github.nexgus.jiudge.feature.map.SearchPeakMarkerLayer
import io.github.nexgus.jiudge.feature.mapdata.DownloadScreen
import io.github.nexgus.jiudge.feature.planning.CrosshairOverlay
import io.github.nexgus.jiudge.feature.planning.DeleteRouteDialog
import io.github.nexgus.jiudge.feature.planning.LoadRouteDialog
import io.github.nexgus.jiudge.feature.planning.MapViewControls
import io.github.nexgus.jiudge.feature.planning.PlanEntryChooser
import io.github.nexgus.jiudge.feature.planning.PlanningBottomBar
import io.github.nexgus.jiudge.feature.planning.RenameRouteDialog
import io.github.nexgus.jiudge.feature.planning.RoutePlanner
import io.github.nexgus.jiudge.feature.planning.RouteViewControls
import io.github.nexgus.jiudge.feature.planning.RouteViewer
import io.github.nexgus.jiudge.feature.planning.SaveRouteDialog
import io.github.nexgus.jiudge.feature.planning.SearchTargetControls
import io.github.nexgus.jiudge.feature.planning.fitToRoute
import io.github.nexgus.jiudge.feature.search.PeakSearchDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.view.MapView
import java.io.File

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = AppPaths(this)
        // Copy the bundled routing profile into place before any planning can use it.
        BRouterProfile.install(this, paths.brouterDir)
        val catalog = MapDataCatalog(paths)
        setContent {
            MaterialTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                // Wrap the Scaffold so the status-bar scrim can be drawn at this outer level, where the
                // status bar inset is still available (the Scaffold consumes it to 0 for its content).
                Box(modifier = Modifier.fillMaxSize()) {
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

                    // Opaque bar behind the system status bar so its clock/battery sit on a solid
                    // strip, not on the map. Outside the Scaffold so the inset is not yet consumed.
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .windowInsetsTopHeight(WindowInsets.statusBars)
                                .background(Color(0xFF202124)),
                    )
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

// Whether the GPS accuracy circle is drawn. Hard-coded on for now; a future Settings screen
// (feature/settings, not yet built) will expose this as a user toggle.
private const val SHOW_ACCURACY_CIRCLE = true

// Centring on a searched peak keeps the current zoom, unless it is below MIN (too far out to see the
// summit), in which case it pulls in to PEAK_VIEW.
private const val PEAK_VIEW_MIN_ZOOM: Byte = 14
private const val PEAK_VIEW_ZOOM: Byte = 15

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

    // Screen pixels per dp; fed to the zoom-aware route/location overlays so their markers stay a
    // constant physical size across screens.
    val density = LocalDensity.current.density

    // DEM elevation source for route slope colouring (trace_spec.md §8). Null when the DEM folder is
    // absent, in which case the route renders without slope colour (grey) rather than failing.
    val demElevation = remember(mapDir) { DemElevation.createOrNull(File(mapDir, RudyMapView.DEM_DIR)) }

    // Reads the basemap to tell when a waypoint sits on a RudyMap "艱難路線", so that leg is routed
    // through the tough path instead of detoured around it (see RoutePlanner).
    val toughDetector = remember(mapDir) { ToughPathDetector(File(mapDir, RudyMapView.BASEMAP_NAME)) }
    DisposableEffect(toughDetector) { onDispose { toughDetector.close() } }

    // One planner / viewer per live MapView; recreated if the view is.
    val planner = remember(map.value) { map.value?.let { RoutePlanner(it, engine, density, demElevation, toughDetector) } }
    val viewer = remember(map.value) { map.value?.let { RouteViewer(it, density, demElevation) } }

    var mode by remember { mutableStateOf(PlanMode.MAP_VIEW) }
    var busy by remember { mutableStateOf(false) }

    // Main menu / "關於": the installed map version is read off the main thread once, so the dialog
    // can show it immediately when opened (null until loaded, or if no theme is installed).
    var aboutOpen by remember { mutableStateOf(false) }
    var mapVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mapDir) {
        mapVersion = withContext(Dispatchers.IO) { MapVersion.installed(mapDir) }
    }

    // Peak-position index backing the (future) name search. Checked on every entry to the map and
    // rebuilt off the main thread when missing or stale (basemap re-installed/updated); the progress
    // drives a non-blocking banner so the few-second build never reads as a frozen UI.
    val peakIndexState by PeakIndex.state.collectAsState()
    LaunchedEffect(mapDir) {
        withContext(Dispatchers.IO) { PeakIndex.ensureUpToDate(mapDir) }
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

    // Peak-name search index, loaded lazily on first search and then kept in memory for the rest of
    // this run, so returning to the result list never re-reads it from disk.
    var peakIndex by remember { mutableStateOf<List<Peak>?>(null) }

    // Whether the search dialog is shown. Separate from the index so the cached list can outlive the
    // dialog being closed.
    var searchDialogOpen by remember { mutableStateOf(false) }

    // Last search keyword, kept in memory so reopening the dialog within this run prefills it (and so
    // restores the same result list); gone when the app exits.
    var lastSearchQuery by remember { mutableStateOf("") }

    // Peak jumped to from the search dialog, awaiting 確認 / 回到搜尋結果. Non-null = the yellow marker
    // is shown and the bottom controls are taken over by the confirm/back pair.
    var pendingPeak by remember { mutableStateOf<Peak?>(null) }

    fun openSearch() {
        if (peakIndexState is PeakIndexState.Building) {
            scope.launch { snackbarHostState.showSnackbar("山頭索引建立中, 請稍候") }
            return
        }
        if (peakIndex != null) {
            searchDialogOpen = true
            return
        }
        scope.launch {
            val peaks = withContext(Dispatchers.IO) { PeakIndex.load(mapDir) }
            if (peaks.isNullOrEmpty()) {
                snackbarHostState.showSnackbar("山頭索引尚未就緒, 請稍候")
            } else {
                peakIndex = peaks
                searchDialogOpen = true
            }
        }
    }

    fun centerOnPeak(peak: Peak) {
        val mapView = map.value ?: return
        val position = mapView.model.mapViewPosition
        // Keep the user's current zoom unless they are zoomed too far out to see the summit, in which
        // case pull in to a peak-viewing level. Set centre + zoom together so the move is one jump.
        val targetZoom = if (position.zoomLevel < PEAK_VIEW_MIN_ZOOM) PEAK_VIEW_ZOOM else position.zoomLevel
        position.setMapPosition(MapPosition(peak.position, targetZoom))
    }

    var showChooser by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var loadList by remember { mutableStateOf<List<RouteStore.Summary>?>(null) }
    // Set while a rename/delete dialog is open over the load picker; null when none is pending.
    var renameTarget by remember { mutableStateOf<RouteStore.Summary?>(null) }
    var deleteTarget by remember { mutableStateOf<RouteStore.Summary?>(null) }
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

    // Current-location ("my location"): a blue dot + facing cone, fed by GPS and the compass only
    // while the map is visible (foreground-only, see CLAUDE.md). The overlay layer lives for the
    // life of the MapView; the recenter FAB below drives it.
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationProvider = remember { LocationProvider(context) }
    val headingProvider = remember { HeadingProvider(context) }
    var locationGranted by remember { mutableStateOf(locationProvider.hasPermission()) }
    // Center the map on the first fix: true at launch when permission is already held (so a returning
    // user opens straight onto their location), and re-armed when the recenter FAB is tapped.
    var recenterOnFix by remember { mutableStateOf(locationProvider.hasPermission()) }

    // Connection-info popup: long-pressing the location marker opens it; the DEM altitude shown there
    // is looked up off the main thread when it opens (independent of the GPS-reported altitude).
    var locationInfoOpen by remember { mutableStateOf(false) }
    var locationInfoDemAltitude by remember { mutableStateOf<Double?>(null) }

    val locationLayer =
        remember(map.value) {
            map.value?.let { mv ->
                CurrentLocationLayer(density, onMarkerLongPress = { locationInfoOpen = true })
                    .also { mv.layerManager.layers.add(it) }
            }
        }

    // Highlight for a peak jumped to from the search dialog: a translucent yellow disc that stays
    // until the user confirms the target or returns to the result list.
    val searchPeakLayer =
        remember(map.value) {
            map.value?.let { mv ->
                SearchPeakMarkerLayer(density)
                    .also { mv.layerManager.layers.add(it) }
            }
        }

    LaunchedEffect(searchPeakLayer, pendingPeak) {
        val peak = pendingPeak
        if (peak != null) searchPeakLayer?.show(peak.position) else searchPeakLayer?.clear()
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                locationGranted = true
                recenterOnFix = true
            } else {
                scope.launch { snackbarHostState.showSnackbar("未授予定位權限, 無法顯示目前位置") }
            }
        }

    // Subscribe to GPS + compass only while permission is held and the screen is resumed; release on
    // pause so nothing runs in the background. The observer stays mounted regardless of the current
    // grant so that ON_RESUME also catches the user adding (or revoking) permission from the system
    // settings page - that path does not fire any ActivityResultLauncher.
    DisposableEffect(lifecycleOwner, locationLayer) {
        if (locationLayer == null) {
            onDispose {}
        } else {
            fun syncOnResume() {
                // Pull the banner's StateFlow into sync first; it must be correct even when there is
                // nothing left to subscribe to (e.g. all location permissions were just revoked).
                locationProvider.refreshPermissionState()
                val granted = locationProvider.hasPermission()
                locationGranted = granted
                if (granted) {
                    locationProvider.start()
                    headingProvider.start()
                } else {
                    locationProvider.stop()
                    headingProvider.stop()
                }
            }
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> syncOnResume()
                        Lifecycle.Event.ON_PAUSE -> {
                            locationProvider.stop()
                            headingProvider.stop()
                        }
                        else -> Unit
                    }
                }
            // ON_RESUME will not re-fire if we are already resumed when this effect runs (e.g. the
            // user just granted permission), so sync now in that case.
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                syncOnResume()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                locationProvider.stop()
                headingProvider.stop()
            }
        }
    }

    // Feed fixes + heading into the overlay by collecting in an effect (not collectAsState) so the
    // frequent compass ticks redraw only the map layer, not the whole MapScreen composable.
    LaunchedEffect(locationLayer) {
        val layer = locationLayer ?: return@LaunchedEffect
        combine(
            locationProvider.fix,
            headingProvider.heading,
            headingProvider.headingAccuracyDeg,
            locationProvider.serviceEnabled,
        ) { fix, heading, headingAccuracy, enabled ->
            // The compass reads magnetic north; shift it by the local declination so the facing
            // cone lines up with the true-north map. GPS movement bearing is already true north.
            val trueHeading =
                if (heading != null && fix != null) {
                    (heading + fix.declinationDeg + 360f) % 360f
                } else {
                    heading
                }
            layer.update(
                fix = fix,
                headingDeg = trueHeading,
                headingAccuracyDeg = headingAccuracy,
                hasCompass = headingProvider.hasCompass,
                showAccuracy = SHOW_ACCURACY_CIRCLE,
                // Location service off but we still hold a last fix: grey it to mark it stale.
                frozen = !enabled && fix != null,
            )
            fix
        }.collect { fix ->
            if (recenterOnFix && fix != null) {
                recenterOnFix = false
                map.value
                    ?.model
                    ?.mapViewPosition
                    ?.center = LatLong(fix.latitude, fix.longitude)
            }
        }
    }

    // Collected in composition (1 Hz, unlike the high-rate heading) to drive the FAB highlight and the
    // recenter behaviour.
    val currentFix by locationProvider.fix.collectAsState()
    val serviceEnabled by locationProvider.serviceEnabled.collectAsState()
    val gnss by locationProvider.gnss.collectAsState()
    // Drives the persistent "only coarse location granted" warning banner. Refreshed inside
    // LocationProvider.start() on every ON_RESUME so an upgrade to precise location made from the
    // system settings page clears the banner automatically.
    val fineLocationGranted by locationProvider.fineLocationGranted.collectAsState()

    // Refresh the popup's DEM altitude whenever it opens or the fix moves, off the main thread (the
    // first touch of a DEM tile memory-maps it). Cleared when the popup is closed.
    LaunchedEffect(locationInfoOpen, currentFix?.latitude, currentFix?.longitude) {
        locationInfoDemAltitude =
            if (locationInfoOpen && currentFix != null && demElevation != null) {
                val fix = currentFix!!
                withContext(Dispatchers.IO) { demElevation.elevationAt(fix.latitude, fix.longitude)?.toDouble() }
            } else {
                null
            }
    }

    // Measured height of the top banner stack (peak-index build), used to push the top controls below
    // whatever is currently shown.
    var topBannersHeightPx by remember { mutableStateOf(0) }

    fun recenterOnCurrentLocation() {
        if (!locationGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        if (!serviceEnabled) {
            // No point waiting for a fix that cannot come; send the user to enable location.
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val fix = locationProvider.fix.value
        if (fix != null) {
            map.value
                ?.model
                ?.mapViewPosition
                ?.center = LatLong(fix.latitude, fix.longitude)
        } else {
            recenterOnFix = true
            scope.launch { snackbarHostState.showSnackbar("正在定位中, 取得位置後將自動置中") }
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

        // Driven by the measured banner-stack height: 0 when nothing is shown (empty column), so the
        // controls sit flush at the top and drop only while a banner is up.
        val controlsTopOffset = with(LocalDensity.current) { topBannersHeightPx.toDp() }

        // Top-start controls, opposite the zoom column: the overflow ("⋮") menu on top, then the
        // identify ("?") toggle, then the peak search ("🔍"). All persist across all modes.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = controlsTopOffset)
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
            SmallFloatingActionButton(
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
            // Peak-name search: opens a dialog to look up a summit and centre the map on it.
            SmallFloatingActionButton(onClick = { openSearch() }) {
                Text(text = "🔍", fontSize = 20.sp)
            }
        }

        // Aim with the centre crosshair, then "辨識" reads the symbol there. Hidden once a result
        // card or chooser is up so the reticle does not sit over the answer.
        if (identifyMode && identifyResult == null && identifyCandidates == null) {
            CrosshairOverlay(modifier = Modifier.fillMaxSize())
            // Centred horizontally (its original, roomy position) but dropped to the "?" button's row
            // so it reads as that button's instruction. Follows controlsTopOffset to stay below the
            // warning banner. No width cap, so the text stays on one line as before.
            IdentifyHint(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = controlsTopOffset + 90.dp),
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

        // Top banner stack, flush below the status bar and measured as one column so the top controls
        // and identify hint drop below whatever is showing. Holds the transient peak-index
        // build/failure banner and the persistent coarse-location warning.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { topBannersHeightPx = it.height },
        ) {
            when (val state = peakIndexState) {
                is PeakIndexState.Building ->
                    PeakIndexBanner(fraction = state.fraction, failed = false, modifier = Modifier.fillMaxWidth())
                PeakIndexState.Failed ->
                    PeakIndexBanner(fraction = null, failed = true, modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
            // Persistent warning whenever precise location is not granted. Tapping reissues the same
            // permission request the recenter button uses, so the system shows its standard dialog
            // (with the precise/approximate toggle) - a single tap upgrades the grant. No dismiss
            // button: accuracy stays poor until precise is granted, so the warning stays put.
            if (!fineLocationGranted) {
                FineLocationMissingBanner(
                    text =
                        if (locationGranted) {
                            "目前僅授予概略位置, 定位點可能誤差數百公尺. 點此改為精確"
                        } else {
                            "尚未授予定位權限, 點此授予"
                        },
                    onClick = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Recenter-on-me button, bottom-end, on the same baseline as the bottom-start controls (same
        // padding, no extra inset) so they share one horizontal line. Hidden during identify and route
        // editing so it does not collide with their bottom bars; highlighted only when location is
        // both granted and the service is on.
        if (!identifyMode &&
            identifyResult == null &&
            identifyCandidates == null &&
            mode != PlanMode.ROUTE_EDIT
        ) {
            MyLocationButton(
                onClick = { recenterOnCurrentLocation() },
                active = locationGranted && serviceEnabled,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
            )
        }

        // Crosshair only while editing a route (independent of the bottom controls row).
        if (mode == PlanMode.ROUTE_EDIT && !identifyMode && identifyResult == null) {
            CrosshairOverlay(modifier = Modifier.fillMaxSize())
        }

        // Bottom-start controls: the mode-specific action buttons sit in the lower-left corner and
        // are hidden during identify, whose own bar/card owns the bottom.
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!identifyMode && identifyResult == null && pendingPeak != null) {
                SearchTargetControls(
                    onConfirm = { pendingPeak = null },
                    onBackToResults = {
                        pendingPeak = null
                        openSearch()
                    },
                )
            } else if (!identifyMode && identifyResult == null) {
                when (mode) {
                    PlanMode.MAP_VIEW ->
                        MapViewControls(
                            canClear = displayedRoute != null,
                            onClear = {
                                viewer?.clear()
                                displayedRoute = null
                            },
                            onPlan = { showChooser = true },
                        )

                    PlanMode.ROUTE_EDIT ->
                        PlanningBottomBar(
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

                    PlanMode.ROUTE_VIEW ->
                        RouteViewControls(
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
                        // Frame the whole trace on file load (only here - not on save/cancel).
                        map.value?.fitToRoute(route.polyline.ifEmpty { route.waypoints })
                        displayedRoute = route
                        mode = PlanMode.ROUTE_VIEW
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("載入失敗: ${e.message}")
                    }
                }
            },
            onRename = { renameTarget = it },
            onDelete = { deleteTarget = it },
            onDismiss = { loadList = null },
        )
    }

    renameTarget?.let { target ->
        RenameRouteDialog(
            initialName = target.name,
            onConfirm = { newName ->
                renameTarget = null
                withStorageAccess {
                    scope.launch {
                        try {
                            val renamed = withContext(Dispatchers.IO) { routeStore.rename(target.file, newName) }
                            loadList = withContext(Dispatchers.IO) { routeStore.list() }
                            // Keep the on-screen route's name in sync if it was the one renamed.
                            if (displayedRoute?.createdAtEpochMs == target.createdAtEpochMs) {
                                displayedRoute = displayedRoute?.copy(name = renamed.name)
                                editBaseline = editBaseline?.copy(name = renamed.name)
                            }
                            snackbarHostState.showSnackbar("已改名為 \"${renamed.name}\"")
                        } catch (e: DuplicateRouteNameException) {
                            renameTarget = target
                            snackbarHostState.showSnackbar("已有同名路線 \"${e.routeName}\", 請改用其他名稱")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("改名失敗: ${e.message}")
                        }
                    }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        DeleteRouteDialog(
            routeName = target.name,
            onConfirm = {
                deleteTarget = null
                withStorageAccess {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { routeStore.delete(target.file) }
                            loadList = withContext(Dispatchers.IO) { routeStore.list() }
                            snackbarHostState.showSnackbar("已刪除 \"${target.name}\"")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("刪除失敗: ${e.message}")
                        }
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (aboutOpen) {
        AboutDialog(mapVersion = mapVersion, onDismiss = { aboutOpen = false })
    }

    if (searchDialogOpen) {
        peakIndex?.let { peaks ->
            PeakSearchDialog(
                peaks = peaks,
                initialQuery = lastSearchQuery,
                onQueryChange = { lastSearchQuery = it },
                onPick = { peak ->
                    searchDialogOpen = false
                    pendingPeak = peak
                    centerOnPeak(peak)
                },
                onDismiss = { searchDialogOpen = false },
            )
        }
    }

    if (locationInfoOpen) {
        LocationInfoDialog(
            fix = currentFix,
            gnss = gnss,
            serviceEnabled = serviceEnabled,
            demAltitude = locationInfoDemAltitude,
            onDismiss = { locationInfoOpen = false },
        )
    }
}

/**
 * "Recenter on my location" FAB. Tapping requests location permission the first time, then centers
 * the map on the current fix. [active] highlights it once location is on, matching the identify
 * toggle's lit state.
 */
@Composable
private fun MyLocationButton(
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor =
            if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                FloatingActionButtonDefaults.containerColor
            },
    ) {
        // U+25CE bullseye - a "locate me" glyph, consistent with the app's text-based FAB icons.
        Text(text = "◎", fontSize = 24.sp)
    }
}

/**
 * Banner for the peak-index build. While building it shows a determinate progress bar with the
 * percentage so the user sees forward motion (never a frozen-looking pause); on [failed] it states
 * the failure and that the next launch retries. Non-blocking - the map stays usable throughout.
 */
@Composable
private fun PeakIndexBanner(
    fraction: Float?,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val text =
        if (failed) {
            "山頭索引建立失敗, 下次啟動時會自動重試"
        } else {
            "正在建立山頭索引... ${((fraction ?: 0f) * 100).toInt()}%"
        }
    Surface(
        modifier = modifier,
        color = if (failed) Color(0xFFFFCDD2) else Color(0xFFBBDEFB), // light red / light blue
        contentColor = if (failed) Color(0xFF7A1B1B) else Color(0xFF0D3C61),
        shape = RectangleShape, // full-width bar flush under the status bar
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text = text, fontSize = 14.sp)
            if (!failed) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Persistent banner shown while precise location is not granted - covers both "no location grant
 * at all" and "coarse only" with caller-supplied wording. The whole surface is the tap target, and
 * there is no dismiss control: precision stays poor (or the dot stays missing) until precise is
 * granted, so the warning stays put.
 */
@Composable
private fun FineLocationMissingBanner(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Color(0xFFFFE0B2), // warning amber, distinct from the index banner's blue/red
        contentColor = Color(0xFF8B5E00),
        shape = RectangleShape,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
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
