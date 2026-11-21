package com.cbnu.nabi.ui


import com.cbnu.nabi.R
import com.cbnu.nabi.tr
import android.content.Context
import com.cbnu.nabi.data.RiskAnalysis
import com.cbnu.nabi.data.UserState
import java.time.LocalDate

/*
 * Data for the "나의 수면 분석" screen, kept apart from the UI so the API teammate can plug in
 * server results later (GET /analysis/risk metrics, GET /sleep-records, Health Connect, UsageStats)
 * without touching SleepAnalysisPanel. Nothing here invents numbers: anything the app cannot read
 * yet is left null and the UI shows "수집 중 / 연동 필요 / 기록 없음".
 */

/** Server risk level → label. Only shown when the server marks the baseline "ready". */
enum class RiskBadge(private val labelRes: Int) {
    STABLE(R.string.stable), WARNING(R.string.caution), HIGH_RISK(R.string.high_risk);

    val label: String get() = tr(labelRes)
}

data class TrendDay(val date: LocalDate, val totalSleepMin: Int?, val efficiencyPct: Int?)

/**
 * One "평소와 비교하기" row. [baseline] / [status] stay null until the server analysis is wired in
 * (baseline median and a confirmed 정상/주의 judgement must come from the server, never the app).
 */
data class CompareRow(val name: String, val baseline: String?, val recent: String?, val status: String? = null)

enum class HabitState { VALUE, NO_RECORD, NEEDS_LINK }
data class HabitRow(val name: String, val iconRes: Int, val state: HabitState, val value: String? = null, val note: String? = null)

data class SleepAnalysis(
    /** null = analysis not available yet (state not loaded, baseline collecting, or UNKNOWN). */
    val risk: RiskBadge?,
    /** Server user state is loaded. */
    val stateLoaded: Boolean,
    val baselineReady: Boolean,
    val validDays: Int?,
    /** Server risk factor codes ("살펴볼 점"); empty unless the baseline is ready. */
    val riskFactors: List<String>,
    /** The server actually analysed recent sleep (baseline ready and sleep_state not UNKNOWN). */
    val sleepAnalyzed: Boolean,
    val trend: List<TrendDay>,
    val comparisons: List<CompareRow>,
    val habits: List<HabitRow>,
)

/**
 * Builds [SleepAnalysis] from GET /users/me/state, GET /analysis/risk?window=7 (personal baseline
 * medians and the metrics the server flagged) and the diary (local + server records).
 */
object SleepAnalysisSource {

    /** The server measures bedtime / wake time as minutes from these clocks (baseline_service.py defaults). */
    private const val BED_ANCHOR_MIN = 23 * 60
    private const val WAKE_ANCHOR_MIN = 7 * 60

    fun build(context: Context, email: String?, state: UserState?, server: RiskAnalysis? = null): SleepAnalysis {
        val today = LocalDate.now()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val byDate = SleepDiaryStore(context, email).all().associateBy { it.date }
        val week = days.mapNotNull { byDate[it] }

        val ready = state?.baselineStatus == "ready"
        val risk = if (!ready) null else when (state?.riskLevel) {
            "LOW" -> RiskBadge.STABLE
            "MODERATE" -> RiskBadge.WARNING
            "HIGH" -> RiskBadge.HIGH_RISK
            else -> null // UNKNOWN: not enough recent nights — never shown as 안정
        }

        val trend = days.map { d -> byDate[d].let { TrendDay(d, it?.totalSleepMin, it?.efficiencyPct) } }

        // 평소 = the server's personal baseline median; status = whether the server flagged that metric.
        val serverReady = server?.baselineStatus == "ready"
        fun base(metric: String, fmt: (Double) -> String): String? =
            server?.metrics?.get(metric)?.baselineMedian?.takeIf { serverReady }?.let(fmt)
        fun status(metric: String): String? = when {
            server == null || base(metric) { "" } == null -> null
            metric in server.flaggedMetrics -> tr(R.string.different_from_usual)
            else -> tr(R.string.usual_range)
        }
        fun minutes(v: Double) = tr(R.string.min_2, Math.round(v))
        fun clock(anchor: Int) = { v: Double -> Modals.format12(java.time.LocalTime.of(0, 0).plusMinutes(((anchor + Math.round(v)) % 1440 + 1440) % 1440)) }
        fun row(name: String, metric: String, fmt: (Double) -> String, recent: String?) =
            CompareRow(name, base(metric, fmt), recent, status(metric))
        fun avgInt(xs: List<Int>) = if (xs.isEmpty()) null else Math.round(xs.average()).toInt()
        val comparisons = listOf(
            row(tr(R.string.total_sleep), "sleep_duration", { formatDuration(Math.round(it).toInt()) }, avgInt(week.map { it.totalSleepMin })?.let(::formatDuration)),
            row(tr(R.string.sleep_efficiency), "sleep_efficiency", { "${Math.round(it * 100)}%" }, avgInt(week.map { it.efficiencyPct })?.let { "$it%" }),
            row(tr(R.string.time_to_fall_asleep), "sleep_onset_latency", ::minutes, avgInt(week.map { it.latencyMin })?.let { tr(R.string.min_2, it) }),
            row(tr(R.string.bedtime), "bedtime", clock(BED_ANCHOR_MIN), averageClock(week.map { it.bedtime }, eveningBased = true)),
            row(tr(R.string.wake_time), "wake_time", clock(WAKE_ANCHOR_MIN), averageClock(week.map { it.wakeTime }, eveningBased = false)),
            row(tr(R.string.time_awake_at_night), "wake_after_sleep_onset", ::minutes, avgInt(week.mapNotNull { it.awakeMinutes })?.let { tr(R.string.min_2, it) }),
        )

        val habits = listOf(
            HabitRow(tr(R.string.phone_use_before_bed), com.cbnu.nabi.R.drawable.ic_moon, HabitState.NEEDS_LINK,
                note = tr(R.string.shown_once_usage_access_is_connected)),
            HabitRow(tr(R.string.weekly_activity_steps), com.cbnu.nabi.R.drawable.ic_paw, HabitState.NEEDS_LINK,
                note = tr(R.string.shown_once_step_data_is_connected)),
            yesNoHabit(tr(R.string.caffeine_after_2_pm), com.cbnu.nabi.R.drawable.ic_sparkle, week) { it.caffeine },
            yesNoHabit(tr(R.string.naps), com.cbnu.nabi.R.drawable.ic_sun, week) { it.nap },
        )

        return SleepAnalysis(
            risk, state != null, ready, state?.baselineValidDays,
            riskFactors = if (ready) state!!.riskFactors else emptyList(),
            sleepAnalyzed = ready && state!!.sleepState != "UNKNOWN",
            trend = trend, comparisons = comparisons, habits = habits,
        )
    }

    /** Yes/no habits from the diary: "N일 / 기록한 M일" (days without that answer are not counted). */
    private fun yesNoHabit(name: String, icon: Int, week: List<DiaryEntry>, yes: (DiaryEntry) -> Boolean?): HabitRow {
        val answered = week.mapNotNull(yes)
        return if (answered.isEmpty()) HabitRow(name, icon, HabitState.NO_RECORD, note = tr(R.string.no_sleep_diary_entries_in_last))
        else HabitRow(name, icon, HabitState.VALUE, tr(R.string.of_logged_days, answered.count { it }, answered.size), tr(R.string.based_on_last_7_days_of))
    }

    /** Mean of "HH:mm" times; bedtimes after midnight count as the same evening. */
    private fun averageClock(times: List<String>, eveningBased: Boolean): String? {
        if (times.isEmpty()) return null
        val mins = times.map { t ->
            val m = java.time.LocalTime.parse(t).toSecondOfDay() / 60
            if (eveningBased && m < 12 * 60) m + 24 * 60 else m
        }
        val avg = Math.round(mins.average()).toInt() % (24 * 60)
        return Modals.format12(java.time.LocalTime.of(avg / 60, avg % 60))
    }
}
