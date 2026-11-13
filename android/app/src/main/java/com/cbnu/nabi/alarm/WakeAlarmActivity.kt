package com.cbnu.nabi.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cbnu.nabi.R
import com.cbnu.nabi.ui.Palette
import com.cbnu.nabi.ui.dp
import com.cbnu.nabi.ui.icon
import com.cbnu.nabi.ui.label
import com.cbnu.nabi.ui.rounded
import java.lang.ref.WeakReference
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Full-screen 기상 알람: shows over the lock screen, loops the system alarm sound with vibration
 * until the user taps "일어났어요" (dismiss) or "5분 더" (snooze). Stops by itself after
 * [AUTO_STOP_MS] so a forgotten phone doesn't ring forever.
 */
class WakeAlarmActivity : Activity() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        showOverLockScreen()
        setContentView(buildView())
        startRinging()
        handler.postDelayed({ stop(snooze = false) }, AUTO_STOP_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRinging()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back must not silently dismiss the alarm; use the buttons.
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun buildView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Palette.page)
        setPadding(dp(32), dp(32), dp(32), dp(32))

        addView(icon(R.drawable.ic_cat, Palette.cat, 120))
        addView(label(LocalTime.now().format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)), 44f, title = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
        })
        addView(label("일어날 시간이에요!", 22f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        addView(label("나비가 깨우러 왔어요. 커튼을 열고 햇빛을 받아 보세요.", 14f, Palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(40))
        })
        addView(button("일어났어요", Palette.lavender, Color.WHITE) { stop(snooze = false) }, fullWidth())
        addView(button("${NabiAlarms.SNOOZE_MIN}분 더", Palette.lavenderSoft, Palette.lavenderDeep) { stop(snooze = true) },
            fullWidth().apply { topMargin = dp(12) })
    }

    private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))

    private fun button(text: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        label(text, 17f, textColor, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(fill, 999)
            setOnClickListener { onClick() }
        }

    private fun startRinging() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(this@WakeAlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator = getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.also {
            @Suppress("DEPRECATION")
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun stopRinging() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun stop(snooze: Boolean) {
        stopRinging()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (snooze) NabiAlarms.snooze(this)
        finish()
    }

    /** "알람 끄기" on the alarm notification (when the full-screen screen didn't open). */
    class DismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            current?.get()?.stop(snooze = false)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 7001
        private const val AUTO_STOP_MS = 10 * 60 * 1000L
        private var current: WeakReference<WakeAlarmActivity>? = null
    }
}
