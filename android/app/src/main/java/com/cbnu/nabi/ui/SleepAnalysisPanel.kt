package com.cbnu.nabi.ui

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
    private val sheet = BottomSheet(activity, "나의 수면 분석", "나의 평소 수면 패턴과 최근 기록을 함께 살펴봐요.")
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

    fun render(state: UserState?) {
        val a = SleepAnalysisSource.build(activity, activity.prefs.email, state)
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
        c.addView(title(R.drawable.ic_info, "현재 수면 상태"))
        val (text, fg, bg) = when (a.risk) {
            RiskBadge.STABLE -> Triple("안정", Color.parseColor("#3F7A4E"), Color.parseColor("#E4F2E7"))
            RiskBadge.WARNING -> Triple("주의", Color.parseColor("#9A6312"), Color.parseColor("#FCEFD9"))
            RiskBadge.HIGH_RISK -> Triple("고위험", Color.parseColor("#A84B43"), Color.parseColor("#FBE3E0"))
            null -> Triple("분석 준비 중", Palette.lavenderDeep, Palette.lavenderSoft)
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
            !a.stateLoaded -> "분석 정보를 불러오는 중이에요."
            !a.baselineReady -> "나의 평소 수면 패턴을 알아가는 중이에요." + (a.validDays?.let { "\n지금까지 유효 기록 ${it}일이 모였어요." } ?: "")
            a.risk == null -> "최근 기록이 더 쌓이면 수면 상태를 알려드려요."
            else -> null
        }
        if (detail != null) c.addView(note(detail, 12f).apply { setTextColor(Palette.ink) })

        // 살펴볼 점 — moved here from the diary. Only real server results; nothing fixed or made up.
        c.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply { topMargin = activity.dp(14) })
        c.addView(activity.label("살펴볼 점", 13.5f, bold = true).apply { setPadding(0, activity.dp(12), 0, 0) })
        when {
            !a.stateLoaded -> c.addView(note("분석 결과를 기다리고 있어요.", 12f))
            !a.baselineReady -> c.addView(note("개인 기준선이 완성되면 평소와 달라진 점을 알려드려요.", 12f))
            a.riskFactors.isNotEmpty() -> {
                val chips = FlowRow()
                a.riskFactors.forEach { chips.addView(factorChip(factorLabel(it))) }
                c.addView(chips, lp(10))
                c.addView(note("평소(개인 기준선)와 비교해 달라진 지표예요.", 10.5f))
            }
            a.sleepAnalyzed -> c.addView(note("최근 수면 패턴에서 눈에 띄는 변화가 없어요.", 12f))
            else -> c.addView(note("분석 결과 준비 중이에요.", 12f))
        }
        c.addView(note("평소 수면 패턴과 비교한 결과이며, 의료적 진단이 아닙니다."))
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
        "PROLONGED_SLEEP_ONSET" -> "잠들기까지 걸린 시간 증가"
        "FREQUENT_NIGHT_AWAKENING" -> "밤중 깨어 있던 시간 증가"
        "LOW_SLEEP_EFFICIENCY" -> "수면 효율 감소"
        "SHORT_SLEEP_DURATION" -> "총 수면시간 감소"
        "IRREGULAR_BEDTIME" -> "취침 시각 변동 증가"
        "IRREGULAR_WAKE_TIME" -> "기상 시각 변동 증가"
        "POOR_SLEEP_QUALITY" -> "수면 만족도 점수 낮음"
        "LATE_CAFFEINE" -> "늦은 시간 카페인 섭취 잦음"
        "EXCESSIVE_PRE_BED_SCREEN" -> "취침 전 화면 사용 시간 김"
        "LONG_DAYTIME_NAP" -> "낮잠 시간 김"
        "HIGH_STRESS" -> "스트레스 점수 높음"
        "LOW_PHYSICAL_ACTIVITY" -> "운동 시간 부족"
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
        c.addView(title(R.drawable.ic_chart, "최근 7일 수면 추세"))
        val logged = a.trend.filter { it.totalSleepMin != null }
        if (logged.isEmpty()) {
            c.addView(note("수면 기록이 쌓이면 추세를 확인할 수 있어요.", 12f).apply { setPadding(0, activity.dp(12), 0, activity.dp(4)) })
            return c
        }
        val avgSleep = Math.round(logged.map { it.totalSleepMin!! }.average()).toInt()
        val avgEff = Math.round(logged.mapNotNull { it.efficiencyPct }.average()).toInt()
        val stats = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statBox("평균 수면시간", formatDuration(avgSleep)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(statBox("평균 수면 효율", "$avgEff%"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
        c.addView(stats, lp(12))

        // legend: two different units, two different marks
        val legend = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        legend.addView(View(activity).apply { background = activity.rounded(BAR, 3) }, LinearLayout.LayoutParams(activity.dp(10), activity.dp(10)))
        legend.addView(activity.label("총 수면시간 (왼쪽, 시간)", 10.5f, Palette.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5); rightMargin = activity.dp(14) })
        legend.addView(View(activity).apply { background = activity.rounded(LINE, 999) }, LinearLayout.LayoutParams(activity.dp(9), activity.dp(9)))
        legend.addView(activity.label("수면 효율 (오른쪽, %)", 10.5f, Palette.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
        c.addView(legend, lp(14))

        c.addView(TrendChart(activity, a.trend).apply {
            contentDescription = "최근 7일 중 ${logged.size}일 기록. 평균 수면시간 ${formatDuration(avgSleep)}, 평균 수면 효율 $avgEff%"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(190)).apply { topMargin = activity.dp(8) })
        c.addView(note("기록한 날 ${logged.size}일 / 7일 · 기록이 없는 날은 비워 두었어요.\n이 기기에 저장된 수면 일지 기준이에요."))
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
        c.addView(title(R.drawable.ic_target, "평소와 비교하기"))
        c.addView(note("평소는 나의 개인 기준선, 최근은 최근 7일 기록의 평균이에요."))

        val head = compareRowView("", "평소", "최근 7일", header = true)
        c.addView(head, lp(10))
        a.comparisons.forEach { r ->
            c.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
            val baselineText = r.baseline ?: if (a.baselineReady) "연동 준비 중" else "수집 중"
            c.addView(compareRowView(r.name, baselineText, r.recent ?: "기록 없음", status = r.status,
                baselineMuted = r.baseline == null, recentMuted = r.recent == null))
        }
        val base = when {
            !a.stateLoaded -> "개인 기준선 정보를 불러오는 중이에요."
            !a.baselineReady -> "개인 기준선 수집 중 · 유효 기록 ${a.validDays ?: 0}일" +
                "\n기준선은 팀 분석 기준에 맞는 유효 기록이 모이면 만들어져요."
            else -> "개인 기준선이 준비되었어요.\n평소 값은 분석 연동 후 보여드려요."
        }
        c.addView(note(base))
        c.addView(note("정상·주의 판정은 분석 결과가 제공될 때만 표시해요.", 10.5f))
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
        c.addView(title(R.drawable.ic_leaf, "생활 습관 살펴보기"))
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
                HabitState.NO_RECORD -> Triple("기록 없음", Palette.muted, Color.parseColor("#F3ECE7"))
                HabitState.NEEDS_LINK -> Triple("연동 필요", Palette.lavenderDeep, Palette.lavenderSoft)
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
        addView(activity.label("수면 일지 보기", 14f, Color.WHITE, bold = true),
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
    private val names = listOf("월", "화", "수", "목", "금", "토", "일")

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
