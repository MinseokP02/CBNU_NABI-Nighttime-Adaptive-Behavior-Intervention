package com.cbnu.nabi.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** AlarmManager forgets everything on reboot and gets confused by clock changes: re-arm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> NabiAlarms.reschedule(context)
        }
    }
}
