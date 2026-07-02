package io.github.nexgus.jiudge.core.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.nexgus.jiudge.MainActivity
import io.github.nexgus.jiudge.core.location.GpsSource
import io.github.nexgus.jiudge.feature.recording.defaultRecordingName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that owns the Recording lease on [GpsSource] for a session, so the track keeps
 * being written with the screen off and the process in Doze. The `location` foreground type lets the
 * service stay alive while in the background (the only foreground type the platform accepts for a
 * service that needs background location); the [PowerManager.PARTIAL_WAKE_LOCK] held for the
 * lifetime of the session prevents Doze from suspending the CPU between fixes, which would
 * otherwise let fix updates coalesce or drop.
 *
 * Wiring follows the three-layer UI state machine (錄製中 -> 已停止 -> 存檔對話框):
 * - [ACTION_START_NEW] / [ACTION_START_CONTINUATION] begin a session and enter 錄製中.
 * - [ACTION_PAUSE] (sent by the bottom bar's "停止", or by [MainActivity] after the notification's
 *   "停止" action brought it to the foreground - see [buildNotification]) moves the session to
 *   已停止 (`Recorder.State.PAUSED`). The service keeps running in the foreground: the GPS
 *   subscription and wake lock are held exactly as they were while recording (see [GpsSource] lease
 *   and [wakeLock] below) - only [RecordingController.handlePause] gates fixes from being appended.
 *   This is a deliberate simplification over tearing down and re-acquiring the lease on every pause.
 * - [ACTION_RESUME] moves back to 錄製中, appending to the same staging file.
 * - [ACTION_FINISH] is sent by the UI once the save or discard dialog has completed (i.e. after
 *   [RecordingController.finalize] / [RecordingController.discard] has already run): it releases the
 *   GPS lease and wake lock, ends the session in [RecordingController], removes the notification, and
 *   stops the service.
 *
 * No WifiLock: recording does not touch the network. Background location permission
 * ([Manifest.permission.ACCESS_BACKGROUND_LOCATION]) must be granted before starting; the activity
 * surfaces the rationale and routes the user to the system settings page if it is missing - if it
 * is somehow not granted by the time [onStartCommand] fires (race or user revoked mid-flight), the
 * service shuts down immediately rather than running without a GPS subscription.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var gpsOwnership: GpsSource.Ownership? = null
    private var fixJob: Job? = null
    private var staleJob: Job? = null
    private var startedForeground = false
    private var displayName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START_NEW -> startNewSession()
            ACTION_START_CONTINUATION -> startContinuationSession(intent)
            ACTION_PAUSE -> {
                pauseSession()
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeSession()
                return START_STICKY
            }
            ACTION_FINISH -> {
                finishSession()
                return START_NOT_STICKY
            }
            else -> {
                // Restart from the system with no recoverable state - v1 has no resume protocol, so
                // just shut down rather than spinning up a half-state foreground notification.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Defensive: finishSession() already releases these on every normal exit, but onDestroy can
        // fire without a FINISH intent (system-initiated teardown), and leaking a wake lock or a
        // GpsSource lease silently harms other consumers of the singleton.
        releaseGps()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app from Recents while a recording was live (recording or paused). A
        // foreground service would otherwise keep running (and its notification stay pinned) after
        // the activity is gone, which reads as "why is the notification still there when I closed the
        // app". Spec H: terminate the recording outright, remove the notification, and stop the
        // service - but the staging file itself is NOT deleted here; it is left on disk for the next
        // launch's trackStore.cleanupStaleRecordings() to sweep, mirroring the existing (already
        // correct) staging cleanup contract rather than reaching into TrackStore from the service.
        releaseGps()
        RecordingController.handleEnd()
        stopSelfCleanly()
        super.onTaskRemoved(rootIntent)
    }

    private fun startNewSession() {
        if (RecordingController.active) {
            // Activity may resend START on rotation / re-entry; do not stack sessions.
            return
        }
        if (!ensureBackgroundLocation()) return
        val now = System.currentTimeMillis()
        val defaultName = defaultRecordingName(now)
        beginForeground(defaultName)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RecordingController.handleStartNew(startEpochMs = now, defaultSaveName = defaultName)
                }
                acquireGps()
            } catch (e: Exception) {
                stopSelfCleanly()
            }
        }
    }

    private fun startContinuationSession(intent: Intent) {
        if (RecordingController.active) {
            return
        }
        val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
        if (path.isNullOrEmpty()) {
            stopSelf()
            return
        }
        if (!ensureBackgroundLocation()) return
        val source = File(path)
        val name = source.nameWithoutExtension.removePrefix(".recording-").ifEmpty { source.name }
        beginForeground(name)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RecordingController.handleStartContinuation(source)
                }
                acquireGps()
            } catch (e: Exception) {
                stopSelfCleanly()
            }
        }
    }

    private fun beginForeground(name: String) {
        displayName = name
        startAsForeground(buildNotification(recording = true))
        startedForeground = true
        acquireWakeLock()
    }

    /**
     * Spec C: pausing keeps the GPS subscription, wake lock, and foreground status untouched - only
     * [RecordingController.handlePause] flips the gate that [RecordingController.handleFix] checks.
     * Bringing the app to the foreground is deliberately NOT done here: a service calling
     * startActivity off a notification action is the "notification trampoline" pattern Android 12+
     * blocks outright (Android 10-11's background-activity-launch restrictions block it too). The
     * notification's "停止" action is instead an Activity PendingIntent into [MainActivity], which
     * reaches the foreground first and forwards [ACTION_PAUSE] back to us - see [buildNotification].
     */
    private fun pauseSession() {
        RecordingController.handlePause()
        updateNotification(recording = false)
    }

    private fun resumeSession() {
        RecordingController.handleResume()
        updateNotification(recording = true)
    }

    /** Spec I: an append failure is treated exactly like the user pressing 停止. */
    private fun pauseSessionOnFixError() = pauseSession()

    private fun finishSession() {
        releaseGps()
        RecordingController.handleEnd()
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        releaseWakeLock()
        if (startedForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        stopSelf()
    }

    private fun acquireGps() {
        if (gpsOwnership != null) return
        // Belt-and-suspenders: ensureBackgroundLocation() already gated the start, but a coarse-only
        // grant would still make Mode.Recording end up with an empty subscription and no fixes.
        val fine =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fine) {
            stopSelfCleanly()
            return
        }
        // Anything already sitting in GpsSource.fix at this instant came from a prior owner (the
        // foreground marker or a seedLastKnown from an earlier lease). Treat it as stale so the
        // recorded polyline only extends once a genuinely new fix arrives.
        val staleFixTimeMs = GpsSource.fix.value?.timeMs ?: Long.MIN_VALUE
        gpsOwnership = GpsSource.acquire(this, GpsSource.Mode.Recording)
        fixJob =
            scope.launch {
                GpsSource.fix.collect { fix ->
                    if (fix != null && fix.timeMs > staleFixTimeMs) {
                        val err =
                            RecordingController.handleFix(
                                fix.latitude,
                                fix.longitude,
                                fix.timeMs,
                                fix.accuracyMeters,
                                fix.speedMps,
                            )
                        if (err != null) pauseSessionOnFixError()
                    }
                }
            }
        staleJob =
            scope.launch {
                // A stale stream means the gate's pending fix will never get its corroborating
                // successor - drop it (docs/gating.md rule 2).
                GpsSource.fixStale.collect { stale ->
                    if (stale) RecordingController.handleFixStale()
                }
            }
    }

    private fun releaseGps() {
        fixJob?.cancel()
        fixJob = null
        staleJob?.cancel()
        staleJob = null
        gpsOwnership?.release()
        gpsOwnership = null
    }

    private fun ensureBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) stopSelf()
        return granted
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(recording: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(recording))
    }

    private fun acquireWakeLock() {
        val wl =
            wakeLock ?: (applicationContext.getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
        if (!wl.isHeld) wl.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Channel attributes are frozen at first creation; deleting the old LOW channel forces the
        // new DEFAULT-importance + silent + public-lockscreen channel to take effect on devices
        // that already had the previous version installed. LOW notifications were grouped into the
        // silent section and hidden on the Pixel lock screen.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "軌跡錄製",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "錄製軌跡時顯示進度"
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = null
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        nm.createNotificationChannel(channel)
    }

    /**
     * Spec C: the recording notification keeps its "停止" action (pauses and brings the app to the
     * foreground); the paused notification drops every action button (no "放棄", no "繼續") and its
     * text switches to reflect the stopped state - only tapping the notification body (the existing
     * content intent) brings the app back, where the user picks 儲存 / 放棄 / 繼續錄製 from the 已停止
     * bottom bar.
     *
     * The "停止" action is an Activity PendingIntent into [MainActivity] (with
     * [MainActivity.ACTION_PAUSE_RECORDING]), not a service PendingIntent: the system launching the
     * activity straight from the notification is exempt from background-activity-launch limits, so
     * the app reliably reaches the foreground, and the activity forwards the pause to this service.
     * [Intent.FLAG_ACTIVITY_SINGLE_TOP] routes the action through onNewIntent on the live single
     * instance instead of recreating it.
     */
    private fun buildNotification(recording: Boolean): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setContentText(displayName)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openApp)
        if (recording) {
            val pause =
                PendingIntent.getActivity(
                    this,
                    1,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_PAUSE_RECORDING)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            builder.setContentTitle("錄製軌跡中").addAction(0, "停止", pause)
        } else {
            builder.setContentTitle("軌跡錄製已停止")
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "recording_session_v2"
        private const val LEGACY_CHANNEL_ID = "recording_session"
        private const val NOTIF_ID = 2001
        private const val WAKELOCK_TAG = "Jiudge:RecordingService"
        private const val ACTION_START_NEW = "io.github.nexgus.jiudge.action.START_NEW_RECORDING"
        private const val ACTION_START_CONTINUATION = "io.github.nexgus.jiudge.action.START_CONTINUATION_RECORDING"
        private const val ACTION_PAUSE = "io.github.nexgus.jiudge.action.PAUSE_RECORDING"
        private const val ACTION_RESUME = "io.github.nexgus.jiudge.action.RESUME_RECORDING"
        private const val ACTION_FINISH = "io.github.nexgus.jiudge.action.FINISH_RECORDING"
        private const val EXTRA_SOURCE_PATH = "source_path"

        /** Starts a brand-new recording. Call from the foreground (activity). */
        fun startNew(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_START_NEW),
            )
        }

        /** Continues an existing track at [source]. Call from the foreground (activity). */
        fun startContinuation(
            context: Context,
            source: File,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START_CONTINUATION)
                    .putExtra(EXTRA_SOURCE_PATH, source.absolutePath),
            )
        }

        /** Pauses the current session (錄製中 -> 已停止). The session and its staging file stay alive. */
        fun pause(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE),
            )
        }

        /** Resumes a paused session (已停止 -> 錄製中), appending to the same staging file. */
        fun resume(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_RESUME),
            )
        }

        /**
         * Ends the session after the UI has already finalised (saved) or discarded it: releases the
         * GPS lease and wake lock, removes the notification, and stops the service.
         */
        fun finish(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_FINISH),
            )
        }
    }
}
