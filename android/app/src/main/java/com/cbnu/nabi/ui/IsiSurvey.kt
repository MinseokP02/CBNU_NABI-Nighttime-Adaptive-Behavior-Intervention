package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
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
        contentDescription = tr(R.string.previous_question)
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
        head.addView(activity.label(tr(R.string.insomnia_severity_index_isi), 20f, title = true),
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
        progressText.text = if (done >= ITEMS.size) tr(R.string.done) else "${done + 1} / ${ITEMS.size}"
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
        body.addView(activity.label(if (index < 3) tr(R.string.over_last_2_weeks_how_severe) else tr(R.string.think_about_last_2_weeks),
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
                addView(activity.label(tr(R.string.pts_2, score), 11f, Palette.muted))
                contentDescription = tr(R.string.pts, text, score)
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
        card.addView(activity.label(tr(R.string.my_isi_score), 13f, Palette.muted, bold = true).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        row.addView(activity.label(total.toString(), 44f, Palette.lavenderDeep, title = true))
        row.addView(activity.label(tr(R.string.n_28), 14f, Palette.muted).apply { setPadding(0, 0, 0, activity.dp(6)) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(8) })
        card.addView(activity.label(band(total), 16f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0) })
        card.addView(activity.label(tr(R.string.isi_measures_how_severe_insomnia_symptoms), 11.5f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(activity.dp(2).toFloat(), 1f)
            setPadding(0, activity.dp(14), 0, 0)
        })
        body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(12) })
        body.addView(Modals.button(activity, confirmLabel, true) { dialog.dismiss(); onDone(total) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(52)).apply { topMargin = activity.dp(22) })
        body.addView(Modals.button(activity, tr(R.string.take_it_again), false) { answers.fill(-1); index = 0; renderQuestion() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(46)).apply { topMargin = activity.dp(10) })
    }

    companion object {
        private val SEVERITY get() = listOf(tr(R.string.none), tr(R.string.mild), tr(R.string.moderate_2), tr(R.string.severe), tr(R.string.very_severe))
        private val AMOUNT get() = listOf(tr(R.string.not_at_all), tr(R.string.mild), tr(R.string.somewhat), tr(R.string.much), tr(R.string.very_much))

        private val ITEMS get() = listOf(
            Item(tr(R.string.difficulty_falling_asleep), SEVERITY),
            Item(tr(R.string.difficulty_staying_asleep_waking_up_often), SEVERITY),
            Item(tr(R.string.waking_up_too_early), SEVERITY),
            Item(tr(R.string.how_satisfied_are_you_with_your), listOf(tr(R.string.very_satisfied), tr(R.string.satisfied), tr(R.string.moderate), tr(R.string.dissatisfied), tr(R.string.very_dissatisfied))),
            Item(tr(R.string.how_noticeable_do_you_think_your), AMOUNT),
            Item(tr(R.string.how_worried_or_distressed_are_you), AMOUNT),
            Item(tr(R.string.how_much_does_your_sleep_problem), AMOUNT),
        )

        /** Standard ISI bands (same as 마이페이지). */
        fun band(score: Int) = when {
            score <= 7 -> tr(R.string.no_clinically_significant_insomnia)
            score <= 14 -> tr(R.string.subthreshold_insomnia)
            score <= 21 -> tr(R.string.moderate_clinical_insomnia)
            else -> tr(R.string.severe_clinical_insomnia)
        }
    }
}
