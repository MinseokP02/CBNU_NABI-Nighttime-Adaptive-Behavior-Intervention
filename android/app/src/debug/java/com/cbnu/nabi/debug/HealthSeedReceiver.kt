package com.cbnu.nabi.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord.Stage
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DEBUG ONLY. Writes last night's sleep session (with stages) and yesterday's 40-minute walk into
 * Health Connect so the auto-filled diary can be tested on an emulator:
 *
 *     adb shell pm grant com.cbnu.nabi android.permission.health.WRITE_SLEEP
 *     adb shell pm grant com.cbnu.nabi android.permission.health.WRITE_EXERCISE
 *     adb shell am broadcast -n com.cbnu.nabi/.debug.HealthSeedReceiver
 *
 * Night: in bed 23:40, asleep 00:05 (latency 25), awake 03:10–03:30 (WASO 20), last sleep ends 07:05.
 */
class HealthSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                runBlocking { seed(context) }
                Log.i(TAG, "seeded Health Connect sample night + exercise")
            } catch (t: Throwable) {
                Log.e(TAG, "seed failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private suspend fun seed(context: Context) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val night = today.minusDays(1)
        fun at(d: LocalDate, h: Int, m: Int) = ZonedDateTime.of(d, LocalTime.of(h, m), zone)
        val bed = at(night, 23, 40)
        val onset = at(today, 0, 5)
        val awake0 = at(today, 3, 10)
        val awake1 = at(today, 3, 30)
        val lastSleep = at(today, 7, 5)
        val out = at(today, 7, 15)
        val offset = bed.offset
        val sleep = SleepSessionRecord(
            startTime = bed.toInstant(), startZoneOffset = offset,
            endTime = out.toInstant(), endZoneOffset = out.offset,
            title = "NABI test night",
            stages = listOf(
                Stage(bed.toInstant(), onset.toInstant(), SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED),
                Stage(onset.toInstant(), awake0.toInstant(), SleepSessionRecord.STAGE_TYPE_LIGHT),
                Stage(awake0.toInstant(), awake1.toInstant(), SleepSessionRecord.STAGE_TYPE_AWAKE),
                Stage(awake1.toInstant(), lastSleep.toInstant(), SleepSessionRecord.STAGE_TYPE_DEEP),
                Stage(lastSleep.toInstant(), out.toInstant(), SleepSessionRecord.STAGE_TYPE_AWAKE),
            ),
        )
        val walkStart = at(night, 18, 0)
        val walk = ExerciseSessionRecord(
            startTime = walkStart.toInstant(), startZoneOffset = walkStart.offset,
            endTime = walkStart.plusMinutes(40).toInstant(), endZoneOffset = walkStart.offset,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
            title = "NABI test walk",
        )
        HealthConnectClient.getOrCreate(context).insertRecords(listOf(sleep, walk))
    }

    companion object { private const val TAG = "NabiHealthSeed" }
}
