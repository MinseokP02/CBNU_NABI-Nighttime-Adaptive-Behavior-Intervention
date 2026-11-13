package com.cbnu.nabi.ui

import android.content.Context
import com.cbnu.nabi.data.ApiClient
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * One diary entry (FR-03). Times are "HH:mm". [date] is the morning the user woke up
 * (Figma: "11월 12일 수면 기록"); the habits are for the day before it.
 *
 * Habits: null = not recorded. [caffeine] means caffeine after the cutoff (오후 2시 이후), the same
 * meaning as the server's caffeine_after_cutoff. [napMin] / [exerciseMin] are minutes when known.
 */
data class DiaryEntry(
    val date: LocalDate,
    val bedtime: String,
    val wakeTime: String,
    val awakenings: Int?,
    val awakeMinutes: Int?,
    val latencyMin: Int,
    val satisfaction: Int?,       // 1..5 = 매우 나쁨..매우 좋음 (server sleep_quality)
    val nap: Boolean?,
    val napMin: Int?,
    val caffeine: Boolean?,
    val exercise: Boolean?,
    val exerciseMin: Int?,
    val serverSaved: Boolean,
    /** Habits were saved with POST /behavior-records. */
    val behaviorSaved: Boolean = false,
    /** Built from the server's records (no local copy on this device). */
    val fromServer: Boolean = false,
) {
    /** Minutes in bed, bedtime → wake time (crossing midnight). Out of bed = wake time in this form. */
    val timeInBedMin: Int
        get() {
            val bed = java.time.LocalTime.parse(bedtime).toSecondOfDay() / 60
            val wake = java.time.LocalTime.parse(wakeTime).toSecondOfDay() / 60
            return if (wake > bed) wake - bed else wake + 24 * 60 - bed
        }

    /** Same formulas as the backend (schemas/sleep.py): TST = TIB − SOL − WASO, SE = TST / TIB. */
    val totalSleepMin: Int get() = (timeInBedMin - latencyMin - (awakeMinutes ?: 0)).coerceAtLeast(0)
    val efficiencyPct: Int get() = if (timeInBedMin > 0) Math.round(totalSleepMin * 100f / timeInBedMin) else 0
}

/** "5시간 55분" */
fun formatDuration(min: Int): String = if (min / 60 == 0) "${min % 60}분" else "${min / 60}시간 ${min % 60}분"

/**
 * Diary entries per logged-in email: the copies saved on THIS device plus a cache of the server's
 * records (GET /sleep-records + GET /behavior-records, refreshed by [syncFromServer]).
 *
 * The local copy wins for a date because it also holds what the server has no field for
 * (night-time awakening count). Server-only dates (another device, reinstall) come from the cache.
 */
class SleepDiaryStore(context: Context, email: String?) {

    private val key = (email ?: "guest").hashCode()
    private val sp = context.getSharedPreferences("nabi_diary_$key", Context.MODE_PRIVATE)
    private val server = context.getSharedPreferences("nabi_diary_srv_$key", Context.MODE_PRIVATE)

    fun get(date: LocalDate): DiaryEntry? = (sp.getString(date.toString(), null) ?: server.getString(date.toString(), null))
        ?.let { runCatching { parse(date, it) }.getOrNull() }

    fun all(): List<DiaryEntry> {
        val local = read(sp)
        return (read(server) + local).toSortedMap().values.toList() // local overrides server for the same date
    }

    fun put(e: DiaryEntry) {
        sp.edit().putString(e.date.toString(), write(e).toString()).apply()
    }

    /**
     * Blocking (call from a background thread). Replaces the server cache with the last ~100 nights.
     * Throws [com.cbnu.nabi.data.ApiException] when the server can't be reached.
     */
    fun syncFromServer(api: ApiClient) {
        val end = LocalDate.now()
        val start = end.minusDays(100)
        val sleeps = api.sleepRecords(start, end)
        val habits = api.behaviorRecords(start.minusDays(1), end).associateBy { it.recordDate }
        val zone = ZoneId.systemDefault()
        val ed = server.edit().clear()
        for (s in sleeps) {
            val bed = s.bedtime.atZoneSameInstant(zone).toLocalTime()
            val wakeAt = s.finalAwakening.atZoneSameInstant(zone)
            val date = wakeAt.toLocalDate()
            val h = habits[date.minusDays(1)]
            val e = DiaryEntry(
                date = date,
                bedtime = "%02d:%02d".format(bed.hour, bed.minute),
                wakeTime = "%02d:%02d".format(wakeAt.hour, wakeAt.minute),
                awakenings = null,
                awakeMinutes = s.wakeAfterSleepOnsetMin,
                latencyMin = s.sleepOnsetLatencyMin,
                satisfaction = s.sleepQuality,
                nap = h?.napDurationMin?.let { it > 0 }, napMin = h?.napDurationMin,
                caffeine = h?.caffeineAfterCutoff,
                exercise = h?.exerciseDurationMin?.let { it > 0 }, exerciseMin = h?.exerciseDurationMin,
                serverSaved = true, behaviorSaved = h != null, fromServer = true,
            )
            ed.putString(date.toString(), write(e).toString())
        }
        ed.apply()
    }

    private fun read(p: android.content.SharedPreferences): Map<LocalDate, DiaryEntry> =
        p.all.mapNotNull { (k, v) -> runCatching { LocalDate.parse(k).let { d -> d to parse(d, v as String) } }.getOrNull() }.toMap()

    private fun write(e: DiaryEntry) = JSONObject()
        .put("bedtime", e.bedtime).put("wakeTime", e.wakeTime)
        .put("awakenings", e.awakenings ?: JSONObject.NULL)
        .put("awakeMinutes", e.awakeMinutes ?: JSONObject.NULL)
        .put("latency", e.latencyMin).put("satisfaction", e.satisfaction ?: JSONObject.NULL)
        .put("nap", e.nap ?: JSONObject.NULL).put("napMin", e.napMin ?: JSONObject.NULL)
        .put("caffeine", e.caffeine ?: JSONObject.NULL)
        .put("exercise", e.exercise ?: JSONObject.NULL).put("exerciseMin", e.exerciseMin ?: JSONObject.NULL)
        .put("serverSaved", e.serverSaved).put("behaviorSaved", e.behaviorSaved).put("fromServer", e.fromServer)

    private fun parse(date: LocalDate, s: String): DiaryEntry {
        val j = JSONObject(s)
        fun int(k: String) = if (!j.has(k) || j.isNull(k)) null else j.getInt(k)
        fun bool(k: String) = if (!j.has(k) || j.isNull(k)) null else j.getBoolean(k)
        return DiaryEntry(
            date = date,
            bedtime = j.getString("bedtime"),
            wakeTime = j.getString("wakeTime"),
            awakenings = int("awakenings"),
            awakeMinutes = int("awakeMinutes"),
            latencyMin = j.getInt("latency"),
            satisfaction = int("satisfaction"),
            nap = bool("nap"), napMin = int("napMin"),
            // Entries saved before the 오후 2시 wording asked about any caffeine: not the same question.
            caffeine = if (j.has("napMin")) bool("caffeine") else null,
            exercise = bool("exercise"), exerciseMin = int("exerciseMin"),
            serverSaved = j.optBoolean("serverSaved"),
            behaviorSaved = j.optBoolean("behaviorSaved"),
            fromServer = j.optBoolean("fromServer"),
        )
    }
}
