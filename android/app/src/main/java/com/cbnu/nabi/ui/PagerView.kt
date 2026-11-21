package com.cbnu.nabi.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * Minimal horizontal pager (no androidx in this app): children sit side by side, each the full
 * width; a horizontal swipe or [show] moves between them. Vertical drags still reach the
 * children, so each page can be its own ScrollView.
 */
class PagerView(context: Context) : ViewGroup(context) {

    var page = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    fun show(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        val changed = target != page
        page = target
        anim?.cancel()
        if (!animate || width == 0) {
            scrollTo(target * width, 0)
        } else {
            anim = ValueAnimator.ofInt(scrollX, target * width).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
        if (changed) onPageChanged?.invoke(page)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (anim?.isRunning != true) scrollTo(page * w, 0) // keep the page across rotation / resize
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; dragging = false
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                velocity?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    lastX = ev.x
                    anim?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = (lastX - ev.x).toInt()
                lastX = ev.x
                val max = (childCount - 1) * width
                scrollTo((scrollX + dx).coerceIn(0, max), 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val nearest = if (width > 0) Math.round(scrollX.toFloat() / width) else page
                val target = when {
                    vx < -minFling -> page + 1
                    vx > minFling -> page - 1
                    else -> nearest
                }
                dragging = false
                velocity?.recycle(); velocity = null
                show(target)
            }
        }
        return true
    }
}
