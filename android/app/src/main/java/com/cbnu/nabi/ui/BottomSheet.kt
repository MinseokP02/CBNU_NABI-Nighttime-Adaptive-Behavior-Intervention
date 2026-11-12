package com.cbnu.nabi.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Figma-style bottom sheet: slides up from the bottom with a handle, a title and a scrolling body.
 * While it is open the overlay cat stays hidden ([CatPresence]).
 */
class BottomSheet(private val activity: Activity, title: String, subtitle: String? = null) {

    private val dialog = Dialog(activity)
    /** Put the sheet content here. */
    val body: Column = Column(activity, 10)
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FBF6F1"))
                val r = activity.dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(activity.dp(16), activity.dp(10), activity.dp(16), activity.dp(16))
        }
        sheet.addView(View(activity).apply {
            background = activity.rounded(Color.argb(90, 117, 100, 91), 3)
        }, LinearLayout.LayoutParams(activity.dp(40), activity.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        sheet.addView(activity.label(title, 22f, title = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = activity.dp(14) })
        if (subtitle != null) {
            sheet.addView(activity.label(subtitle, 12f, Palette.muted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = activity.dp(6) })
        }
        sheet.addView(ScrollView(activity).apply {
            isFillViewport = false
            addView(body.root)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(8) })
        sheet.addView(footer)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.35f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnShowListener { CatPresence.sheetOpened() }
        dialog.setOnDismissListener { CatPresence.sheetClosed() }
    }

    /** A view pinned under the scrolling body (e.g. the "기록 저장" button). */
    fun setFooter(v: View) {
        footer.removeAllViews()
        footer.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(12) })
    }

    fun show() {
        dialog.show()
        // Full width, at most 88% of the screen height.
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.decorView?.post {
            val h = dialog.window?.decorView?.height ?: 0
            if (h > maxH) dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        }
    }

    fun dismiss() = dialog.dismiss()
}
