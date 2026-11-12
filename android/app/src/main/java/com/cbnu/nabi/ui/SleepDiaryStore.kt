package com.cbnu.nabi.ui

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** One diary entry as the user entered it in the sleep record sheet (FR-03). Times are "HH:mm". */
data class DiaryEntry(
    val date: LocalDate,          // the morning the user woke up (Figma: "11월 12일 수면 기록")
    val bedtime: String,
    val wakeTime: String,
    val awakenings: Int?,
    val awakeMinutes: Int?,
    val latencyMin: Int,
    val satisfaction: Int,        // 1..5 = 매우 나쁨..매우 좋음 (server sleep_quality)
    val nap: Boolean,
    val caffeine: Boolean,
    val exercise: Boolean,
    val serverSaved: Boolean,
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
 * Copies of the diary entries saved on THIS device, per logged-in email.
 *
 * Why: the sleep times go to the server (POST /sleep-records), but the server has no fields yet for
 * the night-time awakening count and the yes/no habits (nap, caffeine, exercise), and the app has no
 * GET /sleep-records call yet (API teammate's part). Until then these local copies let the diary
 * show what was entered here. Replace with the server data when the API is connected.
 */
class SleepDiaryStore(context: Context, email: String?) {

    private val sp = context.getSharedPreferences("nabi_diary_" + (email ?: "guest").hashCode(), Context.MODE_PRIVATE)

    fun get(date: LocalDate): DiaryEntry? = sp.getString(date.toString(), null)?.let { parse(date, it) }

    fun all(): List<DiaryEntry> =
        sp.all.mapNotNull { (k, v) -> runCatching { parse(LocalDate.parse(k), v as String) }.getOrNull() }
            .sortedBy { it.date }

    fun put(e: DiaryEntry) {
        val j = JSONObject()
            .put("bedtime", e.bedtime).put("wakeTime", e.wakeTime)
            .put("awakenings", e.awakenings ?: JSONObject.NULL)
            .put("awakeMinutes", e.awakeMinutes ?: JSONObject.NULL)
            .put("latency", e.latencyMin).put("satisfaction", e.satisfaction)
            .put("nap", e.nap).put("caffeine", e.caffeine).put("exercise", e.exercise)
            .put("serverSaved", e.serverSaved)
        sp.edit().putString(e.date.toString(), j.toString()).apply()
    }

    private fun parse(date: LocalDate, s: String): DiaryEntry {
        val j = JSONObject(s)
        return DiaryEntry(
            date = date,
            bedtime = j.getString("bedtime"),
            wakeTime = j.getString("wakeTime"),
            awakenings = if (j.isNull("awakenings")) null else j.getInt("awakenings"),
            awakeMinutes = if (j.isNull("awakeMinutes")) null else j.getInt("awakeMinutes"),
            latencyMin = j.getInt("latency"),
            satisfaction = j.getInt("satisfaction"),
            nap = j.getBoolean("nap"),
            caffeine = j.getBoolean("caffeine"),
            exercise = j.getBoolean("exercise"),
            serverSaved = j.optBoolean("serverSaved"),
        )
    }
}
