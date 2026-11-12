package com.cbnu.nabi.ui

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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "오늘의 수면 기록" bottom sheet from the Figma design (FR-03-01~05).
 *
 * Saved to the server: bedtime, wake time, sleep onset latency, minutes awake at night, satisfaction.
 * Kept on this device only (no server field yet, user decision): awakening count, nap/caffeine/exercise.
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
            existing != null -> "수면 기록 수정"
            date == LocalDate.now() -> "오늘의 수면 기록"
            else -> "${date.monthValue}월 ${date.dayOfMonth}일 수면 기록"
        },
        "어젯밤의 수면과 생활 습관을 기록해요.",
    )
    private lateinit var bedBtn: TextView
    private lateinit var wakeBtn: TextView
    private lateinit var awakeningsInput: EditText
    private lateinit var awakeMinInput: EditText
    private lateinit var latencyText: TextView
    private val errorText = activity.label("", 11f, Color.parseColor("#B45656"), bold = true)
    private val saveText = activity.label("기록 저장", 15f, Color.WHITE, bold = true)
    private val saveBtn = LinearLayout(activity)

    init {
        sheet.body.add(timesCard())
        sheet.body.add(latencyCard())
        sheet.body.add(satisfactionCard())
        sheet.body.add(habitsCard())
        sheet.body.add(errorText.apply { visibility = View.GONE })
        sheet.setFooter(buildSaveButton())
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
        title.addView(cardTitle("자동 수집된 수면 정보"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // FR-03-04: no collected estimate is available yet, so the fields start empty.
        title.addView(activity.label("데이터 없음", 10f, Palette.muted, bold = true).apply {
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
            background = activity.rounded(Color.parseColor("#F1E6DD"), 10)
        })
        c.addView(title)
        c.addView(help("수집값이 없거나 정확하지 않다면 직접 입력할 수 있어요."))

        bedBtn = timeButton { pickTime(true) }
        wakeBtn = timeButton { pickTime(false) }
        c.addView(row(R.drawable.ic_bed, "취침 시각", bedBtn))
        c.addView(row(R.drawable.ic_clock, "기상 시각", wakeBtn))

        awakeningsInput = numberInput("횟수", existing?.awakenings)
        awakeMinInput = numberInput("시간", existing?.awakeMinutes)
        val inputs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        inputs.addView(unitBox(awakeningsInput, "회"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        inputs.addView(unitBox(awakeMinInput, "분"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            .apply { leftMargin = activity.dp(5) })
        c.addView(row(R.drawable.ic_moon, "밤중 각성", inputs))
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
        texts.addView(cardTitle("잠들기까지 걸린 시간"))
        texts.addView(help("침대에 누운 뒤 잠들기까지의 시간이에요.", Palette.muted))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val stepper = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = activity.rounded(Palette.lavenderSoft, 13)
        }
        latencyText = activity.label("", 13f, bold = true).apply { gravity = Gravity.CENTER }
        stepper.addView(stepButton(R.drawable.ic_minus, "5분 줄이기") { setLatency(latency - 5) })
        stepper.addView(latencyText, LinearLayout.LayoutParams(activity.dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
        stepper.addView(stepButton(R.drawable.ic_plus, "5분 늘리기") { setLatency(latency + 5) })
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
        latencyText.text = "${latency}분"
    }

    private fun satisfactionCard(): View {
        val c = card()
        c.addView(cardTitle("수면 만족도"))
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
        c.addView(cardTitle("전날 생활 습관"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = activity.dp(8) })
        c.addView(row(null, "전날 낮잠 여부", yesNo({ nap }) { nap = it }, 0))
        c.addView(row(null, "전날 카페인 섭취 여부", yesNo({ caffeine }) { caffeine = it }, 0))
        c.addView(row(null, "전날 운동 여부", yesNo({ exercise }) { exercise = it }, 0))
        return c
    }

    private fun yesNo(get: () -> Boolean?, set: (Boolean) -> Unit): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val yes = chip("예", filled = false)
        val no = chip("아니요", filled = false)
        fun refresh() { styleChip(yes, get() == true, false); styleChip(no, get() == false, false) }
        yes.setOnClickListener { set(true); refresh() }
        no.setOnClickListener { set(false); refresh() }
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
        bedBtn.text = bedtime?.let(::format12) ?: "시간 선택"
        wakeBtn.text = wakeTime?.let(::format12) ?: "시간 선택"
    }

    private fun pickTime(isBed: Boolean) {
        val initial = (if (isBed) bedtime else wakeTime) ?: if (isBed) LocalTime.of(23, 0) else LocalTime.of(7, 0)
        Modals.timePicker(activity, if (isBed) "취침 시각 수정" else "기상 시각 수정", initial, listOf(sheet.decorView)) { t ->
            if (isBed) bedtime = t else wakeTime = t
            updateTimeButtons()
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
        if (bed == null || wake == null) return showError("취침 시각과 기상 시각을 모두 선택해 주세요.")
        if (sat == null || n == null || c == null || e == null) return showError("수면 만족도와 생활 습관 항목을 모두 선택해 주세요.")
        if (awakeMinInput.text.isNotEmpty() && awakeMin == null || awakeningsInput.text.isNotEmpty() && awakenings == null)
            return showError("밤중 각성은 숫자로 입력해 주세요.")

        val zone = ZoneId.systemDefault()
        val wakeAt = ZonedDateTime.of(date, wake, zone)
        var bedAt = ZonedDateTime.of(date, bed, zone)
        if (!bedAt.isBefore(wakeAt)) bedAt = bedAt.minusDays(1) // went to bed the evening before
        val spanMin = java.time.Duration.between(bedAt, wakeAt).toMinutes()
        if (wakeAt.isAfter(ZonedDateTime.now(zone).plusMinutes(10))) return showError("기상 시각이 아직 지나지 않았어요.")
        if (latency + (awakeMin ?: 0) > spanMin) return showError("잠들기까지 걸린 시간과 밤중 각성 시간이 누워 있던 시간보다 길어요.")
        showError(null)

        setSaving("저장 중…", enabled = false)
        activity.bg({
            activity.api.createSleepRecord(
                bedtime = bedAt,
                sleepOnsetLatencyMin = latency,
                wakeAfterSleepOnsetMin = awakeMin ?: 0,
                finalAwakening = wakeAt,
                outOfBedTime = wakeAt,
                sleepQuality = sat,
            )
        }) { r ->
            r.onSuccess {
                store.put(DiaryEntry(date, "%02d:%02d".format(bed.hour, bed.minute), "%02d:%02d".format(wake.hour, wake.minute),
                    awakenings, awakeMin, latency, sat, n, c, e, serverSaved = true))
                setSaving("저장 완료", enabled = false)
                saveBtn.postDelayed({ sheet.dismiss(); onSaved() }, 650)
            }.onFailure {
                setSaving("다시 저장", enabled = true)
                showError(when ((it as? ApiException)?.code) {
                    "DUPLICATE_RECORD" -> "이 날의 수면 기록이 이미 저장되어 있어요. 기록 수정은 준비 중이에요."
                    "VALIDATION_ERROR" -> "입력한 시간을 다시 확인해 주세요."
                    else -> "기록을 저장하지 못했어요. 잠시 후 다시 시도해 주세요. (${it.friendly()})"
                })
            }
        }
    }

    private fun setSaving(label: String, enabled: Boolean) {
        saveText.text = label
        saveBtn.isEnabled = enabled
        saveBtn.alpha = if (enabled || label == "저장 완료") 1f else 0.75f
    }

    companion object {
        val SATISFACTIONS = listOf("매우 나쁨", "나쁨", "보통", "좋음", "매우 좋음")

        fun format12(t: LocalTime): String = Modals.format12(t)
    }
}
