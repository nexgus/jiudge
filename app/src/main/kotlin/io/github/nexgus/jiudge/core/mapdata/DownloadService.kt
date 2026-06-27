package io.github.nexgus.jiudge.core.mapdata

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.nexgus.jiudge.MainActivity
import io.github.nexgus.jiudge.core.storage.AppPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that downloads and installs every missing map-data asset via
 * [MapDataInstaller]. The `dataSync` foreground type keeps the OS from killing the process while
 * the ~400 MB download is in flight; for the transfer to actually keep moving with the screen off
 * the service also holds a [PowerManager.PARTIAL_WAKE_LOCK] (so Doze does not suspend the CPU
 * mid-`read()`) and a high-perf [WifiManager.WifiLock] (so WiFi does not drop into PSM and starve
 * throughput). Progress is published to [MapDataDownload] for the UI and mirrored to an ongoing
 * notification with a Cancel action; the service stops itself once the run finishes, fails, or is
 * cancelled, and both locks are released along every exit path.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            if (job?.isActive != true) finish()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        acquireLocks()
        startAsForeground(buildNotification("準備下載...", progress = 0, indeterminate = true))
        job = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        // Defensive: finish() already drops the locks on every normal exit, but onDestroy can fire
        // without finish() (e.g. system-initiated teardown), and leaking a wake lock silently
        // drains the battery.
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        val paths = AppPaths(applicationContext)
        val installer = MapDataInstaller(paths)
        val missing = MapDataCatalog(paths).missing(includeOptional = true)
        if (missing.isEmpty()) {
            MapDataDownload.set(DownloadState.Done)
            finish()
            return
        }
        val totalBytes = missing.sumOf { it.approxSizeBytes }.coerceAtLeast(1L)
        var doneBytes = 0L
        var lastFraction = -1f
        var lastPhase: InstallPhase? = null
        var lastIndex = -1
        try {
            missing.forEachIndexed { index, asset ->
                installer.install(asset) { p ->
                    val overall =
                        ((doneBytes + assetFraction(p) * asset.approxSizeBytes) / totalBytes)
                            .coerceIn(0f, 1f)
                    // Throttle: emit on a new asset/phase, or a >=1% move, to avoid spamming the
                    // StateFlow and NotificationManager (the callback fires every 64 KB).
                    if (index != lastIndex || p.phase != lastPhase || overall - lastFraction >= 0.01f) {
                        lastIndex = index
                        lastPhase = p.phase
                        lastFraction = overall
                        publish(asset, p.phase, overall, index, missing.size)
                    }
                }
                doneBytes += asset.approxSizeBytes
            }
            MapDataDownload.set(DownloadState.Done)
        } catch (e: CancellationException) {
            MapDataDownload.set(DownloadState.Idle)
            throw e
        } catch (e: Exception) {
            MapDataDownload.set(DownloadState.Failed(e.message ?: e.javaClass.simpleName))
        } finally {
            finish()
        }
    }

    /** This asset's own 0..1 completion (download bytes; extracting/publishing counts as done). */
    private fun assetFraction(p: InstallProgress): Float =
        when {
            p.phase != InstallPhase.DOWNLOADING -> 1f
            p.totalBytes > 0 -> p.bytes.toFloat() / p.totalBytes
            else -> 0f
        }

    private fun publish(
        asset: MapDataAsset,
        phase: InstallPhase,
        fraction: Float,
        index: Int,
        total: Int,
    ) {
        MapDataDownload.set(DownloadState.Running(fraction, asset.displayName, phase, index, total))
        val pct = (fraction * 100).toInt()
        val verb = if (phase == InstallPhase.DOWNLOADING) "下載" else "安裝"
        notify(buildNotification("$verb ${asset.displayName} ($pct%)", pct, indeterminate = false))
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        }
    }

    private fun finish() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        val wl =
            wakeLock ?: (applicationContext.getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
        if (!wl.isHeld) wl.acquire()
        // WIFI_MODE_FULL_HIGH_PERF was deprecated in API 29 because the platform stopped applying
        // power-saving packet filters to high-perf clients automatically. We still target API 26+,
        // where the constant is the only way to opt out of WiFi PSM during a long download.
        @Suppress("DEPRECATION")
        val wifi =
            wifiLock ?: (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKELOCK_TAG)
                .also {
                    it.setReferenceCounted(false)
                    wifiLock = it
                }
        if (!wifi.isHeld) wifi.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "地圖資料下載",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "下載離線地圖資料時顯示進度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("下載地圖資料")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(openApp)
            .addAction(0, "取消", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "map_data_download"
        private const val NOTIF_ID = 1001
        private const val ACTION_CANCEL = "io.github.nexgus.jiudge.action.CANCEL_DOWNLOAD"
        private const val WAKELOCK_TAG = "Jiudge:DownloadService"

        /** Starts (or no-ops if already running) the foreground download. Call from the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
