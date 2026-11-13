package com.cbnu.nabi.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cbnu.nabi.R

/**
 * "나비의 제안" — small centred modal for one [MissionProposal] (patent [0045] mission card).
 * 수락하기 (primary) / 나중에 / 무시 (text buttons). Closing with ✕, back or an outside tap
 * records no response.
 */
class MissionProposalModal(
    private val activity: MainActivity,
    private val proposal: MissionProposal,
    private val onResponse: (ProposalResponse) -> Unit,
) {
    private val dialog = Dialog(activity)

    fun show() {
        val box = Modals.box(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }

        // header: "나비의 제안" + close (✕)
        val head = FrameLayout(activity)
        head.addView(activity.label("나비의 제안", 12f, Palette.lavenderDeep, bold = true).apply {
            setPadding(activity.dp(10), activity.dp(4), activity.dp(10), activity.dp(4))
            background = activity.rounded(Palette.lavenderSoft, 999)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        head.addView(FrameLayout(activity).apply {
            addView(activity.icon(R.drawable.ic_plus, Palette.muted, 16).apply { rotation = 45f },
                FrameLayout.LayoutParams(activity.dp(16), activity.dp(16), Gravity.CENTER))
            contentDescription = "닫기"
            setOnClickListener { dialog.dismiss() } // no response recorded
        }, FrameLayout.LayoutParams(activity.dp(36), activity.dp(36), Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { setMargins(0, -activity.dp(10), -activity.dp(10), 0) })
        box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // kind icon
        box.addView(FrameLayout(activity).apply {
            background = activity.rounded(Color.parseColor("#F1E9F6"), 16)
            addView(activity.icon(proposal.kind.iconRes, Color.parseColor("#8264A1"), 24),
                FrameLayout.LayoutParams(activity.dp(24), activity.dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply { topMargin = activity.dp(14) })

        box.addView(activity.label(proposal.title, 19f, title = true).apply {
            gravity = Gravity.CENTER; setPadding(0, activity.dp(12), 0, 0)
        })
        box.addView(activity.label(proposal.description, 12.5f, Palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(activity.dp(4), activity.dp(8), activity.dp(4), 0)
            setLineSpacing(activity.dp(3).toFloat(), 1f)
        })
        if (proposal.preview) box.addView(activity.label("UI 미리보기 · 실제 추천이 아니에요", 10f, Palette.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, activity.dp(8), 0, 0)
        })

        // 수락하기 (primary, full width) — then 나중에 · 무시 as text buttons
        box.addView(Modals.button(activity, "수락하기", true) { respond(ProposalResponse.ACCEPTED) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(46)).apply { topMargin = activity.dp(18) })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(textButton("나중에") { respond(ProposalResponse.LATER) }, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        row.addView(textButton("무시") { respond(ProposalResponse.DISMISSED) }, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(4) })

        // soft dim behind (no blur), centred, sized to its content
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setWindowAnimations(android.R.style.Animation_Dialog)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(minOf(activity.dp(320), activity.resources.displayMetrics.widthPixels - activity.dp(40)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun respond(r: ProposalResponse) {
        dialog.dismiss()
        onResponse(r)
    }

    private fun textButton(text: String, onClick: () -> Unit) = activity.label(text, 13f, Palette.muted, bold = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
