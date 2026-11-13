package com.cbnu.nabi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.cbnu.nabi.data.TrendMetric
import com.cbnu.nabi.data.Trends
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * "전체 수면 분석" under the sleep diary: one line chart per analysed metric (GET /analysis/trends).
 * The shaded band, the dashed 평소 line and the status of the last point all come from the server's
 * analysis (personal baseline median/MAD, |robust z| < 2), so the chart never disagrees with it.
 */

/** Names and value formats for the server's metric ids. */
object TrendFormat {
    fun name(m: String) = when (m) {
        "bedtime" -> "취침 시각"
        "sleep_onset_latency" -> "잠들기까지 걸린 시간"
        "sleep_duration" -> "총 수면시간"
        "sleep_efficiency" -> "수면 효율"
        "wake_after_sleep_onset" -> "밤중 깨어 있던 시간"
        "screen_before_bed_min" -> "취침 전 스마트폰 사용"
        "nap_duration_min" -> "낮잠 시간"
        "exercise_duration_min" -> "운동 시간"
        else -> m
    }

    private fun anchorMin(t: TrendMetric) = t.anchor?.let { LocalTime.parse(it).toSecondOfDay() / 60 } ?: 0

    private fun clock(t: TrendMetric, v: Double): LocalTime {
        val m = ((anchorMin(t) + v.roundToInt()) % 1440 + 1440) % 1440
        return LocalTime.of(m / 60, m % 60)
    }

    /** Full value for headers and labels: "오후 11:03", "18분", "7시간 35분", "94%". */
    fun value(t: TrendMetric, v: Double): String = when {
        t.unit == "clock" -> Modals.format12(clock(t, v))
        t.unit == "ratio" -> "${(v * 100).roundToInt()}%"
        t.metric == "sleep_duration" -> formatDuration(v.roundToInt())
        else -> "${v.roundToInt()}분"
    }

    /** Short value for the y axis: "23:03", "18", "7:35", "94%". */
    fun axis(t: TrendMetric, v: Double): String = when {
        t.unit == "clock" -> clock(t, v).let { "%02d:%02d".format(it.hour, it.minute) }
        t.unit == "ratio" -> "${(v * 100).roundToInt()}%"
        t.metric == "sleep_duration" -> v.roundToInt().let { "${it / 60}:%02d".format(it % 60) }
        else -> "${v.roundToInt()}"
    }

    /** Status chip (text, text color, background) from the server's judgement of the last point. */
    fun status(t: TrendMetric): Triple<String, Int, Int>? = when (t.latestStatus) {
        "changed" -> Triple(if (t.domain == "sleep") "평소와 달라요" else "주의 필요", Color.parseColor("#B4583C"), Color.parseColor("#FBE3DA"))
        "improved" -> Triple("좋아졌어요", Color.parseColor("#3E7F5A"), Color.parseColor("#DDF0E4"))
        "normal" -> Triple(if (t.domain == "sleep") "평소 범위" else "기준 이내", Palette.lavenderDeep, Palette.lavenderSoft)
        "unknown" -> Triple("기준선 수집 중", Palette.muted, Color.parseColor("#F1E6DD"))
        else -> null
    }

    /** The engine's reference rule for behavior metrics (risk_service BEHAVIOR_RULES). */
    fun referenceNote(t: TrendMetric): String? {
        val r = t.reference?.roundToInt() ?: return null
        return when (t.metric) {
            "exercise_duration_min" -> "기준선: ${r}분 미만이면 주의"
            else -> "기준선: ${r}분 이상이면 주의"
        }
    }

    fun statusColor(status: String?): Int = when (status) {
        "changed" -> Color.parseColor("#E07A5C")
        "improved" -> Color.parseColor("#4FA272")
        "unknown" -> Palette.muted
        else -> Color.parseColor("#7E67B5")
    }
}

/**
 * One metric over [start]..[end]: points by date (gaps where nothing was recorded), the usual-range
 * band and dashed median (sleep) or the dashed reference line (behavior), the last point highlighted.
 */
@SuppressLint("ViewConstructor")
class TrendChartView(context: Context, private val t: TrendMetric, private val start: LocalDate, private val end: LocalDate) : View(context) {
    private val d = resources.displayMetrics.density
    private fun px(v: Float) = v * d

    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.muted; textSize = px(9.5f) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px(10f); isFakeBoldText = true }
    private val grid = Paint().apply { color = Color.parseColor("#EFE3DA"); strokeWidth = px(1f) }
    private val bandPaint = Paint().apply { color = Color.argb(70, 169, 145, 207) }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = px(1.4f); pathEffect = DashPathEffect(floatArrayOf(px(5f), px(4f)), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = px(2f); color = Color.parseColor("#7E67B5"); strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7E67B5") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        contentDescription = TrendFormat.name(t.metric) + " 추이 그래프"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), px(150f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = px(40f); val right = width - px(62f); val top = px(14f); val bottom = height - px(22f)
        val values = t.points.map { it.value } + listOfNotNull(t.band?.low, t.band?.high, t.reference)
        if (values.isEmpty()) return
        var lo = values.min(); var hi = values.max()
        val minSpan = when (t.unit) { "ratio" -> 0.05; "clock" -> 30.0; else -> 10.0 }
        if (hi - lo < minSpan) { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = mid + minSpan / 2 }
        val pad = (hi - lo) * 0.12; lo -= pad; hi += pad
        if (t.unit != "clock") lo = max(lo, 0.0)
        if (t.unit == "ratio") hi = min(hi, 1.0)
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        val span = max(1L, ChronoUnit.DAYS.between(start, end)).toFloat()
        fun x(date: LocalDate) = left + ChronoUnit.DAYS.between(start, date) / span * (right - left)

        // y axis: 3 ticks with light grid lines
        for (i in 0..2) {
            val v = lo + (hi - lo) * i / 2
            val yy = y(v)
            c.drawLine(left, yy, right, yy, grid)
            val s = TrendFormat.axis(t, v)
            c.drawText(s, left - px(5f) - axisText.measureText(s), yy + px(3.5f), axisText)
        }

        // usual range (personal baseline) + dashed median, or the reference threshold
        t.band?.let { b ->
            c.drawRect(left, y(b.high), right, y(b.low), bandPaint)
            dashed.color = Color.parseColor("#8F78BD")
            c.drawLine(left, y(b.median), right, y(b.median), dashed)
            sideLabel(c, right, y(b.median), "평소", TrendFormat.axis(t, b.median), Color.parseColor("#6E58A0"))
        }
        t.reference?.let { r ->
            dashed.color = Color.parseColor("#E07A5C")
            c.drawLine(left, y(r), right, y(r), dashed)
            sideLabel(c, right, y(r), "기준", TrendFormat.axis(t, r) + "분", Color.parseColor("#B4583C"))
        }

        // the line, then the points; the last point is larger and colored by the server's status
        val pts = t.points.map { x(it.date) to y(it.value) }
        if (pts.size > 1) {
            val path = Path().apply { moveTo(pts[0].first, pts[0].second); pts.drop(1).forEach { lineTo(it.first, it.second) } }
            c.drawPath(path, linePaint)
        }
        pts.dropLast(1).forEach { (px0, py0) -> c.drawCircle(px0, py0, px(3f), dot) }
        pts.lastOrNull()?.let { (lx, ly) ->
            val color = TrendFormat.statusColor(t.latestStatus)
            c.drawCircle(lx, ly, px(6.5f), ring)
            c.drawCircle(lx, ly, px(5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            labelText.color = color
            val s = TrendFormat.axis(t, t.points.last().value) + if (t.unit == "minutes" && t.metric != "sleep_duration") "분" else ""
            // Left of the point, so it never collides with the 평소/기준 labels on the right.
            val tx = max(left, lx - labelText.measureText(s) - px(9f))
            val ty = if (ly - px(8f) < top + px(4f)) ly + px(16f) else ly - px(8f)
            c.drawText(s, tx, ty, labelText)
        }

        // x axis: first day … last day
        val fmt = { day: LocalDate -> "${day.monthValue}/${day.dayOfMonth}" }
        c.drawText(fmt(start), left, height - px(6f), axisText)
        val endLabel = "${fmt(end)} (최근)"
        c.drawText(endLabel, right - axisText.measureText(endLabel), height - px(6f), axisText)
    }

    private fun sideLabel(c: Canvas, right: Float, yy: Float, title: String, value: String, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = px(9.5f); isFakeBoldText = true }
        c.drawText(title, right + px(6f), yy - px(2f), p)
        p.isFakeBoldText = false
        c.drawText(value, right + px(6f), yy + px(10f), p)
    }
}

/** The "전체 수면 분석" card: period chips (7/14/30일) and one chart per metric. */
class SleepTrendSection(private val activity: MainActivity) {
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        background = activity.cardBg(Palette.card, 20, Color.parseColor("#E6D4C8"))
        elevation = activity.dp(1).toFloat()
    }
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val chips = mutableMapOf<Int, android.widget.TextView>()
    private var days = 14
    private var data: Trends? = null
    private var loading = false

    init {
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.icon(com.cbnu.nabi.R.drawable.ic_chart, Palette.lavender, 20))
        head.addView(activity.label("전체 수면 분석", 17f, title = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(7) })
        for (d in listOf(7, 14, 30)) {
            val chip = activity.label("${d}일", 11f, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(activity.dp(9), activity.dp(4), activity.dp(9), activity.dp(4))
                contentDescription = "최근 ${d}일 보기"
                setOnClickListener { if (days != d) { days = d; styleChips(); load() } }
            }
            chips[d] = chip
            head.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = activity.dp(4) })
        }
        view.addView(head)
        view.addView(body)
        styleChips()
        render()
    }

    private fun styleChips() = chips.forEach { (d, v) ->
        val on = d == days
        v.background = if (on) activity.rounded(Color.parseColor("#AA8BCC"), 10) else activity.rounded(Palette.lavenderSoft, 10)
        v.setTextColor(if (on) Color.WHITE else Palette.lavenderDeep)
    }

    /** Fetch GET /analysis/trends for the chosen period and redraw. */
    fun load() {
        if (loading || !activity.prefs.loggedIn) return
        loading = true
        val asked = days
        activity.bg({ activity.api.trends(asked) }) { r ->
            loading = false
            r.onSuccess { data = it; render() }.onFailure { render(it.friendly()) }
            if (asked != days) load() // the period changed while loading
        }
    }

    private fun note(text: String, size: Float = 11f) = activity.label(text, size, Palette.muted).apply {
        setPadding(0, activity.dp(6), 0, 0); setLineSpacing(activity.dp(2).toFloat(), 1f)
    }

    private fun render(error: String? = null) {
        body.removeAllViews()
        val t = data
        if (t == null) {
            body.addView(note(error?.let { "분석을 불러오지 못했어요. ($it)" } ?: "분석을 불러오는 중이에요…"))
            return
        }
        val start = t.startDate; val end = t.endDate
        if (start == null || end == null || t.metrics.all { it.points.isEmpty() }) {
            body.addView(note("아직 분석할 기록이 없어요. 수면 일지를 기록하면 여기에서 변화를 보여드려요."))
            return
        }
        body.addView(note(
            (if (t.baselineReady) "음영은 나의 평소 범위(개인 기준선), 점선은 평소 값이에요."
            else "개인 기준선을 만드는 중이라 평소 범위는 아직 표시되지 않아요.") +
                "\n날짜는 잠든 밤 기준 · 마지막 점이 가장 최근 기록이에요."))
        val missing = mutableListOf<String>()
        for (m in t.metrics) {
            if (m.points.isEmpty()) { missing += TrendFormat.name(m.metric); continue }
            body.addView(metricBlock(m, start, end))
        }
        if (missing.isNotEmpty()) body.addView(note("이 기간에 기록 없음: ${missing.joinToString(", ")}", 10.5f))
    }

    private fun metricBlock(m: TrendMetric, start: LocalDate, end: LocalDate): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, activity.dp(14), 0, 0) }
        box.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply { bottomMargin = activity.dp(10) })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        names.addView(activity.label(TrendFormat.name(m.metric), 13f, bold = true))
        val last = m.points.last()
        val sub = "최근 ${TrendFormat.value(m, last.value)}" + (m.band?.let { " · 평소 ${TrendFormat.value(m, it.median)}" } ?: "")
        names.addView(activity.label(sub, 10.5f, Palette.muted))
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        TrendFormat.status(m)?.let { (text, fg, bg) ->
            row.addView(activity.label(text, 10f, fg, bold = true).apply {
                setPadding(activity.dp(8), activity.dp(3), activity.dp(8), activity.dp(3))
                background = activity.rounded(bg, 10)
            })
        }
        box.addView(row)
        box.addView(TrendChartView(activity, m, start, end), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(6) })
        TrendFormat.referenceNote(m)?.let { box.addView(note(it, 10f)) }
        if (abs(ChronoUnit.DAYS.between(last.date, end)) > 0) box.addView(note("마지막 기록: ${last.date.monthValue}/${last.date.dayOfMonth}", 10f))
        return box
    }
}
