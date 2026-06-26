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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import io.github.nexgus.jiudge.core.location.HeadingProvider
import io.github.nexgus.jiudge.core.location.LocationProvider
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
import io.github.nexgus.jiudge.data.route.TraceMigration
import io.github.nexgus.jiudge.feature.about.AboutDialog
import io.github.nexgus.jiudge.feature.about.MainMenuButton
import io.github.nexgus.jiudge.feature.identify.IdentifyBar
import io.github.nexgus.jiudge.feature.identify.IdentifyChooser
import io.github.nexgus.jiudge.feature.identify.IdentifyHint
import io.github.nexgus.jiudge.feature.identify.IdentifyResultCard
import io.github.nexgus.jiudge.feature.identify.SymbolIdentifier
import io.github.nexgus.jiudge.feature.identify.SymbolTable
import io.github.nexgus.jiudge.feature.map.CurrentLocationLayer
import io.github.nexgus.jiudge.feature.map.RudyMapView
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
import io.github.nexgus.jiudge.feature.planning.fitToRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
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

// Low-accuracy warning thresholds, with hysteresis to stop the banner flickering when the radius
// hovers near the limit: it appears once the fix is coarser than SHOW, and only hides once it
// improves past the tighter HIDE. Good open-sky GPS stays well under both.
private const val LOW_ACCURACY_SHOW_M = 50f
private const val LOW_ACCURACY_HIDE_M = 35f

// Once shown, the banner stays up at least this long so a brief coarse fix does not just flash by.
private const val LOW_ACCURACY_MIN_VISIBLE_MS = 3_000L

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

    // One planner / viewer per live MapView; recreated if the view is.
    val planner = remember(map.value) { map.value?.let { RoutePlanner(it, engine, density, demElevation) } }
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

    // Upgrade any legacy `.json` plans to the JSONL trace format once, in the background (spec §13).
    // Only meaningful when storage access is already held - legacy files could only have been written
    // under the same permission, so if it is not held there is nothing to migrate. The meta file makes
    // repeated launches a no-op.
    LaunchedEffect(Unit) {
        if (hasStorageAccess()) {
            withContext(Dispatchers.IO) { TraceMigration().migrateIfNeeded() }
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

    val locationLayer =
        remember(map.value) {
            map.value?.let { mv ->
                CurrentLocationLayer(density).also { mv.layerManager.layers.add(it) }
            }
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
    // pause so nothing runs in the background.
    DisposableEffect(lifecycleOwner, locationGranted, locationLayer) {
        if (!locationGranted || locationLayer == null) {
            onDispose {}
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            locationProvider.start()
                            headingProvider.start()
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            locationProvider.stop()
                            headingProvider.stop()
                        }
                        else -> Unit
                    }
                }
            // ON_RESUME will not re-fire if we are already resumed when this effect runs (e.g. the
            // user just granted permission), so start now in that case.
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                locationProvider.start()
                headingProvider.start()
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
            locationProvider.serviceEnabled,
        ) { fix, heading, enabled -> Triple(fix, heading, enabled) }
            .collect { (fix, heading, enabled) ->
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
                    hasCompass = headingProvider.hasCompass,
                    showAccuracy = SHOW_ACCURACY_CIRCLE,
                    // Location service off but we still hold a last fix: grey it to mark it stale.
                    frozen = !enabled && fix != null,
                )
                if (recenterOnFix && fix != null) {
                    recenterOnFix = false
                    map.value
                        ?.model
                        ?.mapViewPosition
                        ?.center = LatLong(fix.latitude, fix.longitude)
                }
            }
    }

    // Collected in composition (1 Hz, unlike the high-rate heading) to drive the GPS warning banner,
    // the FAB highlight, and the recenter behaviour.
    val currentFix by locationProvider.fix.collectAsState()
    val serviceEnabled by locationProvider.serviceEnabled.collectAsState()

    // The GPS warning banner shows whenever we lack a GPS-level precise fix - no fix, a coarse
    // WiFi/cell fix, or the location service being off. Once a precise GPS fix lands it clears, but
    // only after a minimum on-screen time so it never just flashes by; a small accuracy hysteresis
    // band stops it flickering at the threshold.
    var gpsWarnVisible by remember { mutableStateOf(false) }
    var gpsWarnShownAt by remember { mutableStateOf(0L) }
    var gpsWarnAccuracy by remember { mutableStateOf<Float?>(null) }
    // Measured height of the full-width warning banner, used to push the top controls below it.
    var gpsWarnHeightPx by remember { mutableStateOf(0) }
    val accuracy = currentFix?.accuracyMeters
    val haveGpsFix = serviceEnabled && currentFix?.fromGps == true && accuracy != null
    LaunchedEffect(locationGranted, haveGpsFix, accuracy) {
        gpsWarnAccuracy = accuracy
        when {
            !locationGranted -> gpsWarnVisible = false
            // No precise GPS yet (no fix, coarse network fix, or service off): warn.
            !haveGpsFix || accuracy > LOW_ACCURACY_SHOW_M -> {
                if (!gpsWarnVisible) {
                    gpsWarnVisible = true
                    gpsWarnShownAt = System.currentTimeMillis()
                }
            }
            // Precise GPS acquired: clear, but keep the banner up for its minimum time first.
            accuracy <= LOW_ACCURACY_HIDE_M -> {
                if (gpsWarnVisible) {
                    val remaining = LOW_ACCURACY_MIN_VISIBLE_MS - (System.currentTimeMillis() - gpsWarnShownAt)
                    if (remaining > 0) delay(remaining)
                    gpsWarnVisible = false
                }
            }
            // GPS fix in the hysteresis band (HIDE..SHOW): leave visibility unchanged.
        }
    }

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

        // The full-width warning banner sits flush below the status bar and persists across all modes
        // (including identify) so a low-accuracy warning is never hidden. When shown, the top controls
        // and the identify hint are pushed down by its measured height so it never covers them.
        val warnVisible = gpsWarnVisible
        val controlsTopOffset =
            if (warnVisible) with(LocalDensity.current) { gpsWarnHeightPx.toDp() } else 0.dp

        // Top-start controls, opposite the zoom column: the overflow ("⋮") menu on top, then the
        // identify ("?") toggle. Both persist across all modes.
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

        // GPS warning banner: a full-width bar flush below the status bar, shown while there is no
        // GPS-level precise fix. When the location service is off it reads as such and taps through to
        // the system location settings; otherwise it is a "waiting for / low-accuracy GPS" notice.
        // Hidden during identify, whose hint owns top-center.
        if (warnVisible) {
            GpsWarningBanner(
                serviceEnabled = serviceEnabled,
                accuracyMeters = if (currentFix != null) gpsWarnAccuracy else null,
                onClick =
                    if (serviceEnabled) {
                        null
                    } else {
                        { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onSizeChanged { gpsWarnHeightPx = it.height },
            )
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
            if (!identifyMode && identifyResult == null) {
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
 * Banner shown while there is no GPS-level precise fix. When [serviceEnabled] is false it warns that
 * the location service is off and (via [onClick]) taps through to settings; otherwise it is a
 * waiting/low-accuracy notice, reporting [accuracyMeters] when a coarse fix is available.
 */
@Composable
private fun GpsWarningBanner(
    serviceEnabled: Boolean,
    accuracyMeters: Float?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val text =
        when {
            !serviceEnabled -> "定位服務已關閉, 點此開啟"
            accuracyMeters != null -> "定位精度較低 (約 ±${accuracyMeters.toInt()} m), 等待 GPS 訊號..."
            else -> "尚未取得定位, 等待 GPS 訊號..."
        }
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        color = Color(0xFFFFE0B2), // light amber
        contentColor = Color(0xFF7A4F01), // dark amber, readable on the fill
        shape = RectangleShape, // full-width bar flush under the status bar
        shadowElevation = 6.dp,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
