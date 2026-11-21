package com.cbnu.nabi.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One night read from Health Connect. [wakeTime] is the final awakening (end of the last sleep
 * stage, or the session end when there are no stages). Stage-derived values are null without stages.
 */
data class HealthNight(
    val bedtime: ZonedDateTime,
    val wakeTime: ZonedDateTime,
    val latencyMin: Int?,
    val wakeAfterSleepOnsetMin: Int?,
    val awakenings: Int?,
    val source: String,
)

/**
 * Health Connect (sleep / exercise sessions). All reads are blocking: call them from a background
 * thread ([com.cbnu.nabi.ui.bg]). Every read returns null when Health Connect or the permission is
 * missing, so callers simply fall back to manual input.
 */
object HealthConnectSource {

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    private const val PROVIDER = "com.google.android.apps.healthdata"

    enum class Status { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

    fun status(context: Context): Status = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> Status.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_UPDATE
        else -> Status.UNAVAILABLE
    }

    private fun client(context: Context): HealthConnectClient? =
        if (status(context) == Status.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    /** Intent for the system permission screen; parse the result with [parseGranted]. */
    fun permissionIntent(context: Context): Intent =
        PermissionController.createRequestPermissionResultContract().createIntent(context, PERMISSIONS)

    fun parseGranted(resultCode: Int, data: Intent?): Set<String> =
        PermissionController.createRequestPermissionResultContract().parseResult(resultCode, data)

    /** Play Store page to install / update the Health Connect app (Android 13 and lower). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))

    fun grantedPermissions(context: Context): Set<String> {
        val c = client(context) ?: return emptySet()
        return runCatching { runBlocking { c.permissionController.getGrantedPermissions() } }.getOrDefault(emptySet())
    }

    fun hasAll(context: Context) = grantedPermissions(context).containsAll(PERMISSIONS)

    /**
     * The night that ended on the morning of [wakeDate]: the longest sleep session between
     * 12:00 the day before and 18:00 that day.
     */
    fun readNight(context: Context, wakeDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): HealthNight? {
        val c = client(context) ?: return null
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in grantedPermissions(context)) return null
        val from = ZonedDateTime.of(wakeDate.minusDays(1), LocalTime.NOON, zone).toInstant()
        val to = ZonedDateTime.of(wakeDate, LocalTime.of(18, 0), zone).toInstant()
        val sessions = runCatching {
            runBlocking { c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records }
        }.getOrNull() ?: return null
        val s = sessions.maxByOrNull { Duration.between(it.startTime, it.endTime) } ?: return null
        return toNight(s, zone)
    }

    /** Minutes of exercise sessions on [day] (local midnight to midnight), or null when not readable. */
    fun exerciseMinutes(context: Context, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val c = client(context) ?: return null
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in grantedPermissions(context)) return null
        val from = day.atStartOfDay(zone).toInstant()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant()
        val sessions = runCatching {
            runBlocking { c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(from, to))).records }
        }.getOrNull() ?: return null
        return sessions.sumOf { overlapMin(it.startTime, it.endTime, from, to) }.toInt()
    }

    private val AWAKE_STAGES = setOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    )
    private val SLEEP_STAGES = setOf(
        SleepSessionRecord.STAGE_TYPE_SLEEPING,
        SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_DEEP,
        SleepSessionRecord.STAGE_TYPE_REM,
    )

    /** Session → diary values. Latency = start → first sleep stage; WASO = awake stages between first and last sleep. */
    internal fun toNight(s: SleepSessionRecord, zone: ZoneId): HealthNight {
        val stages = s.stages.sortedBy { it.startTime }
        val asleep = stages.filter { it.stage in SLEEP_STAGES }
        val source = s.metadata.dataOrigin.packageName
        if (asleep.isEmpty()) {
            return HealthNight(s.startTime.atZone(zone), s.endTime.atZone(zone), null, null, null, source)
        }
        val firstSleep = asleep.first().startTime
        val lastSleep = asleep.maxOf { it.endTime }
        val awakeInside = stages.filter { it.stage in AWAKE_STAGES && it.endTime > firstSleep && it.startTime < lastSleep }
        return HealthNight(
            bedtime = s.startTime.atZone(zone),
            wakeTime = lastSleep.atZone(zone),
            latencyMin = Duration.between(s.startTime, firstSleep).toMinutes().toInt().coerceAtLeast(0),
            wakeAfterSleepOnsetMin = awakeInside.sumOf { overlapMin(it.startTime, it.endTime, firstSleep, lastSleep) }.toInt(),
            awakenings = awakeInside.size,
            source = source,
        )
    }

    private fun overlapMin(a0: Instant, a1: Instant, b0: Instant, b1: Instant): Long {
        val start = maxOf(a0, b0)
        val end = minOf(a1, b1)
        return if (end > start) Duration.between(start, end).toMinutes() else 0
    }
}

/**
 * UsageStats ("사용 정보 접근"): minutes the screen was on before falling asleep, sent to the server
 * as `screen_before_bed_min` (rule EXCESSIVE_PRE_BED_SCREEN fires at a median of 60+ minutes).
 */
object ScreenUsageSource {

    /** Minutes before sleep onset that count as "before bed". */
    const val WINDOW_MIN = 120L

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            // Some devices open this app's page directly with the package URI; others ignore it.
            data = Uri.parse("package:${context.packageName}")
        }

    /** Screen-on minutes in the [WINDOW_MIN] minutes before [sleepOnset], or null without access / on Android 8.x. */
    fun minutesBefore(context: Context, sleepOnset: ZonedDateTime): Int? {
        if (!hasAccess(context) || Build.VERSION.SDK_INT < 28) return null
        val onset = sleepOnset.toInstant().toEpochMilli()
        val start = onset - WINDOW_MIN * 60_000
        val end = minOf(onset, System.currentTimeMillis()) // never count a screen that is "still on" into the future
        if (end <= start) return 0
        return screenOnMillis(context, start, end)?.let { (it / 60_000).toInt() }
    }

    /** Sum of SCREEN_INTERACTIVE → SCREEN_NON_INTERACTIVE intervals clipped to [start, end). */
    private fun screenOnMillis(context: Context, start: Long, end: Long): Long? {
        if (Build.VERSION.SDK_INT < 28) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        // Look back a few hours so the screen state at [start] is known.
        val events = usm.queryEvents(start - 6 * 3_600_000L, end) ?: return null
        val e = UsageEvents.Event()
        var onSince: Long? = null
        var total = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (onSince == null) onSince = e.timeStamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> onSince?.let { s ->
                    total += clip(s, e.timeStamp, start, end); onSince = null
                }
            }
        }
        onSince?.let { total += clip(it, end, start, end) } // still on at the end of the window
        return total
    }

    private fun clip(a: Long, b: Long, start: Long, end: Long) = (minOf(b, end) - maxOf(a, start)).coerceAtLeast(0)
}
