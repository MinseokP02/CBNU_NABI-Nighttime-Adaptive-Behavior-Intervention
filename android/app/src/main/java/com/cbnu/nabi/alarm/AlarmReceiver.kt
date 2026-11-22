package com.cbnu.nabi.alarm


import com.cbnu.nabi.tr
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import com.cbnu.nabi.R
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.ui.MainActivity
import com.cbnu.nabi.ui.SleepDiaryStore
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.concurrent.thread

/** Fires one [NabiAlarms.Kind], shows its notification (or the alarm screen) and re-arms it for tomorrow. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NabiAlarms.ACTION_FIRE) return
        val kind = intent.getStringExtra(NabiAlarms.EXTRA_KIND)
            ?.let { name -> NabiAlarms.Kind.values().firstOrNull { it.name == name } } ?: return
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        // Re-arm first so a crash below never stops tomorrow's alarm. Starting a minute ahead keeps
        // an alarm that fired a hair early from being set again for the same moment.
        NabiAlarms.reschedule(ctx, from = LocalDateTime.now().plusMinutes(1))
        if (!prefs.loggedIn) return
        NabiAlarms.ensureChannels(ctx)

        when (kind) {
            NabiAlarms.Kind.SLEEP_PREP -> notify(
                ctx, kind, tr(R.string.almost_bedtime),
                tr(R.string.min_until_your_target_bedtime_dim, NabiAlarms.SLEEP_PREP_LEAD_MIN),
            )
            NabiAlarms.Kind.DIARY -> {
                if (SleepDiaryStore(ctx, prefs.email).get(LocalDate.now()) == null) {
                    notify(ctx, kind, tr(R.string.how_did_you_sleep_last_night), tr(R.string.log_your_sleep_diary_before_you), MainActivity.TAB_SLEEP)
                }
            }
            NabiAlarms.Kind.MISSION -> {
                val pending = goAsync()
                thread {
                    try {
                        val open = runCatching { ApiClient(prefs).activeMissions().filter { it.status == "ASSIGNED" } }
                        val missions = open.getOrNull()
                        when {
                            missions == null -> notify(ctx, kind, tr(R.string.shall_we_move_little), tr(R.string.light_stretch_or_walk_helps_you))
                            missions.isNotEmpty() -> notify(
                                ctx, kind, tr(R.string.you_still_have_missions_today),
                                if (missions.size == 1) tr(R.string.how_about_mission, missions[0].title)
                                else tr(R.string.and_more_missions_are_waiting, missions[0].title, missions.size - 1),
                            )
                            // All missions done: nothing to nag about.
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            NabiAlarms.Kind.WAKE -> showWakeAlarm(ctx)
        }
    }

    private fun contentIntent(ctx: Context, kind: NabiAlarms.Kind, tab: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab != null) i.putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
        return PendingIntent.getActivity(ctx, kind.requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(ctx: Context, kind: NabiAlarms.Kind, title: String, text: String, tab: String? = null) {
        val n = Notification.Builder(ctx, NabiAlarms.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_cat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(ctx, kind, tab))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(kind.requestCode, n) }
    }

    private fun showWakeAlarm(ctx: Context) {
        val screen = Intent(ctx, WakeAlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val full = PendingIntent.getActivity(ctx, NabiAlarms.Kind.WAKE.requestCode, screen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = PendingIntent.getBroadcast(ctx, NabiAlarms.Kind.WAKE.requestCode,
            Intent(ctx, WakeAlarmActivity.DismissReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, NabiAlarms.CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_cat)
            .setContentTitle(tr(R.string.time_to_wake_up))
            .setContentText(tr(R.string.nabi_came_to_wake_you_up))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_cat), tr(R.string.stop_alarm), dismiss).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(WakeAlarmActivity.NOTIFICATION_ID, n) }
        // With the overlay permission (used for the floating cat) the app may open the screen
        // directly, which also covers phones that deny full-screen intents (Android 14+).
        if (Settings.canDrawOverlays(ctx)) runCatching { ctx.startActivity(screen) }
    }
}
