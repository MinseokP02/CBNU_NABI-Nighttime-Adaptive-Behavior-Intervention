package com.cbnu.nabi.alarm

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
                ctx, kind, "곧 잘 시간이에요 🌙",
                "목표 취침 시각까지 ${NabiAlarms.SLEEP_PREP_LEAD_MIN}분 남았어요. 화면을 어둡게 하고 잠들 준비를 해요.",
            )
            NabiAlarms.Kind.DIARY -> {
                if (SleepDiaryStore(ctx, prefs.email).get(LocalDate.now()) == null) {
                    notify(ctx, kind, "어젯밤 잠은 어땠나요?", "잊기 전에 수면 일지를 기록해 주세요. 나비가 기다리고 있어요.", MainActivity.TAB_SLEEP)
                }
            }
            NabiAlarms.Kind.MISSION -> {
                val pending = goAsync()
                thread {
                    try {
                        val open = runCatching { ApiClient(prefs).activeMissions().filter { it.status == "ASSIGNED" } }
                        val missions = open.getOrNull()
                        when {
                            missions == null -> notify(ctx, kind, "잠깐 몸을 움직여 볼까요?", "가벼운 스트레칭이나 산책은 밤잠에 도움이 돼요.")
                            missions.isNotEmpty() -> notify(
                                ctx, kind, "오늘의 미션이 남아 있어요",
                                if (missions.size == 1) "‘${missions[0].title}’ 미션을 해 볼까요?"
                                else "‘${missions[0].title}’ 외 ${missions.size - 1}개의 미션이 기다리고 있어요.",
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
            .setContentTitle("일어날 시간이에요 ☀️")
            .setContentText("나비가 깨우러 왔어요.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_cat), "알람 끄기", dismiss).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(WakeAlarmActivity.NOTIFICATION_ID, n) }
        // With the overlay permission (used for the floating cat) the app may open the screen
        // directly, which also covers phones that deny full-screen intents (Android 14+).
        if (Settings.canDrawOverlays(ctx)) runCatching { ctx.startActivity(screen) }
    }
}
