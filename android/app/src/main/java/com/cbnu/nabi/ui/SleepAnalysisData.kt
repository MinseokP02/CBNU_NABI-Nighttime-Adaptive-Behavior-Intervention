package com.cbnu.nabi.ui

import android.content.Context
import com.cbnu.nabi.data.UserState
import java.time.LocalDate

/*
 * Data for the "나의 수면 분석" screen, kept apart from the UI so the API teammate can plug in
 * server results later (GET /analysis/risk metrics, GET /sleep-records, Health Connect, UsageStats)
 * without touching SleepAnalysisPanel. Nothing here invents numbers: anything the app cannot read
 * yet is left null and the UI shows "수집 중 / 연동 필요 / 기록 없음".
 */

/** Server risk level → label. Only shown when the server marks the baseline "ready". */
enum class RiskBadge(val label: String) { STABLE("안정"), WARNING("주의"), HIGH_RISK("고위험") }

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

/** Builds [SleepAnalysis] from what the app has today: GET /users/me/state + the local diary. */
object SleepAnalysisSource {

    fun build(context: Context, email: String?, state: UserState?): SleepAnalysis {
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

        // 평소: the app can't read the server baseline yet (GET /analysis/risk metrics) → null.
        val baseline: String? = null
        fun avgInt(xs: List<Int>) = if (xs.isEmpty()) null else Math.round(xs.average()).toInt()
        val comparisons = listOf(
            CompareRow("총 수면시간", baseline, avgInt(week.map { it.totalSleepMin })?.let(::formatDuration)),
            CompareRow("수면 효율", baseline, avgInt(week.map { it.efficiencyPct })?.let { "$it%" }),
            CompareRow("잠들기까지 걸린 시간", baseline, avgInt(week.map { it.latencyMin })?.let { "${it}분" }),
            CompareRow("취침 시각", baseline, averageClock(week.map { it.bedtime }, eveningBased = true)),
            CompareRow("기상 시각", baseline, averageClock(week.map { it.wakeTime }, eveningBased = false)),
            CompareRow("밤중 깨어 있던 시간", baseline, avgInt(week.mapNotNull { it.awakeMinutes })?.let { "${it}분" }),
        )

        val habits = listOf(
            HabitRow("취침 전 스마트폰 사용", com.cbnu.nabi.R.drawable.ic_moon, HabitState.NEEDS_LINK,
                note = "사용 정보 연동 후 보여드려요."),
            HabitRow("주간 활동량 · 걸음 수", com.cbnu.nabi.R.drawable.ic_paw, HabitState.NEEDS_LINK,
                note = "걸음 수 연동 후 보여드려요."),
            yesNoHabit("카페인 섭취", com.cbnu.nabi.R.drawable.ic_sparkle, week) { it.caffeine },
            yesNoHabit("낮잠", com.cbnu.nabi.R.drawable.ic_sun, week) { it.nap },
        )

        return SleepAnalysis(
            risk, state != null, ready, state?.baselineValidDays,
            riskFactors = if (ready) state!!.riskFactors else emptyList(),
            sleepAnalyzed = ready && state!!.sleepState != "UNKNOWN",
            trend = trend, comparisons = comparisons, habits = habits,
        )
    }

    /** Yes/no habits from the diary: "N일 / 기록한 M일" — no amounts or durations are made up. */
    private fun yesNoHabit(name: String, icon: Int, week: List<DiaryEntry>, yes: (DiaryEntry) -> Boolean): HabitRow =
        if (week.isEmpty()) HabitRow(name, icon, HabitState.NO_RECORD, note = "최근 7일 수면 일지에 기록이 없어요.")
        else HabitRow(name, icon, HabitState.VALUE, "${week.count(yes)}일 / 기록한 ${week.size}일", "최근 7일 수면 일지 기준")

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
