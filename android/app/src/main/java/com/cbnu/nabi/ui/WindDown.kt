package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.cbnu.nabi.R

/**
 * Bedtime wind-down (화면 밝기 감소, patent [0043] BRIGHTNESS_NOTICE): a 나비 heads-up notification,
 * then a dark layer slowly fades in over whatever app is open (e.g. YouTube).
 *
 * The layer is an overlay window (same SYSTEM_ALERT_WINDOW permission as the overlay cat) that never
 * takes touches. Its opacity stops at [MAX_ALPHA]: from Android 12, touches only pass through
 * another app's overlay up to 0.8 opacity, so the app underneath stays usable. Opening NABI again
 * removes it ([clear]).
 */
object WindDown {
    private const val CHANNEL = "wind_down"
    private const val NOTIFICATION_ID = 2201
    private const val MAX_ALPHA = 0.78f
    private const val NOTICE_TO_DIM_MS = 1_000L
    private const val DIM_MS = 4_500L

    private val main = Handler(Looper.getMainLooper())
    private var shade: View? = null
    private var animator: ValueAnimator? = null
    private var pending: Runnable? = null

    /** Notification now, dimming shortly after. [delayMs] waits first (cancelled by [clear]). */
    fun start(context: Context, delayMs: Long = 0) {
        val app = context.applicationContext
        cancelPending()
        val run = Runnable {
            pending = null
            notify(app)
            val dim = Runnable { pending = null; dim(app) }
            pending = dim
            main.postDelayed(dim, NOTICE_TO_DIM_MS)
        }
        pending = run
        main.postDelayed(run, delayMs)
    }

    /** Back in the app: stop and remove the dark layer. */
    fun clear() {
        cancelPending()
        animator?.cancel(); animator = null
        shade?.let { v -> runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } }
        shade = null
    }

    private fun cancelPending() {
        pending?.let(main::removeCallbacks)
        pending = null
    }

    private fun notify(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.bedtime_wind_down), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr(R.string.slowly_dims_screen_when_it_s)
            })
        }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = ProposalKind.BRIGHTNESS_NOTICE.defaultTitle
        val text = tr(R.string.it_s_almost_bedtime_your_screen_2)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, CHANNEL)
        else android.app.Notification.Builder(context).setPriority(android.app.Notification.PRIORITY_HIGH)
        val n = builder
            .setSmallIcon(R.drawable.ic_cat)
            .setContentTitle(tr(R.string.nabi_2, title))
            .setContentText(text)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) } // no notification permission: just dim
    }

    private fun dim(context: Context) {
        if (shade != null || !Settings.canDrawOverlays(context)) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(context).apply { setBackgroundColor(Color.BLACK) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = 0f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { wm.addView(view, lp) }.isFailure) return
        shade = view
        // The window's own alpha (not the view's) is what Android checks for touch pass-through.
        animator = ValueAnimator.ofFloat(0f, MAX_ALPHA).apply {
            duration = DIM_MS
            addUpdateListener {
                if (shade !== view) return@addUpdateListener
                lp.alpha = it.animatedValue as Float
                runCatching { wm.updateViewLayout(view, lp) }
            }
            start()
        }
    }
}
