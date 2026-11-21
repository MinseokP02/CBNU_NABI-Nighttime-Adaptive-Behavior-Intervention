package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
import android.app.Dialog
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.cbnu.nabi.R
import com.cbnu.nabi.data.ApiException
import com.cbnu.nabi.data.HealthConnectSource
import com.cbnu.nabi.data.HealthNight
import com.cbnu.nabi.data.ScreenUsageSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "오늘의 수면 기록" bottom sheet from the Figma design (FR-03-01~05).
 *
 * Saved to the server: bedtime, wake time, sleep onset latency, minutes awake at night, satisfaction
 * (POST /sleep-records), and the previous day's habits (POST /behavior-records): caffeine after
 * 오후 2시, nap minutes, exercise minutes, and screen minutes before sleep. Kept on this device only
 * (no server field): awakening count.
 *
 * Auto-collected (FR-03-04): a new entry is pre-filled from Health Connect (sleep session → times,
 * latency, WASO, awakenings; exercise sessions → the previous day's exercise minutes). Screen time
 * before sleep comes from UsageStats. The user can still change every pre-filled value.
 */
class SleepRecordSheet(
    private val activity: MainActivity,
    private val date: LocalDate = LocalDate.now(),
    private val onSaved: () -> Unit,
) {
    private val store = SleepDiaryStore(activity, activity.prefs.email)
    private val existing = store.get(date)

    private var bedtime: LocalTime? = existing?.bedtime?.let(LocalTime::parse)
    private var wakeTime: LocalTime? = existing?.wakeTime?.let(LocalTime::parse)
    private var latency = existing?.latencyMin ?: 0
    private var satisfaction: Int? = existing?.satisfaction
    private var nap: Boolean? = existing?.nap
    private var caffeine: Boolean? = existing?.caffeine
    private var exercise: Boolean? = existing?.exercise

    private val sheet = BottomSheet(
        activity,
        when {
            existing != null -> tr(R.string.edit_sleep_record)
            date == LocalDate.now() -> tr(R.string.today_s_sleep_record)
            else -> tr(R.string.sleep_record_for, date.monthValue, date.dayOfMonth)
        },
        tr(R.string.log_last_night_s_sleep_and),
    )
    private lateinit var bedBtn: TextView
    private lateinit var wakeBtn: TextView
    private lateinit var awakeningsInput: EditText
    private lateinit var awakeMinInput: EditText
    private lateinit var latencyText: TextView
    private lateinit var napMinInput: EditText
    private lateinit var exerciseMinInput: EditText
    private lateinit var sourceBadge: TextView
    private lateinit var sourceHelp: TextView
    private lateinit var screenText: TextView
    private var exerciseRow: View? = null
    private var refreshExercise: () -> Unit = {}
    private val errorText = activity.label("", 11f, Color.parseColor("#B45656"), bold = true)
    private val saveText = activity.label(tr(R.string.save_record), 15f, Color.WHITE, bold = true)
    private val saveBtn = LinearLayout(activity)

    init {
        sheet.body.add(timesCard())
        sheet.body.add(latencyCard())
        sheet.body.add(satisfactionCard())
        sheet.body.add(habitsCard())
        sheet.body.add(errorText.apply { visibility = View.GONE })
        sheet.setFooter(buildSaveButton())
        loadDeviceData()
    }

    // ---------------- auto-collected data (Health Connect / UsageStats) ----------------
    private fun loadDeviceData() {
        activity.bg({
            val night = HealthConnectSource.readNight(activity, date)
            val exercise = HealthConnectSource.exerciseMinutes(activity, date.minusDays(1))
            night to exercise
        }) { r ->
            val (night, exerciseMin) = r.getOrNull() ?: (null to null)
            if (night != null) applyNight(night)
            // Only fill exercise when Health Connect actually has sessions (0 may just mean "not tracked").
            if (existing == null && exerciseMin != null && exerciseMin > 0) {
                exercise = true
                exerciseMinInput.setText(exerciseMin.toString())
                exerciseRow?.visibility = View.VISIBLE
                refreshExercise()
            }
            updateScreenTime()
        }
    }

    private fun applyNight(n: HealthNight) {
        sourceBadge.text = "Health Connect"
        sourceBadge.setTextColor(Color.WHITE)
        sourceBadge.background = activity.rounded(Color.parseColor("#7FB394"), 10)
        if (existing != null) {
            sourceHelp.text = tr(R.string.health_connect_has_record_for_this)
            return
        }
        bedtime = n.bedtime.toLocalTime().withSecond(0).withNano(0)
        wakeTime = n.wakeTime.toLocalTime().withSecond(0).withNano(0)
        n.latencyMin?.let { setLatency(it) }
        n.wakeAfterSleepOnsetMin?.let { awakeMinInput.setText(it.toString()) }
        n.awakenings?.let { awakeningsInput.setText(it.toString()) }
        updateTimeButtons()
        sourceHelp.text = if (n.latencyMin != null) tr(R.string.filled_in_from_your_health_connect)
        else tr(R.string.bedtime_and_wake_time_came_from)
    }

    /** Sleep onset = bedtime + latency, on the night before [date]. */
    private fun sleepOnset(): ZonedDateTime? {
        val bed = bedtime ?: return null
        val wake = wakeTime ?: LocalTime.of(7, 0)
        val zone = ZoneId.systemDefault()
        var bedAt = ZonedDateTime.of(date, bed, zone)
        if (!bedAt.isBefore(ZonedDateTime.of(date, wake, zone))) bedAt = bedAt.minusDays(1)
        return bedAt.plusMinutes(latency.toLong())
    }

    /** "잠들기 전 2시간 화면 사용" row: recomputed when bedtime or latency changes. */
    private fun updateScreenTime() {
        if (!::screenText.isInitialized) return
        val onset = sleepOnset()
        activity.bg({
            val access = ScreenUsageSource.hasAccess(activity)
            access to onset?.let { ScreenUsageSource.minutesBefore(activity, it) }
        }) { r ->
            val (access, min) = r.getOrNull() ?: (false to null)
            screenText.text = when {
                !access -> tr(R.string.permission_needed)
                onset == null -> tr(R.string.calculated_after_you_pick_bedtime)
                min == null -> tr(R.string.no_record)
                else -> tr(R.string.min_auto, min)
            }
            screenText.setOnClickListener(if (!access) View.OnClickListener { activity.openPermissionSettings() } else null)
        }
    }

    fun show() = sheet.show()

    // ---------------- cards ----------------
    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(10))
        background = activity.cardBg(Color.parseColor("#FFFAF6"), 20, Color.parseColor("#EADBD0"))
    }

    private fun cardTitle(text: String) = activity.label(text, 16f, title = true)

    private fun help(text: String, color: Int = Color.parseColor("#8B73A8")) =
        activity.label(text, 10.5f, color).apply { setPadding(0, activity.dp(5), 0, activity.dp(6)) }

    /** A form row with a hairline on top: icon + bold label on the left, [right] on the right. */
    private fun row(iconRes: Int?, text: String, right: View, rightWidthDp: Int = 125): LinearLayout {
        val r = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(46)
        }
        if (iconRes != null) r.addView(activity.icon(iconRes, Palette.lavender, 19).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = activity.dp(7)
        })
        r.addView(activity.label(text, 12.5f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(right, LinearLayout.LayoutParams(
            if (rightWidthDp > 0) activity.dp(rightWidthDp) else ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(34)))
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hairline())
            addView(r)
        }
    }

    private fun hairline() = View(activity).apply { setBackgroundColor(Color.parseColor("#EEE2DA")) }
        .also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)) }

    private fun timesCard(): View {
        val c = card()
        val title = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(cardTitle(tr(R.string.automatically_collected_sleep_info)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // FR-03-04: "데이터 없음" until a Health Connect sleep session is found ([applyNight]).
        sourceBadge = activity.label(tr(R.string.no_data), 10f, Palette.muted, bold = true).apply {
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
            background = activity.rounded(Color.parseColor("#F1E6DD"), 10)
        }
        title.addView(sourceBadge)
        c.addView(title)
        sourceHelp = help(tr(R.string.if_nothing_was_collected_or_it))
        c.addView(sourceHelp)

        bedBtn = timeButton { pickTime(true) }
        wakeBtn = timeButton { pickTime(false) }
        c.addView(row(R.drawable.ic_bed, tr(R.string.bedtime), bedBtn))
        c.addView(row(R.drawable.ic_clock, tr(R.string.wake_time), wakeBtn))

        awakeningsInput = numberInput(tr(R.string.times_2), existing?.awakenings)
        awakeMinInput = numberInput(tr(R.string.minutes), existing?.awakeMinutes)
        val inputs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        inputs.addView(unitBox(awakeningsInput, tr(R.string.times)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        inputs.addView(unitBox(awakeMinInput, tr(R.string.min)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            .apply { leftMargin = activity.dp(5) })
        c.addView(row(R.drawable.ic_moon, tr(R.string.night_waking), inputs))
        updateTimeButtons()
        return c
    }

    private fun timeButton(onClick: () -> Unit) = activity.label("", 12f, bold = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(10), 0, activity.dp(8), 0)
        background = activity.cardBg(Palette.lavenderSoft, 11, Color.parseColor("#E2D5EE"))
        val chevron = activity.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply {
            setTint(Palette.muted); setBounds(0, 0, activity.dp(14), activity.dp(14))
        }
        setCompoundDrawables(null, null, chevron, null)
        setOnClickListener { onClick() }
    }

    private fun numberInput(hint: String, value: Int?) = EditText(activity).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 12f
        isSingleLine = true
        background = null
        setPadding(0, 0, 0, 0)
        setText(value?.toString() ?: "")
        typeface = Fonts.body(activity)
        setTextColor(Palette.ink)
        setHintTextColor(Color.parseColor("#A89A92"))
    }

    private fun unitBox(input: EditText, unit: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
        background = activity.cardBg(Palette.lavenderSoft, 11, Color.parseColor("#E2D5EE"))
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(activity.label(unit, 10f, Palette.muted, bold = true))
    }

    private fun latencyCard(): View {
        val c = card()
        val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(cardTitle(tr(R.string.time_to_fall_asleep)))
        texts.addView(help(tr(R.string.time_from_getting_into_bed_until), Palette.muted))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val stepper = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = activity.rounded(Palette.lavenderSoft, 13)
        }
        latencyText = activity.label("", 13f, bold = true).apply { gravity = Gravity.CENTER }
        stepper.addView(stepButton(R.drawable.ic_minus, tr(R.string.n_5_min_less)) { setLatency(latency - 5) })
        stepper.addView(latencyText, LinearLayout.LayoutParams(activity.dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
        stepper.addView(stepButton(R.drawable.ic_plus, tr(R.string.n_5_min_more)) { setLatency(latency + 5) })
        r.addView(stepper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(38)))
        c.addView(r)
        setLatency(latency)
        return c
    }

    private fun stepButton(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(activity).apply {
        addView(activity.icon(res, Palette.lavenderDeep, 17), FrameLayout.LayoutParams(activity.dp(17), activity.dp(17), Gravity.CENTER))
        contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(activity.dp(38), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setLatency(v: Int) {
        latency = v.coerceIn(0, 720)
        latencyText.text = tr(R.string.min_2, latency)
        updateScreenTime()
    }

    private fun satisfactionCard(): View {
        val c = card()
        c.addView(cardTitle(tr(R.string.sleep_satisfaction)))
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val views = SATISFACTIONS.mapIndexed { i, label ->
            chip(label, filled = true).also { v ->
                chips.addView(v, LinearLayout.LayoutParams(0, activity.dp(36), 1f).apply { if (i > 0) leftMargin = activity.dp(5) })
            }
        }
        fun refresh() = views.forEachIndexed { i, v -> styleChip(v, satisfaction == i + 1, filled = true) }
        views.forEachIndexed { i, v -> v.setOnClickListener { satisfaction = i + 1; refresh() } }
        refresh()
        c.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(36)).apply {
            topMargin = activity.dp(10); bottomMargin = activity.dp(4)
        })
        return c
    }

    private fun habitsCard(): View {
        val c = card()
        c.addView(cardTitle(tr(R.string.yesterday_s_habits)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = activity.dp(8) })
        napMinInput = numberInput(tr(R.string.e_g_30), existing?.napMin?.takeIf { it > 0 })
        exerciseMinInput = numberInput(tr(R.string.e_g_30), existing?.exerciseMin?.takeIf { it > 0 })
        val napMinRow = row(null, tr(R.string.nap_time), unitBox(napMinInput, tr(R.string.min)), 90)
        val exerciseMinRow = row(null, tr(R.string.exercise_time), unitBox(exerciseMinInput, tr(R.string.min)), 90)
        c.addView(row(null, tr(R.string.napped_yesterday), yesNo({ nap }) { nap = it; napMinRow.visibility = if (it) View.VISIBLE else View.GONE }, 0))
        c.addView(napMinRow.apply { visibility = if (nap == true) View.VISIBLE else View.GONE })
        c.addView(row(null, tr(R.string.caffeine_after_2_pm_2), yesNo({ caffeine }) { caffeine = it }, 0))
        c.addView(row(null, tr(R.string.exercised_yesterday), yesNo({ exercise }, { refreshExercise = it }) { exercise = it; exerciseMinRow.visibility = if (it) View.VISIBLE else View.GONE }, 0))
        c.addView(exerciseMinRow.apply { visibility = if (exercise == true) View.VISIBLE else View.GONE })
        exerciseRow = exerciseMinRow
        screenText = activity.label(tr(R.string.checking), 11f, Palette.lavenderDeep, bold = true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        c.addView(row(null, tr(R.string.screen_time_2_h_before_sleep), screenText, 0))
        return c
    }

    private fun yesNo(get: () -> Boolean?, exposeRefresh: ((() -> Unit) -> Unit)? = null, set: (Boolean) -> Unit): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val yes = chip(tr(R.string.yes), filled = false)
        val no = chip(tr(R.string.no), filled = false)
        fun refresh() { styleChip(yes, get() == true, false); styleChip(no, get() == false, false) }
        yes.setOnClickListener { set(true); refresh() }
        no.setOnClickListener { set(false); refresh() }
        exposeRefresh?.invoke(::refresh)
        box.addView(yes, LinearLayout.LayoutParams(activity.dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
        box.addView(no, LinearLayout.LayoutParams(activity.dp(56), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = activity.dp(5) })
        refresh()
        return box
    }

    private fun chip(text: String, filled: Boolean) = activity.label(text, 11f, bold = true).apply {
        gravity = Gravity.CENTER
        styleChip(this, false, filled)
    }

    private fun styleChip(v: TextView, selected: Boolean, filled: Boolean) {
        v.background = when {
            selected -> activity.cardBg(Color.parseColor("#AA8BCC"), 12, Color.parseColor("#A98BCA"))
            filled -> activity.rounded(Color.parseColor("#F3ECF7"), 12)
            else -> activity.cardBg(Color.WHITE, 12, Color.parseColor("#E3D3C8"))
        }
        v.setTextColor(if (selected) Color.WHITE else Palette.ink)
    }

    // ---------------- time picker ----------------
    private fun updateTimeButtons() {
        bedBtn.text = bedtime?.let(::format12) ?: tr(R.string.pick_time)
        wakeBtn.text = wakeTime?.let(::format12) ?: tr(R.string.pick_time)
    }

    private fun pickTime(isBed: Boolean) {
        val initial = (if (isBed) bedtime else wakeTime) ?: if (isBed) LocalTime.of(23, 0) else LocalTime.of(7, 0)
        Modals.timePicker(activity, if (isBed) tr(R.string.edit_bedtime) else tr(R.string.edit_wake_time), initial, listOf(sheet.decorView)) { t ->
            if (isBed) bedtime = t else wakeTime = t
            updateTimeButtons()
            updateScreenTime()
        }
    }

    // ---------------- save (FR-03-05) ----------------
    private fun buildSaveButton(): View = saveBtn.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = activity.rounded(Color.parseColor("#9D7BC4"), 17)
        elevation = activity.dp(3).toFloat()
        minimumHeight = activity.dp(51)
        addView(activity.icon(R.drawable.ic_check, Color.WHITE, 20))
        addView(saveText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = activity.dp(8) })
        setOnClickListener { submit() }
    }

    private fun showError(msg: String?) {
        errorText.text = msg ?: ""
        errorText.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun submit() {
        val bed = bedtime
        val wake = wakeTime
        val awakenings = awakeningsInput.text.toString().toIntOrNull()
        val awakeMin = awakeMinInput.text.toString().toIntOrNull()
        val sat = satisfaction
        val n = nap; val c = caffeine; val e = exercise

        // Input checks before saving (FR-03-05). Times are required by the server.
        if (bed == null || wake == null) return showError(tr(R.string.pick_both_bedtime_and_wake_time))
        if (sat == null || n == null || c == null || e == null) return showError(tr(R.string.answer_sleep_satisfaction_and_all_habit))
        if (awakeMinInput.text.isNotEmpty() && awakeMin == null || awakeningsInput.text.isNotEmpty() && awakenings == null)
            return showError(tr(R.string.enter_night_waking_as_numbers))
        // 예 → minutes are required (the server analyses durations); 아니요 → 0분.
        val napMin = if (n) napMinInput.text.toString().toIntOrNull() else 0
        val exerciseMin = if (e) exerciseMinInput.text.toString().toIntOrNull() else 0
        if (napMin == null || n && napMin !in 1..1440) return showError(tr(R.string.enter_nap_time_in_minutes))
        if (exerciseMin == null || e && exerciseMin !in 1..1440) return showError(tr(R.string.enter_exercise_time_in_minutes))

        val zone = ZoneId.systemDefault()
        val wakeAt = ZonedDateTime.of(date, wake, zone)
        var bedAt = ZonedDateTime.of(date, bed, zone)
        if (!bedAt.isBefore(wakeAt)) bedAt = bedAt.minusDays(1) // went to bed the evening before
        val spanMin = java.time.Duration.between(bedAt, wakeAt).toMinutes()
        if (wakeAt.isAfter(ZonedDateTime.now(zone).plusMinutes(10))) return showError(tr(R.string.that_wake_time_hasn_t_come))
        if (latency + (awakeMin ?: 0) > spanMin) return showError(tr(R.string.time_to_fall_asleep_plus_time))
        showError(null)

        setSaving(tr(R.string.saving), enabled = false)
        activity.bg({
            activity.api.createSleepRecord(
                bedtime = bedAt,
                sleepOnsetLatencyMin = latency,
                wakeAfterSleepOnsetMin = awakeMin ?: 0,
                finalAwakening = wakeAt,
                outOfBedTime = wakeAt,
                sleepQuality = sat,
            )
            // The habits are for the day before the wake-up morning. A failure here must not undo the
            // saved sleep record, so it is reported separately.
            // Recomputed with the final bedtime/latency (UsageStats; null without access → not sent).
            val screen = ScreenUsageSource.minutesBefore(activity, bedAt.plusMinutes(latency.toLong()))
            runCatching {
                activity.api.createBehaviorRecord(
                    date = date.minusDays(1),
                    caffeineAfterCutoff = c,
                    screenBeforeBedMin = screen,
                    napDurationMin = napMin,
                    exerciseDurationMin = exerciseMin,
                )
            }.fold({ true }, { (it as? ApiException)?.code == "DUPLICATE_RECORD" })
        }) { r ->
            r.onSuccess { behaviorSaved ->
                store.put(DiaryEntry(date, "%02d:%02d".format(bed.hour, bed.minute), "%02d:%02d".format(wake.hour, wake.minute),
                    awakenings, awakeMin, latency, sat, n, napMin, c, e, exerciseMin, serverSaved = true, behaviorSaved = behaviorSaved))
                if (!behaviorSaved) android.widget.Toast.makeText(activity,
                    tr(R.string.your_sleep_record_was_saved_but), android.widget.Toast.LENGTH_LONG).show()
                setSaving(tr(R.string.saved), enabled = false)
                saveBtn.postDelayed({ sheet.dismiss(); onSaved() }, 650)
            }.onFailure {
                setSaving(tr(R.string.save_again), enabled = true)
                showError(when ((it as? ApiException)?.code) {
                    "DUPLICATE_RECORD" -> tr(R.string.sleep_record_for_this_day_is)
                    "VALIDATION_ERROR" -> tr(R.string.please_check_times_you_entered)
                    else -> tr(R.string.couldn_t_save_record_please_try, it.friendly())
                })
            }
        }
    }

    private fun setSaving(label: String, enabled: Boolean) {
        saveText.text = label
        saveBtn.isEnabled = enabled
        saveBtn.alpha = if (enabled || label == tr(R.string.saved)) 1f else 0.75f
    }

    companion object {
        val SATISFACTIONS get() = listOf(tr(R.string.very_bad), tr(R.string.bad), tr(R.string.moderate), tr(R.string.good), tr(R.string.very_good))

        fun format12(t: LocalTime): String = Modals.format12(t)
    }
}
