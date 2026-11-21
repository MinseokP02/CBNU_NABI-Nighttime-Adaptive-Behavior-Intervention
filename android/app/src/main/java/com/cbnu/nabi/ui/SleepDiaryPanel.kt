package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
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
 * Left / 수면 일지 tab (FR-03-06, FR-04). Two pages side by side in a [PagerView]:
 * [전체 수면 분석] on the LEFT (trend charts, link to the detailed analysis sheet) and
 * [나의 수면 일지] on the right (the default). Swipe or use the switch at the top.
 *
 * The diary page follows the Figma "나의 수면 일지" screen:
 * "살펴볼 점" (risk factors, FR-04-03 — added by user decision), month calendar with record dots,
 * last 7 days of sleep, and the selected day's record.
 *
 * Records come from [SleepDiaryStore]: entries saved on this device plus the server's records
 * (GET /sleep-records, GET /behavior-records) — the source row says which.
 */
class SleepDiaryPanel(private val activity: MainActivity) {

    private companion object {
        /** Mid lavender: soft but still clearly readable on the cream page. */
        val LINK: Int = Color.parseColor("#8467A8")
        const val PAGE_ANALYSIS = 0
        const val PAGE_DIARY = 1
    }

    private val store = SleepDiaryStore(activity, activity.prefs.email)
    private var entries: Map<LocalDate, DiaryEntry> = emptyMap()
    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()

    private val calendarBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val detailBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    /** "전체 수면 분석": trend charts from GET /analysis/trends. */
    private val trends = SleepTrendSection(activity)

    /** The whole tab: switch on top, pager below. */
    val view: View

    private val pager = PagerView(activity)
    private lateinit var analysisScroll: android.widget.ScrollView
    private lateinit var diaryScroll: android.widget.ScrollView
    private val switchItems = mutableListOf<android.widget.TextView>()

    /** "+ 기록하기" (the tutorial points at it). */
    val recordButton = LinearLayout(activity)

    init {
        val page = activity.page()
        // Row 1: title + "+ 기록하기" (vertically centred on the title).
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(activity.label(tr(R.string.my_sleep_diary), 26f, title = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(recordButton.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(11), 0, activity.dp(13), 0)
            background = activity.cardBg(Palette.lavenderSoft, 999, Color.parseColor("#DCCDEB"))
            addView(activity.icon(R.drawable.ic_plus, Color.parseColor("#71568D"), 14))
            addView(activity.label(tr(R.string.log_sleep), 12.5f, Color.parseColor("#71568D"), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(5) })
            contentDescription = tr(R.string.log_today_s_sleep)
            setOnClickListener { activity.openSleepRecord() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(30)))
        page.add(titleRow)

        // Row 2: subtitle (a little closer to the title) + "수면 분석 보기 ›", right edges aligned with row 1.
        val subRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        subRow.addView(activity.label(tr(R.string.your_sleep_day_by_day), 12f, Palette.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        subRow.addView(analysisLink())
        val sub = page.add(subRow)
        (sub.layoutParams as LinearLayout.LayoutParams).topMargin = activity.dp(2)
        page.add(calendarBox)
        page.add(detailBox)
        diaryScroll = activity.scrollPage(page)
        analysisScroll = activity.scrollPage(analysisPage())

        pager.addView(analysisScroll)
        pager.addView(diaryScroll)
        pager.show(PAGE_DIARY, animate = false)
        pager.onPageChanged = { styleSwitch() }
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pageSwitch(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(activity.dp(24), activity.dp(14), activity.dp(24), 0) })
            addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        styleSwitch()
        refresh()
    }

    /** Phone: room at the bottom of each page so the floating tab bar never covers the end. */
    fun setBottomInset(px: Int) {
        for (s in listOf(analysisScroll, diaryScroll)) {
            s.setPadding(0, 0, 0, px)
            s.clipToPadding = false
        }
    }

    /** Show [전체 수면 분석] (left page). */
    fun showAnalysisPage() = pager.show(PAGE_ANALYSIS)

    /** Show [나의 수면 일지] (right page). */
    fun showDiaryPage(animate: Boolean = true) = pager.show(PAGE_DIARY, animate)

    // ---------------- page switch + 전체 수면 분석 page ----------------
    private fun pageSwitch(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
            background = activity.cardBg(Color.parseColor("#F3ECF7"), 999, Color.parseColor("#E2D5EE"))
        }
        listOf(tr(R.string.sleep_overview) to PAGE_ANALYSIS, tr(R.string.my_sleep_diary) to PAGE_DIARY).forEach { (text, index) ->
            val item = activity.label(text, 12.5f, bold = true).apply {
                gravity = Gravity.CENTER
                contentDescription = tr(R.string.view, text)
                setOnClickListener { pager.show(index) }
            }
            switchItems.add(item)
            box.addView(item, LinearLayout.LayoutParams(0, activity.dp(34), 1f))
        }
        return box
    }

    private fun styleSwitch() = switchItems.forEachIndexed { i, v ->
        val on = i == pager.page
        v.background = if (on) activity.rounded(Color.parseColor("#9D7BC4"), 999) else null
        v.setTextColor(if (on) Color.WHITE else Color.parseColor("#71568D"))
    }

    private fun analysisPage(): Column {
        val page = activity.page()
        page.add(activity.label(tr(R.string.sleep_overview), 26f, title = true))
        val sub = page.add(activity.label(tr(R.string.see_how_your_sleep_changes_over), 12f, Palette.muted))
        (sub.layoutParams as LinearLayout.LayoutParams).topMargin = activity.dp(6)
        page.add(trends.view)
        page.add(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = activity.cardBg(Palette.lavenderSoft, 16, Color.parseColor("#DCCDEB"))
            addView(activity.icon(R.drawable.ic_chart, Color.parseColor("#71568D"), 16))
            addView(activity.label(tr(R.string.details_current_status_vs_usual_habits), 12.5f, Color.parseColor("#71568D"), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(6) })
            contentDescription = tr(R.string.see_my_sleep_analysis_in_detail)
            setOnClickListener { activity.openAnalysis() }
        })
        page.add(activity.label(tr(R.string.back_to_my_sleep_diary), 11.5f, LINK, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showDiaryPage() }
        })
        return page
    }

    /** Redraw from what is cached now (after saving a record, on resume). [HomePanel.refresh] syncs the server copy. */
    fun refresh() {
        entries = store.all().associateBy { it.date }
        renderCalendar()
        renderDetail()
        trends.load()
    }

    /** Opened from the home analysis card: jump back to this month and today's record. */
    fun showToday() {
        month = YearMonth.now()
        selected = LocalDate.now()
        refresh()
        showDiaryPage(animate = false)
        diaryScroll.scrollTo(0, 0)
    }

    /**
     * "‹ 전체 수면 분석" → slides to the analysis page on the left. A plain text link with a
     * small chart icon (no background or border); the padding gives it a comfortable touch area.
     */
    private fun analysisLink() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(12), activity.dp(10), activity.dp(2), activity.dp(10))
        addView(activity.icon(R.drawable.ic_chevron_left, LINK, 14),
            LinearLayout.LayoutParams(activity.dp(14), activity.dp(14)).apply { rightMargin = activity.dp(2) })
        addView(activity.icon(R.drawable.ic_chart, LINK, 13),
            LinearLayout.LayoutParams(activity.dp(13), activity.dp(13)).apply { rightMargin = activity.dp(5) })
        addView(activity.label(tr(R.string.sleep_overview), 12f, LINK, bold = true))
        contentDescription = tr(R.string.view_sleep_overview)
        setOnClickListener { showAnalysisPage() }
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
        head.addView(navButton(R.drawable.ic_chevron_left, tr(R.string.previous_month)) { changeMonth(-1) })
        head.addView(activity.label(tr(R.string.text_479, month.year, month.monthValue), 18f, title = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(navButton(R.drawable.ic_chevron_right, tr(R.string.next_month)) { changeMonth(1) })
        c.addView(head)

        val week = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, activity.dp(10), 0, activity.dp(6)) }
        listOf(tr(R.string.sun), tr(R.string.mon), tr(R.string.tue), tr(R.string.wed), tr(R.string.thu), tr(R.string.fri), tr(R.string.sat)).forEachIndexed { i, d ->
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
                    cell.contentDescription = tr(R.string.text_481, month.monthValue, day) + if (entries.containsKey(date)) tr(R.string.has_sleep_record_2) else ""
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
        legend.addView(activity.label(tr(R.string.has_sleep_record), 10f, Palette.muted),
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
        val scroll = diaryScroll
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
        val title = tr(R.string.sleep_record_for, selected.monthValue, selected.dayOfMonth)
        val future = selected.isAfter(LocalDate.now())
        val c = card()
        if (e == null) {
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(activity.icon(R.drawable.ic_calendar, Palette.lavender, 28))
            c.addView(activity.label(title, 16f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0) })
            c.addView(activity.label(if (future) tr(R.string.this_day_hasn_t_come_yet) else tr(R.string.no_sleep_record_saved_for_this), 11.5f, Palette.muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(6), 0, activity.dp(10)) })
            if (!future) c.addView(pillButton(R.drawable.ic_edit, tr(R.string.log_sleep)) { activity.openSleepRecord(selected) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            detailBox.addView(c)
            return
        }

        c.addView(titleRow(R.drawable.ic_calendar, title, pillButton(R.drawable.ic_edit, tr(R.string.edit)) { activity.openSleepRecord(selected) }))
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, activity.dp(10), 0, 0) }
        val bed = SleepRecordSheet.format12(LocalTime.parse(e.bedtime))
        val wake = SleepRecordSheet.format12(LocalTime.parse(e.wakeTime))
        grid.addView(metricRow(tr(R.string.bedtime) to bed, tr(R.string.wake_time) to wake))
        grid.addView(metricRow(tr(R.string.total_sleep) to formatDuration(e.totalSleepMin), tr(R.string.sleep_efficiency_2) to "${e.efficiencyPct}%"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        c.addView(grid)

        // "생활 습관과 상세 지표 보기" — collapsible like the Figma <details>
        val more = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, activity.dp(4), 0, 0) }
        fun minutes(b: Boolean?, min: Int?) = when (b) {
            null -> tr(R.string.no_record); false -> tr(R.string.no); true -> min?.let { tr(R.string.yes_min, it) } ?: tr(R.string.yes)
        }
        more.addView(detailLine(tr(R.string.time_to_fall_asleep), tr(R.string.min_2, e.latencyMin)))
        more.addView(detailLine(tr(R.string.night_waking), when {
            e.awakenings == null && e.awakeMinutes == null -> tr(R.string.no_data)
            e.awakenings == null -> tr(R.string.min_2, e.awakeMinutes)
            else -> tr(R.string.times_min, e.awakenings, e.awakeMinutes ?: 0)
        }))
        more.addView(detailLine(tr(R.string.sleep_satisfaction), e.satisfaction?.let { SleepRecordSheet.SATISFACTIONS[it - 1] } ?: tr(R.string.no_record)))
        more.addView(detailLine(tr(R.string.nap_day_before), minutes(e.nap, e.napMin)))
        more.addView(detailLine(tr(R.string.caffeine_after_2_pm), when (e.caffeine) { null -> tr(R.string.no_record); true -> tr(R.string.yes); false -> tr(R.string.no) }))
        more.addView(detailLine(tr(R.string.exercise_day_before), minutes(e.exercise, e.exerciseMin)))
        val toggle = activity.label(tr(R.string.show_habits_and_detailed_metrics_2), 11.5f, Color.parseColor("#71568D"), bold = true).apply {
            setPadding(0, activity.dp(12), 0, activity.dp(4))
        }
        toggle.setOnClickListener {
            val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = (if (open) "▾ " else "▸ ") + tr(R.string.show_habits_and_detailed_metrics)
        }
        c.addView(hairline())
        c.addView(toggle)
        c.addView(more)
        c.addView(hairline())

        val src = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, activity.dp(10), 0, 0) }
        src.addView(activity.icon(R.drawable.ic_source, Palette.lavender, 20))
        src.addView(activity.label(tr(R.string.data_source), 11f, Palette.muted).apply { maxLines = 1 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(6) })
        val srcText = when {
            e.fromServer -> tr(R.string.user_input_loaded_from_server)
            e.serverSaved -> tr(R.string.user_input_saved_to_server)
            else -> tr(R.string.user_input_saved_on_this_device)
        }
        src.addView(activity.label(srcText, 11f, bold = true).apply { gravity = Gravity.END },
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
