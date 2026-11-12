package com.cbnu.nabi.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cbnu.nabi.R
import com.cbnu.nabi.data.Labels
import com.cbnu.nabi.data.UserState
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Left / 수면 일지 tab (FR-03-06, FR-04), following the Figma "나의 수면 일지" screen:
 * "살펴볼 점" (risk factors, FR-04-03 — added by user decision), month calendar with record dots,
 * last 7 days of sleep, and the selected day's record.
 *
 * Records come from [SleepDiaryStore] (entries saved on this device) until the app can read
 * GET /sleep-records — the source row says so.
 */
class SleepDiaryPanel(private val activity: MainActivity) {

    private val store = SleepDiaryStore(activity, activity.prefs.email)
    private var entries: Map<LocalDate, DiaryEntry> = emptyMap()
    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var state: UserState? = null

    private val factorsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val calendarBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val chartBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val detailBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    val view: View

    init {
        val page = activity.page()
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(activity.label("나의 수면 일지", 26f, title = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // "+ 기록하기": light-lavender long pill, opens today's record sheet.
        header.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(14), 0, activity.dp(16), 0)
            background = activity.cardBg(Palette.lavenderSoft, 999, Color.parseColor("#DCCDEB"))
            addView(activity.icon(R.drawable.ic_plus, Color.parseColor("#71568D"), 16))
            addView(activity.label("기록하기", 13f, Color.parseColor("#71568D"), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
            contentDescription = "오늘 수면 기록하기"
            setOnClickListener { activity.openSleepRecord() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(38)))
        page.add(header)
        page.add(activity.label("하루하루 쌓이는 나의 수면 기록", 12f, Palette.muted).apply { setPadding(0, 0, 0, activity.dp(4)) })
        page.add(factorsBox)
        page.add(calendarBox)
        page.add(chartBox)
        page.add(detailBox)
        view = activity.scrollPage(page)
        refresh()
    }

    /** Re-read the local diary (after saving a record, on resume) and redraw. */
    fun refresh() {
        entries = store.all().associateBy { it.date }
        renderFactors()
        renderCalendar()
        renderChart()
        renderDetail()
    }

    /** The home screen loaded GET /users/me/state. */
    fun setState(s: UserState) {
        state = s
        renderFactors()
    }

    // ---------------- shared bits ----------------
    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        background = activity.cardBg(Palette.card, 20, Color.parseColor("#E6D4C8"))
        elevation = activity.dp(1).toFloat()
    }

    private fun titleRow(iconRes: Int, title: String, right: View? = null): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(activity.icon(iconRes, Palette.lavender, 20))
        row.addView(activity.label(title, 17f, title = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(7) })
        if (right != null) row.addView(right)
        return row
    }

    private fun pillButton(iconRes: Int, text: String, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
        background = activity.rounded(Palette.lavenderSoft, 11)
        addView(activity.icon(iconRes, Color.parseColor("#71568D"), 14))
        addView(activity.label(text, 11f, Color.parseColor("#71568D"), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(4) })
        setOnClickListener { onClick() }
    }

    private fun badge(text: String) = activity.label(text, 10f, Color.parseColor("#71568D"), bold = true).apply {
        setPadding(activity.dp(9), activity.dp(5), activity.dp(9), activity.dp(5))
        background = activity.rounded(Palette.lavenderSoft, 10)
    }

    // ---------------- 살펴볼 점 (risk factors, FR-04-02/03, NFR-04) ----------------
    /**
     * Only server-provided state is shown — nothing is made up:
     * - no state yet → waiting
     * - baseline not "ready" (collecting or unknown) → collecting, with progress by VALID record days
     * - ready + factors → factor tags
     * - ready + analysis ran (sleep_state known) + no factors → "no notable change"
     * - ready but recent nights too few to analyze (sleep_state UNKNOWN) → waiting
     */
    private fun renderFactors() {
        factorsBox.removeAllViews()
        val c = card()
        c.addView(titleRow(R.drawable.ic_info, "살펴볼 점"))
        val s = state
        when {
            s == null -> c.addView(note("분석 결과를 기다리고 있어요."))
            s.baselineStatus != "ready" -> renderCollecting(c, s.baselineValidDays)
            s.riskFactors.isNotEmpty() -> {
                val chips = FlowRow()
                s.riskFactors.forEach { chips.addView(badge(factorLabel(it))) }
                c.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = activity.dp(10) })
                c.addView(note("평소(개인 기준선)와 비교한 결과예요. 의료 진단이 아니에요.", 10f))
            }
            s.sleepState != "UNKNOWN" -> c.addView(note("최근 수면 패턴에서 눈에 띄는 변화가 없어요."))
            else -> c.addView(note("최근 기록이 더 쌓이면 평소 수면 패턴과 비교해 알려드릴게요."))
        }
        factorsBox.addView(c)
    }

    private fun note(text: String, size: Float = 12f) = activity.label(text, size, Palette.muted).apply {
        setPadding(0, activity.dp(8), 0, 0); setLineSpacing(activity.dp(2).toFloat(), 1f)
    }

    /** Baseline still being built: no tags, explain + a small progress bar of valid record days. */
    private fun renderCollecting(c: LinearLayout, validDays: Int) {
        c.addView(activity.label("나의 수면 패턴을 알아가는 중이에요.", 13.5f, bold = true).apply { setPadding(0, activity.dp(10), 0, 0) })
        c.addView(activity.label("개인 기준선 수집 중 · 유효 기록 ${validDays}일", 11.5f, Color.parseColor("#71568D"), bold = true)
            .apply { setPadding(0, activity.dp(6), 0, 0) })

        // Progress counts VALID records (server baseline_valid_days), not days since sign-up.
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val track = FrameLayout(activity).apply { background = activity.rounded(Palette.lavenderSoft, 999) }
        val fill = View(activity).apply { background = activity.rounded(Palette.lavender, 999) }
        val ratio = (validDays.toFloat() / BASELINE_REQUIRED_VALID_DAYS).coerceIn(0f, 1f)
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        track.post { fill.layoutParams = fill.layoutParams.apply { width = (track.width * ratio).toInt() } }
        row.addView(track, LinearLayout.LayoutParams(0, activity.dp(7), 1f))
        row.addView(activity.label("${validDays.coerceAtMost(BASELINE_REQUIRED_VALID_DAYS)} / ${BASELINE_REQUIRED_VALID_DAYS}일", 10.5f, Palette.muted, bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(8) })
        row.contentDescription = "유효 기록 ${validDays}일, 기준선에 필요한 ${BASELINE_REQUIRED_VALID_DAYS}일 중"
        c.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(10) })

        c.addView(note("기록이 충분히 쌓이면 평소 수면 패턴과 비교해 알려드릴게요.", 11f))
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
        else -> Labels.factor(code)
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

    // ---------------- calendar ----------------
    private fun renderCalendar() {
        calendarBox.removeAllViews()
        val c = card()
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(navButton(R.drawable.ic_chevron_left, "이전 달") { changeMonth(-1) })
        head.addView(activity.label("${month.year}년 ${month.monthValue}월", 18f, title = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(navButton(R.drawable.ic_chevron_right, "다음 달") { changeMonth(1) })
        c.addView(head)

        val week = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, activity.dp(10), 0, activity.dp(6)) }
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
            val color = when (i) { 0 -> Color.parseColor("#D9645A"); 6 -> Color.parseColor("#7A63B0"); else -> Palette.muted }
            week.addView(activity.label(d, 11f, color, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(week)
        c.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EADDD3")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))

        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // Sunday first
        val days = month.lengthOfMonth()
        val today = LocalDate.now()
        var day = 1 - lead
        while (day <= days) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 7) {
                val cell = FrameLayout(activity)
                if (day in 1..days) {
                    val date = month.atDay(day)
                    val isSel = date == selected
                    val inner = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        if (isSel) background = activity.rounded(Color.parseColor("#E7DCF2"), 10)
                    }
                    inner.addView(activity.label(day.toString(), 12f, if (date.isAfter(today)) Color.parseColor("#B8A99F") else Palette.ink,
                        bold = isSel || date == today).apply { gravity = Gravity.CENTER })
                    inner.addView(View(activity).apply {
                        background = activity.rounded(Palette.lavender, 3)
                        visibility = if (entries.containsKey(date)) View.VISIBLE else View.INVISIBLE
                    }, LinearLayout.LayoutParams(activity.dp(5), activity.dp(5)).apply { topMargin = activity.dp(2); gravity = Gravity.CENTER_HORIZONTAL })
                    cell.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(activity.dp(3), activity.dp(2), activity.dp(3), activity.dp(2))
                    })
                    cell.contentDescription = "${month.monthValue}월 ${day}일" + if (entries.containsKey(date)) ", 수면 기록 있음" else ""
                    cell.setOnClickListener { selected = date; renderCalendar(); renderDetail(); revealDetail() }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, activity.dp(40), 1f))
                day++
            }
            c.addView(row)
        }

        val legend = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0) }
        legend.addView(View(activity).apply { background = activity.rounded(Palette.lavender, 3) }, LinearLayout.LayoutParams(activity.dp(6), activity.dp(6)))
        legend.addView(activity.label("수면 기록 있음", 10f, Palette.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
        c.addView(legend)
        calendarBox.addView(c)
    }

    private fun navButton(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(activity).apply {
        background = activity.rounded(Palette.lavenderSoft, 10)
        addView(activity.icon(res, Palette.lavenderDeep, 19), FrameLayout.LayoutParams(activity.dp(19), activity.dp(19), Gravity.CENTER))
        contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(activity.dp(34), activity.dp(34))
    }

    private fun changeMonth(offset: Long) {
        month = month.plusMonths(offset)
        selected = month.atDay(1)
        renderCalendar()
        renderDetail()
    }

    // ---------------- last 7 days ----------------
    private fun renderChart() {
        chartBox.removeAllViews()
        val today = LocalDate.now()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val minutes = days.map { entries[it]?.totalSleepMin }
        val values = minutes.filterNotNull()
        val avg = if (values.isEmpty()) null else values.sum() / values.size

        val c = card()
        c.addView(titleRow(R.drawable.ic_chart, "최근 7일 수면시간", badge(if (avg == null) "기록 없음" else "평균 ${formatDuration(avg)}")))
        val bars = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, activity.dp(12), 0, 0) }
        val trackH = activity.dp(84)
        days.forEachIndexed { i, d ->
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val m = minutes[i]
            col.addView(activity.label(if (m == null) "—" else formatDuration(m), 8.5f, Palette.muted).apply {
                gravity = Gravity.CENTER; maxLines = 1
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val track = FrameLayout(activity)
            track.addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EADDD3")) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1), Gravity.BOTTOM))
            if (m != null) {
                val h = (trackH * (m / 600f).coerceIn(0.08f, 1f)).toInt()
                track.addView(View(activity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.parseColor("#C9B5E6"), Color.parseColor("#A991CF"))).apply {
                        val r = activity.dp(8).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    }
                }, FrameLayout.LayoutParams(activity.dp(26), h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            }
            col.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, trackH).apply { topMargin = activity.dp(4) })
            col.addView(activity.label(listOf("월", "화", "수", "목", "금", "토", "일")[d.dayOfWeek.value - 1], 11f,
                bold = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(6), 0, 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bars.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(bars)
        chartBox.addView(c)
    }

    /** After picking a date, scroll so the selected day's card (empty + 기록하기, or detail + 수정) is visible. */
    private fun revealDetail() {
        val scroll = view as? android.widget.ScrollView ?: return
        detailBox.post {
            val bottom = detailBox.bottom + scroll.paddingBottom
            val target = (bottom - scroll.height).coerceAtLeast(0)
            if (target > scroll.scrollY) scroll.smoothScrollTo(0, target)
        }
    }

    // ---------------- selected day ----------------
    private fun renderDetail() {
        detailBox.removeAllViews()
        val e = entries[selected]
        val title = "${selected.monthValue}월 ${selected.dayOfMonth}일 수면 기록"
        val future = selected.isAfter(LocalDate.now())
        val c = card()
        if (e == null) {
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(activity.icon(R.drawable.ic_calendar, Palette.lavender, 28))
            c.addView(activity.label(title, 16f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0) })
            c.addView(activity.label(if (future) "아직 오지 않은 날이에요." else "이 날짜에는 저장된 수면 기록이 없어요.", 11.5f, Palette.muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(6), 0, activity.dp(10)) })
            if (!future) c.addView(pillButton(R.drawable.ic_edit, "기록하기") { activity.openSleepRecord(selected) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            detailBox.addView(c)
            return
        }

        c.addView(titleRow(R.drawable.ic_calendar, title, pillButton(R.drawable.ic_edit, "수정") { activity.openSleepRecord(selected) }))
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, activity.dp(10), 0, 0) }
        val bed = SleepRecordSheet.format12(LocalTime.parse(e.bedtime))
        val wake = SleepRecordSheet.format12(LocalTime.parse(e.wakeTime))
        grid.addView(metricRow("취침 시각" to bed, "기상 시각" to wake))
        grid.addView(metricRow("총 수면시간" to formatDuration(e.totalSleepMin), "수면효율" to "${e.efficiencyPct}%"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        c.addView(grid)

        // "생활 습관과 상세 지표 보기" — collapsible like the Figma <details>
        val more = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, activity.dp(4), 0, 0) }
        fun yn(b: Boolean) = if (b) "예" else "아니요"
        more.addView(detailLine("잠들기까지 걸린 시간", "${e.latencyMin}분"))
        more.addView(detailLine("밤중 각성", if (e.awakenings == null && e.awakeMinutes == null) "데이터 없음"
            else "${e.awakenings ?: 0}회 · ${e.awakeMinutes ?: 0}분"))
        more.addView(detailLine("수면 만족도", SleepRecordSheet.SATISFACTIONS[e.satisfaction - 1]))
        more.addView(detailLine("낮잠 / 카페인 / 운동", "${yn(e.nap)} · ${yn(e.caffeine)} · ${yn(e.exercise)}"))
        val toggle = activity.label("▸ 생활 습관과 상세 지표 보기", 11.5f, Color.parseColor("#71568D"), bold = true).apply {
            setPadding(0, activity.dp(12), 0, activity.dp(4))
        }
        toggle.setOnClickListener {
            val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = (if (open) "▾ " else "▸ ") + "생활 습관과 상세 지표 보기"
        }
        c.addView(hairline())
        c.addView(toggle)
        c.addView(more)
        c.addView(hairline())

        val src = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, activity.dp(10), 0, 0) }
        src.addView(activity.icon(R.drawable.ic_source, Palette.lavender, 20))
        src.addView(activity.label("데이터 출처", 11f, Palette.muted),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(6) })
        src.addView(activity.label("사용자 입력 · 이 기기에서 저장", 11f, bold = true))
        c.addView(src)
        detailBox.addView(c)
    }

    private fun metricRow(a: Pair<String, String>, b: Pair<String, String>) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(metricBox(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metricBox(b), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
    }

    private fun metricBox(p: Pair<String, String>) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
        background = activity.rounded(Color.parseColor("#F6EFF8"), 12)
        addView(activity.label(p.first, 10f, Palette.muted))
        addView(activity.label(p.second, 15f).apply { setPadding(0, activity.dp(6), 0, 0) })
    }

    private fun detailLine(name: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, activity.dp(6), 0, activity.dp(6))
        addView(activity.label(name, 11.5f, Palette.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(activity.label(value, 11.5f, bold = true))
    }

    private fun hairline(): View = View(activity).apply {
        setBackgroundColor(Color.parseColor("#EADDD3"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply { topMargin = activity.dp(10) }
    }

    companion object {
        /**
         * Valid record days the server needs for a baseline. Mirrors the backend default
         * (core/config.py baseline_min_valid_days = 10). TODO: read `required_valid_days` from
         * GET /analysis/baseline once the app has that API call (API teammate).
         */
        const val BASELINE_REQUIRED_VALID_DAYS = 10
    }
}
