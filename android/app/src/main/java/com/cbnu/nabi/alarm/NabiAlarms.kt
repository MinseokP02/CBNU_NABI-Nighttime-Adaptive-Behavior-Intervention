package com.cbnu.nabi.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.ui.MainActivity
import com.cbnu.nabi.ui.ProfileStore
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules the 알림 설정 reminders and the 기상 알람 with AlarmManager.
 *
 * Every alarm is one-shot and re-armed for the next day when it fires (and on boot, time change,
 * app update, toggle change, goal change, login). [reschedule] is idempotent: it reads the current
 * settings of the logged-in user and sets or cancels each kind.
 */
object NabiAlarms {

    enum class Kind(val requestCode: Int, val prefKey: String) {
        SLEEP_PREP(100, ProfileStore.N_SLEEP_PREP),
        DIARY(101, ProfileStore.N_DIARY),
        MISSION(102, ProfileStore.N_MISSION),
        WAKE(103, ProfileStore.N_WAKE_ALARM),
    }

    const val ACTION_FIRE = "com.cbnu.nabi.alarm.FIRE"
    const val EXTRA_KIND = "kind"

    const val CHANNEL_REMINDERS = "nabi_reminders"
    const val CHANNEL_WAKE = "nabi_wake_alarm"

    /** 취침 준비 안내: this many minutes before the goal bedtime. */
    const val SLEEP_PREP_LEAD_MIN = 30L
    /** 수면 일지 작성 알림: this long after the goal wake time. */
    const val DIARY_DELAY_MIN = 60L
    /** 활동 및 미션 안내: every day at this time. */
    val MISSION_TIME: LocalTime = LocalTime.of(15, 0)
    const val SNOOZE_MIN = 5L

    fun reschedule(context: Context, from: LocalDateTime = LocalDateTime.now()) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.loggedIn) { cancelAll(ctx); return }
        val store = ProfileStore(ctx, prefs.email)
        val master = store.notif(ProfileStore.N_MASTER)
        for (kind in Kind.values()) {
            val at = if (master && store.notif(kind.prefKey)) nextTime(kind, store, from) else null
            if (at == null) cancel(ctx, kind) else set(ctx, kind, at)
        }
    }

    fun cancelAll(context: Context) {
        Kind.values().forEach { cancel(context.applicationContext, it) }
    }

    /** "5분 더" on the wake alarm screen. */
    fun snooze(context: Context) {
        set(context.applicationContext, Kind.WAKE, LocalDateTime.now().plusMinutes(SNOOZE_MIN))
    }

    /** Next time [kind] should go off, or null if the sleep goal isn't set. */
    fun nextTime(kind: Kind, store: ProfileStore, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val time = when (kind) {
            Kind.SLEEP_PREP -> store.goalBedtime?.let(LocalTime::parse)?.minusMinutes(SLEEP_PREP_LEAD_MIN)
            Kind.DIARY -> store.goalWakeTime?.let(LocalTime::parse)?.plusMinutes(DIARY_DELAY_MIN)
            // Fixed time, but like the others only once the user has set a sleep goal.
            Kind.MISSION -> MISSION_TIME.takeIf { store.goalBedtime != null && store.goalWakeTime != null }
            Kind.WAKE -> store.goalWakeTime?.let(LocalTime::parse)
        } ?: return null
        val today = now.toLocalDate().atTime(time)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Android 12+ needs the user's grant for exact alarms; without it we fall back to inexact ones. */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun set(ctx: Context, kind: Kind, at: LocalDateTime) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val op = firePending(ctx, kind)
        try {
            when {
                kind == Kind.WAKE && canScheduleExact(ctx) -> {
                    val show = PendingIntent.getActivity(
                        ctx, kind.requestCode, Intent(ctx, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), op)
                }
                canScheduleExact(ctx) -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, op)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, op)
            }
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, op)
        }
    }

    private fun cancel(ctx: Context, kind: Kind) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePending(ctx, kind))
    }

    private fun firePending(ctx: Context, kind: Kind): PendingIntent = PendingIntent.getBroadcast(
        ctx, kind.requestCode,
        Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_KIND, kind.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "수면 및 활동 안내", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "취침 준비, 수면 일지, 활동·미션 안내"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "기상 알람", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "목표 기상 시각에 울리는 알람"
                // The alarm screen plays the sound itself, so the notification stays silent.
                setSound(null, null as AudioAttributes?)
                enableVibration(false)
            }
        )
    }
}
