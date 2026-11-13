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
 */
class BottomSheet(private val activity: Activity, title: String, subtitle: String? = null) {

    private val dialog = Dialog(activity)
    /** Put the sheet content here. */
    val body: Column = Column(activity, 10)
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    // No scrollbar: it drew over the cards' right edge. Drag-scrolling still works as before.
    private val scroll = ScrollView(activity).apply { addView(body.root); isVerticalScrollBarEnabled = false }
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
            // lighter dim when the screen behind is blurred too
            setDimAmount(if (android.os.Build.VERSION.SDK_INT >= 31) 0.2f else 0.35f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // blur the app behind the sheet while it is open
        dialog.setOnShowListener { Blur.push(activity.window.decorView) }
        dialog.setOnDismissListener { Blur.pop(activity.window.decorView); onDismiss?.invoke() }
        dialog.setCanceledOnTouchOutside(true)
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
        // Full width on phones; at most 560dp, centred, on tablets.
        val w = minOf(activity.resources.displayMetrics.widthPixels, activity.dp(MAX_WIDTH_DP))
        dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.decorView?.post {
            val h = dialog.window?.decorView?.height ?: 0
            if (h > maxH) dialog.window?.setLayout(w, maxH)
        }
    }

    fun dismiss() = dialog.dismiss()

    /**
     * Tutorial: the sheet can't be dragged, tapped outside or backed out of; back goes to [onBack]
     * (the previous tutorial step). The dim matches the tutorial's own dim.
     */
    fun lockForTutorial(onBack: () -> Unit) {
        dialog.setCancelable(false)
        dialog.window?.setDimAmount(0.62f)
        dialog.setOnKeyListener { _, code, ev ->
            if (code != android.view.KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (ev.action == android.view.KeyEvent.ACTION_UP) onBack()
            true
        }
    }

    /** Start at the top of the content (e.g. right after [show]). */
    fun scrollToTop() = scroll.post { scroll.scrollTo(0, 0) }

    /** Called after the sheet closes (drag, outside tap, back or [dismiss]). */
    var onDismiss: (() -> Unit)? = null

    companion object {
        const val MAX_WIDTH_DP = 560
    }

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
