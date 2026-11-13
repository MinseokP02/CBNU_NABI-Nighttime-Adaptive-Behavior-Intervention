package com.cbnu.nabi.ui

import android.content.Context
import com.cbnu.nabi.alarm.NabiAlarms

/**
 * 마이페이지 values kept on this device, per logged-in email.
 *
 * The backend has no API yet for sleep goals or notification preferences (spec §8), and the app has
 * no GET /users/me call for the ISI score (API teammate's part). Until those exist:
 * - sleep goals and notification switches are stored here (the alarms themselves: [NabiAlarms]),
 * - the ISI score is the one entered when registering on this device (null otherwise).
 */
class ProfileStore(context: Context, email: String?) {

    private val app = context.applicationContext

    private val sp = context.getSharedPreferences("nabi_profile_" + (email ?: "guest").hashCode(), Context.MODE_PRIVATE)

    val goalBedtime: String? get() = sp.getString(K_GOAL_BED, null)
    val goalWakeTime: String? get() = sp.getString(K_GOAL_WAKE, null)

    /** Returns false if the write failed (FR-07-03 shows that). Re-arms the alarms tied to the goals. */
    fun saveGoals(bed: String, wake: String): Boolean =
        sp.edit().putString(K_GOAL_BED, bed).putString(K_GOAL_WAKE, wake).commit()
            .also { if (it) NabiAlarms.reschedule(app) }

    val isiScore: Int? get() = sp.getInt(K_ISI, -1).takeIf { it in 0..28 }

    fun notif(key: String): Boolean = sp.getBoolean(key, false)
    /** Saves one 알림 설정 switch and re-arms the alarms (see [NabiAlarms]). */
    fun setNotif(key: String, on: Boolean): Boolean =
        sp.edit().putBoolean(key, on).commit().also { if (it) NabiAlarms.reschedule(app) }

    companion object {
        private const val K_GOAL_BED = "goal_bedtime"
        private const val K_GOAL_WAKE = "goal_wake"
        private const val K_ISI = "isi_score"
        const val N_MASTER = "notif_master"
        const val N_SLEEP_PREP = "notif_sleep_prep"
        const val N_DIARY = "notif_diary"
        const val N_MISSION = "notif_mission"
        const val N_WAKE_ALARM = "notif_wake_alarm"

        /** Called after a successful sign-up on this device. */
        fun rememberIsi(context: Context, email: String, score: Int) {
            context.getSharedPreferences("nabi_profile_" + email.hashCode(), Context.MODE_PRIVATE)
                .edit().putInt(K_ISI, score).apply()
        }
    }
}
