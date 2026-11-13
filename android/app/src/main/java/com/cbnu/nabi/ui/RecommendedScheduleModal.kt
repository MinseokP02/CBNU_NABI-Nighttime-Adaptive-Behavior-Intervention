package com.cbnu.nabi.ui

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
        box.addView(header("추천 수면 일정", R.drawable.ic_moon) { dialog.dismiss() })
        // Scrolls inside the modal on small screens; the box never grows past the screen.
        box.addView(ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        Modals.show(activity, dialog, box)
        load()
    }

    private fun load() {
        if (previewSchedule != null) { render(RecommendationResult.Ready(previewSchedule)); return }
        renderMessage("추천 일정을 불러오는 중이에요…", null)
        activity.bg({ SleepScheduleRecommendations.provider(activity) }) { r ->
            render(r.getOrElse { RecommendationResult.Failed(it.friendly()) })
        }
    }

    // ---------------- states ----------------
    private fun render(r: RecommendationResult) = when (r) {
        is RecommendationResult.Ready -> renderSchedule(r.schedule)
        RecommendationResult.None -> renderMessage("아직 추천 일정이 없어요.",
            "수면 기록이 쌓이면 나비가 나에게 맞는 일정을 제안해 줄 거예요.")
        is RecommendationResult.Failed -> {
            renderMessage("추천 일정을 불러오지 못했어요.", "잠시 후 다시 시도해 주세요.")
            content.addView(activity.label("다시 시도", 12.5f, Color.parseColor("#8467A8"), bold = true).apply {
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
        content.addView(activity.label("최근 수면 패턴을 바탕으로 추천하는 일정이에요.", 12f, Palette.muted).apply {
            setPadding(0, activity.dp(8), 0, 0)
        }, lp())
        if (s.preview) content.addView(activity.label("UI 미리보기 · 실제 추천이 아니에요", 10f, Palette.muted).apply {
            setPadding(0, activity.dp(4), 0, 0)
        }, lp())

        val store = ProfileStore(activity, activity.prefs.email)
        content.addView(timeBox("현재 설정", Color.parseColor("#F3ECE7"), Palette.muted,
            store.goalBedtime?.let { Modals.format12(LocalTime.parse(it)) } ?: "설정 안 됨",
            store.goalWakeTime?.let { Modals.format12(LocalTime.parse(it)) } ?: "설정 안 됨", Palette.ink), lp(14))
        content.addView(FrameLayout(activity).apply {
            addView(activity.icon(R.drawable.ic_chevron_down, Palette.lavender, 18), FrameLayout.LayoutParams(activity.dp(18), activity.dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(26)))
        content.addView(timeBox("나비가 추천하는 일정", Palette.lavenderSoft, Palette.lavenderDeep,
            Modals.format12(s.bedtime), Modals.format12(s.wakeTime), Palette.lavenderDeep, border = Color.parseColor("#DCCDEB")), lp())

        // Reason only when the recommendation came with one — never made up.
        if (!s.reason.isNullOrBlank()) content.addView(activity.label(s.reason, 12f, Palette.ink).apply {
            setPadding(activity.dp(2), activity.dp(12), activity.dp(2), 0); setLineSpacing(activity.dp(3).toFloat(), 1f)
        }, lp())

        if (applied) {
            content.addView(activity.label("✓ 추천 일정을 적용했어요", 13f, Palette.lavenderDeep, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(14), 0, activity.dp(14))
                background = activity.rounded(Palette.lavenderSoft, 14)
            }, lp(18))
        } else {
            content.addView(Modals.button(activity, "이 일정 적용하기", true) { confirm(s) },
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
            row.addView(timeCell(R.drawable.ic_moon, "취침 시각", bed, valueColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(timeCell(R.drawable.ic_sun, "기상 시각", wake, valueColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
        box.addView(header("추천 일정을 적용할까요?", null, onClose = null))
        box.addView(activity.label("현재 설정한 수면 목표가 아래 일정으로 변경돼요.", 12f, Palette.muted).apply {
            setPadding(0, activity.dp(8), 0, 0)
        }, lp())
        box.addView(timeBox("적용할 일정", Palette.lavenderSoft, Palette.lavenderDeep,
            Modals.format12(s.bedtime), Modals.format12(s.wakeTime), Palette.lavenderDeep, border = Color.parseColor("#DCCDEB")), lp(14))
        val error = activity.label("", 11.5f, Color.parseColor("#B45656"), bold = true).apply { visibility = View.GONE; setPadding(0, activity.dp(10), 0, 0) }
        box.addView(error, lp())
        lateinit var applyBtn: TextView
        val buttons = Modals.buttons(activity, "취소", { c.dismiss() }, "적용하기") {
            if (s.preview) { // preview data never changes the real goal
                error.text = "미리보기 데이터라 실제 목표는 바꾸지 않았어요."; error.visibility = View.VISIBLE
                return@buttons
            }
            applyBtn.isEnabled = false; applyBtn.text = "적용 중…"; error.visibility = View.GONE
            activity.bg({ SleepScheduleRecommendations.applier(activity, activity.prefs.email, s) }) { r ->
                if (r.getOrDefault(false)) {
                    c.dismiss()
                    applied = true
                    renderSchedule(s)   // now shows "현재 설정" = the new goal and the applied state
                    onApplied()
                } else {
                    applyBtn.isEnabled = true; applyBtn.text = "적용하기"
                    error.text = "적용하지 못했어요. 잠시 후 다시 시도해 주세요."; error.visibility = View.VISIBLE
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
            contentDescription = "닫기"
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)).apply { rightMargin = -activity.dp(8) })
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = activity.dp(top) }
}
