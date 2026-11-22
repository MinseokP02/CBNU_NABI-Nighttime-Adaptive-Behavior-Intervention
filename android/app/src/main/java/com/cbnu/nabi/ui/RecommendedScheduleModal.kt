package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.R
import java.time.LocalTime

/**
 * "추천 수면 일정" centred modal over a blurred + dimmed background (blur on Android 12+, dim only
 * before that — see [Modals.center]). Shows loading / empty / error / recommendation, then a
 * confirm modal before anything is saved. UI only; data comes from [SleepScheduleRecommendations].
 */
class RecommendedScheduleModal(
    private val activity: MainActivity,
    /** Called after a recommendation was really saved, so the caller can refresh its goal card. */
    private val onApplied: () -> Unit,
    /** Debug UI preview: show this instead of asking the provider. */
    private val previewSchedule: RecommendedSchedule? = null,
) {
    private val dialog = Dialog(activity)
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var applied = false

    fun show() {
        val box = Modals.box(activity)
        box.addView(header(tr(R.string.recommended_sleep_schedule), R.drawable.ic_moon) { dialog.dismiss() })
        // Scrolls inside the modal on small screens; the box never grows past the screen.
        box.addView(ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Modals.show(activity, dialog, box)
        load()
    }

    private fun load() {
        if (previewSchedule != null) { render(RecommendationResult.Ready(previewSchedule)); return }
        renderMessage(tr(R.string.loading_recommended_schedule), null)
        activity.bg({ SleepScheduleRecommendations.provider(activity) }) { r ->
            render(r.getOrElse { RecommendationResult.Failed(it.friendly()) })
        }
    }

    // ---------------- states ----------------
    private fun render(r: RecommendationResult) = when (r) {
        is RecommendationResult.Ready -> renderSchedule(r.schedule)
        RecommendationResult.None -> renderMessage(tr(R.string.no_recommended_schedule_yet),
            tr(R.string.once_you_have_more_sleep_records))
        is RecommendationResult.Failed -> {
            renderMessage(tr(R.string.couldn_t_load_recommended_schedule), tr(R.string.please_try_again_in_moment))
            content.addView(activity.label(tr(R.string.retry), 12.5f, Color.parseColor("#8467A8"), bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(10), 0, activity.dp(6))
                setOnClickListener { load() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun renderMessage(title: String, sub: String?) {
        content.removeAllViews()
        content.addView(activity.label(title, 14f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, activity.dp(18), 0, 0)
        }, lp())
        if (sub != null) content.addView(activity.label(sub, 12f, Palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(activity.dp(4), activity.dp(8), activity.dp(4), activity.dp(8))
            setLineSpacing(activity.dp(3).toFloat(), 1f)
        }, lp())
    }

    private fun renderSchedule(s: RecommendedSchedule) {
        content.removeAllViews()
        content.addView(activity.label(tr(R.string.schedule_recommended_from_your_recent_sleep), 12f, Palette.muted).apply {
            setPadding(0, activity.dp(8), 0, 0)
        }, lp())
        if (s.preview) content.addView(activity.label(tr(R.string.ui_preview_not_real_recommendation), 10f, Palette.muted).apply {
            setPadding(0, activity.dp(4), 0, 0)
        }, lp())

        val store = ProfileStore(activity, activity.prefs.email)
        content.addView(timeBox(tr(R.string.current), Color.parseColor("#F3ECE7"), Palette.muted,
            store.goalBedtime?.let { Modals.format12(LocalTime.parse(it)) } ?: tr(R.string.not_set),
            store.goalWakeTime?.let { Modals.format12(LocalTime.parse(it)) } ?: tr(R.string.not_set), Palette.ink), lp(14))
        content.addView(FrameLayout(activity).apply {
            addView(activity.icon(R.drawable.ic_chevron_down, Palette.lavender, 18), FrameLayout.LayoutParams(activity.dp(18), activity.dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(26)))
        content.addView(timeBox(tr(R.string.nabi_s_recommendation), Palette.lavenderSoft, Palette.lavenderDeep,
            Modals.format12(s.bedtime), Modals.format12(s.wakeTime), Palette.lavenderDeep, border = Color.parseColor("#DCCDEB")), lp())

        // Reason only when the recommendation came with one — never made up.
        if (!s.reason.isNullOrBlank()) content.addView(activity.label(s.reason, 12f, Palette.ink).apply {
            setPadding(activity.dp(2), activity.dp(12), activity.dp(2), 0); setLineSpacing(activity.dp(3).toFloat(), 1f)
        }, lp())

        if (applied) {
            content.addView(activity.label(tr(R.string.recommended_schedule_applied), 13f, Palette.lavenderDeep, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(14), 0, activity.dp(14))
                background = activity.rounded(Palette.lavenderSoft, 14)
            }, lp(18))
        } else {
            content.addView(Modals.button(activity, tr(R.string.apply_this_schedule), true) { confirm(s) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(46)).apply { topMargin = activity.dp(18) })
        }
    }

    /** "현재 설정" / "나비가 추천하는 일정": label + 취침 · 기상 in two columns. */
    private fun timeBox(title: String, bg: Int, titleColor: Int, bed: String, wake: String, valueColor: Int, border: Int? = null) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            background = if (border != null) activity.cardBg(bg, 16, border) else activity.rounded(bg, 16)
            addView(activity.label(title, 11.5f, titleColor, bold = true))
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, activity.dp(8), 0, 0) }
            row.addView(timeCell(R.drawable.ic_moon, tr(R.string.bedtime), bed, valueColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(timeCell(R.drawable.ic_sun, tr(R.string.wake_time), wake, valueColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = activity.dp(8) })
            addView(row)
        }

    private fun timeCell(iconRes: Int, name: String, value: String, valueColor: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.icon(iconRes, Palette.muted, 13))
        head.addView(activity.label(name, 10.5f, Palette.muted),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(4) })
        addView(head)
        addView(activity.label(value, 16f, valueColor, bold = true).apply { setPadding(0, activity.dp(4), 0, 0) })
    }

    // ---------------- 적용 확인 ----------------
    /** One confirm modal on top (the schedule modal stays underneath and is blurred too). */
    private fun confirm(s: RecommendedSchedule) {
        val c = Dialog(activity)
        val box = Modals.box(activity)
        box.addView(header(tr(R.string.apply_recommended_schedule), null, onClose = null))
        box.addView(activity.label(tr(R.string.your_current_sleep_goals_will_change), 12f, Palette.muted).apply {
            setPadding(0, activity.dp(8), 0, 0)
        }, lp())
        box.addView(timeBox(tr(R.string.schedule_to_apply), Palette.lavenderSoft, Palette.lavenderDeep,
            Modals.format12(s.bedtime), Modals.format12(s.wakeTime), Palette.lavenderDeep, border = Color.parseColor("#DCCDEB")), lp(14))
        val error = activity.label("", 11.5f, Color.parseColor("#B45656"), bold = true).apply { visibility = View.GONE; setPadding(0, activity.dp(10), 0, 0) }
        box.addView(error, lp())
        lateinit var applyBtn: TextView
        val buttons = Modals.buttons(activity, tr(R.string.cancel), { c.dismiss() }, tr(R.string.apply)) {
            if (s.preview) { // preview data never changes the real goal
                error.text = tr(R.string.this_is_preview_data_so_your); error.visibility = View.VISIBLE
                return@buttons
            }
            applyBtn.isEnabled = false; applyBtn.text = tr(R.string.applying); error.visibility = View.GONE
            activity.bg({ SleepScheduleRecommendations.applier(activity, activity.prefs.email, s) }) { r ->
                if (r.getOrDefault(false)) {
                    c.dismiss()
                    applied = true
                    renderSchedule(s)   // now shows "현재 설정" = the new goal and the applied state
                    onApplied()
                } else {
                    applyBtn.isEnabled = true; applyBtn.text = tr(R.string.apply)
                    error.text = tr(R.string.couldn_t_apply_it_please_try); error.visibility = View.VISIBLE
                }
            }
        }
        applyBtn = (buttons as LinearLayout).getChildAt(1) as TextView
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(18) })
        Modals.show(activity, c, box, extraBehind = listOf(dialog.window?.decorView), stacked = true)
    }

    // ---------------- shared ----------------
    private fun header(title: String, iconRes: Int?, onClose: (() -> Unit)?) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (iconRes != null) addView(activity.icon(iconRes, Palette.lavender, 18).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = activity.dp(7)
        })
        addView(activity.label(title, 18f, title = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onClose != null) addView(FrameLayout(activity).apply {
            addView(activity.icon(R.drawable.ic_plus, Palette.muted, 16).apply { rotation = 45f },
                FrameLayout.LayoutParams(activity.dp(16), activity.dp(16), Gravity.CENTER))
            contentDescription = tr(R.string.close)
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)).apply { rightMargin = -activity.dp(8) })
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = activity.dp(top) }
}
