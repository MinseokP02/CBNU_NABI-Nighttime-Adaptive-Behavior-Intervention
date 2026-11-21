package com.cbnu.nabi.pet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.cbnu.nabi.R

/**
 * "나비의 제안 · 산책" card shown OVER OTHER APPS next to the overlay cat. [onAccept] / [onLater]
 * are called once; the card removes itself. It also goes away by itself after [AUTO_HIDE_MS]
 * (no response recorded then).
 */
class WalkSuggestionCard(
    private val context: Context,
    private val wm: WindowManager,
    private val title: String,
    private val description: String,
    private val onAccept: () -> Unit,
    private val onLater: () -> Unit,
) {
    private var view: View? = null
    val isShowing get() = view != null

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun font(title: Boolean) = runCatching { context.resources.getFont(if (title) R.font.jua else R.font.gowun_dodum) }.getOrNull()

    private fun text(s: String, size: Float, color: Int, title: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create(font(title), Typeface.BOLD) else font(title)
        includeFontPadding = false
    }

    private fun round(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    fun show() {
        if (view != null) return
        val ink = Color.parseColor("#4A3027")
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = round(Color.parseColor("#FFFAF5"), 22, Color.parseColor("#DDC9BB"))
            elevation = dp(8).toFloat()
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_paw)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8264A1"))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        head.addView(text("나비의 제안", 12f, Color.parseColor("#71568D"), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        box.addView(head)
        box.addView(text(title, 18f, ink, title = true).apply { setPadding(0, dp(10), 0, 0) })
        box.addView(text(description, 12.5f, Color.parseColor("#75645B")).apply {
            setPadding(0, dp(6), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        val later = text("나중에", 14f, ink, bold = true).apply {
            gravity = Gravity.CENTER; background = round(Color.parseColor("#F0E7E1"), 14)
            setOnClickListener { dismiss(); onLater() }
        }
        val accept = text("좋아, 같이 걷자", 14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; background = round(Color.parseColor("#9D7BC4"), 14)
            setOnClickListener { dismiss(); onAccept() }
        }
        row.addView(later, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(accept, LinearLayout.LayoutParams(0, dp(44), 1.5f).apply { leftMargin = dp(8) })
        box.addView(row)

        val width = minOf(dp(340), context.resources.displayMetrics.widthPixels - dp(32))
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(110)
            windowAnimations = android.R.style.Animation_Toast
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(box, lp) }.onSuccess {
            view = box
            box.postDelayed({ dismiss() }, AUTO_HIDE_MS)
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    companion object {
        const val AUTO_HIDE_MS = 30_000L
    }
}
