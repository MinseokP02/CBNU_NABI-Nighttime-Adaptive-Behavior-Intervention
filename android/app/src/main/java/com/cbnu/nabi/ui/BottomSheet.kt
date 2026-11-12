package com.cbnu.nabi.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Figma-style bottom sheet: slides up from the bottom with a handle, a title and a scrolling body.
 * Drag it down (from the handle, or anywhere while the content is scrolled to the top) to close it.
 * While it is open the overlay cat stays hidden ([CatPresence]).
 */
class BottomSheet(private val activity: Activity, title: String, subtitle: String? = null) {

    private val dialog = Dialog(activity)
    /** Put the sheet content here. */
    val body: Column = Column(activity, 10)
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(activity).apply { addView(body.root) }
    private val sheet = DragSheet()

    /** The sheet's window, e.g. to blur it behind a modal on top. */
    val decorView: View? get() = dialog.window?.decorView

    init {
        sheet.apply {
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
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = activity.dp(8) })
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

    /** The sheet container: follows a downward drag and closes past a threshold (or on a quick fling). */
    private inner class DragSheet : LinearLayout(activity) {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downY = 0f
        private var dragging = false
        private var velocity: VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    // Only take over a downward drag when the content can't scroll further up.
                    if (dy > slop && abs(dy) > slop && !scroll.canScrollVertically(-1)) {
                        dragging = true
                        velocity = VelocityTracker.obtain()
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocity?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; return true }
                MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    translationY = (ev.rawY - downY).coerceAtLeast(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity?.computeCurrentVelocity(1000)
                    val fling = (velocity?.yVelocity ?: 0f) > activity.dp(900)
                    velocity?.recycle(); velocity = null
                    if (dragging && (translationY > height * 0.25f || fling)) slideOut() else settle()
                    dragging = false
                }
            }
            return true
        }

        private fun settle() = animate().translationY(0f).setDuration(180).start()

        private fun slideOut() {
            dialog.window?.setWindowAnimations(0) // we already slid it; skip the window exit animation
            animate().translationY(height.toFloat()).setDuration(180).withEndAction { dialog.dismiss() }.start()
        }
    }
}
