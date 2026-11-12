package com.cbnu.nabi.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Point
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.pet.CatView
import com.cbnu.nabi.pet.PetHost

/**
 * The existing [CatView] reused inside the home room (FR-05-01~04). The cat's "screen" is only the
 * [floor] area, so it walks, sits and sleeps on the room floor and never wanders over the rest of
 * the UI (FR-05-02). Touch, petting reactions and long-press → chat work exactly like the overlay.
 */
class RoomCat(private val activity: Activity, private val floor: FrameLayout) : PetHost {

    private var cat: CatView? = null
    private var touch: View? = null

    val isShown: Boolean get() = cat != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (cat != null) return
        if (floor.width == 0 || floor.height == 0) { floor.post { show() }; return }
        val c = CatView(activity, this)
        // Same split as the overlay: the cat view draws only; a small box around its body takes touches.
        val t = object : View(activity) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                c.handleTouch(ev.actionMasked, ev.x + translationX - c.translationX, ev.y + translationY - c.translationY)
                return true
            }
        }
        t.contentDescription = "나비. 쓰다듬거나 길게 눌러 대화하기"
        floor.addView(c, FrameLayout.LayoutParams(c.winW, c.winH))
        floor.addView(t, FrameLayout.LayoutParams(1, 1))
        cat = c; touch = t
    }

    fun hide() {
        cat?.let { floor.removeView(it) }
        touch?.let { floor.removeView(it) }
        cat = null; touch = null
    }

    /** The floor was resized (rotation, split screen). */
    fun onFloorResized() = cat?.onScreenChanged()

    /** "나비 부르기" while the cat is already in the room: it walks over to the user. */
    fun callOver() = cat?.callOver()

    fun react(action: CatAction, line: String?) = cat?.applyAction(action, line)

    /** Top-centre of the cat's body in [floor] coordinates (for placing a hint above it), or null. */
    fun anchor(): Pair<Float, Float>? {
        val t = touch ?: return null
        return (t.translationX + t.layoutParams.width / 2f) to t.translationY
    }

    // ---- PetHost ----
    override fun place(dispX: Int, dispY: Int, tx: Int, ty: Int, tw: Int, th: Int) {
        cat?.apply { translationX = dispX.toFloat(); translationY = dispY.toFloat() }
        val t = touch ?: return
        t.translationX = tx.toFloat(); t.translationY = ty.toFloat()
        val lp = t.layoutParams
        if (lp.width != tw || lp.height != th) {
            lp.width = tw; lp.height = th
            t.layoutParams = lp
        }
    }

    override fun buzz(kind: Int) {
        if (Prefs(activity).haptics) floor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun screenSize(): Point = Point(floor.width, floor.height)

    override fun openChat() {
        activity.startActivity(Intent(activity, ChatActivity::class.java))
    }
}
