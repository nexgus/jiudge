package io.github.nexgus.jiudge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.MotionEvent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
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
import io.github.nexgus.jiudge.core.location.GpsSource
import io.github.nexgus.jiudge.core.location.HeadingProvider
import io.github.nexgus.jiudge.core.location.LocationFix
import io.github.nexgus.jiudge.core.mapdata.DownloadService
import io.github.nexgus.jiudge.core.mapdata.DownloadState
import io.github.nexgus.jiudge.core.mapdata.MapDataCatalog
import io.github.nexgus.jiudge.core.mapdata.MapDataDownload
import io.github.nexgus.jiudge.core.mapdata.MapVersion
import io.github.nexgus.jiudge.core.recording.RecordingController
import io.github.nexgus.jiudge.core.recording.RecordingService
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.routing.BRouterProfile
import io.github.nexgus.jiudge.core.routing.ToughPathDetector
import io.github.nexgus.jiudge.core.storage.AppPaths
import io.github.nexgus.jiudge.data.route.DuplicateRouteNameException
import io.github.nexgus.jiudge.data.route.DuplicateTrackNameException
import io.github.nexgus.jiudge.data.route.PlannedRoute
import io.github.nexgus.jiudge.data.route.RecordedTrack
import io.github.nexgus.jiudge.data.route.RouteStore
import io.github.nexgus.jiudge.data.route.TrackStore
import io.github.nexgus.jiudge.feature.about.AboutDialog
import io.github.nexgus.jiudge.feature.about.MainMenuButton
import io.github.nexgus.jiudge.feature.identify.IdentifyBar
import io.github.nexgus.jiudge.feature.identify.IdentifyChooser
import io.github.nexgus.jiudge.feature.identify.IdentifyHint
import io.github.nexgus.jiudge.feature.identify.IdentifyResultCard
import io.github.nexgus.jiudge.feature.identify.SymbolIdentifier
import io.github.nexgus.jiudge.feature.identify.SymbolTable
import io.github.nexgus.jiudge.feature.map.CurrentLocationLayer
import io.github.nexgus.jiudge.feature.map.LabelOverlayView
import io.github.nexgus.jiudge.feature.map.LocationInfoDialog
import io.github.nexgus.jiudge.feature.map.MapBearingMode
import io.github.nexgus.jiudge.feature.map.MapBearingPrefs
import io.github.nexgus.jiudge.feature.map.MapFollow
import io.github.nexgus.jiudge.feature.map.RudyMapView
import io.github.nexgus.jiudge.feature.map.SearchPeakMarkerLayer
import io.github.nexgus.jiudge.feature.map.applyMapBearing
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
import io.github.nexgus.jiudge.feature.recording.BackgroundLocationRationaleDialog
import io.github.nexgus.jiudge.feature.recording.DeleteTrackDialog
import io.github.nexgus.jiudge.feature.recording.DiscardRecordingDialog
import io.github.nexgus.jiudge.feature.recording.HISTORY_CHEVRON_COLOR
import io.github.nexgus.jiudge.feature.recording.HISTORY_CHEVRON_HALO_COLOR
import io.github.nexgus.jiudge.feature.recording.HistoryTrackViewControls
import io.github.nexgus.jiudge.feature.recording.LoadTrackDialog
import io.github.nexgus.jiudge.feature.recording.RecordEntryChooser
import io.github.nexgus.jiudge.feature.recording.RecordedTrackLayer
import io.github.nexgus.jiudge.feature.recording.Recorder
import io.github.nexgus.jiudge.feature.recording.RecordingBottomBar
import io.github.nexgus.jiudge.feature.recording.RenameTrackDialog
import io.github.nexgus.jiudge.feature.recording.SaveTrackDialog
import io.github.nexgus.jiudge.feature.search.PeakSearchDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.model.common.Observer
import java.io.File

/**
 * A held-over recording start request waiting for the permission flow to finish. A null
 * [continuationSource] means a brand-new recording; non-null means continue that saved track.
 */
private data class PendingRecordingStart(
    val continuationSource: File?,
)

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
                                trackStore = remember { TrackStore() },
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
    trackStore: TrackStore,
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

    // Recording session - driven by [RecordingService] (foreground service + PARTIAL_WAKE_LOCK so
    // the track keeps being written with the screen off and the process in Doze). The activity
    // does not hold the [Recorder] directly: it goes through [RecordingController] for state
    // (overlay polyline, save-dialog trigger) and through service intents for start / stop.
    val recordingState by RecordingController.state.collectAsState()
    val recordedPoints by RecordingController.points.collectAsState()
    // Set when the service stops mid-session (either via the bottom-bar button or the
    // notification's stop action); drives the save / discard dialog flow.
    val pendingRecordingSession by RecordingController.pendingSession.collectAsState()
    val pendingSessionPoints by RecordingController.lastSessionPoints.collectAsState()

    // Dialog flags for the recording flow. saveTrackNameDraft mirrors saveNameDraft - preserved
    // across a rejected duplicate so the reopened dialog prefills the typed name for editing.
    var showRecordEntryChooser by remember { mutableStateOf(false) }
    var showSaveTrack by remember { mutableStateOf(false) }
    var showDiscardRecording by remember { mutableStateOf(false) }
    var loadTrackList by remember { mutableStateOf<List<TrackStore.Summary>?>(null) }
    var renameTrackTarget by remember { mutableStateOf<TrackStore.Summary?>(null) }
    var deleteTrackTarget by remember { mutableStateOf<TrackStore.Summary?>(null) }
    var saveTrackNameDraft by remember { mutableStateOf<String?>(null) }
    // Pending start request held across the permission flow. null source = brand-new recording;
    // non-null source = continuation of that saved track. Cleared once the service is dispatched
    // or the user cancels the rationale.
    var pendingRecordingStart by remember { mutableStateOf<PendingRecordingStart?>(null) }
    var showBackgroundLocationRationale by remember { mutableStateOf(false) }

    // The GpsSource lease this activity currently holds, if any. Managed by the DisposableEffect
    // that tracks the map screen's lifecycle, by the RecordingController.state observer, and by
    // dispatchPendingStart() (which releases it before dispatching the start Intent so the service's
    // Strict-lease Recording acquire cannot collide). Declared up here rather than beside the other
    // map-marker state so dispatchPendingStart, defined a few lines below, can see it.
    var gpsOwnership: GpsSource.Ownership? by remember { mutableStateOf(null) }

    // History-track viewing state. historyTrack holds the loaded RecordedTrack (null when nothing is
    // loaded); historyTrackFile is the on-disk file it was loaded from (kept so 繼續錄製 can hand it
    // to recorder.startContinuation without re-resolving by name). viewingHistory is the sub-mode
    // flag that surfaces the "繼續錄製 / 離開" action bar - 離開 keeps historyTrack on screen but
    // exits the sub-mode, so the user sees the main map controls again with the blue overlay still
    // visible until cleared.
    var historyTrack by remember { mutableStateOf<RecordedTrack?>(null) }
    var historyTrackFile by remember { mutableStateOf<File?>(null) }
    var viewingHistory by remember { mutableStateOf(false) }

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
            scope.launch { snackbarHostState.showSnackbar("未取得存取權, 無法儲存或讀取資料") }
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

    // Recording-start permission flow: POST_NOTIFICATIONS on Android 13+ (asked inline; the service
    // still works without it, the notification is just hidden), then ACCESS_BACKGROUND_LOCATION on
    // Android 10+ (the OS refuses to grant this via inline dialog on Android 11+, so we walk the
    // user to the system settings page via [BackgroundLocationRationaleDialog]).
    fun dispatchPendingStart() {
        val pending = pendingRecordingStart ?: return
        // Strict lease: the service will acquire GpsSource as Mode.Recording. Release the Foreground
        // lease here (not only via the RecordingController.state observer, which fires after
        // handleStartNew flips the state) so the two acquires cannot race.
        gpsOwnership?.release()
        gpsOwnership = null
        val src = pending.continuationSource
        if (src == null) {
            RecordingService.startNew(context)
        } else {
            RecordingService.startContinuation(context, src)
        }
        pendingRecordingStart = null
    }

    fun proceedAfterNotifPermission() {
        if (pendingRecordingStart == null) return
        val needsBg =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
        if (needsBg) {
            showBackgroundLocationRationale = true
            return
        }
        dispatchPendingStart()
    }

    val recordingNotifPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Whether or not the user granted POST_NOTIFICATIONS, proceed with the next step:
            // the foreground service is still allowed to run; the notification is just hidden.
            proceedAfterNotifPermission()
        }

    // ACCESS_BACKGROUND_LOCATION via the runtime contract: Android 10 shows an inline dialog with
    // the "Allow all the time" option; Android 11+ jumps to the App's location-permission page
    // (one screen, four radios) - much shallower than ACTION_APPLICATION_DETAILS_SETTINGS, and the
    // result comes back here so we can auto-resume the pending start without making the user tap
    // the record button again.
    val recordingBackgroundLocationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                dispatchPendingStart()
            } else {
                pendingRecordingStart = null
                scope.launch {
                    snackbarHostState.showSnackbar("未取得背景定位權限, 無法在螢幕關閉時持續錄製")
                }
            }
        }

    fun requestStartRecording(continuationSource: File?) {
        pendingRecordingStart = PendingRecordingStart(continuationSource)
        val needsNotif =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        if (needsNotif) {
            recordingNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceedAfterNotifPermission()
        }
    }

    // Current-location ("my location"): a blue dot + facing cone, fed by GpsSource (the process-wide
    // singleton also used by RecordingService, so track head and marker read the same fix) and the
    // compass. GpsSource keeps subscribing under RecordingService's Recording lease, but the
    // activity's Foreground lease is only held while the map is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    val headingProvider = remember { HeadingProvider(context) }
    var locationGranted by remember { mutableStateOf(GpsSource.hasPermission(context)) }
    // Center the map on the first fix: true at launch when permission is already held (so a returning
    // user opens straight onto their location), and re-armed when the recenter FAB is tapped.
    var recenterOnFix by remember { mutableStateOf(GpsSource.hasPermission(context)) }
    // Persistent marker-follow toggle. While true, the map keeps the marker in sight on every fix -
    // safe-zone push while it is inside the viewport, hard recenter once it drifts out (typical of
    // GPS jumps out of a tunnel or on public transport). Cleared when the user pans / zooms the
    // map (that is their signal they want to look elsewhere); re-armed on ON_RESUME (recording or
    // not, a return to the app is a "refocus") and on the recenter FAB. Sits alongside the one-shot
    // [recenterOnFix] which still owns first-fix / FAB "jump to me" behaviour.
    var followUser by remember { mutableStateOf(true) }

    // Map bearing (NORTH_UP vs TRACK_UP). Persisted so the choice survives an app relaunch. Applied to
    // the MapView on every heading update so TRACK_UP rotation tracks the compass with no extra wiring.
    var bearingMode by remember { mutableStateOf(MapBearingPrefs.load(context)) }
    // True while at least one finger is on the map. Pauses the safe-zone follow so an incoming GPS
    // update does not fight the user's pan/pinch mid-gesture.
    var userTouching by remember { mutableStateOf(false) }
    // True when the current-location marker projects inside the MapView's pixel rectangle. Drives the
    // recentre FAB's lit state (lit only when the map is no longer tracking the marker), and is
    // recomputed on every map move (mapsforge Observer) and every new fix.
    var markerInViewport by remember { mutableStateOf(false) }

    // Pixel sizes of the map-overlay container and the two bottom control groups. Drive the
    // small-screen fallback that lifts the bottom-end FAB column above the bottom-start pill row
    // when they would otherwise overlap (e.g. with three pills in MapViewControls on narrow phones).
    var mapContainerWidthPx by remember { mutableStateOf(0) }
    var pillRowWidthPx by remember { mutableStateOf(0) }
    var pillRowHeightPx by remember { mutableStateOf(0) }
    var fabColumnWidthPx by remember { mutableStateOf(0) }

    // Connection-info popup: long-pressing the location marker opens it; the DEM altitude shown there
    // is looked up off the main thread when it opens (independent of the GPS-reported altitude).
    var locationInfoOpen by remember { mutableStateOf(false) }
    var locationInfoDemAltitude by remember { mutableStateOf<Double?>(null) }

    val locationLayer =
        remember(map.value) {
            map.value?.let { mv ->
                CurrentLocationLayer(
                    density = density,
                    mapViewPosition = mv.model.mapViewPosition,
                    onMarkerLongPress = { locationInfoOpen = true },
                ).also { mv.layerManager.layers.add(it) }
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

    // Live recording overlay: red ">" chevrons rendering whichever polyline the recorder currently
    // holds. Mounted for the life of this MapView; the recorder simply pushes points in via update().
    val recordedTrackLayer =
        remember(map.value) {
            map.value?.let { mv ->
                RecordedTrackLayer(density)
                    .also { mv.layerManager.layers.add(it) }
            }
        }

    // History-track viewer overlay: same chevron layer in blue, rendering whichever saved track is
    // currently loaded for viewing. Mounted for the life of this MapView; the polyline is pushed in
    // whenever historyTrack changes (load / clear).
    val historyTrackLayer =
        remember(map.value) {
            map.value?.let { mv ->
                RecordedTrackLayer(
                    density = density,
                    chevronColor = HISTORY_CHEVRON_COLOR,
                    chevronHaloColor = HISTORY_CHEVRON_HALO_COLOR,
                ).also { mv.layerManager.layers.add(it) }
            }
        }

    // Clean up any leftover .recording-*.jsonl files from a previous run (e.g. a crash or kill mid-
    // recording). v1 has no resume - the dot-prefixed files would just sit and accumulate noise.
    LaunchedEffect(trackStore) {
        withContext(Dispatchers.IO) { trackStore.cleanupStaleRecordings() }
    }

    // Push the recorder's live polyline into the layer whenever it changes.
    LaunchedEffect(recordedTrackLayer, recordedPoints) {
        val layer = recordedTrackLayer ?: return@LaunchedEffect
        layer.update(recordedPoints)
        map.value?.layerManager?.redrawLayers()
    }

    // Push the loaded history track into its layer whenever it changes; null clears the overlay.
    LaunchedEffect(historyTrackLayer, historyTrack) {
        val layer = historyTrackLayer ?: return@LaunchedEffect
        layer.update(historyTrack?.polyline ?: emptyList())
        map.value?.layerManager?.redrawLayers()
    }

    // Observe controller.pendingSession - set whenever the service stops (bottom-bar button or
    // notification stop action). Snapshot the just-recorded polyline into the history-view
    // overlay and bring up the save dialog. Cleared via RecordingController.clearPending() once
    // the save / discard flow finishes.
    LaunchedEffect(Unit) {
        RecordingController.pendingSession.collect { session ->
            if (session != null) {
                val snapshot = RecordingController.lastSessionPoints.value
                historyTrack =
                    RecordedTrack(
                        name = session.defaultName,
                        createdAtEpochMs = session.startEpochMs,
                        points =
                            snapshot.map { p ->
                                RecordedTrack.Point(
                                    latitude = p.latitude,
                                    longitude = p.longitude,
                                    timeMs = 0L,
                                )
                            },
                    )
                historyTrackFile = null
                viewingHistory = true
                saveTrackNameDraft = session.defaultName
                showSaveTrack = true
            }
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

    // Take the GpsSource Foreground lease only while permission is held and the screen is resumed;
    // release on pause so the singleton has no owner (and no LocationManager subscription) when the
    // app is backgrounded. The observer stays mounted regardless of the current grant so that
    // ON_RESUME also catches the user adding (or revoking) permission from the system settings page
    // - that path does not fire any ActivityResultLauncher. Recording sessions own the lease
    // separately (see the RecordingController.state observer below), so ON_RESUME while a recording
    // is active skips the acquire and just re-syncs the banner.
    DisposableEffect(lifecycleOwner, locationLayer) {
        if (locationLayer == null) {
            onDispose {}
        } else {
            fun syncOnResume() {
                // Pull the banner's StateFlow into sync first; it must be correct even when there is
                // nothing left to subscribe to (e.g. all location permissions were just revoked).
                GpsSource.refreshPermissionState(context)
                val granted = GpsSource.hasPermission(context)
                locationGranted = granted
                if (granted) {
                    if (RecordingController.state.value == Recorder.State.IDLE && gpsOwnership == null) {
                        gpsOwnership = GpsSource.acquire(context, GpsSource.Mode.Foreground)
                    }
                    headingProvider.start()
                    // Returning to the app is treated as a refocus: whatever the user was looking
                    // at before backgrounding is not preserved, and the next fix pulls the map back
                    // to the marker. This covers "recording, phone was in a pocket, come back and
                    // the marker is off-screen" as well as ordinary "left the app, opened it a few
                    // minutes later, the world moved" cases. If the user immediately pans again,
                    // they turn follow off in one gesture.
                    followUser = true
                } else {
                    gpsOwnership?.release()
                    gpsOwnership = null
                    headingProvider.stop()
                }
            }
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> syncOnResume()
                        Lifecycle.Event.ON_PAUSE -> {
                            gpsOwnership?.release()
                            gpsOwnership = null
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
                gpsOwnership?.release()
                gpsOwnership = null
                headingProvider.stop()
            }
        }
    }

    // Hand the Foreground lease back and forth around a recording session. When the activity is in
    // the foreground and recording ends, this reacquires so the map marker resumes updating; when
    // recording starts (via the button or a resume mid-recording), any activity-held lease is
    // released so RecordingService can take Strict-lease ownership. The onClick path also releases
    // the lease directly before dispatching the start Intent to close the tiny window between the
    // Intent going out and this collector observing RECORDING.
    LaunchedEffect(Unit) {
        RecordingController.state.collect { state ->
            when (state) {
                Recorder.State.RECORDING -> {
                    gpsOwnership?.release()
                    gpsOwnership = null
                }
                Recorder.State.IDLE -> {
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        GpsSource.hasPermission(context) &&
                        gpsOwnership == null
                    ) {
                        gpsOwnership = GpsSource.acquire(context, GpsSource.Mode.Foreground)
                    }
                }
            }
        }
    }

    // Feed fixes + heading into the overlay by collecting in an effect (not collectAsState) so the
    // frequent compass ticks redraw only the map layer, not the whole MapScreen composable. Also
    // applies the map bearing on every heading tick: NORTH_UP would only need a one-shot apply, but
    // TRACK_UP needs it per tick to follow the compass, and re-applying a NULL_ROTATION is cheap.
    LaunchedEffect(locationLayer, bearingMode) {
        val layer = locationLayer ?: return@LaunchedEffect
        combine(
            GpsSource.fix,
            headingProvider.heading,
            headingProvider.headingAccuracyDeg,
            GpsSource.serviceEnabled,
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
            map.value?.let { applyMapBearing(it, bearingMode, trueHeading) }
        }.collect { }
    }

    // 1 Hz: per-fix safe-zone follow and first-fix center. Restarted when the MapView is recreated or
    // when the plan mode changes; entering ROUTE_EDIT (tapping the crosshair to add waypoints)
    // disables the safe-zone push so the map does not slide under the user's tap.
    LaunchedEffect(map.value, mode) {
        val mv = map.value ?: return@LaunchedEffect
        var lastFix: LocationFix? = null
        GpsSource.fix.collect { fix ->
            if (fix == null) {
                lastFix = null
                markerInViewport = false
                return@collect
            }
            if (recenterOnFix) {
                // One-shot land-on-marker; takes priority over safe-zone logic since the user
                // explicitly asked for centring (either at launch or via the recenter FAB).
                recenterOnFix = false
                mv.model.mapViewPosition.center = LatLong(fix.latitude, fix.longitude)
            } else if (mode != PlanMode.ROUTE_EDIT && !userTouching && followUser) {
                // followUser gates every automatic pan below. It is only false once the user has
                // actually panned / zoomed the map themselves (see the touch listener above), so
                // the two branches here can safely assume the user still wants to be followed.
                if (MapFollow.isMarkerInViewport(mv, fix)) {
                    // Marker on-screen: soft-follow through the safe zone as before.
                    when (val action = MapFollow.evaluate(mv, fix, lastFix)) {
                        MapFollow.Action.None -> Unit
                        is MapFollow.Action.Push -> {
                            if (action.animate) {
                                mv.model.mapViewPosition.animateTo(action.target)
                            } else {
                                mv.model.mapViewPosition.center = action.target
                            }
                        }
                    }
                } else {
                    // Marker off-screen. MapFollow.evaluate's original free-mode silence was there
                    // to protect a hand-panned map, but that protection is already covered by the
                    // followUser flag: reaching this branch means the user did not pan, and a fix
                    // that puts the marker outside the viewport is a GPS jump (tunnel exit, MRT,
                    // fast motion) that should snap the map back. No animate: crawling across the
                    // screen for hundreds of metres is worse than a jump.
                    mv.model.mapViewPosition.center = LatLong(fix.latitude, fix.longitude)
                }
            }
            // Refresh the recentre-button highlight after any pan we just performed.
            markerInViewport = MapFollow.isMarkerInViewport(mv, fix)
            lastFix = fix
        }
    }

    // Map-move observer: pan / zoom / rotate by the user changes whether the marker projects inside
    // the viewport, so the recentre button's lit state needs to refresh independently of fix updates.
    DisposableEffect(map.value) {
        val mv = map.value
        if (mv == null) {
            onDispose {}
        } else {
            val observer =
                Observer {
                    markerInViewport = MapFollow.isMarkerInViewport(mv, GpsSource.fix.value)
                }
            mv.model.mapViewPosition.addObserver(observer)
            onDispose { mv.model.mapViewPosition.removeObserver(observer) }
        }
    }

    // Collected in composition (1 Hz, unlike the high-rate heading) to drive the FAB highlight and the
    // recenter behaviour.
    val currentFix by GpsSource.fix.collectAsState()
    val serviceEnabled by GpsSource.serviceEnabled.collectAsState()
    val gnss by GpsSource.gnss.collectAsState()
    // Drives the persistent "only coarse location granted" warning banner. Refreshed in syncOnResume
    // above (which calls GpsSource.refreshPermissionState) on every ON_RESUME so an upgrade to
    // precise location made from the system settings page clears the banner automatically.
    val fineLocationGranted by GpsSource.fineLocationGranted.collectAsState()

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
        // The FAB is the explicit "follow me" trigger, so re-arm the persistent follow as well as
        // whatever one-shot centring path we take below. Without this, tapping the FAB after a
        // manual pan would centre once and then stop tracking on the next fix.
        followUser = true
        val fix = GpsSource.fix.value
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

    Box(modifier = modifier.onSizeChanged { mapContainerWidthPx = it.width }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                RudyMapView.create(ctx, mapDir).also { mv ->
                    // Observe touches for two things: (1) userTouching pauses the safe-zone follow
                    // while a finger (or two) is down, so an incoming fix does not fight the user's
                    // pan/pinch mid-gesture; (2) if the gesture actually changed the map's centre or
                    // zoom, clear followUser so subsequent fixes leave the map alone until the user
                    // asks for a recenter. Compare centre + zoom captured at DOWN against the values
                    // at UP/CANCEL so a stray tap (which touches nothing) does not disengage follow.
                    // rotation is left out because TRACK_UP applies rotation from the compass, not
                    // the user's fingers. Returning false lets mapsforge's own gesture handling run
                    // unchanged.
                    var touchStartCenter: LatLong? = null
                    var touchStartZoom: Byte? = null
                    mv.setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                userTouching = true
                                val position = mv.model.mapViewPosition
                                touchStartCenter = position.center
                                touchStartZoom = position.zoomLevel
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                userTouching = false
                                val startCenter = touchStartCenter
                                val startZoom = touchStartZoom
                                touchStartCenter = null
                                touchStartZoom = null
                                if (startCenter != null && startZoom != null) {
                                    val position = mv.model.mapViewPosition
                                    if (position.center != startCenter || position.zoomLevel != startZoom) {
                                        followUser = false
                                    }
                                }
                            }
                            else -> Unit
                        }
                        false
                    }
                    onMapCreated(mv)
                    map.value = mv
                }
            },
        )

        // Labels drawn in screen space on top of the MapView, decoupled from the frame buffer's
        // matrix scale so they keep their theme-defined size during fractional zoom. The overlay
        // is non-clickable, so touches fall through to MapView below.
        map.value?.let { mv ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> LabelOverlayView(ctx, mv) },
            )
        }

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
                Icon(imageVector = Icons.Filled.QuestionMark, contentDescription = "辨識地圖符號")
            }
            // Peak-name search: opens a dialog to look up a summit and centre the map on it.
            SmallFloatingActionButton(onClick = { openSearch() }) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = "搜尋山名")
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

        // Bottom-end controls, stacked: bearing-mode toggle on top, recenter FAB below. Hidden during
        // identify and route editing so it does not collide with their bottom bars. The recenter
        // button is highlighted only when the map is no longer tracking the marker (the marker has
        // drifted off-viewport or there is no fix yet); otherwise it is dimmed to signal "already
        // following".
        //
        // Placement: by default it sits at BottomEnd on the same baseline as the bottom-start pill
        // row. On small screens, where the pill row (e.g. all three of 錄製軌跡/規劃路徑/清除軌跡/路徑)
        // would otherwise overlap this column, it lifts above the pill row instead - measured live
        // off the actual pill-row width/height and the container width, so the choice adapts to
        // whatever pill set the current mode is showing.
        if (!identifyMode &&
            identifyResult == null &&
            identifyCandidates == null &&
            mode != PlanMode.ROUTE_EDIT
        ) {
            val stackAboveGapDp = 12.dp
            // True only when we have measurements for both groups AND they would overlap. The
            // measured pillRowWidthPx / fabColumnWidthPx already include each group's own 16 dp
            // padding (onSizeChanged sits after padding in the modifier chain), so the sum touching
            // the container width means the two padded edges already meet - no further gap is
            // available. While any measurement is still 0 (first frame, or the pill row has not laid
            // out yet) we keep the default BottomEnd placement so the FAB does not flash to an
            // above-pills position.
            val stackAbovePills =
                pillRowWidthPx > 0 &&
                    fabColumnWidthPx > 0 &&
                    mapContainerWidthPx > 0 &&
                    pillRowWidthPx + fabColumnWidthPx > mapContainerWidthPx
            val pillRowHeightDp = with(LocalDensity.current) { pillRowHeightPx.toDp() }
            // pillRowHeightDp already covers the row's own 16 dp top+bottom padding. To sit the FAB
            // column's content edge stackAboveGapDp above the pill row's content edge, we want the
            // FAB's bottom inset to equal (pillRow content top distance from box bottom) + gap, i.e.
            // (pillRowHeightDp - 16 dp) + stackAboveGapDp.
            val bottomPadding =
                if (stackAbovePills) {
                    pillRowHeightDp - 16.dp + stackAboveGapDp
                } else {
                    16.dp
                }
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = bottomPadding)
                        .onSizeChanged { fabColumnWidthPx = it.width },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BearingModeButton(
                    mode = bearingMode,
                    onToggle = {
                        val next =
                            if (bearingMode == MapBearingMode.NORTH_UP) {
                                MapBearingMode.TRACK_UP
                            } else {
                                MapBearingMode.NORTH_UP
                            }
                        bearingMode = next
                        MapBearingPrefs.save(context, next)
                    },
                )
                MyLocationButton(
                    onClick = { recenterOnCurrentLocation() },
                    active = locationGranted && serviceEnabled && !markerInViewport,
                )
            }
        }

        // Crosshair only while editing a route (independent of the bottom controls row).
        if (mode == PlanMode.ROUTE_EDIT && !identifyMode && identifyResult == null) {
            CrosshairOverlay(modifier = Modifier.fillMaxSize())
        }

        // Bottom-start controls: the mode-specific action buttons sit in the lower-left corner and
        // are hidden during identify, whose own bar/card owns the bottom. onSizeChanged feeds the
        // small-screen check above that lifts the bottom-end FAB column when this row gets wide
        // enough to overlap it.
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .onSizeChanged {
                        pillRowWidthPx = it.width
                        pillRowHeightPx = it.height
                    },
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
            } else if (!identifyMode && identifyResult == null && recordingState != Recorder.State.IDLE) {
                // While a recording session is alive the bottom-start row is owned by the recording
                // bar; the map-view / planning controls do not show. This also hides "規劃路徑"
                // during recording, sidestepping the bottom-row collision the planning flow would
                // otherwise cause.
                RecordingBottomBar(
                    onStop = {
                        // Hand off to the service - it stops the GPS subscription, calls
                        // RecordingController.handleStop() (which snapshots points into
                        // lastSessionPoints and exposes the session via pendingSession), then
                        // stopSelf()s. The pendingSession observer above brings up the save dialog
                        // and the history-view overlay, so this path is symmetric with the
                        // notification's stop action - both end up in the same flow.
                        RecordingService.stop(context)
                    },
                )
            } else if (!identifyMode && identifyResult == null) {
                when (mode) {
                    PlanMode.MAP_VIEW ->
                        if (viewingHistory && historyTrack != null) {
                            // History-track viewing sub-mode: equal-width 繼續錄製 / 離開 pair.
                            // 繼續錄製 seeds the recorder from the loaded file and transitions to
                            // the recording state (RecordingBottomBar takes over via the
                            // recordingState branch above). 離開 keeps the blue overlay on screen
                            // but exits the sub-mode, returning to the default MapViewControls -
                            // the user can then clear via "清除軌跡/路徑".
                            HistoryTrackViewControls(
                                onContinue = {
                                    val file = historyTrackFile
                                    if (file != null) {
                                        viewingHistory = false
                                        requestStartRecording(continuationSource = file)
                                    }
                                },
                                onLeave = { viewingHistory = false },
                            )
                        } else {
                            MapViewControls(
                                canRecord = locationGranted,
                                canClear = displayedRoute != null || historyTrack != null,
                                onRecord = {
                                    withStorageAccess { showRecordEntryChooser = true }
                                },
                                onPlan = { showChooser = true },
                                onClear = {
                                    viewer?.clear()
                                    displayedRoute = null
                                    historyTrack = null
                                    historyTrackFile = null
                                },
                            )
                        }

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
                    "Jiudge 會將你規劃的路線與錄製的軌跡存放在 \"文件/Jiudge\" 資料夾. 為了讓這些檔案在解除安裝後仍然保留, " +
                        "重新安裝後也能直接讀回, app 需要系統的 \"所有檔案存取權\". 此權限僅用於讀寫本 app 自己的路線與軌跡檔.",
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

    if (showRecordEntryChooser) {
        RecordEntryChooser(
            onNew = {
                showRecordEntryChooser = false
                requestStartRecording(continuationSource = null)
            },
            onLoad = {
                showRecordEntryChooser = false
                withStorageAccess {
                    scope.launch {
                        try {
                            loadTrackList = withContext(Dispatchers.IO) { trackStore.list() }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("讀取軌跡清單失敗: ${e.message}")
                        }
                    }
                }
            },
            onCancel = { showRecordEntryChooser = false },
        )
    }

    loadTrackList?.let { summaries ->
        LoadTrackDialog(
            summaries = summaries,
            onPick = { summary ->
                loadTrackList = null
                scope.launch {
                    try {
                        val loaded = withContext(Dispatchers.IO) { trackStore.load(summary.file) }
                        historyTrack = loaded
                        historyTrackFile = summary.file
                        viewingHistory = true
                        if (loaded.polyline.isNotEmpty()) {
                            map.value?.fitToRoute(loaded.polyline)
                        }
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("載入軌跡失敗: ${e.message}")
                    }
                }
            },
            onRename = { renameTrackTarget = it },
            onDelete = { deleteTrackTarget = it },
            onDismiss = { loadTrackList = null },
        )
    }

    renameTrackTarget?.let { target ->
        RenameTrackDialog(
            initialName = target.name,
            onConfirm = { newName ->
                renameTrackTarget = null
                withStorageAccess {
                    scope.launch {
                        try {
                            val renamed = withContext(Dispatchers.IO) { trackStore.rename(target.file, newName) }
                            loadTrackList = withContext(Dispatchers.IO) { trackStore.list() }
                            snackbarHostState.showSnackbar("已改名為 \"${renamed.name}\"")
                        } catch (e: DuplicateTrackNameException) {
                            renameTrackTarget = target
                            snackbarHostState.showSnackbar("已有同名軌跡 \"${e.trackName}\", 請改用其他名稱")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("改名失敗: ${e.message}")
                        }
                    }
                }
            },
            onDismiss = { renameTrackTarget = null },
        )
    }

    deleteTrackTarget?.let { target ->
        DeleteTrackDialog(
            trackName = target.name,
            onConfirm = {
                deleteTrackTarget = null
                withStorageAccess {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { trackStore.delete(target.file) }
                            loadTrackList = withContext(Dispatchers.IO) { trackStore.list() }
                            snackbarHostState.showSnackbar("已刪除 \"${target.name}\"")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("刪除失敗: ${e.message}")
                        }
                    }
                }
            },
            onDismiss = { deleteTrackTarget = null },
        )
    }

    if (showSaveTrack) {
        val session = pendingRecordingSession
        SaveTrackDialog(
            initialName = saveTrackNameDraft ?: session?.defaultName ?: "",
            onConfirm = { name ->
                showSaveTrack = false
                if (session != null) {
                    withStorageAccess {
                        scope.launch {
                            try {
                                val savedFile = withContext(Dispatchers.IO) { RecordingController.finalize(session, name) }
                                // Stop now leaves the just-saved track on screen in the blue
                                // history-view style with the 繼續錄製 / 離開 controls (gui-redesign:
                                // stopping should not erase what was just recorded). Reload from the
                                // finalised file so historyTrack carries the real timestamps and
                                // historyTrackFile points at a public file 繼續錄製 can hand to
                                // RecordingService.startContinuation.
                                val loaded = withContext(Dispatchers.IO) { trackStore.load(savedFile) }
                                historyTrack = loaded
                                historyTrackFile = savedFile
                                viewingHistory = true
                                RecordingController.clearPending()
                                saveTrackNameDraft = null
                                snackbarHostState.showSnackbar("已儲存軌跡: $name")
                            } catch (e: DuplicateTrackNameException) {
                                saveTrackNameDraft = name
                                showSaveTrack = true
                                snackbarHostState.showSnackbar("已有同名軌跡 \"${e.trackName}\", 請改用其他名稱")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("儲存失敗: ${e.message}")
                            }
                        }
                    }
                }
            },
            onDismiss = {
                showSaveTrack = false
                showDiscardRecording = true
            },
        )
    }

    if (showDiscardRecording) {
        val session = pendingRecordingSession
        DiscardRecordingDialog(
            continuationName = if (session?.isContinuation == true) session.defaultName else null,
            onConfirm = {
                showDiscardRecording = false
                if (session != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) { RecordingController.discard(session) }
                        // A discarded continuation still has its original source file intact, so
                        // fall back to viewing that original (blue overlay + 繼續錄製 / 離開) - the
                        // user discarded only the newly added extension, not the underlying track.
                        // A discarded fresh recording has no source to fall back to; clear the
                        // viewer entirely and let the default map-view controls return.
                        val source = session.source
                        if (session.isContinuation && source != null) {
                            try {
                                val loaded = withContext(Dispatchers.IO) { trackStore.load(source) }
                                historyTrack = loaded
                                historyTrackFile = source
                                viewingHistory = true
                            } catch (e: Exception) {
                                historyTrack = null
                                historyTrackFile = null
                                viewingHistory = false
                                snackbarHostState.showSnackbar("回復原始軌跡失敗: ${e.message}")
                            }
                        } else {
                            historyTrack = null
                            historyTrackFile = null
                            viewingHistory = false
                        }
                        RecordingController.clearPending()
                        saveTrackNameDraft = null
                    }
                }
            },
            onDismiss = {
                showDiscardRecording = false
                showSaveTrack = true
            },
        )
    }

    if (showBackgroundLocationRationale) {
        BackgroundLocationRationaleDialog(
            onConfirm = {
                showBackgroundLocationRationale = false
                // Hand off to the runtime contract - the launcher's callback runs
                // dispatchPendingStart() on grant, so the user does not have to tap 錄製 again.
                recordingBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            },
            onDismiss = {
                showBackgroundLocationRationale = false
                pendingRecordingStart = null
            },
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
 * the map on the current fix. [active] highlights it only when the map is no longer tracking the
 * marker, so it acts as both a button and a "needs your attention" indicator. Uses the small FAB
 * variant so it sits at the same size as every other map-screen control button.
 */
@Composable
private fun MyLocationButton(
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor =
            if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                FloatingActionButtonDefaults.containerColor
            },
    ) {
        Icon(imageVector = Icons.Filled.MyLocation, contentDescription = "回到目前位置")
    }
}

/**
 * Map-bearing toggle (NORTH_UP vs TRACK_UP). Shows the *currently active* mode's icon: a fixed north
 * arrow for NORTH_UP, the rotating compass needle for TRACK_UP. TRACK_UP gets the accent fill since
 * it is the "engaged" rotating mode that the user normally wants to be aware of; NORTH_UP is the
 * resting default and uses the standard FAB fill.
 */
@Composable
private fun BearingModeButton(
    mode: MapBearingMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = mode == MapBearingMode.TRACK_UP
    SmallFloatingActionButton(
        onClick = onToggle,
        modifier = modifier,
        containerColor =
            if (accent) {
                MaterialTheme.colorScheme.primary
            } else {
                FloatingActionButtonDefaults.containerColor
            },
    ) {
        Icon(
            imageVector = if (accent) Icons.Filled.Explore else Icons.Filled.North,
            contentDescription = if (accent) "地圖隨方向旋轉 (按下切回北方朝上)" else "北方朝上 (按下切為隨方向旋轉)",
        )
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
