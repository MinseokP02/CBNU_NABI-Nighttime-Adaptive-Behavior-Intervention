package com.cbnu.nabi.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cbnu.nabi.R

/**
 * ISI (Insomnia Severity Index) 7문항 검사 (FR-02-01, FR-07-01). Each item is scored 0–4 and the
 * total (0–28) is what the server stores as isi_score. One question per screen; picking an answer
 * moves on, ‹ goes back. The result page shows the score with the standard bands and the
 * "not a diagnosis" note, then [onDone] gets the total when the user confirms.
 */
class IsiSurvey(
    private val activity: MainActivity,
    private val confirmLabel: String,
    private val onDone: (Int) -> Unit,
) {
    private class Item(val question: String, val options: List<String>)

    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    private val answers = IntArray(ITEMS.size) { -1 }
    private var index = 0

    private val progressText = activity.label("", 12f, Palette.lavenderDeep, bold = true)
    private val progressFill = View(activity).apply { background = activity.rounded(Palette.lavender, 999) }
    private val progressTrack = FrameLayout(activity).apply {
        background = activity.rounded(Palette.lavenderSoft, 999)
        addView(progressFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val backButton = FrameLayout(activity).apply {
        addView(activity.icon(R.drawable.ic_chevron_right, Palette.ink, 22).apply { rotation = 180f },
            FrameLayout.LayoutParams(activity.dp(22), activity.dp(22), Gravity.CENTER))
        contentDescription = "이전 문항"
        setOnClickListener { back() }
    }
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.page)
            setPadding(activity.dp(20), activity.dp(28), activity.dp(20), activity.dp(24))
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(backButton, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        head.addView(activity.label("불면증 심각도 검사 (ISI)", 20f, title = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(4) })
        head.addView(progressText)
        root.addView(head)
        root.addView(progressTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(6))
            .apply { topMargin = activity.dp(14) })
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { topMargin = activity.dp(22) })

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Palette.page))
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) { back(); true } else false
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        renderQuestion()
    }

    private fun back() {
        when {
            index >= ITEMS.size -> { index = ITEMS.size - 1; renderQuestion() } // result → last question
            index > 0 -> { index--; renderQuestion() }
            else -> dialog.dismiss()
        }
    }

    private fun setProgress(done: Int) {
        progressText.text = if (done >= ITEMS.size) "완료" else "${done + 1} / ${ITEMS.size}"
        progressTrack.post {
            val w = progressTrack.width * (minOf(done + 1, ITEMS.size)) / ITEMS.size
            progressFill.layoutParams = progressFill.layoutParams.apply { width = w }
            progressFill.requestLayout()
        }
    }

    private fun renderQuestion() {
        setProgress(index)
        body.removeAllViews()
        val item = ITEMS[index]
        body.addView(activity.label(if (index < 3) "최근 2주 동안, 다음 문제는 얼마나 심했나요?" else "최근 2주 동안을 떠올려 주세요.",
            12.5f, Palette.muted, bold = true))
        body.addView(activity.label(item.question, 21f, title = true).apply {
            setLineSpacing(activity.dp(3).toFloat(), 1f)
            setPadding(0, activity.dp(10), 0, activity.dp(20))
        })
        item.options.forEachIndexed { score, text ->
            val selected = answers[index] == score
            val option = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(16), 0, activity.dp(16), 0)
                background = activity.cardBg(if (selected) Palette.lavenderSoft else Palette.card, 16,
                    if (selected) Palette.lavender else Palette.line)
                addView(activity.label(text, 15f, Palette.ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(activity.label("${score}점", 11f, Palette.muted))
                contentDescription = "$text (${score}점)"
                setOnClickListener { choose(score, this) }
            }
            body.addView(option, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(56))
                .apply { topMargin = activity.dp(10) })
        }
    }

    private fun choose(score: Int, option: View) {
        answers[index] = score
        option.background = activity.cardBg(Palette.lavenderSoft, 16, Palette.lavender)
        body.isEnabled = false
        // A short beat so the choice is visible before the next question.
        option.postDelayed({
            if (!dialog.isShowing) return@postDelayed
            index++
            if (index < ITEMS.size) renderQuestion() else renderResult()
        }, 160)
    }

    private fun renderResult() {
        setProgress(ITEMS.size)
        body.removeAllViews()
        val total = answers.sum()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(20), activity.dp(24), activity.dp(20), activity.dp(22))
            background = activity.cardBg(Palette.card, 22, Color.parseColor("#E6D4C8"))
        }
        card.addView(activity.label("나의 ISI 점수", 13f, Palette.muted, bold = true).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(activity.label(total.toString(), 44f, Palette.lavenderDeep, title = true))
        row.addView(activity.label(" / 28점", 14f, Palette.muted).apply { setPadding(0, 0, 0, activity.dp(6)) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(8) })
        card.addView(activity.label(band(total), 16f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0) })
        card.addView(activity.label("ISI는 불면 증상의 심각도를 파악하기 위한 설문이며,\n의학적 진단을 대신하지 않습니다.", 11.5f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            setPadding(0, activity.dp(14), 0, 0)
        })
        body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(12) })
        body.addView(Modals.button(activity, confirmLabel, true) { dialog.dismiss(); onDone(total) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(52)).apply { topMargin = activity.dp(22) })
        body.addView(Modals.button(activity, "다시 검사하기", false) { answers.fill(-1); index = 0; renderQuestion() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(46)).apply { topMargin = activity.dp(10) })
    }

    companion object {
        private val SEVERITY = listOf("없음", "약간", "중간", "심함", "매우 심함")
        private val AMOUNT = listOf("전혀 아니다", "약간", "어느 정도", "많이", "매우 많이")

        private val ITEMS = listOf(
            Item("잠들기 어려움", SEVERITY),
            Item("잠을 유지하기 어려움\n(자다가 자주 깸)", SEVERITY),
            Item("너무 일찍 깨는 문제", SEVERITY),
            Item("현재 수면 상태에\n얼마나 만족하나요?", listOf("매우 만족", "만족", "보통", "불만족", "매우 불만족")),
            Item("수면 문제가 다른 사람에게\n얼마나 눈에 띈다고 생각하나요?", AMOUNT),
            Item("현재 수면 문제 때문에\n얼마나 걱정되거나 괴로운가요?", AMOUNT),
            Item("수면 문제가 낮 동안의 생활\n(피로, 집중력, 기분 등)을 얼마나 방해하나요?", AMOUNT),
        )

        /** Standard ISI bands (same as 마이페이지). */
        fun band(score: Int) = when {
            score <= 7 -> "임상적으로 유의한 불면 증상 없음"
            score <= 14 -> "역치하 불면"
            score <= 21 -> "중등도 임상적 불면"
            else -> "심한 임상적 불면"
        }
    }
}
