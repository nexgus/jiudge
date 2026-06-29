package io.github.nexgus.jiudge.core.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.nexgus.jiudge.MainActivity
import io.github.nexgus.jiudge.feature.recording.defaultRecordingName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that owns the GPS subscription for a recording session, so the track keeps
 * being written with the screen off and the process in Doze. The `location` foreground type lets the
 * service stay alive while in the background (the only foreground type the platform accepts for a
 * service that needs background location); the [PowerManager.PARTIAL_WAKE_LOCK] held for the
 * lifetime of the session prevents Doze from suspending the CPU between fixes, which would
 * otherwise let `LocationListener` callbacks coalesce or drop.
 *
 * Wiring: the activity sends [ACTION_START_NEW] / [ACTION_START_CONTINUATION] to begin a session,
 * the service drives [RecordingController] (start, fix-per-tick, stop), and [ACTION_STOP] hands the
 * stopped session over to [RecordingController.pendingSession] so the activity can open its save /
 * discard dialog. The service then `stopSelf()`s; the staged session survives in the controller
 * until the user commits or drops it.
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
    private var subscribed = false
    private var startedForeground = false

    private val locationManager: LocationManager
        get() = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = handleLocation(location)

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Required by interface; unused.")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) = Unit
        }

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
            ACTION_STOP -> {
                handleStop()
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
        // Defensive: subscribe()/handleStop() already release these on every normal exit, but
        // onDestroy can fire without a STOP intent (system-initiated teardown), and leaking a
        // wake lock silently drains the battery.
        unsubscribe()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
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
                subscribe()
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
        val displayName = source.nameWithoutExtension.removePrefix(".recording-").ifEmpty { source.name }
        beginForeground(displayName)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RecordingController.handleStartContinuation(source)
                }
                subscribe()
            } catch (e: Exception) {
                stopSelfCleanly()
            }
        }
    }

    private fun beginForeground(displayName: String) {
        startAsForeground(buildNotification(displayName))
        startedForeground = true
        acquireWakeLock()
    }

    private fun handleStop() {
        unsubscribe()
        RecordingController.handleStop()
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

    @SuppressLint("MissingPermission")
    private fun subscribe() {
        if (subscribed) return
        // Belt-and-suspenders: ensureBackgroundLocation() already gated the start, but a coarse-only
        // grant would still throw SecurityException on GPS_PROVIDER. Require fine.
        val fine =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fine) {
            stopSelfCleanly()
            return
        }
        // GPS only: the recorded track is meant to be a real movement trace. The network provider
        // gives a coarse, often-stale location that would poison the polyline with non-positions.
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            MIN_TIME_MS,
            MIN_DISTANCE_M,
            listener,
            Looper.getMainLooper(),
        )
        subscribed = true
    }

    private fun unsubscribe() {
        if (!subscribed) return
        locationManager.removeUpdates(listener)
        subscribed = false
    }

    private fun handleLocation(location: Location) {
        val err = RecordingController.handleFix(location.latitude, location.longitude, location.time)
        if (err != null) {
            // Disk failure mid-session: stop cleanly rather than spinning forever appending to a
            // file we cannot write. The session is left as pending so the activity surfaces it.
            handleStop()
        }
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

    private fun buildNotification(displayName: String): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("錄製軌跡中")
            .setContentText(displayName)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording_session_v2"
        private const val LEGACY_CHANNEL_ID = "recording_session"
        private const val NOTIF_ID = 2001
        private const val WAKELOCK_TAG = "Jiudge:RecordingService"
        private const val ACTION_START_NEW = "io.github.nexgus.jiudge.action.START_NEW_RECORDING"
        private const val ACTION_START_CONTINUATION = "io.github.nexgus.jiudge.action.START_CONTINUATION_RECORDING"
        private const val ACTION_STOP = "io.github.nexgus.jiudge.action.STOP_RECORDING"
        private const val EXTRA_SOURCE_PATH = "source_path"

        // 1 Hz GPS - matches the rate the foreground LocationProvider already uses for the marker.
        private const val MIN_TIME_MS = 1_000L
        private const val MIN_DISTANCE_M = 0f

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

        /** Requests the service to stop the current session and stage it for the save dialog. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
