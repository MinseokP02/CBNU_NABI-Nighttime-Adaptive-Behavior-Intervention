package com.cbnu.nabi.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cbnu.nabi.R
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

    private companion object {
        /** Mid lavender: soft but still clearly readable on the cream page. */
        val LINK: Int = Color.parseColor("#8467A8")
    }

    private val store = SleepDiaryStore(activity, activity.prefs.email)
    private var entries: Map<LocalDate, DiaryEntry> = emptyMap()
    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()

    private val calendarBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val detailBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    val view: View

    /** "+ 기록하기" (the tutorial points at it). */
    val recordButton = LinearLayout(activity)

    init {
        val page = activity.page()
        // Row 1: title + "+ 기록하기" (vertically centred on the title).
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(activity.label("나의 수면 일지", 26f, title = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(recordButton.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(11), 0, activity.dp(13), 0)
            background = activity.cardBg(Palette.lavenderSoft, 999, Color.parseColor("#DCCDEB"))
            addView(activity.icon(R.drawable.ic_plus, Color.parseColor("#71568D"), 14))
            addView(activity.label("기록하기", 12.5f, Color.parseColor("#71568D"), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
            contentDescription = "오늘 수면 기록하기"
            setOnClickListener { activity.openSleepRecord() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(30)))
        page.add(titleRow)

        // Row 2: subtitle (a little closer to the title) + "수면 분석 보기 ›", right edges aligned with row 1.
        val subRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        subRow.addView(activity.label("하루하루 쌓이는 나의 수면 기록", 12f, Palette.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        subRow.addView(analysisLink())
        val sub = page.add(subRow)
        (sub.layoutParams as LinearLayout.LayoutParams).topMargin = activity.dp(2)
        page.add(calendarBox)
        page.add(detailBox)
        view = activity.scrollPage(page)
        refresh()
    }

    /** Re-read the local diary (after saving a record, on resume) and redraw. */
    fun refresh() {
        entries = store.all().associateBy { it.date }
        renderCalendar()
        renderDetail()
    }

    /** Opened from the home analysis card: jump back to this month and today's record. */
    fun showToday() {
        month = YearMonth.now()
        selected = LocalDate.now()
        refresh()
        (view as? android.widget.ScrollView)?.scrollTo(0, 0)
    }

    /**
     * "수면 분석 보기 ›" → the 나의 수면 분석 sheet. A plain text link with a small chart icon
     * (no background or border); the padding gives it a comfortable touch area.
     */
    private fun analysisLink() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(12), activity.dp(10), activity.dp(2), activity.dp(10))
        addView(activity.icon(R.drawable.ic_chart, LINK, 13),
            LinearLayout.LayoutParams(activity.dp(13), activity.dp(13)).apply { rightMargin = activity.dp(5) })
        addView(activity.label("수면 분석 보기", 12f, LINK, bold = true))
        addView(activity.icon(R.drawable.ic_chevron_right, LINK, 14),
            LinearLayout.LayoutParams(activity.dp(14), activity.dp(14)).apply { leftMargin = activity.dp(2) })
        contentDescription = "수면 분석 보기"
        setOnClickListener { activity.openAnalysis() }
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

        // Sits right under the last week row (that row already has its own breathing room).
        val legend = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, activity.dp(2), 0, 0) }
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
        src.addView(activity.label("데이터 출처", 11f, Palette.muted).apply { maxLines = 1 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(6) })
        src.addView(activity.label("사용자 입력 · 이 기기에서 저장", 11f, bold = true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
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

}
