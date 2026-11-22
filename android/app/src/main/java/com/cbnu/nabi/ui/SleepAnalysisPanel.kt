package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cbnu.nabi.R
import com.cbnu.nabi.data.UserState
import java.time.LocalDate

/**
 * "나의 수면 분석" detail, opened from the home analysis card as a bottom sheet (drag down, tap
 * outside or press back to close; the app behind is blurred). UI only: it draws a [SleepAnalysis]
 * built by [SleepAnalysisSource].
 */
class SleepAnalysisPanel(
    private val activity: MainActivity,
    private val onOpenDiary: () -> Unit,
    onClosed: () -> Unit,
) {
    private val sheet = BottomSheet(activity, tr(R.string.my_sleep_analysis), tr(R.string.see_your_usual_sleep_pattern_alongside))
    private val col = sheet.body

    init {
        sheet.setFooter(diaryButton())
        sheet.onDismiss = onClosed
    }

    fun show() {
        sheet.show()
        sheet.scrollToTop() // always open on 현재 수면 상태
    }
    fun dismiss() = sheet.dismiss()

    // For the tutorial (read only).
    val sheetWindow: ViewGroup? get() = sheet.decorView as? ViewGroup
    var statusView: View? = null
        private set
    var trendView: View? = null
        private set
    var compareView: View? = null
        private set
    fun lockForTutorial(onBack: () -> Unit) = sheet.lockForTutorial(onBack)

    fun render(state: UserState?, risk: com.cbnu.nabi.data.RiskAnalysis? = null) {
        val a = SleepAnalysisSource.build(activity, activity.prefs.email, state, risk)
        col.root.removeAllViews()
        statusView = col.add(statusCard(a))
        trendView = col.add(trendCard(a))
        compareView = col.add(compareCard(a))
        col.add(habitsCard(a))
    }

    // ---------------- shared ----------------
    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
        background = activity.cardBg(Palette.card, 20, Color.parseColor("#E6D4C8"))
        elevation = activity.dp(1).toFloat()
    }

    private fun title(iconRes: Int, text: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(activity.icon(iconRes, Palette.lavender, 20))
        addView(activity.label(text, 17f, title = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(7) })
    }

    private fun note(text: String, size: Float = 11f) = activity.label(text, size, Palette.muted).apply {
        setPadding(0, activity.dp(8), 0, 0); setLineSpacing(activity.dp(2).toFloat(), 1f)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = activity.dp(top) }

    // ---------------- ① 현재 수면 상태 ----------------
    private fun statusCard(a: SleepAnalysis): View {
        val c = card()
        c.addView(title(R.drawable.ic_info, tr(R.string.current_sleep_status)))
        val (text, fg, bg) = when (a.risk) {
            RiskBadge.STABLE -> Triple(tr(R.string.stable), Color.parseColor("#3F7A4E"), Color.parseColor("#E4F2E7"))
            RiskBadge.WARNING -> Triple(tr(R.string.caution), Color.parseColor("#9A6312"), Color.parseColor("#FCEFD9"))
            RiskBadge.HIGH_RISK -> Triple(tr(R.string.high_risk), Color.parseColor("#A84B43"), Color.parseColor("#FBE3E0"))
            null -> Triple(tr(R.string.preparing_analysis), Palette.lavenderDeep, Palette.lavenderSoft)
        }
        // Badge on the right end of the "현재 수면 상태" title row, vertically centred with it.
        val titleRow = c.getChildAt(0) as LinearLayout
        titleRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        titleRow.addView(activity.label(text, 14f, fg, title = true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(14), activity.dp(5), activity.dp(14), activity.dp(5))
            background = activity.rounded(bg, 999)
        })
        val detail = when {
            !a.stateLoaded -> tr(R.string.loading_analysis)
            !a.baselineReady -> tr(R.string.learning_your_usual_sleep_pattern) + (a.validDays?.let { tr(R.string.valid_days_collected_so_far, it) } ?: "")
            a.risk == null -> tr(R.string.your_sleep_status_will_appear_once)
            else -> null
        }
        if (detail != null) c.addView(note(detail, 12f).apply { setTextColor(Palette.ink) })

        // 살펴볼 점 — moved here from the diary. Only real server results; nothing fixed or made up.
        c.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply { topMargin = activity.dp(14) })
        c.addView(activity.label(tr(R.string.things_to_watch), 13.5f, bold = true).apply { setPadding(0, activity.dp(12), 0, 0) })
        when {
            !a.stateLoaded -> c.addView(note(tr(R.string.waiting_for_analysis), 12f))
            !a.baselineReady -> c.addView(note(tr(R.string.once_your_personal_baseline_is_ready), 12f))
            a.riskFactors.isNotEmpty() -> {
                val chips = FlowRow()
                a.riskFactors.forEach { chips.addView(factorChip(factorLabel(it))) }
                c.addView(chips, lp(10))
                c.addView(note(tr(R.string.metrics_that_changed_compared_with_your), 10.5f))
            }
            a.sleepAnalyzed -> c.addView(note(tr(R.string.no_notable_changes_in_your_recent), 12f))
            else -> c.addView(note(tr(R.string.analysis_is_being_prepared), 12f))
        }
        c.addView(note(tr(R.string.compared_with_your_usual_sleep_pattern)))
        return c
    }

    private fun factorChip(text: String) = activity.label(text, 11f, Color.parseColor("#71568D"), bold = true).apply {
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
        background = activity.rounded(Palette.lavenderSoft, 10)
    }

    /**
     * Metric-based wording for the backend risk codes (services/risk_service.py rules).
     * Unknown codes fall back to the shared labels rather than inventing a meaning.
     */
    private fun factorLabel(code: String): String = when (code) {
        "PROLONGED_SLEEP_ONSET" -> tr(R.string.longer_time_to_fall_asleep)
        "FREQUENT_NIGHT_AWAKENING" -> tr(R.string.more_time_awake_at_night)
        "LOW_SLEEP_EFFICIENCY" -> tr(R.string.lower_sleep_efficiency)
        "SHORT_SLEEP_DURATION" -> tr(R.string.less_total_sleep)
        "IRREGULAR_BEDTIME" -> tr(R.string.more_variable_bedtime)
        "IRREGULAR_WAKE_TIME" -> tr(R.string.more_variable_wake_time)
        "POOR_SLEEP_QUALITY" -> tr(R.string.low_sleep_satisfaction)
        "LATE_CAFFEINE" -> tr(R.string.frequent_late_caffeine)
        "EXCESSIVE_PRE_BED_SCREEN" -> tr(R.string.long_screen_time_before_bed)
        "LONG_DAYTIME_NAP" -> tr(R.string.long_naps_2)
        "HIGH_STRESS" -> tr(R.string.high_stress_score)
        "LOW_PHYSICAL_ACTIVITY" -> tr(R.string.not_enough_exercise)
        else -> com.cbnu.nabi.data.Labels.factor(code)
    }

    /** Wraps chips onto new lines. */
    private inner class FlowRow : ViewGroup(activity) {
        private val gap = activity.dp(6)
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                measureChild(ch, widthMeasureSpec, heightMeasureSpec)
                if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                x += ch.measuredWidth + gap
                rowH = maxOf(rowH, ch.measuredHeight)
            }
            setMeasuredDimension(maxW, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
                x += ch.measuredWidth + gap
                rowH = maxOf(rowH, ch.measuredHeight)
            }
        }
    }

    // ---------------- ② 최근 7일 수면 추세 ----------------
    private fun trendCard(a: SleepAnalysis): View {
        val c = card()
        c.addView(title(R.drawable.ic_chart, tr(R.string.sleep_trend_last_7_days)))
        val logged = a.trend.filter { it.totalSleepMin != null }
        if (logged.isEmpty()) {
            c.addView(note(tr(R.string.you_ll_see_trends_once_you), 12f).apply { setPadding(0, activity.dp(12), 0, activity.dp(4)) })
            return c
        }
        val avgSleep = Math.round(logged.map { it.totalSleepMin!! }.average()).toInt()
        val avgEff = Math.round(logged.mapNotNull { it.efficiencyPct }.average()).toInt()
        val stats = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statBox(tr(R.string.avg_sleep), formatDuration(avgSleep)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(statBox(tr(R.string.avg_sleep_efficiency), "$avgEff%"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
        c.addView(stats, lp(12))

        // legend: two different units, two different marks
        val legend = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        legend.addView(View(activity).apply { background = activity.rounded(BAR, 3) }, LinearLayout.LayoutParams(activity.dp(10), activity.dp(10)))
        legend.addView(activity.label(tr(R.string.total_sleep_left_hours), 10.5f, Palette.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5); rightMargin = activity.dp(14) })
        legend.addView(View(activity).apply { background = activity.rounded(LINE, 999) }, LinearLayout.LayoutParams(activity.dp(9), activity.dp(9)))
        legend.addView(activity.label(tr(R.string.sleep_efficiency_right), 10.5f, Palette.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
        c.addView(legend, lp(14))

        c.addView(TrendChart(activity, a.trend).apply {
            contentDescription = tr(R.string.logged_of_last_7_days_avg, logged.size, formatDuration(avgSleep), avgEff)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(190)).apply { topMargin = activity.dp(8) })
        c.addView(note(tr(R.string.logged_of_7_days_days_without, logged.size)))
        return c
    }

    private fun statBox(name: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        background = activity.rounded(Color.parseColor("#F6EFF8"), 12)
        addView(activity.label(name, 10.5f, Palette.muted))
        addView(activity.label(value, 16f).apply { setPadding(0, activity.dp(5), 0, 0) })
    }

    // ---------------- ③ 평소와 비교하기 ----------------
    private fun compareCard(a: SleepAnalysis): View {
        val c = card()
        c.addView(title(R.drawable.ic_target, tr(R.string.compare_with_usual)))
        c.addView(note(tr(R.string.usual_is_your_personal_baseline_recent)))

        val head = compareRowView("", tr(R.string.usual), tr(R.string.last_7_days), header = true)
        c.addView(head, lp(10))
        a.comparisons.forEach { r ->
            c.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
            val baselineText = r.baseline ?: if (a.baselineReady) "—" else tr(R.string.collecting)
            c.addView(compareRowView(r.name, baselineText, r.recent ?: tr(R.string.no_record), status = r.status,
                baselineMuted = r.baseline == null, recentMuted = r.recent == null))
        }
        val base = when {
            !a.stateLoaded -> tr(R.string.loading_your_personal_baseline)
            !a.baselineReady -> tr(R.string.building_personal_baseline_valid_days, a.validDays ?: 0) +
                tr(R.string.baseline_is_created_once_enough_valid)
            else -> tr(R.string.your_personal_baseline_is_ready_usual)
        }
        c.addView(note(base))
        c.addView(note(tr(R.string.different_from_usual_marks_items_where), 10.5f))
        return c
    }

    private fun compareRowView(
        name: String, baseline: String, recent: String, header: Boolean = false,
        status: String? = null, baselineMuted: Boolean = false, recentMuted: Boolean = false,
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, activity.dp(if (header) 0 else 11), 0, activity.dp(if (header) 6 else 11))
        val nameCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        nameCol.addView(activity.label(name, 12.5f, bold = true))
        if (status != null) nameCol.addView(activity.label(status, 10f, Palette.lavenderDeep, bold = true).apply {
            setPadding(activity.dp(7), activity.dp(2), activity.dp(7), activity.dp(2))
            background = activity.rounded(Palette.lavenderSoft, 8)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(4) })
        addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun cell(t: String, muted: Boolean) = activity.label(t, if (header) 10.5f else 12f,
            if (header || muted) Palette.muted else Palette.ink, bold = !header && !muted).apply { gravity = Gravity.END }
        addView(cell(baseline, baselineMuted), LinearLayout.LayoutParams(activity.dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(cell(recent, recentMuted), LinearLayout.LayoutParams(activity.dp(86), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(6) })
    }

    // ---------------- ④ 생활 습관 살펴보기 ----------------
    private fun habitsCard(a: SleepAnalysis): View {
        val c = card()
        c.addView(title(R.drawable.ic_leaf, tr(R.string.look_at_your_habits)))
        a.habits.forEachIndexed { i, h ->
            if (i > 0) c.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, activity.dp(11), 0, activity.dp(11))
            }
            row.addView(FrameLayout(activity).apply {
                background = activity.rounded(Color.parseColor("#F1E9F6"), 11)
                addView(activity.icon(h.iconRes, Color.parseColor("#9475B7"), 18), FrameLayout.LayoutParams(activity.dp(18), activity.dp(18), Gravity.CENTER))
            }, LinearLayout.LayoutParams(activity.dp(32), activity.dp(32)))
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(activity.label(h.name, 12.5f, bold = true))
            if (h.note != null) texts.addView(activity.label(h.note, 10.5f, Palette.muted).apply { setPadding(0, activity.dp(3), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(10); rightMargin = activity.dp(8) })
            val (value, fg, bg) = when (h.state) {
                HabitState.VALUE -> Triple(h.value ?: "", Palette.ink, Color.TRANSPARENT)
                HabitState.NO_RECORD -> Triple(tr(R.string.no_record), Palette.muted, Color.parseColor("#F3ECE7"))
                HabitState.NEEDS_LINK -> Triple(tr(R.string.needs_connection), Palette.lavenderDeep, Palette.lavenderSoft)
            }
            row.addView(activity.label(value, 11.5f, fg, bold = true).apply {
                if (bg != Color.TRANSPARENT) { setPadding(activity.dp(9), activity.dp(4), activity.dp(9), activity.dp(4)); background = activity.rounded(bg, 999) }
            })
            c.addView(row)
        }
        return c
    }

    // ---------------- 수면 일지 보기 ----------------
    private fun diaryButton() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = activity.dp(50)
        background = activity.rounded(Color.parseColor("#9D7BC4"), 17)
        elevation = activity.dp(2).toFloat()
        addView(activity.icon(R.drawable.ic_book, Color.WHITE, 19))
        addView(activity.label(tr(R.string.view_sleep_diary), 14f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(7) })
        setOnClickListener { onOpenDiary() }
    }

    companion object {
        val BAR: Int = Color.parseColor("#C9B5E6")
        val LINE: Int = Color.parseColor("#E99680")
    }
}

/**
 * 7-day chart with two separate scales: bars = total sleep (left axis, 0–12h),
 * line + dots = sleep efficiency (right axis, 0–100%). The 6h/50% gridline is shared.
 * Days without a record are left empty and break the line.
 */
@SuppressLint("ViewConstructor")
class TrendChart(context: Context, private val days: List<TrendDay>) : View(context) {
    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EADDD3"); strokeWidth = d }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textSize = 9.5f * d; typeface = Fonts.body(context) }
    private val dayText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ink; textSize = 11f * d; textAlign = Paint.Align.CENTER; typeface = Fonts.body(context) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SleepAnalysisPanel.BAR }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SleepAnalysisPanel.LINE; style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SleepAnalysisPanel.LINE }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val names = listOf(tr(R.string.mon), tr(R.string.tue), tr(R.string.wed), tr(R.string.thu), tr(R.string.fri), tr(R.string.sat), tr(R.string.sun))

    override fun onDraw(c: Canvas) {
        val left = 30 * d; val right = width - 36 * d
        val top = 8 * d; val bottom = height - 24 * d
        val h = bottom - top
        // gridlines at 0 / 6 / 12h = 0 / 50 / 100%
        for (i in 0..2) {
            val y = bottom - h * i / 2f
            c.drawLine(left, y, right, y, grid)
            axis.textAlign = Paint.Align.RIGHT
            c.drawText("${i * 6}h", left - 6 * d, y + 3.5f * d, axis)
            axis.textAlign = Paint.Align.LEFT
            c.drawText("${i * 50}%", right + 6 * d, y + 3.5f * d, axis)
        }
        val slot = (right - left) / days.size
        val today = LocalDate.now()
        val path = Path(); var drawing = false
        days.forEachIndexed { i, day ->
            val cx = left + slot * (i + 0.5f)
            day.totalSleepMin?.let { m ->
                val bh = h * (m / 720f).coerceIn(0.02f, 1f)
                c.drawRoundRect(RectF(cx - slot * 0.22f, bottom - bh, cx + slot * 0.22f, bottom), 5 * d, 5 * d, bar)
                c.drawRect(cx - slot * 0.22f, bottom - minOf(bh, 5 * d), cx + slot * 0.22f, bottom, bar) // square bottom
            }
            val e = day.efficiencyPct
            if (e != null) {
                val y = bottom - h * (e / 100f).coerceIn(0f, 1f)
                if (drawing) path.lineTo(cx, y) else path.moveTo(cx, y)
                drawing = true
            } else drawing = false
            dayText.isFakeBoldText = day.date == today
            c.drawText(names[day.date.dayOfWeek.value - 1], cx, height - 6 * d, dayText)
        }
        c.drawPath(path, line)
        days.forEachIndexed { i, day ->
            val e = day.efficiencyPct ?: return@forEachIndexed
            val cx = left + slot * (i + 0.5f)
            val y = bottom - h * (e / 100f).coerceIn(0f, 1f)
            c.drawCircle(cx, y, 4f * d, dot)
            c.drawCircle(cx, y, 4f * d, dotRing)
        }
    }
}
