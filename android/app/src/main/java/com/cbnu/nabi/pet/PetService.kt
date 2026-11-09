package com.cbnu.nabi.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.cbnu.nabi.R
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.CatState
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.ui.ChatActivity
import com.cbnu.nabi.ui.MainActivity
import java.util.concurrent.Executors

/** What the cat needs from whoever hosts its windows. */
interface PetHost {
    fun place(dispX: Int, dispY: Int, tx: Int, ty: Int, tw: Int, th: Int)
    fun buzz(kind: Int)
    fun screenSize(): Point
    fun openChat()
}

/**
 * Foreground service that keeps Nabi on screen (ported from Mochi).
 *
 * Two overlay windows:
 *  - a display window that draws the cat and its speech bubble but never takes touches,
 *  - a small transparent touch window kept tight around the cat's body,
 * so taps anywhere else still reach the apps underneath.
 *
 * Server sync: every [POLL_MS] it reads GET /cat/state and lets the cat react
 * (HAPPY after a mission, SLEEP at night, COMFORT when sleep risk is up).
 * Other screens push reactions with [show].
 */
class PetService : Service(), PetHost {

    companion object {
        const val ACTION_STOP = "com.cbnu.nabi.STOP"
        const val ACTION_CAT = "com.cbnu.nabi.CAT"
        const val ACTION_SYNC = "com.cbnu.nabi.SYNC"
        const val EXTRA_ACTION = "cat_action"
        const val EXTRA_LINE = "line"
        private const val CHANNEL = "nabi_pet"
        private const val NOTIF_ID = 7
        private const val POLL_MS = 5 * 60 * 1000L
        const val BUZZ_PURR = 0
        const val BUZZ_SWAT = 1
        const val BUZZ_HISS = 2

        /** True while the overlay is on screen. Lets other screens skip pushing when it is hidden. */
        @Volatile
        var isRunning = false
            private set

        /** Make the cat play [action] and optionally say [line] (e.g. an LLM reply). No-op if hidden. */
        fun show(context: Context, action: CatAction, line: String? = null) {
            if (!isRunning) return
            context.startService(
                Intent(context, PetService::class.java)
                    .setAction(ACTION_CAT)
                    .putExtra(EXTRA_ACTION, action.name)
                    .putExtra(EXTRA_LINE, line)
            )
        }

        /** Re-read /cat/state now (e.g. after login or finishing a mission). */
        fun sync(context: Context) {
            if (!isRunning) return
            context.startService(Intent(context, PetService::class.java).setAction(ACTION_SYNC))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private var cat: CatView? = null
    private var touch: View? = null
    private lateinit var dispLp: WindowManager.LayoutParams
    private lateinit var touchLp: WindowManager.LayoutParams
    private var vibrator: Vibrator? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastServerAction: CatAction? = null
    private val poll = object : Runnable {
        override fun run() {
            syncCatState()
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun findVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = ApiClient(prefs)
        startAsForeground()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        vibrator = findVibrator()

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        addWindows()
        isRunning = true
        main.postDelayed(poll, 1500) // first sync shortly after the cat appears
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CAT -> {
                val a = CatAction.parse(intent.getStringExtra(EXTRA_ACTION))
                cat?.applyAction(a, intent.getStringExtra(EXTRA_LINE))
            }
            ACTION_SYNC -> { lastServerAction = null; syncCatState() }
            else -> cat?.callOver()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cat?.onScreenChanged()
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        cat?.let { runCatching { wm.removeView(it) } }
        touch?.let { runCatching { wm.removeView(it) } }
        cat = null; touch = null
        super.onDestroy()
    }

    // ---- server sync ----
    private fun syncCatState() {
        if (!prefs.loggedIn) return
        io.execute {
            val state: CatState? = try {
                api.catState()
            } catch (e: Exception) {
                null // offline, logged out or bad JSON: the cat just keeps wandering on its own
            }
            if (state != null) main.post { onCatState(state) }
        }
    }

    private fun onCatState(s: CatState) {
        val view = cat ?: return
        prefs.catName = s.name
        view.setMood(s.action)
        if (s.action == lastServerAction) return
        lastServerAction = s.action
        val line = when (s.reason) {
            "MISSION_COMPLETED" -> "미션 해냈다냥! 최고야"
            "SLEEP_RISK" -> "요즘 잠이 힘들지? 옆에 있을게"
            "NIGHT_TIME" -> "이제 잘 시간이다냥… 하암"
            else -> null
        }
        view.applyAction(s.action, line)
    }

    // ---- windows ----
    private fun addWindows() {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val view = CatView(this, this)
        dispLp = WindowManager.LayoutParams(
            view.winW, view.winH, type,
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches that pass through an opaque overlay from another app.
            // Windows at or below 80% opacity are exempt, so taps around the cat still reach the app below.
            if (Build.VERSION.SDK_INT >= 31) alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val t = object : View(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val x = ev.x + touchLp.x - dispLp.x
                val y = ev.y + touchLp.y - dispLp.y
                cat?.handleTouch(ev.actionMasked, x, y)
                return true
            }
        }
        touchLp = WindowManager.LayoutParams(
            1, 1, type,
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        cat = view; touch = t
        wm.addView(view, dispLp)
        wm.addView(t, touchLp)
    }

    // ---- PetHost ----
    override fun place(dispX: Int, dispY: Int, tx: Int, ty: Int, tw: Int, th: Int) {
        val c = cat ?: return
        val tv = touch ?: return
        if (dispLp.x != dispX || dispLp.y != dispY) {
            dispLp.x = dispX; dispLp.y = dispY
            runCatching { wm.updateViewLayout(c, dispLp) }
        }
        if (touchLp.x != tx || touchLp.y != ty || touchLp.width != tw || touchLp.height != th) {
            touchLp.x = tx; touchLp.y = ty; touchLp.width = tw; touchLp.height = th
            runCatching { wm.updateViewLayout(tv, touchLp) }
        }
    }

    override fun buzz(kind: Int) {
        if (!prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (kind) {
            BUZZ_PURR -> if (v.hasAmplitudeControl())
                VibrationEffect.createWaveform(longArrayOf(0, 25, 35, 25), intArrayOf(0, 45, 0, 35), -1)
            else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
            BUZZ_SWAT -> VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            else -> VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 160), -1)
        }
        runCatching { v.vibrate(effect) }
    }

    @Suppress("DEPRECATION")
    override fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            Point(m.widthPixels, m.heightPixels)
        }

    /** Long-press on the cat. Apps holding the overlay permission may start activities from the background. */
    override fun openChat() {
        val target = if (prefs.loggedIn) ChatActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    // ---- foreground notification ----
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "나비", NotificationManager.IMPORTANCE_LOW).apply {
                description = "나비가 화면에 있는 동안 표시돼요"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val chat = PendingIntent.getActivity(
            this, 2, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_cat)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cat)
            .setContentTitle("나비가 화면에 있어요")
            .setContentText("나비를 꾹 누르면 대화할 수 있어요")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "대화하기", chat).build())
            .addAction(Notification.Action.Builder(icon, "숨기기", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIF_ID, n)
    }
}
