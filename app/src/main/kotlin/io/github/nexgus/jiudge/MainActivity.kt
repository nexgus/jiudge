package io.github.nexgus.jiudge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.core.geo.simplifyPolyline
import io.github.nexgus.jiudge.core.index.Peak
import io.github.nexgus.jiudge.core.index.PeakIndex
import io.github.nexgus.jiudge.core.index.PeakIndexState
import io.github.nexgus.jiudge.core.location.GpsSource
import io.github.nexgus.jiudge.core.location.HeadingProvider
import io.github.nexgus.jiudge.core.location.LocationFix
import io.github.nexgus.jiudge.core.mapdata.DownloadService
import io.github.nexgus.jiudge.core.mapdata.DownloadState
import io.github.nexgus.jiudge.core.mapdata.InstallPhase
import io.github.nexgus.jiudge.core.mapdata.MapDataCatalog
import io.github.nexgus.jiudge.core.mapdata.MapDataDownload
import io.github.nexgus.jiudge.core.mapdata.MapDataVersionStore
import io.github.nexgus.jiudge.core.mapdata.MapUpdate
import io.github.nexgus.jiudge.core.mapdata.MapUpdateChecker
import io.github.nexgus.jiudge.core.mapdata.MapUpdateState
import io.github.nexgus.jiudge.core.mapdata.MapVersion
import io.github.nexgus.jiudge.core.recording.RecordingController
import io.github.nexgus.jiudge.core.recording.RecordingService
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.routing.BRouterProfile
import io.github.nexgus.jiudge.core.routing.ToughPathDetector
import io.github.nexgus.jiudge.core.stats.TraceStats
import io.github.nexgus.jiudge.core.stats.TraceStatsCalculator
import io.github.nexgus.jiudge.core.storage.AppPaths
import io.github.nexgus.jiudge.data.route.DuplicatePathNameException
import io.github.nexgus.jiudge.data.route.GpxExporter
import io.github.nexgus.jiudge.data.route.GpxImporter
import io.github.nexgus.jiudge.data.route.GpxParseException
import io.github.nexgus.jiudge.data.route.PATH_SIMPLIFY_TOLERANCE_M
import io.github.nexgus.jiudge.data.route.PathKind
import io.github.nexgus.jiudge.data.route.PathStore
import io.github.nexgus.jiudge.data.route.PlannedRoute
import io.github.nexgus.jiudge.data.route.RecordedTrack
import io.github.nexgus.jiudge.data.route.SavedPath
import io.github.nexgus.jiudge.data.route.Trace
import io.github.nexgus.jiudge.data.route.toPlannedRoute
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
import io.github.nexgus.jiudge.feature.map.MapFollow
import io.github.nexgus.jiudge.feature.map.RudyMapView
import io.github.nexgus.jiudge.feature.map.SearchPeakMarkerLayer
import io.github.nexgus.jiudge.feature.mapdata.DownloadScreen
import io.github.nexgus.jiudge.feature.mapdata.MapUpdateDialog
import io.github.nexgus.jiudge.feature.path.CopyAsPlanDialog
import io.github.nexgus.jiudge.feature.path.DeletePathDialog
import io.github.nexgus.jiudge.feature.path.ImportPathDialog
import io.github.nexgus.jiudge.feature.path.MapEntryControls
import io.github.nexgus.jiudge.feature.path.PathDisplayOptions
import io.github.nexgus.jiudge.feature.path.PathManagerScreen
import io.github.nexgus.jiudge.feature.path.PathOverlays
import io.github.nexgus.jiudge.feature.path.PathStyle
import io.github.nexgus.jiudge.feature.path.PathViewControls
import io.github.nexgus.jiudge.feature.path.RenamePathDialog
import io.github.nexgus.jiudge.feature.path.SavePathDialog
import io.github.nexgus.jiudge.feature.planning.CrosshairOverlay
import io.github.nexgus.jiudge.feature.planning.PlanningBottomBar
import io.github.nexgus.jiudge.feature.planning.RoutePlanner
import io.github.nexgus.jiudge.feature.planning.SearchTargetControls
import io.github.nexgus.jiudge.feature.planning.fitToRoute
import io.github.nexgus.jiudge.feature.recording.BackgroundLocationRationaleDialog
import io.github.nexgus.jiudge.feature.recording.BatteryExemptionRationaleDialog
import io.github.nexgus.jiudge.feature.recording.DiscardRecordingDialog
import io.github.nexgus.jiudge.feature.recording.PausedBottomBar
import io.github.nexgus.jiudge.feature.recording.Recorder
import io.github.nexgus.jiudge.feature.recording.RecordingBottomBar
import io.github.nexgus.jiudge.feature.search.PeakSearchDialog
import io.github.nexgus.jiudge.feature.stats.StatsScreen
import io.github.nexgus.jiudge.feature.stats.rememberChartColors
import io.github.nexgus.jiudge.feature.stats.renderProfileChartBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.model.common.Observer
import java.io.File
import kotlin.math.roundToInt

/**
 * A held-over recording start request waiting for the permission flow to finish. A null
 * [continuationSource] means a brand-new recording; non-null means continue that saved track.
 */
private data class PendingRecordingStart(
    val continuationSource: File?,
)

/**
 * Fixed staging slot for a parsed GPX import awaiting its name, written as a regular path file of
 * whichever kind the import turned out to be (a track when every point is timestamped, otherwise a
 * planned route). The geometry (potentially thousands of points) must not ride in the saved-state
 * Bundle (~1 MB binder limit), so only the prefilled name string is kept in `rememberSaveable`
 * state and the draft body lives here - the naming dialog then survives activity recreation
 * without losing the parsed import. The single fixed name doubles as cleanup: a remnant from an
 * abandoned session is simply overwritten by the next import.
 */
private fun importDraftFile(context: Context): File = File(context.cacheDir, "import_draft.jsonl")

/**
 * A saved path shown on the map. [id] is what identifies it for as long as it stays opened - it is
 * the key of its map layer and what the focus points at - and it never changes, unlike [file],
 * whose location follows a rename. Whenever a path (re)joins the opened paths it does so under a
 * fresh id, which is what puts it on top of the others.
 *
 * Deliberately not a data class: two entries are the same only when they are the same instance, so
 * comparing or removing one never walks the (possibly huge) point lists.
 */
private class OpenedPath(
    val id: Long,
    val path: SavedPath,
    val file: File,
)

/**
 * Whether [a] and [b] denote the same path file. Beyond plain equality this follows a file the
 * store has moved out of a legacy folder since one of the references was taken: a move keeps the
 * file name, so equal names in different folders are one file - unless both still exist, in which
 * case they are two files that merely share a name.
 */
private fun samePathFile(
    a: File,
    b: File,
): Boolean = a == b || (a.name == b.name && !(a.exists() && b.exists()))

/** The points the map is framed on when [this] path is opened. */
private fun SavedPath.framingPoints(): List<LatLong> = polyline.ifEmpty { (this as? PlannedRoute)?.waypoints?.map { it.point }.orEmpty() }

/** [this] path under another name; geometry, times and creation time are kept. */
private fun SavedPath.renamed(name: String): SavedPath =
    when (this) {
        is PlannedRoute -> copy(name = name)
        is RecordedTrack -> copy(name = name)
    }

/** Suggested file name handed to the SAF create-document picker ('/' would read as a path). */
private fun gpxSuggestedName(name: String): String = "${name.replace('/', '-')}.gpx"

/** Suggested file name for the stats-chart PNG export (D4, docs/stats.md), same sanitisation as GPX. */
private fun pngSuggestedName(name: String): String = "${name.replace('/', '-')}.png"

/** The trace whose stats screen is open: display name plus whether it is a recorded track. */
private data class StatsTarget(
    val name: String,
    val isTrack: Boolean,
)

/**
 * A saved path awaiting export to GPX, kept alive across the SAF "create document" picker (it
 * returns asynchronously). [file] is the path file under Documents/Jiudge and [name] its display
 * name (used for both the snackbar and the suggested file name). The exported format follows the
 * kind the file turns out to hold when it is read.
 */
private data class GpxExportTarget(
    val file: File,
    val name: String,
) {
    companion object {
        /** Saves the pending export across activity recreation while the SAF picker is open. */
        val Saver =
            listSaver<GpxExportTarget?, String>(
                save = { target -> target?.let { listOf(it.file.absolutePath, it.name) } ?: emptyList() },
                restore = { saved -> if (saved.isEmpty()) null else GpxExportTarget(File(saved[0]), saved[1]) },
            )
    }
}

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null

    // A .gpx handed over by another app (the manifest's ACTION_VIEW filters), parked here until the
    // map screen is composed and runs the GPX import flow on it. Compose state so a URI that
    // arrives while the download screen is still up is picked up as soon as the map appears.
    private var pendingGpxUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a genuinely fresh launch: a recreation (process death restore) redelivers the same
        // intent, and replaying the pause then would be wrong - the user may have resumed since.
        // Same for a .gpx hand-over: the import dialog state is itself saveable, so replaying the
        // parse would stomp the staged draft the restored dialog still points at.
        if (savedInstanceState == null) {
            handleRecordingIntent(intent)
            handleGpxViewIntent(intent)
        }
        // Keep the screen from timing out while actively recording (RECORDING only - PAUSED and
        // IDLE restore the normal timeout). The flag freezes the idle countdown; the power button
        // still turns the screen off, after which the foreground service + PARTIAL_WAKE_LOCK keep
        // the track being written. Collected on lifecycleScope (not per-screen) so the flag stays
        // correct whichever screen is composed, and a recreation re-syncs from the StateFlow's
        // current value.
        lifecycleScope.launch {
            RecordingController.state.collect { state ->
                if (state == Recorder.State.RECORDING) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
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
                            // A .gpx arriving before the map data is ready stays parked; tell the
                            // user once why nothing visibly happened to their tap.
                            LaunchedEffect(pendingGpxUri) {
                                if (pendingGpxUri != null) {
                                    snackbarHostState.showSnackbar("圖資就緒後將開始匯入 GPX")
                                }
                            }
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
                            // mapEpoch forces a full MapScreen rebuild when the user applies a map
                            // data update: the old MapFile/theme/DEM handles and tile cache go away
                            // with the old subtree (AndroidView onRelease) and the new files are
                            // opened fresh. The camera is carried over explicitly; everything else
                            // (edit state, overlays) intentionally resets to a plain map view.
                            var mapEpoch by remember { mutableIntStateOf(0) }
                            var restoreCamera by remember { mutableStateOf<MapPosition?>(null) }
                            LaunchedEffect(mapEpoch) {
                                if (mapEpoch > 0) snackbarHostState.showSnackbar("已套用新版圖資")
                            }
                            key(mapEpoch) {
                                MapScreen(
                                    mapDir = paths.mapDir,
                                    engine = remember { BRouterEngine(paths.brouterDir) },
                                    pathStore = remember { PathStore() },
                                    snackbarHostState = snackbarHostState,
                                    onMapCreated = { mapView = it },
                                    // Identity-guarded: on a mapEpoch rebuild the new screen's
                                    // onMapCreated may run before the old subtree's onRelease, and
                                    // an unconditional null-out would drop the fresh reference.
                                    onMapReleased = { released -> if (mapView === released) mapView = null },
                                    initialCamera = restoreCamera,
                                    onApplyUpdate = {
                                        restoreCamera = mapView?.model?.mapViewPosition?.mapPosition
                                        MapUpdate.reset()
                                        mapEpoch++
                                    },
                                    externalGpxUri = pendingGpxUri,
                                    onExternalGpxConsumed = { pendingGpxUri = null },
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .padding(padding),
                                )
                            }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replace the stored intent so a later configuration change does not replay the original
        // launch intent's (possibly stale) action.
        setIntent(intent)
        handleRecordingIntent(intent)
        handleGpxViewIntent(intent)
    }

    /**
     * A VIEW intent from the manifest's .gpx filters. Only the URI is parked; parsing waits for the
     * map screen (the import flow's dialogs and error surfaces all live there). The read grant on a
     * VIEW content URI lasts until this task finishes, so deferring the open is safe.
     */
    private fun handleGpxViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { pendingGpxUri = it }
        }
    }

    /**
     * The notification's "停止" action is an Activity PendingIntent aimed here (spec C: pressing it
     * must both pause the recording and bring the app to the foreground). It cannot be a service
     * PendingIntent that then calls startActivity: Android 12+ blocks that "notification trampoline"
     * pattern outright, and Android 10-11's background-activity-launch restrictions block it too. So
     * the system launches this activity directly (activity PendingIntents from a notification are
     * exempt from BAL restrictions) and we forward the pause to the service from here.
     * [io.github.nexgus.jiudge.feature.recording.Recorder.pause] is a no-op without a live session,
     * so a stale or duplicated delivery is harmless.
     */
    private fun handleRecordingIntent(intent: Intent?) {
        if (intent?.action == ACTION_PAUSE_RECORDING) {
            RecordingService.pause(this)
        }
    }

    // MapView teardown lives in MapScreen's AndroidView onRelease (it also covers the mapEpoch
    // rebuild); disposing the composition on activity destroy runs the same path, so there is no
    // onDestroy cleanup here - a second destroyAll() on an already-destroyed view is not safe.

    companion object {
        /** Sent by the recording notification's "停止" action - see [handleRecordingIntent]. */
        const val ACTION_PAUSE_RECORDING = "io.github.nexgus.jiudge.action.PAUSE_RECORDING_FROM_NOTIFICATION"
    }
}

/**
 * The three states of the map screen, which decide the bottom-start action bar (unless a recording
 * session, which sits on top of any of them, owns that bar).
 */
private enum class ScreenMode {
    /** No focused path and no edit in progress: the bar holds the entry buttons. */
    MAP,

    /** One opened path is focused: the bar acts on it. */
    VIEW,

    /** A planned route is being edited with the crosshair. */
    EDIT,
}

// Whether the GPS accuracy circle is drawn. Hard-coded on for now; a future Settings screen
// (feature/settings, not yet built) will expose this as a user toggle.
private const val SHOW_ACCURACY_CIRCLE = true

// Display options applied to every path on the map. Fixed for now - slope colouring off, distance
// labels on - until the two on-map switches that toggle them exist.
private val PATH_DISPLAY_OPTIONS = PathDisplayOptions(showSlope = false, showDistanceMarkers = true)

// Centring on a searched peak keeps the current zoom, unless it is below MIN (too far out to see the
// summit), in which case it pulls in to PEAK_VIEW.
private const val PEAK_VIEW_MIN_ZOOM: Byte = 14
private const val PEAK_VIEW_ZOOM: Byte = 15

// On ON_RESUME, a fix older than this is treated as too stale to snap the map to (e.g. mid-tunnel
// where GPS has been silent for a while - centring on the pre-tunnel spot would mislead). Short
// signal shadows (traffic lights, dense urban canyons) commonly last well under this window and
// still resolve to a usable "last known" position, so 60 s is a deliberately generous threshold.
private const val STALE_FIX_THRESHOLD_MS: Long = 60_000L

@Composable
private fun MapScreen(
    mapDir: File,
    engine: BRouterEngine,
    pathStore: PathStore,
    snackbarHostState: SnackbarHostState,
    onMapCreated: (MapView) -> Unit,
    onMapReleased: (MapView) -> Unit,
    initialCamera: MapPosition?,
    onApplyUpdate: () -> Unit,
    externalGpxUri: Uri?,
    onExternalGpxConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val map = remember { mutableStateOf<MapView?>(null) }

    // Screen pixels per dp; fed to the zoom-aware route/location overlays so their markers stay a
    // constant physical size across screens.
    val density = LocalDensity.current.density

    // Resolved once on the main thread (needs a composition-bound FontFamily.Resolver) and reused
    // by the stats-chart PNG export (D4): the actual render + PNG compression still happens off the
    // main thread in pngExportLauncher, only these lightweight lookups need to run here.
    val chartTextMeasurer = rememberTextMeasurer()
    val chartColors = rememberChartColors()
    val chartDensity = LocalDensity.current

    // DEM elevation source for route slope colouring (trace_spec.md §8). Null when the DEM folder is
    // absent, in which case the route renders without slope colour (grey) rather than failing.
    val demElevation = remember(mapDir) { DemElevation.createOrNull(File(mapDir, RudyMapView.DEM_DIR)) }

    // Reads the basemap to tell when a waypoint sits on a RudyMap "艱難路線", so that leg is routed
    // through the tough path instead of detoured around it (see RoutePlanner).
    val toughDetector = remember(mapDir) { ToughPathDetector(File(mapDir, RudyMapView.BASEMAP_NAME)) }
    DisposableEffect(toughDetector) { onDispose { toughDetector.close() } }

    // Every path drawn over the map - the opened paths, the plan being edited and the recording -
    // goes through one PathOverlays per live MapView (recreated if the view is). It owns their
    // layers and stacking order; closing it cancels any slope sampling still running.
    val pathOverlays =
        remember(map.value) { map.value?.let { PathOverlays(it, density, demElevation, PATH_DISPLAY_OPTIONS) } }
    DisposableEffect(pathOverlays) { onDispose { pathOverlays?.close() } }

    // One planner per live MapView, drawing through the overlays' editing layer.
    val planner =
        remember(map.value, pathOverlays) {
            val mv = map.value
            if (mv == null || pathOverlays == null) null else RoutePlanner(mv, engine, pathOverlays, toughDetector)
        }

    // The saved paths shown on the map, bottom to top; any number may be open at once. They stay
    // on the map through leaving the view state, editing another plan and recording, and go away
    // only through 清除 or by deleting the path in the file manager. Memory only: a restart (or the
    // screen rebuild after a map-data update) begins with none.
    var openedPaths by remember { mutableStateOf<List<OpenedPath>>(emptyList()) }
    var nextOpenedId by remember { mutableLongStateOf(0L) }

    // The opened path the view-state action bar acts on; null when none is focused.
    var focusId by remember { mutableStateOf<Long?>(null) }
    val focusedPath by remember { derivedStateOf { openedPaths.firstOrNull { it.id == focusId } } }

    // Raised while a plan is being edited through the planner. editSource is the opened path the
    // edit started from - off openedPaths for the duration, since the editing layer draws it
    // instead - and is what 取消 puts back; null while a brand-new plan is being edited.
    var editing by remember { mutableStateOf(false) }
    var editSource by remember { mutableStateOf<OpenedPath?>(null) }

    // Derived, never set: editing wins, otherwise a focused path means the view state. Read through
    // the state delegate so long-lived effects and callbacks always see the current value.
    val mode by remember {
        derivedStateOf {
            when {
                editing -> ScreenMode.EDIT
                focusedPath != null -> ScreenMode.VIEW
                else -> ScreenMode.MAP
            }
        }
    }

    fun openedFor(file: File): OpenedPath? = openedPaths.firstOrNull { samePathFile(it.file, file) }

    // Puts [path] on the map as the topmost opened path and returns its entry. An entry already
    // showing the same file is dropped first, so a file is never on the map twice.
    fun addOpened(
        path: SavedPath,
        file: File,
    ): OpenedPath {
        val opened = OpenedPath(nextOpenedId++, path, file)
        openedPaths = openedPaths.filterNot { samePathFile(it.file, file) } + opened
        return opened
    }

    var busy by remember { mutableStateOf(false) }

    // Main menu / "關於": the installed map version is read off the main thread once, so the dialog
    // can show it immediately when opened (null until loaded, or if no theme is installed).
    var aboutOpen by remember { mutableStateOf(false) }
    var mapVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mapDir) {
        mapVersion = withContext(Dispatchers.IO) { MapVersion.installed(mapDir) }
    }

    // Map-data update: the dialog checks the mirrors and starts DownloadService in update mode;
    // progress/completion arrive through the MapUpdate flow (banner + apply, wired further down).
    // rememberSaveable so a rotation mid-check reopens the dialog (the cheap HEADs simply rerun).
    val mapUpdateState by MapUpdate.state.collectAsState()
    var mapUpdateDialogOpen by rememberSaveable { mutableStateOf(false) }
    val launchMapUpdate = startMapUpdate()

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

    var showSave by remember { mutableStateOf(false) }
    // The file manager's listing; non-null keeps the full-screen file manager open.
    var managerList by remember { mutableStateOf<List<PathStore.Summary>?>(null) }
    // Set while a rename / delete / copy dialog is open over the file manager; null when none is
    // pending. deleteTargetOpened records, as the delete dialog opens, whether that path is on the
    // map - the confirmation says so. The two name drafts keep what was typed across a
    // rejected-duplicate name, like saveNameDraft below.
    var renameTarget by remember { mutableStateOf<PathStore.Summary?>(null) }
    var renameNameDraft by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<PathStore.Summary?>(null) }
    var deleteTargetOpened by remember { mutableStateOf(false) }
    var copyTarget by remember { mutableStateOf<PathStore.Summary?>(null) }
    var copyNameDraft by remember { mutableStateOf<String?>(null) }
    // Name kept across a rejected-duplicate save so the reopened dialog prefills it for editing.
    var saveNameDraft by remember { mutableStateOf<String?>(null) }
    // Prefilled name of a parsed GPX import awaiting its name; non-null keeps the naming dialog
    // open. Saveable (the draft geometry itself is staged in [importDraftFile]) so the dialog
    // survives rotation and process death instead of forcing a re-import.
    var importDraftName by rememberSaveable { mutableStateOf<String?>(null) }
    // Save/load failures mean data was not written or cannot be read back - errors the user must
    // not miss - so they raise a blocking dialog instead of a timed snackbar (see CLAUDE.md,
    // "Message surfaces"). Null while no such error is showing.
    var storageErrorMessage by remember { mutableStateOf<String?>(null) }
    // Path awaiting GPX export, held from the picker launch until the SAF callback fires.
    // Saveable: the picker is a separate activity, so ours may be recreated (rotation, process
    // death) before the callback delivers the destination URI.
    var exportTarget by rememberSaveable(stateSaver = GpxExportTarget.Saver) { mutableStateOf<GpxExportTarget?>(null) }

    // Recording session - driven by [RecordingService] (foreground service + PARTIAL_WAKE_LOCK so
    // the track keeps being written with the screen off and the process in Doze). The activity
    // does not hold the [Recorder] directly: it goes through [RecordingController] for state
    // (overlay polyline, the 已停止 layer) and through service intents for start / pause / resume /
    // finish. The three-layer state machine (錄製中 -> 已停止 -> 存檔對話框) is driven directly off
    // recordingState: RECORDING shows RecordingBottomBar, PAUSED shows PausedBottomBar: there is no
    // separate "pending session" signal - the save/discard dialogs read the session straight from
    // RecordingController.currentSession() while paused.
    val recordingState by RecordingController.state.collectAsState()
    val recordedPoints by RecordingController.points.collectAsState()

    // Bootstrap provisional first point (docs/gating.md §3.5): non-null only while a fresh
    // session's first point is still converging. Drives the rubber band's fixed end (so the
    // overlay responds within seconds of recording starting) and the 定位收斂中 readout.
    val recordingProvisional by RecordingController.provisional.collectAsState()

    // Dialog flags for the recording flow. saveTrackNameDraft mirrors saveNameDraft - preserved
    // across a rejected duplicate so the reopened dialog prefills the typed name for editing.
    var showSaveTrack by remember { mutableStateOf(false) }
    var showDiscardRecording by remember { mutableStateOf(false) }
    var saveTrackNameDraft by remember { mutableStateOf<String?>(null) }
    // Pending start request held across the permission flow. null source = brand-new recording;
    // non-null source = continuation of that saved track. Cleared once the service is dispatched
    // or the user cancels the rationale.
    var pendingRecordingStart by remember { mutableStateOf<PendingRecordingStart?>(null) }
    var showBackgroundLocationRationale by remember { mutableStateOf(false) }
    var showBatteryExemptionRationale by remember { mutableStateOf(false) }

    // The GpsSource lease this activity currently holds, if any. Managed by the DisposableEffect
    // that tracks the map screen's lifecycle, by the RecordingController.state observer, and by
    // dispatchPendingStart() (which releases it before dispatching the start Intent so the service's
    // Strict-lease Recording acquire cannot collide). Declared up here rather than beside the other
    // map-marker state so dispatchPendingStart, defined a few lines below, can see it.
    var gpsOwnership: GpsSource.Ownership? by remember { mutableStateOf(null) }

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

    // Stats screen: statsTarget is non-null while the stats overlay is up; statsData stays null
    // while the numbers are still being computed off the main thread (the screen shows a spinner
    // meanwhile). The request id keeps a stale computation from landing after the user has
    // switched to another trace or closed the screen.
    var statsTarget by remember { mutableStateOf<StatsTarget?>(null) }
    var statsData by remember { mutableStateOf<TraceStats?>(null) }
    var statsRequestId by remember { mutableIntStateOf(0) }

    // Opens the stats overlay for one trace. [load] runs on the IO dispatcher and returns the
    // flattened points plus the per-point timestamps (null for plans, which have none); a load or
    // computation failure is a data-read error the user must not miss, so it raises the blocking
    // error dialog (see CLAUDE.md "Message surfaces") instead of a snackbar.
    fun openStats(
        name: String,
        isTrack: Boolean,
        load: suspend () -> Pair<List<LatLong>, List<Long>?>,
    ) {
        statsRequestId += 1
        val requestId = statsRequestId
        statsTarget = StatsTarget(name, isTrack)
        statsData = null
        scope.launch {
            try {
                val stats =
                    withContext(Dispatchers.IO) {
                        val (points, timesMs) = load()
                        // DEM lookups run inside compute; absent DEM leaves the elevation-derived
                        // fields null and the screen shows "-" / a no-data profile placeholder.
                        val lookup: ((Double, Double) -> Float?)? =
                            demElevation?.let { dem -> { lat, lon -> dem.elevationAt(lat, lon) } }
                        TraceStatsCalculator.compute(points, timesMs, lookup)
                    }
                if (statsRequestId == requestId && statsTarget != null) statsData = stats
            } catch (e: Exception) {
                if (statsRequestId == requestId) {
                    statsTarget = null
                    storageErrorMessage = "統計計算失敗: ${e.message}"
                }
            }
        }
    }

    // Stats-chart PNG export (D4, docs/stats.md): the trace name awaiting export, held from the
    // picker launch until the SAF callback fires. Not saveable - unlike GPX export this never
    // touches Documents/Jiudge (the profile is already in memory), so there is nothing worth
    // recovering across an activity recreation; a lost target after rotation simply requires
    // re-tapping 匯出 PNG.
    var pngExportName by remember { mutableStateOf<String?>(null) }
    val pngExportLauncher =
        rememberLauncherForActivityResult(
            object : ActivityResultContracts.CreateDocument("image/png") {
                override fun createIntent(
                    context: Context,
                    input: String,
                ): Intent =
                    super.createIntent(context, input).putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    )
            },
        ) { uri ->
            val name = pngExportName
            pngExportName = null
            val profile = statsData?.profile
            if (uri == null) {
                // User cancelled the picker - silent (D4).
                return@rememberLauncherForActivityResult
            }
            if (name == null || profile == null) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                storageErrorMessage = "匯出失敗: 匯出目標已遺失, 請重新操作"
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        val bitmap =
                            renderProfileChartBitmap(
                                profile = profile,
                                colors = chartColors,
                                textMeasurer = chartTextMeasurer,
                                title = name,
                                density = chartDensity,
                            )
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
                        } ?: error("cannot open the destination file")
                    }
                    snackbarHostState.showSnackbar("已匯出 \"$name\"")
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    storageErrorMessage = "匯出失敗: ${e.message}"
                }
            }
        }

    // GPX import: a one-off content URI from the system document picker or from another app's
    // ACTION_VIEW hand-over (reading it needs no storage permission - only the later save into
    // Documents/Jiudge does). What the file becomes follows from its contents and is never asked:
    // a track when every point is timestamped (all points and times kept, created at the earliest
    // one), otherwise a planned route (times dropped, each segment simplified and marked imported).
    // Parsing runs off the main thread; the result is staged on disk in [importDraftFile] and only
    // the prefilled name parks in importDraftName, whose naming dialog drives the save - so an
    // activity recreation under the dialog costs nothing. Parse failures and empty files are
    // data-level errors the user must not miss, so they raise the blocking error dialog (see
    // CLAUDE.md "Message surfaces").
    fun importGpx(uri: Uri) {
        scope.launch {
            try {
                // Null when the file parsed but holds no usable segment.
                val stagedName =
                    withContext(Dispatchers.IO) {
                        val parsed =
                            context.contentResolver.openInputStream(uri)?.use { GpxImporter.parse(it) }
                                ?: throw GpxParseException("cannot open the selected file")
                        if (parsed.segments.isEmpty()) return@withContext null
                        // Prefer the name embedded in the file; fall back to the display name
                        // (file name without extension) the provider reports. Purely best-effort:
                        // a provider that ignores the projection, omits the column, or throws must
                        // only cost the prefill, never fail the import.
                        val fallbackName =
                            runCatching {
                                context.contentResolver
                                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                    ?.use { cursor ->
                                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                                    }
                            }.getOrNull()
                                ?.substringBeforeLast('.')
                                ?.trim()
                        val name =
                            parsed.name
                                ?.trim()
                                .orEmpty()
                                .ifEmpty { fallbackName.orEmpty() }
                        val draft: SavedPath =
                            if (parsed.isTrackEligible) {
                                val points =
                                    parsed.segments.flatten().map { p ->
                                        RecordedTrack.Point(p.latitude, p.longitude, requireNotNull(p.timeMs))
                                    }
                                RecordedTrack(name = name, createdAtEpochMs = points.minOf { it.timeMs }, points = points)
                            } else {
                                val segments =
                                    parsed.segments.map { seg ->
                                        simplifyPolyline(seg.map { it.latLong }, PATH_SIMPLIFY_TOLERANCE_M)
                                    }
                                PlannedRoute.fromImportedSegments(name, System.currentTimeMillis(), segments)
                            }
                        Trace.write(importDraftFile(context), draft.header(), draft.toRecords())
                        name
                    }
                if (stagedName == null) {
                    storageErrorMessage = "匯入失敗: 檔案內沒有任何軌跡點"
                } else {
                    importDraftName = stagedName
                }
            } catch (e: Exception) {
                storageErrorMessage = "匯入失敗: ${e.message}"
            }
        }
    }

    val gpxPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importGpx(uri)
        }

    // A .gpx opened from another app: same import flow as the file manager's picker. Refused while
    // a plan is being edited, so the unsaved edit is never left behind a freshly imported path.
    // Consumed (cleared) up front so a recomposition cannot replay the parse.
    LaunchedEffect(externalGpxUri) {
        val uri = externalGpxUri ?: return@LaunchedEffect
        onExternalGpxConsumed()
        if (mode == ScreenMode.EDIT) {
            snackbarHostState.showSnackbar("路徑編輯中, 無法匯入 GPX; 請先儲存或離開編輯")
        } else {
            importGpx(uri)
        }
    }

    // GPX export: saves through the SAF "create document" picker rather than writing straight into
    // Documents/Jiudge, since the export destination is the user's choice (Downloads, a synced
    // folder, etc.), not our own fixed storage. The custom contract only adds an initial-location
    // hint pointing the picker at Downloads - vendor pickers are free to ignore it.
    //
    // The MIME is octet-stream, not the semantically correct application/gpx+xml, because a
    // DocumentsProvider resolving a name clash calls FileUtils.splitFileName(), which only treats
    // ".gpx" as an extension when the MIME it maps to matches the declared one. MimeTypeMap knows
    // no gpx entry, so it maps to octet-stream: declaring gpx+xml makes the two disagree, the whole
    // "name.gpx" is taken as the base name, and the copy lands as "name.gpx (1)" - which no other
    // app then recognises as GPX. Agreeing on octet-stream yields "name (1).gpx".
    val gpxExportLauncher =
        rememberLauncherForActivityResult(
            object : ActivityResultContracts.CreateDocument("application/octet-stream") {
                override fun createIntent(
                    context: Context,
                    input: String,
                ): Intent =
                    super.createIntent(context, input).putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    )
            },
        ) { uri ->
            if (uri == null) {
                // User cancelled the picker - drop the pending target silently.
                exportTarget = null
                return@rememberLauncherForActivityResult
            }
            val target = exportTarget
            exportTarget = null
            if (target == null) {
                // Should be unreachable - the target is saveable across recreation. If it is gone
                // anyway, the picker already created an empty file: remove it and tell the user
                // instead of silently doing nothing after an explicit 儲存.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                storageErrorMessage = "匯出失敗: 匯出目標已遺失, 請重新操作"
                return@rememberLauncherForActivityResult
            }
            // Reading the source path file lives under Documents/Jiudge, which needs storage
            // access; writing to the picked SAF uri does not. The file's kind picks the format: a
            // planned route goes out as named waypoints plus a route, a track as a timed track.
            withStorageAccess {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val lookup: ((Double, Double) -> Float?)? =
                                demElevation?.let { dem -> { lat, lon -> dem.elevationAt(lat, lon) } }
                            val stream =
                                context.contentResolver.openOutputStream(uri)
                                    ?: error("cannot open the destination file")
                            stream.use { out ->
                                when (val path = pathStore.load(target.file)) {
                                    is PlannedRoute -> GpxExporter.write(path, out, lookup)
                                    is RecordedTrack -> GpxExporter.write(path, out, lookup)
                                }
                            }
                        }
                        snackbarHostState.showSnackbar("已匯出 \"${target.name}\"")
                    } catch (e: Exception) {
                        // Best-effort cleanup of a possibly-half-written file; failure here is not
                        // itself an error worth surfacing.
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        storageErrorMessage = "匯出失敗: ${e.message}"
                    }
                }
            }
        }

    // Recording-start permission flow: POST_NOTIFICATIONS on Android 13+ (asked inline; the service
    // still works without it, the notification is just hidden), then ACCESS_BACKGROUND_LOCATION on
    // Android 10+ (the OS refuses to grant this via inline dialog on Android 11+, so we walk the
    // user to the system settings page via [BackgroundLocationRationaleDialog]), then the advisory
    // battery-optimization exemption ([BatteryExemptionRationaleDialog] - recording starts whether
    // or not the user grants it).
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

    // Last gate before dispatch, and the only advisory one: without the battery-optimization
    // exemption, Doze ignores the recording service's wake lock once the device sits still long
    // enough with the screen off, so fixes may be dropped during long stationary rests. Asked on
    // every start while unexempted (the user can revoke the exemption in Settings at any time, so
    // no refusal is remembered); every path out of the rationale still starts the recording.
    fun proceedAfterBatteryExemption() {
        if (pendingRecordingStart == null) return
        val exempt =
            context
                .getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        if (exempt) {
            dispatchPendingStart()
        } else {
            showBatteryExemptionRationale = true
        }
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
        proceedAfterBatteryExemption()
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
                proceedAfterBatteryExemption()
            } else {
                pendingRecordingStart = null
                scope.launch {
                    snackbarHostState.showSnackbar("未取得背景定位權限, 無法在螢幕關閉時持續錄製")
                }
            }
        }

    // The system exemption dialog fired by [BatteryExemptionRationaleDialog]'s 前往允許. The result
    // code is deliberately ignored: allow or deny, the recording proceeds - the exemption only
    // affects fix reliability through Doze, not the ability to record.
    val batteryExemptionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            dispatchPendingStart()
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
    // Suppressed when this screen was rebuilt with a restored camera (map-data update apply): the
    // next fix must not yank the view away from where the user had it.
    var recenterOnFix by remember { mutableStateOf(initialCamera == null && GpsSource.hasPermission(context)) }
    // Rebuilding this screen while the activity is RESUMED (map-data update apply) replays a
    // synthetic ON_RESUME into the freshly registered lifecycle observer, whose refocus would
    // override the restored camera. Suppress that one refocus; later (real) resumes refocus as usual.
    var suppressResumeRefocus by remember { mutableStateOf(initialCamera != null) }
    // Persistent marker-follow toggle. While true, the map keeps the marker in sight on every fix -
    // safe-zone push while it is inside the viewport, hard recenter once it drifts out (typical of
    // GPS jumps out of a tunnel or on public transport). Decided by each map gesture's end state:
    // a gesture that ends with the marker still projecting inside the viewport keeps (or re-arms)
    // the follow, one that ends with it outside stops it (that is the user's signal they want to
    // look elsewhere). Also re-armed on ON_RESUME (recording or not, a return to the app is a
    // "refocus") and on the recenter FAB. Sits alongside the one-shot [recenterOnFix] which still
    // owns first-fix / FAB "jump to me" behaviour. Starts off after a restored-camera rebuild for
    // the same reason as recenterOnFix; the next gesture or the FAB re-arms it as usual.
    var followUser by remember { mutableStateOf(initialCamera == null) }

    // True while at least one finger is on the map. Pauses the safe-zone follow so an incoming GPS
    // update does not fight the user's pan/pinch mid-gesture.
    var userTouching by remember { mutableStateOf(false) }
    // True when the current-location marker projects inside the MapView's pixel rectangle. Drives the
    // recentre FAB's lit state (lit only when the map is no longer tracking the marker), and is
    // recomputed on every map move (mapsforge Observer) and every new fix.
    var markerInViewport by remember { mutableStateOf(false) }

    // Pixel sizes of the map-overlay container and the two bottom control groups. Drive the
    // small-screen fallback that lifts the bottom-end FAB column above the bottom-start pill row
    // when they would otherwise overlap (e.g. with the four view-state pills on narrow phones).
    var mapContainerWidthPx by remember { mutableStateOf(0) }
    var pillRowWidthPx by remember { mutableStateOf(0) }
    var pillRowHeightPx by remember { mutableStateOf(0) }
    var fabColumnWidthPx by remember { mutableStateOf(0) }

    // Connection-info popup: long-pressing the location marker opens it; the DEM altitude shown there
    // is looked up off the main thread when it opens (independent of the GPS-reported altitude).
    var locationInfoOpen by remember { mutableStateOf(false) }
    var locationInfoDemAltitude by remember { mutableStateOf<Double?>(null) }

    // The two marker layers are added through pathOverlays, which places them above every path:
    // the search highlight first, the current-location marker on top of everything.
    val locationLayer =
        remember(map.value, pathOverlays) {
            val mv = map.value
            if (mv == null || pathOverlays == null) {
                null
            } else {
                CurrentLocationLayer(
                    density = density,
                    mapViewPosition = mv.model.mapViewPosition,
                    onMarkerLongPress = { locationInfoOpen = true },
                ).also { pathOverlays.addMarkerLayer(it) }
            }
        }

    // Highlight for a peak jumped to from the search dialog: a translucent yellow disc that stays
    // until the user confirms the target or returns to the result list.
    val searchPeakLayer =
        remember(pathOverlays) {
            pathOverlays?.let { overlays -> SearchPeakMarkerLayer(density).also { overlays.addMarkerLayer(it) } }
        }

    // Sweep any leftover .recording-*.jsonl staging files (from a crash or a swiped-away task).
    // Delegated to RecordingController, which skips the live session's file: this effect re-runs on
    // every activity recreation, and a background recording may well be in progress at that moment.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecordingController.sweepStaleStaging() }
    }

    // Live recording overlay: push the recorder's polyline into the recording layer whenever it
    // changes (an empty list, once the session ends, clears the line).
    LaunchedEffect(pathOverlays, recordedPoints) {
        pathOverlays?.setRecording(recordedPoints)
    }

    // Rubber-band segment (docs/gating.md §6): while actively recording, keep the trail visually
    // connected to the current-location marker even when gating holds fixes back. Display-only -
    // nothing here is written to the track. Cleared outside RECORDING so a paused or finished
    // track does not chase the marker.
    LaunchedEffect(pathOverlays) {
        val overlays = pathOverlays ?: return@LaunchedEffect
        combine(GpsSource.fix, RecordingController.state) { fix, state ->
            if (state == Recorder.State.RECORDING && fix != null) LatLong(fix.latitude, fix.longitude) else null
        }.collect { tip -> overlays.setRecordingLiveTip(tip) }
    }

    // Bootstrap provisional (docs/gating.md §3.5/§6): before any point is written, the rubber
    // band's fixed end follows the best first-point candidate, so the overlay shows a dashed link
    // to the marker within seconds of recording starting instead of staying blank until the first
    // point commits. Display-only, like the band itself.
    LaunchedEffect(pathOverlays) {
        val overlays = pathOverlays ?: return@LaunchedEffect
        combine(RecordingController.provisional, RecordingController.state) { prov, state ->
            if (state == Recorder.State.RECORDING && prov != null) LatLong(prov.latitude, prov.longitude) else null
        }.collect { start -> overlays.setRecordingProvisionalStart(start) }
    }

    // Opened-path overlays: mirror openedPaths onto the map, one layer per entry keyed by its id.
    // An entry's geometry never changes under its id - an edit or a continued recording comes back
    // as a new entry - so only ids that appeared or went away need any work, and a rename touches
    // nothing. New ids are always at the end of the list, so showing them in list order stacks
    // them above the paths already there (and rebuilds the whole order on a fresh PathOverlays).
    LaunchedEffect(pathOverlays) {
        val overlays = pathOverlays ?: return@LaunchedEffect
        var shownIds = emptySet<Long>()
        snapshotFlow { openedPaths }.collect { paths ->
            val ids = paths.mapTo(HashSet()) { it.id }
            (shownIds - ids).forEach { overlays.removeOpened(it) }
            for (opened in paths) {
                if (opened.id in shownIds) continue
                val path = opened.path
                overlays.showOpened(
                    key = opened.id,
                    style = if (path.kind == PathKind.TRACK) PathStyle.TRACK else PathStyle.PLAN,
                    points = path.polyline,
                    waypoints = (path as? PlannedRoute)?.waypoints?.map { it.point }.orEmpty(),
                )
            }
            shownIds = ids
        }
    }

    // A continued recording takes its source track off the opened paths: all of that track's
    // points are the start of the recording now, so the recording layer draws them. Done once the
    // session is actually live rather than on the button press, so a start that never happens (a
    // permission refused along the way) leaves the track where it was, still focused.
    LaunchedEffect(recordingState) {
        if (recordingState == Recorder.State.IDLE) return@LaunchedEffect
        val source = RecordingController.currentSession()?.takeIf { it.isContinuation }?.source ?: return@LaunchedEffect
        val taken = openedFor(source) ?: return@LaunchedEffect
        openedPaths = openedPaths - taken
        if (focusId == taken.id) focusId = null
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
            // A denial needs no snackbar: the missing grant is a persistent state already covered
            // by FineLocationMissingBanner ("尚未授予定位權限, 點此授予"), which stays up and keeps
            // offering the retry tap.
            if (granted) {
                locationGranted = true
                recenterOnFix = true
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
                    // Spec G: the service keeps its Strict-lease Recording ownership through a pause
                    // (GPS subscription and wake lock stay held; only the state gate in Recorder
                    // drops fixes), so the activity must not also try to acquire the Foreground lease
                    // while RECORDING or PAUSED - only once the session is fully IDLE again.
                    if (RecordingController.state.value == Recorder.State.IDLE && gpsOwnership == null) {
                        gpsOwnership = GpsSource.acquire(context, GpsSource.Mode.Foreground)
                    }
                    headingProvider.start()
                    // Only refocus when the current UI context is one where following makes sense
                    // (the plain map state, or recording on top of it - all with no search / path
                    // work in progress). In the view or edit state, or with a pending search peak
                    // / open search dialog, the user is deliberately looking at something else -
                    // keep the map where they left it and let followUser retain its prior value.
                    if (mode == ScreenMode.MAP && pendingPeak == null && !searchDialogOpen && !suppressResumeRefocus) {
                        followUser = true
                        // Snap the map onto the last known position now if it is fresh; a stale
                        // fix (typically inside a long tunnel where GPS has been silent) would
                        // jump the map to an outdated spot, so fall back to the one-shot
                        // recenterOnFix and wait for a real fix. The touch listener also clears
                        // recenterOnFix on a manual pan, so this cannot fight a user who returns
                        // and immediately looks elsewhere.
                        val mv = map.value
                        val fix = GpsSource.fix.value
                        if (mv != null &&
                            fix != null &&
                            System.currentTimeMillis() - fix.timeMs < STALE_FIX_THRESHOLD_MS
                        ) {
                            mv.model.mapViewPosition.center = LatLong(fix.latitude, fix.longitude)
                        } else {
                            recenterOnFix = true
                        }
                    }
                    suppressResumeRefocus = false
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
    // Intent going out and this collector observing RECORDING. PAUSED is grouped with RECORDING here
    // (spec G): the service keeps its Recording lease through a pause, so the activity's Foreground
    // lease stays released until the session is fully IDLE.
    LaunchedEffect(Unit) {
        RecordingController.state.collect { state ->
            when (state) {
                Recorder.State.RECORDING, Recorder.State.PAUSED -> {
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
    // frequent compass ticks redraw only the map layer, not the whole MapScreen composable.
    LaunchedEffect(locationLayer) {
        val layer = locationLayer ?: return@LaunchedEffect
        combine(
            GpsSource.fix,
            headingProvider.heading,
            headingProvider.headingAccuracyDeg,
            GpsSource.serviceEnabled,
            GpsSource.fixStale,
        ) { fix, heading, headingAccuracy, enabled, stale ->
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
                // Grey the held fix once it no longer reflects a live position: the location
                // service was switched off, or every subscribed provider went quiet (fix stale).
                frozen = (!enabled || stale) && fix != null,
            )
        }.collect { }
    }

    // 1 Hz: per-fix safe-zone follow and first-fix center. Restarted when the MapView is recreated or
    // when the screen mode changes; entering the edit state (tapping the crosshair to add waypoints)
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
            if (recenterOnFix && !userTouching) {
                // One-shot land-on-marker; takes priority over safe-zone logic since the user
                // explicitly asked for centring (either at launch or via the recenter FAB). Guarded
                // by !userTouching so a fix arriving mid-gesture cannot fight the user's pan; if
                // the gesture turns out to be a real pan, the touch listener clears recenterOnFix
                // on release and this branch never fires.
                recenterOnFix = false
                mv.model.mapViewPosition.center = LatLong(fix.latitude, fix.longitude)
            } else if (mode == ScreenMode.MAP &&
                pendingPeak == null &&
                !searchDialogOpen &&
                !userTouching &&
                followUser
            ) {
                // The mode / pendingPeak / searchDialogOpen trio is the context gate: follow only
                // in the plain map state (recording sits on top of it so it is included) with no
                // search dialog and no pending search peak. The view state, the edit state and the
                // search flow are cases where the user is deliberately looking at something other
                // than their own location, so an incoming fix must not drag the map away.
                // followUser gates every automatic pan below. It is only false once one of the
                // user's own map gestures ended with the marker outside the viewport (see the
                // touch listener below), or the user explicitly picked a target to look at
                // (centerOnPeak, loading a route to edit), so the two branches here can safely
                // assume the user still wants to be followed.
                // Foreground mode subscribes to every enabled non-passive provider (GPS + network,
                // plus fused on Android 12+; GPS is dropped under a coarse-only grant), and all of
                // them drive the marker; the follow path deliberately treats them the same so a
                // coarse network fix indoors still keeps the marker roughly on screen. Recording is
                // a separate concern - GpsSource enforces GPS-only at the subscription layer for
                // Recording mode.
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
    val currentFixStale by GpsSource.fixStale.collectAsState()
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

    // Enters the edit state on a blank plan; opened paths stay on the map. Routing a leg needs the
    // BRouter data, so without it the edit state is not entered and a timed message says why.
    fun startNewPlan() {
        if (!engine.isReady()) {
            scope.launch { snackbarHostState.showSnackbar("BRouter 路由資料未就緒, 無法規劃路徑") }
            return
        }
        planner?.clear()
        editSource = null
        editing = true
    }

    // Enters the edit state on an opened planned route: it leaves the opened paths (the others
    // stay on the map) and the planner's editing layer draws it instead.
    fun startEditing(source: OpenedPath) {
        val route = source.path as? PlannedRoute ?: return
        val activePlanner = planner ?: return
        openedPaths = openedPaths - source
        focusId = null
        editSource = source
        activePlanner.loadFrom(route)
        // loadFrom pans to the route's first waypoint: stop following so the next fix does not
        // drag the map back onto the marker.
        followUser = false
        recenterOnFix = false
        editing = true
    }

    // Leaves the edit state without saving. A route that was opened before the edit goes back on
    // the map as it was, on top and focused; a brand-new plan leaves nothing behind.
    fun cancelEditing() {
        planner?.clear()
        val source = editSource
        editSource = null
        editing = false
        if (source != null) focusId = addOpened(source.path, source.file).id
    }

    // Opens the file manager on a fresh listing. The manager belongs to the plain map state, so a
    // listing that arrives after the screen has moved on - a tap on 規劃 or 錄製 landed while the
    // folder was being read - is dropped.
    fun openManager() {
        scope.launch {
            try {
                val listing = withContext(Dispatchers.IO) { pathStore.list() }
                if (mode == ScreenMode.MAP && recordingState == Recorder.State.IDLE) managerList = listing
            } catch (e: Exception) {
                storageErrorMessage = "讀取清單失敗: ${e.message}"
            }
        }
    }

    // Re-reads the listing after the file manager changed something. Dropped when the manager was
    // closed in the meantime, since assigning the listing is what shows it.
    suspend fun reloadManagerList() {
        try {
            val fresh = withContext(Dispatchers.IO) { pathStore.list() }
            if (managerList != null) managerList = fresh
        } catch (e: Exception) {
            storageErrorMessage = "讀取清單失敗: ${e.message}"
        }
    }

    // Opens a path picked in the file manager: it joins the opened paths (those already on the map
    // stay), the manager closes, the map frames the whole path and it becomes the focused one. A
    // path that is already open is not added again - it is only focused and framed. On a read
    // failure the manager stays up under the error dialog.
    fun openPath(summary: PathStore.Summary) {
        fun focus(opened: OpenedPath) {
            managerList = null
            focusId = opened.id
            map.value?.fitToRoute(opened.path.framingPoints())
        }
        val shown = openedFor(summary.file)
        if (shown != null) {
            focus(shown)
            return
        }
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { pathStore.load(summary.file) }
                // A second tap on the row may have opened it while this read was running.
                focus(openedFor(summary.file) ?: addOpened(path, summary.file))
            } catch (e: Exception) {
                storageErrorMessage = "開啟失敗: ${e.message}"
            }
        }
    }

    Box(modifier = modifier.onSizeChanged { mapContainerWidthPx = it.width }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            onRelease = { mv ->
                // Fires when this subtree leaves the composition for good: the mapEpoch rebuild
                // after a map-data update, and the composition disposal on activity destroy. This
                // is the only owner of MapView teardown - it closes the MapFile and clears the tile
                // cache, releasing the old (already replaced) files' disk space.
                mv.destroyAll()
                onMapReleased(mv)
            },
            factory = { ctx ->
                RudyMapView.create(ctx, mapDir, initialCamera).also { mv ->
                    // Observe touches for two things: (1) userTouching pauses the safe-zone follow
                    // while a finger (or two) is down, so an incoming fix does not fight the user's
                    // pan/pinch mid-gesture; (2) at gesture end, decide followUser purely from the
                    // end state: a marker still projecting inside the viewport means the user kept
                    // (or brought back) their position in sight, so keep following - zooming around
                    // the marker or panning back onto it re-arms the follow without the FAB. A
                    // marker outside the viewport means they want to look elsewhere, so stop
                    // following and drop any armed one-shot recenter (without that clear, a fix
                    // arriving after the user let go would still snap the map back onto the marker,
                    // defeating the pan). With no usable fix (none yet, or the last one is stale)
                    // there is nothing to project, so both flags keep their pre-gesture values.
                    // Returning false lets mapsforge's own gesture handling run unchanged.
                    mv.setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> userTouching = true
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                userTouching = false
                                val fix = GpsSource.fix.value
                                if (fix != null &&
                                    System.currentTimeMillis() - fix.timeMs < STALE_FIX_THRESHOLD_MS
                                ) {
                                    if (MapFollow.isMarkerInViewport(mv, fix)) {
                                        followUser = true
                                    } else {
                                        followUser = false
                                        recenterOnFix = false
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

        // Top-start controls, opposite the zoom column: the main menu (hamburger) on top, then the
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
                onSettings = {
                    scope.launch { snackbarHostState.showSnackbar("設定功能尚未完成") }
                },
                onMapUpdate = { mapUpdateDialogOpen = true },
                onAbout = { aboutOpen = true },
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
            // Map-data update: progress while the service runs, then a tap-to-apply prompt once the
            // new data is on disk (both persistent states, hence banners; failure is an AlertDialog).
            when (val update = mapUpdateState) {
                is MapUpdateState.Running ->
                    MapUpdateBanner(
                        fraction = update.fraction,
                        currentName = update.currentName,
                        phase = update.phase,
                        onCancel = { DownloadService.cancel(appContext) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                MapUpdateState.Done ->
                    MapUpdateApplyBanner(onClick = onApplyUpdate, modifier = Modifier.fillMaxWidth())
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
        // row. On small screens, where the pill row (e.g. the four pills of the view state) would
        // otherwise overlap this column, it lifts above the pill row instead - measured live
        // off the actual pill-row width/height and the container width, so the choice adapts to
        // whatever pill set the current mode is showing.
        if (!identifyMode &&
            identifyResult == null &&
            identifyCandidates == null &&
            mode != ScreenMode.EDIT
        ) {
            val stackAboveGapDp = 12.dp
            // True only when we have measurements for both groups AND they would overlap. The
            // measured pillRowWidthPx / fabColumnWidthPx already include each group's own 16 dp
            // padding (onSizeChanged sits before padding in the modifier chain, so it wraps the
            // padded node), so the sum touching the container width means the two padded edges
            // already meet - no further gap is available. While any measurement is still 0 (first
            // frame, or the pill row has not laid out yet) we keep the default BottomEnd placement
            // so the FAB does not flash to an above-pills position.
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
                        .onSizeChanged { fabColumnWidthPx = it.width }
                        .padding(end = 16.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MyLocationButton(
                    onClick = { recenterOnCurrentLocation() },
                    active = locationGranted && serviceEnabled && !markerInViewport,
                )
            }
        }

        // Crosshair only while editing a route (independent of the bottom controls row).
        if (mode == ScreenMode.EDIT && !identifyMode && identifyResult == null) {
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
                    .onSizeChanged {
                        pillRowWidthPx = it.width
                        pillRowHeightPx = it.height
                    }.padding(16.dp),
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
            } else if (!identifyMode && identifyResult == null && recordingState == Recorder.State.RECORDING) {
                // While a recording session is alive the bottom-start row is owned by the recording
                // bar; the entry / view / editing controls do not show. This also hides "規劃" and
                // "檔案" during recording: a reference path is opened before the recording starts.
                RecordingBottomBar(
                    onStop = {
                        // Spec C: pause (not stop-and-finalise) - the service keeps the GPS
                        // subscription, wake lock, and foreground notification alive, only flipping
                        // RecordingController to PAUSED so the 已停止 bar takes over below.
                        RecordingService.pause(context)
                    },
                    onDiscard = {
                        // Spec B: 放棄 while recording first pauses (exactly like 停止) and lands on
                        // the 已停止 layer with the confirmation on top. Cancelling the dialog then
                        // leaves the user paused - resuming is an explicit 繼續錄製, the same recovery
                        // as after a mistaken 停止. Pausing first also guarantees the discard never
                        // races the fix writer: the append gate is closed before the file can go away.
                        RecordingService.pause(context)
                        showDiscardRecording = true
                    },
                    // First-point convergence readout (docs/gating.md §3.5): tells the user why no
                    // track has appeared yet. Provisional non-null implies zero committed points.
                    statusText =
                        recordingProvisional?.let { prov ->
                            prov.accuracyMeters?.let { "定位收斂中 (目前精度 ${it.roundToInt()} m)" }
                                ?: "定位收斂中"
                        },
                )
            } else if (!identifyMode && identifyResult == null && recordingState == Recorder.State.PAUSED) {
                // 已停止 layer (spec A/F): 儲存 opens the save dialog, 放棄 opens the discard
                // confirmation, 繼續錄製 resumes the same session. 儲存 is disabled only for a
                // brand-new recording with zero points so far; a continuation always has at least the
                // source track's own points and is never disabled here.
                PausedBottomBar(
                    canSave = recordedPoints.isNotEmpty(),
                    onSave = {
                        saveTrackNameDraft = RecordingController.currentSession()?.defaultName
                        showSaveTrack = true
                    },
                    onDiscard = { showDiscardRecording = true },
                    onResume = { RecordingService.resume(context) },
                )
            } else if (!identifyMode && identifyResult == null) {
                when (mode) {
                    ScreenMode.MAP ->
                        MapEntryControls(
                            canRecord = locationGranted,
                            canClear = openedPaths.isNotEmpty(),
                            // 錄製 and 規劃 start right away, with no chooser in between.
                            onRecord = {
                                withStorageAccess { requestStartRecording(continuationSource = null) }
                            },
                            onPlan = { startNewPlan() },
                            onFiles = { withStorageAccess { openManager() } },
                            // Takes every opened path off the map; the files are untouched.
                            onClear = {
                                openedPaths = emptyList()
                                focusId = null
                            },
                        )

                    ScreenMode.EDIT ->
                        PlanningBottomBar(
                            waypointCount = planner?.waypoints?.size ?: 0,
                            canRemove = planner?.canRemoveLast ?: false,
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
                            onCancel = { cancelEditing() },
                        )

                    ScreenMode.VIEW ->
                        focusedPath?.let { focused ->
                            val path = focused.path
                            PathViewControls(
                                kind = path.kind,
                                onEdit = { startEditing(focused) },
                                // The track stays focused until the session is live (see the
                                // effect that takes a continuation's source off the opened paths).
                                onContinueRecording = { requestStartRecording(continuationSource = focused.file) },
                                onStats = {
                                    openStats(path.name, isTrack = path.kind == PathKind.TRACK) {
                                        path.polyline to path.pointTimesMs
                                    }
                                },
                                onExport = {
                                    exportTarget = GpxExportTarget(focused.file, path.name)
                                    gpxExportLauncher.launch(gpxSuggestedName(path.name))
                                },
                                // Back to the map state; the path stays on the map as an opened path.
                                onLeave = { focusId = null },
                            )
                        }
                }
            }
        }

        // File manager: a full-screen surface over the map, banners and controls. Being part of
        // this screen rather than a dialog window, timed messages still show above it and the
        // stats overlay below can cover it in turn. Every action but opening a path, a finished
        // import and closing leaves the manager up.
        managerList?.let { summaries ->
            PathManagerScreen(
                summaries = summaries,
                onOpen = { openPath(it) },
                onStats = { summary ->
                    // Reading the path file needs the same storage access as opening it.
                    withStorageAccess {
                        openStats(summary.name, isTrack = summary.kind == PathKind.TRACK) {
                            val path = pathStore.load(summary.file)
                            path.polyline to path.pointTimesMs
                        }
                    }
                },
                onRename = { renameTarget = it },
                onExport = { summary ->
                    exportTarget = GpxExportTarget(summary.file, summary.name)
                    gpxExportLauncher.launch(gpxSuggestedName(summary.name))
                },
                onCopyAsPlan = { copyTarget = it },
                onDelete = { summary ->
                    deleteTargetOpened = openedFor(summary.file) != null
                    deleteTarget = summary
                },
                onImport = {
                    // GPX has no reliably registered MIME type (providers commonly report
                    // application/octet-stream), so do not filter - the parser rejects non-GPX content.
                    gpxPickerLauncher.launch(arrayOf("*/*"))
                },
                onClose = { managerList = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Stats overlay: drawn last inside the map Box so it covers the map, banners, controls and
        // the file manager (its opaque Surface also swallows touches). Back and 關閉 both just clear
        // the state; whatever it was opened from - the view state or the file manager - is
        // untouched underneath, so closing lands back where the user came from.
        statsTarget?.let { target ->
            StatsScreen(
                name = target.name,
                isTrack = target.isTrack,
                stats = statsData,
                onClose = {
                    statsTarget = null
                    statsData = null
                },
                onExportPng = {
                    pngExportName = target.name
                    pngExportLauncher.launch(pngSuggestedName(target.name))
                },
                modifier = Modifier.fillMaxSize(),
            )
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

    importDraftName?.let { draftName ->
        // Saves the staged import under [name] and shows it: whichever kind the draft holds, it
        // becomes the topmost opened path, is focused and framed whole, and the file manager and
        // the stats screen - either may be up when the import came from another app - close. The
        // draft stays staged across a rejected-duplicate reopen, so retrying under another name
        // re-reads it.
        fun saveImported(name: String) {
            scope.launch {
                // The staged draft can only vanish if the OS cleared the cache dir while the
                // naming dialog was open - rare, but report it rather than silently dropping
                // an explicit 匯入.
                val staged =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            Trace.read(importDraftFile(context))?.let { SavedPath.fromTrace(it) }
                        }.getOrNull()
                    }
                if (staged == null) {
                    storageErrorMessage = "匯入失敗: 草稿已遺失, 請重新匯入"
                    return@launch
                }
                // A track keeps the creation time taken from its earliest point; a planned route
                // is created now.
                val imported: SavedPath =
                    when (staged) {
                        is PlannedRoute -> staged.copy(name = name, createdAtEpochMs = System.currentTimeMillis())
                        is RecordedTrack -> staged.copy(name = name)
                    }
                try {
                    val savedFile =
                        withContext(Dispatchers.IO) {
                            val file =
                                when (imported) {
                                    is PlannedRoute -> pathStore.save(imported)
                                    is RecordedTrack -> pathStore.save(imported)
                                }
                            importDraftFile(context).delete()
                            file
                        }
                    managerList = null
                    statsTarget = null
                    statsData = null
                    focusId = addOpened(imported, savedFile).id
                    map.value?.fitToRoute(imported.framingPoints())
                    snackbarHostState.showSnackbar("已匯入路徑: $name")
                } catch (e: DuplicatePathNameException) {
                    // Keep the typed name (and the staged file) and reopen so the user can
                    // rename in place.
                    importDraftName = name
                    snackbarHostState.showSnackbar("已有同名路徑 \"${e.pathName}\", 請改用其他名稱")
                } catch (e: Exception) {
                    storageErrorMessage = "儲存失敗: ${e.message}"
                }
            }
        }

        ImportPathDialog(
            initialName = draftName,
            onConfirm = { name ->
                importDraftName = null
                withStorageAccess { saveImported(name) }
            },
            onDismiss = {
                importDraftName = null
                scope.launch(Dispatchers.IO) {
                    runCatching { importDraftFile(context).delete() }
                }
            },
        )
    }

    if (showSave) {
        SavePathDialog(
            initialName = saveNameDraft ?: editSource?.path?.name ?: "",
            onConfirm = { name ->
                val p = planner
                showSave = false
                if (p != null) {
                    val saved = p.toPlannedRoute(name, System.currentTimeMillis())
                    // Saving an edited route writes a fresh file (the name is stamped with the new
                    // creation time), so hand the edit source over: the store deletes it, or the
                    // file manager would list both copies, and leaves it out of the duplicate-name
                    // check so the route may keep its own name. A brand-new plan replaces nothing.
                    val replacing = editSource?.file
                    withStorageAccess {
                        scope.launch {
                            try {
                                val savedFile =
                                    withContext(Dispatchers.IO) {
                                        pathStore.save(saved, replacing = replacing)
                                    }
                                // Keep the route on screen: leave editing for the view state, with
                                // the saved route as the topmost opened path and the focused one.
                                p.clear()
                                editSource = null
                                editing = false
                                saveNameDraft = null
                                focusId = addOpened(saved, savedFile).id
                                snackbarHostState.showSnackbar("已儲存路徑: $name")
                            } catch (e: DuplicatePathNameException) {
                                // Keep the typed name and reopen so the user can rename in place.
                                saveNameDraft = name
                                showSave = true
                                snackbarHostState.showSnackbar("已有同名路徑 \"${e.pathName}\", 請改用其他名稱")
                            } catch (e: Exception) {
                                storageErrorMessage = "儲存失敗: ${e.message}"
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

    renameTarget?.let { target ->
        RenamePathDialog(
            initialName = renameNameDraft ?: target.name,
            onConfirm = { newName ->
                renameTarget = null
                renameNameDraft = null
                withStorageAccess {
                    scope.launch {
                        try {
                            val renamed = withContext(Dispatchers.IO) { pathStore.rename(target.file, newName) }
                            // Keep the path's entry on the map in step when it is opened: same id,
                            // so its layer and the focus are untouched, with the new name and the
                            // new file - renaming may shift the file path (the slug follows the
                            // name), and export, edit and continued recording all act on that file.
                            openedFor(target.file)?.let { opened ->
                                val entry = OpenedPath(opened.id, opened.path.renamed(renamed.name), renamed.file)
                                openedPaths = openedPaths.map { if (it === opened) entry else it }
                            }
                            reloadManagerList()
                            snackbarHostState.showSnackbar("已改名為 \"${renamed.name}\"")
                        } catch (e: DuplicatePathNameException) {
                            // Keep the typed name and reopen so the user can rename in place.
                            renameNameDraft = newName
                            renameTarget = target
                            snackbarHostState.showSnackbar("已有同名路徑 \"${e.pathName}\", 請改用其他名稱")
                        } catch (e: Exception) {
                            storageErrorMessage = "改名失敗: ${e.message}"
                        }
                    }
                }
            },
            onDismiss = {
                renameTarget = null
                renameNameDraft = null
            },
        )
    }

    deleteTarget?.let { target ->
        DeletePathDialog(
            pathName = target.name,
            isOpened = deleteTargetOpened,
            onConfirm = {
                deleteTarget = null
                withStorageAccess {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { pathStore.delete(target.file) }
                            // A deleted path leaves the map with its file, so no line is ever left
                            // on screen without a file behind it. The other opened paths stay.
                            openedFor(target.file)?.let { opened ->
                                openedPaths = openedPaths - opened
                                if (focusId == opened.id) focusId = null
                            }
                            reloadManagerList()
                            snackbarHostState.showSnackbar("已刪除 \"${target.name}\"")
                        } catch (e: Exception) {
                            storageErrorMessage = "刪除失敗: ${e.message}"
                        }
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    copyTarget?.let { target ->
        CopyAsPlanDialog(
            initialName = copyNameDraft ?: "${target.name} 規劃",
            onConfirm = { name ->
                copyTarget = null
                copyNameDraft = null
                withStorageAccess {
                    scope.launch {
                        try {
                            // The copy is a new planned route created now; the track it came from
                            // is only read. Nothing on the map changes - the file manager stays up
                            // and simply lists one more path.
                            withContext(Dispatchers.IO) {
                                val track =
                                    pathStore.load(target.file) as? RecordedTrack
                                        ?: error("not a recorded track: ${target.file.name}")
                                pathStore.save(track.toPlannedRoute(name, System.currentTimeMillis()))
                            }
                            reloadManagerList()
                            snackbarHostState.showSnackbar("已複製為規劃路徑: $name")
                        } catch (e: DuplicatePathNameException) {
                            // Keep the typed name and reopen so the user can rename in place.
                            copyNameDraft = name
                            copyTarget = target
                            snackbarHostState.showSnackbar("已有同名路徑 \"${e.pathName}\", 請改用其他名稱")
                        } catch (e: Exception) {
                            storageErrorMessage = "儲存失敗: ${e.message}"
                        }
                    }
                }
            },
            onDismiss = {
                copyTarget = null
                copyNameDraft = null
            },
        )
    }

    if (showSaveTrack) {
        // The session lives in RecordingController for as long as the 已停止 layer is up (state
        // stays PAUSED), so the save dialog reads it straight off currentSession() rather than a
        // one-shot "pending session" signal - it is still there if the dialog reopens after a
        // rejected duplicate name.
        val session = RecordingController.currentSession()
        SavePathDialog(
            initialName = saveTrackNameDraft ?: session?.defaultName ?: "",
            onConfirm = { name ->
                showSaveTrack = false
                if (session != null) {
                    withStorageAccess {
                        scope.launch {
                            try {
                                val savedFile = withContext(Dispatchers.IO) { RecordingController.finalize(session, name) }
                                // Saving ends the session - RecordingService.finish() tells the
                                // service to release GPS/wake lock, remove the notification, and
                                // call RecordingController.handleEnd() (back to IDLE) - and lands
                                // in the view state on the just-saved track. It is read back from
                                // the finalised file so the opened entry carries the real
                                // timestamps and a file 繼續錄製 can hand to the service again.
                                RecordingService.finish(context)
                                val saved = withContext(Dispatchers.IO) { pathStore.load(savedFile) }
                                saveTrackNameDraft = null
                                focusId = addOpened(saved, savedFile).id
                                snackbarHostState.showSnackbar("已儲存路徑: $name")
                            } catch (e: DuplicatePathNameException) {
                                // Keep the session alive (still PAUSED) and reopen the dialog with
                                // the user's typed name preserved so they can retry.
                                saveTrackNameDraft = name
                                showSaveTrack = true
                                snackbarHostState.showSnackbar("已有同名路徑 \"${e.pathName}\", 請改用其他名稱")
                            } catch (e: Exception) {
                                storageErrorMessage = "儲存失敗: ${e.message}"
                            }
                        }
                    }
                }
            },
            onDismiss = {
                // Back / outside-tap / 取消 all just close the dialog - back to 已停止, no discard,
                // no session change.
                showSaveTrack = false
            },
        )
    }

    if (showDiscardRecording) {
        val session = RecordingController.currentSession()
        DiscardRecordingDialog(
            continuationName = if (session?.isContinuation == true) session.defaultName else null,
            onConfirm = {
                showDiscardRecording = false
                if (session != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) { RecordingController.discard(session) }
                        // Discarding always ends the session - RecordingService.finish() releases
                        // GPS/wake lock, removes the notification, and calls
                        // RecordingController.handleEnd() (back to IDLE).
                        RecordingService.finish(context)
                        saveTrackNameDraft = null
                        // A discarded continuation loses only the newly added points; its source
                        // file is intact, so that track goes back on the map, on top and focused.
                        // The source comes from the session, not from what this screen remembers,
                        // so this also holds after the activity was recreated mid-recording. A
                        // discarded fresh recording has nothing to fall back to: back to the map
                        // state, opened paths untouched.
                        val source = session.source
                        if (session.isContinuation && source != null) {
                            try {
                                val original = withContext(Dispatchers.IO) { pathStore.load(source) }
                                focusId = addOpened(original, source).id
                            } catch (e: Exception) {
                                focusId = null
                                storageErrorMessage = "回復原始軌跡失敗: ${e.message}"
                            }
                        } else {
                            focusId = null
                        }
                    }
                }
            },
            onDismiss = {
                // 取消 just closes this dialog, landing on the 已停止 layer (放棄 from the recording
                // layer pauses before opening this dialog, so the session is always PAUSED here);
                // the user resumes explicitly via 繼續錄製.
                showDiscardRecording = false
            },
        )
    }

    // Blocking surface for save/load failures - a timed snackbar can vanish unseen, but these
    // errors mean the data is not on disk (or cannot be read back), so they must be acknowledged.
    storageErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { storageErrorMessage = null },
            title = { Text("操作失敗") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { storageErrorMessage = null }) { Text("確定") }
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

    if (showBatteryExemptionRationale) {
        BatteryExemptionRationaleDialog(
            onConfirm = {
                showBatteryExemptionRationale = false
                // The launcher's callback dispatches the pending start when the system dialog
                // returns, whichever way the user answered.
                batteryExemptionLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            onSkip = {
                showBatteryExemptionRationale = false
                dispatchPendingStart()
            },
        )
    }

    if (aboutOpen) {
        AboutDialog(mapVersion = mapVersion, onDismiss = { aboutOpen = false })
    }

    if (mapUpdateDialogOpen) {
        val paths = remember(appContext) { AppPaths(appContext) }
        MapUpdateDialog(
            installedVersion = mapVersion,
            checker = remember(mapDir) { MapUpdateChecker(MapDataCatalog(paths), MapDataVersionStore(mapDir)) },
            stagingDir = paths.stagingDir,
            updateState = mapUpdateState,
            onStartUpdate = { ids ->
                mapUpdateDialogOpen = false
                launchMapUpdate(ids)
            },
            onDismiss = { mapUpdateDialogOpen = false },
        )
    }

    // An interrupted update means the new data is not (fully) on disk - that must be acknowledged,
    // not left to a timed snackbar. Partial downloads stay in staging, so a retry resumes.
    (mapUpdateState as? MapUpdateState.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = { MapUpdate.reset() },
            title = { Text("地圖更新失敗") },
            text = { Text("${failed.message}\n\n已下載的部分會保留, 重試時將從中斷處續傳.") },
            confirmButton = {
                TextButton(onClick = { MapUpdate.reset() }) { Text("確定") }
            },
        )
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
                    // User asked to look at a specific summit: stop following so the next fix does
                    // not drag the map back onto the current-location marker. The recentre FAB
                    // re-arms follow when tapped.
                    followUser = false
                    recenterOnFix = false
                },
                onDismiss = { searchDialogOpen = false },
            )
        }
    }

    if (locationInfoOpen) {
        LocationInfoDialog(
            fix = currentFix,
            fixStale = currentFixStale,
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
 * Progress banner while a map-data update run is in flight. Mirrors [PeakIndexBanner]'s layout;
 * its own colour so the two never read as one when stacked. The inline "取消" stops the service
 * (partial downloads stay in staging for a later resume).
 */
@Composable
private fun MapUpdateBanner(
    fraction: Float,
    currentName: String,
    phase: InstallPhase,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFD1C4E9), // light purple, distinct from the index banner's blue
        contentColor = Color(0xFF311B92),
        shape = RectangleShape,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val verb = if (phase == InstallPhase.DOWNLOADING) "下載" else "安裝"
                Text(
                    text = "更新圖資: $verb $currentName (${(fraction * 100).toInt()}%)",
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "取消",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clickable(onClick = onCancel)
                            .padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Persistent prompt once an update has fully landed on disk: the map still renders the old files
 * until the user taps here to rebuild it (deliberately user-triggered - a rebuild wipes in-progress
 * route editing / viewing state, so it must not happen at an unpredictable moment). If never
 * tapped, the next launch simply opens on the new data.
 */
@Composable
private fun MapUpdateApplyBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Color(0xFFC8E6C9), // light green: positive, action available
        contentColor = Color(0xFF1B5E20),
        shape = RectangleShape,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = "地圖更新完成, 點此套用新圖資",
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

/**
 * Builds the "start map update" action, mirroring [startDownload]'s POST_NOTIFICATIONS handling
 * (the update runs either way; the permission only makes the progress notification visible). The
 * asset ids are parked in state because the permission launcher's callback cannot take arguments.
 */
@Composable
private fun startMapUpdate(): (List<String>) -> Unit {
    val context = LocalContext.current
    var pendingIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { DownloadService.startUpdate(context, pendingIds) }
    return { ids ->
        pendingIds = ids
        val needsNotifPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotifPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DownloadService.startUpdate(context, ids)
        }
    }
}
