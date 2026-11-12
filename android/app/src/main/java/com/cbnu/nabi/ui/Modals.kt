package com.cbnu.nabi.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import java.time.LocalTime

/** Centered Figma-style modals: time picker, info dialog, and the shared blur-behind handling. */
object Modals {

    /**
     * Center the dialog (Samsung otherwise puts dialogs at the bottom) and blur what is behind it.
     * Many phones (e.g. Galaxy S20+) don't support system window blur, so on Android 12+ the app's
     * own windows behind ([extraBehind] + the activity) are blurred directly with RenderEffect.
     */
    fun center(activity: Activity, dialog: Dialog, extraBehind: List<View?> = emptyList(), stacked: Boolean = false) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setWindowAnimations(android.R.style.Animation_Dialog)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // A modal stacked on another one adds only a light dim/blur on top of what is already there.
            setDimAmount(when {
                stacked -> 0.06f
                Build.VERSION.SDK_INT >= 31 -> 0.15f
                else -> 0.4f
            })
        }
        val behind = (listOf<View?>(activity.window.decorView) + extraBehind).filterNotNull()
        val extraRadius = if (stacked) 3f else 12f
        dialog.setOnShowListener {
            Blur.push(activity.window.decorView)
            extraBehind.filterNotNull().forEach { Blur.push(it, extraRadius) }
        }
        dialog.setOnDismissListener { behind.forEach(Blur::pop) }
    }

    /** Rounded card the modals are built on. */
    fun box(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(20), activity.dp(22), activity.dp(20), activity.dp(18))
        background = activity.cardBg(Palette.card, 24, Color.parseColor("#DDC9BB"))
    }

    fun button(activity: Activity, text: String, primary: Boolean, onClick: () -> Unit) =
        activity.label(text, 14f, if (primary) Color.WHITE else Palette.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = activity.rounded(if (primary) Color.parseColor("#9D7BC4") else Color.parseColor("#F0E7E1"), 14)
            setOnClickListener { onClick() }
        }

    /** A cancel / confirm button row. */
    fun buttons(activity: Activity, cancel: String?, onCancel: () -> Unit, confirm: String, onConfirm: () -> Unit): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (cancel != null) row.addView(button(activity, cancel, false, onCancel), LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        row.addView(button(activity, confirm, true, onConfirm), LinearLayout.LayoutParams(0, activity.dp(44), 1.4f).apply {
            if (cancel != null) leftMargin = activity.dp(8)
        })
        return row
    }

    fun show(activity: Activity, dialog: Dialog, content: View, extraBehind: List<View?> = emptyList(), stacked: Boolean = false) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        center(activity, dialog, extraBehind, stacked)
        dialog.show()
        dialog.window?.setLayout(minOf(activity.dp(328), activity.resources.displayMetrics.widthPixels - activity.dp(32)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Icon + title + text, with an optional action button (Figma Modal). */
    fun info(
        activity: Activity, iconRes: Int, title: String, text: String,
        action: Pair<String, () -> Unit>? = null,
    ) {
        val dialog = Dialog(activity)
        val box = box(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(FrameLayout(activity).apply {
            background = activity.rounded(Palette.lavenderSoft, 16)
            addView(activity.icon(iconRes, Palette.lavenderDeep, 24), FrameLayout.LayoutParams(activity.dp(24), activity.dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        box.addView(activity.label(title, 19f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(12), 0, 0) })
        box.addView(activity.label(text, 12.5f, Palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0); setLineSpacing(activity.dp(3).toFloat(), 1f)
        })
        val row = if (action == null) buttons(activity, null, {}, "확인") { dialog.dismiss() }
        else buttons(activity, "닫기", { dialog.dismiss() }, action.first) { dialog.dismiss(); action.second() }
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(18) })
        show(activity, dialog, box)
    }

    /** Figma TimePicker: 오전/오후 · 시 · 분 wheels. */
    fun timePicker(
        activity: Activity, title: String, initial: LocalTime,
        extraBehind: List<View?> = emptyList(), onConfirm: (LocalTime) -> Unit,
    ) {
        val dialog = Dialog(activity)
        val box = box(activity)
        val preview = activity.label("", 15f, Palette.lavenderDeep, bold = true)
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.label(title, 18f, title = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(preview)
        box.addView(head)

        val period = wheel(activity, 0, 1) { if (it == 0) "오전" else "오후" }.apply { value = if (initial.hour >= 12) 1 else 0 }
        val hour = wheel(activity, 1, 12) { it.toString() }.apply { value = (initial.hour % 12).let { if (it == 0) 12 else it } }
        val minute = wheel(activity, 0, 59) { "%02d".format(it) }.apply { value = initial.minute }
        fun current(): LocalTime = LocalTime.of((hour.value % 12) + if (period.value == 1) 12 else 0, minute.value)
        val update = NumberPicker.OnValueChangeListener { _, _, _ -> preview.text = format12(current()) }
        listOf(period, hour, minute).forEach { it.setOnValueChangedListener(update) }
        preview.text = format12(current())

        val wheels = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, picker) in listOf("구분" to period, "시" to hour, "분" to minute)) {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(activity.label(label, 11f, Palette.muted, bold = true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = activity.dp(6) })
                addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(160)))
                background = activity.cardBg(Color.parseColor("#FAF5F8"), 14, Color.parseColor("#EADDE8"))
                setPadding(activity.dp(6), activity.dp(12), activity.dp(6), activity.dp(8))
            }
            wheels.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (label != "구분") leftMargin = activity.dp(8)
            })
        }
        box.addView(wheels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(16) })
        box.addView(buttons(activity, "취소", { dialog.dismiss() }, "확인") { onConfirm(current()); dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = activity.dp(14) })
        show(activity, dialog, box, extraBehind)
    }

    private fun wheel(activity: Activity, min: Int, max: Int, fmt: (Int) -> String) = NumberPicker(activity).apply {
        minValue = min; maxValue = max
        displayedValues = (min..max).map(fmt).toTypedArray()
        wrapSelectorWheel = max - min > 2
        descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 29) textColor = Palette.ink
    }

    /** "오후 11:30" */
    fun format12(t: LocalTime): String {
        val period = if (t.hour >= 12) "오후" else "오전"
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return "$period $h:%02d".format(t.minute)
    }
}
