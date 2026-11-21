package com.cbnu.nabi.walk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.cbnu.nabi.R

/**
 * The walk in progress. Lives in memory while the app process is alive; [WalkTrackingService]
 * feeds it, [WalkActivity] reads it (also after the user left and came back to the page).
 */
object WalkSession {
    var active = false
        private set
    var startedAtMs = 0L
        private set
    var targetMinutes: Int? = null
    var plannedRoute: List<LatLon> = emptyList()
    var plannedDistanceM: Double? = null
    var proposalId: String? = null

    val path = mutableListOf<LatLon>()
    var distanceM = 0.0
        private set
    private var last: Location? = null

    /** Called on the main thread after each accepted fix. */
    var listener: (() -> Unit)? = null

    fun start() {
        active = true
        startedAtMs = System.currentTimeMillis()
        path.clear(); distanceM = 0.0; last = null
    }

    fun stop() { active = false }

    val elapsedSec: Long get() = if (startedAtMs == 0L) 0 else (System.currentTimeMillis() - startedAtMs) / 1000

    /** Drops inaccurate fixes, GPS jitter (< 4 m) and jumps faster than a run (> 6 m/s). */
    fun add(loc: Location) {
        if (!active) return
        if (loc.hasAccuracy() && loc.accuracy > 40f) return
        val prev = last
        if (prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            val dt = ((loc.time - prev.time) / 1000.0).coerceAtLeast(1.0)
            if (d < 4.0 || d / dt > 6.0) return
            distanceM += d
        }
        last = loc
        path.add(doubleArrayOf(loc.latitude, loc.longitude))
        listener?.invoke()
    }
}

/**
 * Foreground service (type location) so the walk keeps being recorded with the screen off or the
 * phone in a pocket. Started only from [WalkActivity] after the location permission was granted.
 */
class WalkTrackingService : Service(), LocationListener {

    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateNotification(); main.postDelayed(this, 30_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission") // checked by WalkActivity before starting
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        startAsForeground()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) {
                runCatching { lm.requestLocationUpdates(provider, 3_000L, 3f, this, Looper.getMainLooper()) }
            }
        }
        main.postDelayed(tick, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        // Network fixes only until GPS answers, so the two don't zig-zag the path.
        if (location.provider == LocationManager.NETWORK_PROVIDER && hasGps) return
        if (location.provider == LocationManager.GPS_PROVIDER) hasGps = true
        WalkSession.add(location)
    }

    private var hasGps = false

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        runCatching { lm.removeUpdates(this) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, WalkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val min = WalkSession.elapsedSec / 60
        val km = WalkSession.distanceM / 1000
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("나비와 산책 중")
            .setContentText("%d분 · %.2fkm".format(min, km))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "산책 기록", NotificationManager.IMPORTANCE_LOW).apply {
                description = "산책하는 동안 이동 경로를 기록할 때 표시돼요"
                setShowBadge(false)
            })
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, notification())
    }

    companion object {
        private const val CHANNEL = "nabi_walk"
        private const val NOTIF_ID = 31
        private const val ACTION_STOP = "com.cbnu.nabi.walk.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, WalkTrackingService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, WalkTrackingService::class.java))
    }
}
