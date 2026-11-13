package com.cbnu.nabi.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cbnu.nabi.R
import com.cbnu.nabi.ui.ShopStore.Placement
import com.cbnu.nabi.ui.ShopStore.Slot

/**
 * "방 꾸미기" mode, cozy-2D-game style (no selection boxes or corner handles):
 * tap a piece of furniture → it pops up a little with a soft shadow under it;
 * drag it around; pinch with two fingers or tap − / + in the little bubble above it to resize;
 * "처음 배치로" restores the Figma layout, "완료" saves and leaves.
 *
 * Positions are kept as room fractions ([Placement]) so the room looks the same on every screen.
 */
@SuppressLint("ViewConstructor")
class RoomDecorator(
    private val activity: MainActivity,
    private val room: FrameLayout,
    private val items: Map<Slot, ImageView>,
    private val aspect: (ImageView) -> Float,
    private val apply: (Slot, Placement) -> Unit,
    /** [saved] is null when cancelled; [stored] = items put back into 내 아이템 (only when saved). */
    private val onFinish: (saved: Map<Slot, Placement>?, stored: Set<Slot>) -> Unit,
) : FrameLayout(activity) {

    private val work = mutableMapOf<Slot, Placement>()
    /** The room as it was when 방 꾸미기 opened — "처음 배치로" goes back to this. */
    private val initial = mutableMapOf<Slot, Placement>()
    /** Items put away during this session (applied only on 완료). */
    private val stored = mutableSetOf<Slot>()
    private var selected: Slot? = null
    private var dragging = false
    private var grabDx = 0f
    private var grabDy = 0f

    private val shadow = View(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Color.argb(70, 60, 35, 25), Color.argb(0, 60, 35, 25))
        }
        visibility = View.GONE
    }
    private val nameText = activity.label("", 11f, Palette.ink, bold = true)
    private val bubble = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
        background = activity.cardBg(Color.argb(240, 255, 250, 246), 999, Color.argb(200, 185, 155, 137))
        elevation = activity.dp(4).toFloat()
        addView(roundButton(R.drawable.ic_minus, "작게") { resizeBy(0.9f) })
        addView(nameText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = activity.dp(8); rightMargin = activity.dp(8) })
        addView(roundButton(R.drawable.ic_plus, "크게") { resizeBy(1.1f) })
        // "보관": take this piece out of the room (back to 내 아이템 › 보관 중)
        addView(activity.label("보관", 11f, Palette.lavenderDeep, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(activity.dp(12), 0, activity.dp(12), 0)
            background = activity.cardBg(Color.WHITE, 999, Color.parseColor("#C9B5E6"))
            contentDescription = "이 가구 보관하기"
            setOnClickListener { storeSelected() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(34)).apply { leftMargin = activity.dp(6) })
        visibility = View.GONE
    }

    private val scaler = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { resizeBy(d.scaleFactor); return true }
    })

    init {
        // shadow sits just above the furniture layer but below the bubble and bars
        addView(shadow, LayoutParams(0, 0))
        addView(banner(), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(activity.dp(16), activity.dp(12), activity.dp(16), 0)
        })
        addView(bubble, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(42)))
        addView(bottomBar(), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(activity.dp(16), 0, activity.dp(16), activity.dp(20))
        })
        captureFromViews()
        initial.putAll(work)
    }

    // ---------------- UI ----------------
    private fun banner() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
        background = activity.cardBg(Color.argb(242, 255, 249, 243), 20, Color.argb(178, 185, 155, 137))
        elevation = activity.dp(4).toFloat()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(activity.icon(R.drawable.ic_sparkle, Palette.lavender, 20))
        row.addView(activity.label("방 꾸미기", 18f, title = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(7) })
        addView(row)
        addView(activity.label("가구를 눌러서 옮겨 보세요.\n두 손가락이나 − / + 로 크기를 바꾸고, 보관하면 내 아이템으로 돌아가요.", 11.5f, Palette.muted).apply {
            setPadding(0, activity.dp(6), 0, 0); setLineSpacing(activity.dp(2).toFloat(), 1f)
        })
    }

    private fun bottomBar() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Modals.button(activity, "처음 배치로", false) { reset() }.apply { elevation = activity.dp(3).toFloat() },
            LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        addView(Modals.button(activity, "완료", true) { finish(true) }.apply { elevation = activity.dp(3).toFloat() },
            LinearLayout.LayoutParams(0, activity.dp(48), 1.4f).apply { leftMargin = activity.dp(10) })
    }

    private fun roundButton(res: Int, desc: String, onClick: () -> Unit) = FrameLayout(activity).apply {
        background = activity.rounded(Palette.lavenderSoft, 999)
        addView(activity.icon(res, Palette.lavenderDeep, 16), LayoutParams(activity.dp(16), activity.dp(16), Gravity.CENTER))
        contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(activity.dp(34), activity.dp(34))
    }

    // ---------------- state ----------------
    /** Read where the furniture currently is (saved or default layout) as fractions. */
    private fun captureFromViews() {
        val w = room.width.toFloat(); val h = room.height.toFloat()
        if (w <= 0f || h <= 0f) return
        work.clear()
        for ((slot, v) in items) {
            if (v.visibility != View.VISIBLE || v.width == 0) continue
            work[slot] = Placement((v.left + v.width / 2f) / w, v.bottom / h, v.width / w)
        }
    }

    private fun select(slot: Slot?) {
        val prev = selected
        if (prev != null && prev != slot) items[prev]?.animate()?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.setDuration(150)?.start()
        selected = slot
        if (slot == null) { shadow.visibility = View.GONE; bubble.visibility = View.GONE; return }
        val v = items.getValue(slot)
        v.pivotX = v.width / 2f; v.pivotY = v.height.toFloat()
        // "picked up": a little pop with an overshoot, like lifting it off the floor
        v.animate().scaleX(1.06f).scaleY(1.06f).translationY(-activity.dp(6).toFloat())
            .setInterpolator(OvershootInterpolator(3f)).setDuration(220).start()
        nameText.text = when (slot) { Slot.RUG -> "카펫"; Slot.TOWER -> "캣타워"; Slot.PLANT -> "화분" }
        shadow.visibility = View.VISIBLE
        bubble.visibility = View.VISIBLE
        follow()
    }

    /** Keep the shadow under and the bubble above the selected item. */
    private fun follow() {
        val slot = selected ?: return
        val v = items.getValue(slot)
        val p = work[slot] ?: return
        val w = room.width; val h = room.height
        val itemW = (p.width * w).toInt()
        val itemH = (itemW * aspect(v)).toInt()
        val cx = (p.cx * w).toInt()
        val bottom = (p.bottom * h).toInt()
        (shadow.layoutParams as LayoutParams).apply {
            width = (itemW * 0.9f).toInt(); height = activity.dp(18)
            leftMargin = cx - width / 2; topMargin = bottom - height / 2
            gravity = Gravity.TOP or Gravity.START
        }
        shadow.requestLayout()
        bubble.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        (bubble.layoutParams as LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (cx - bubble.measuredWidth / 2).coerceIn(activity.dp(8), w - bubble.measuredWidth - activity.dp(8))
            topMargin = (bottom - itemH - activity.dp(56)).coerceAtLeast(activity.dp(8))
        }
        bubble.requestLayout()
    }

    private fun update(slot: Slot, p: Placement) {
        val minW = activity.dp(56).toFloat() / room.width
        val clamped = Placement(
            cx = p.cx.coerceIn(0.03f, 0.97f),
            bottom = p.bottom.coerceIn(0.38f, 1f - activity.dp(4).toFloat() / room.height),
            width = p.width.coerceIn(minW, 0.95f),
        )
        work[slot] = clamped
        apply(slot, clamped)
        follow()
    }

    private fun resizeBy(factor: Float) {
        val slot = selected ?: return
        val p = work[slot] ?: return
        update(slot, p.copy(width = p.width * factor))
    }

    /** "처음 배치로": back to how the room was when 방 꾸미기 opened (stored items come back too). */
    private fun reset() {
        select(null)
        stored.forEach { items[it]?.visibility = View.VISIBLE }
        stored.clear()
        work.clear()
        work.putAll(initial)
        initial.forEach { (slot, p) -> apply(slot, p) }
    }

    private fun storeSelected() {
        val s = selected ?: return
        select(null)
        stored += s
        work.remove(s)
        items[s]?.visibility = View.GONE
    }

    private fun finish(save: Boolean) {
        select(null)
        if (save) onFinish(work.toMap(), stored.toSet()) else onFinish(null, emptySet())
    }

    /** System back: leave without saving anything. */
    fun cancel() = finish(false)

    // ---------------- touch ----------------
    /** Topmost visible item under the finger (cat tower, then plant, then rug). */
    private fun hit(x: Float, y: Float): Slot? = listOf(Slot.TOWER, Slot.PLANT, Slot.RUG).firstOrNull { s ->
        val v = items[s] ?: return@firstOrNull false
        v.visibility == View.VISIBLE && x >= v.left && x <= v.right && y >= v.top && y <= v.bottom
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        val w = room.width.toFloat(); val h = room.height.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = hit(ev.x, ev.y)
                select(s)
                dragging = s != null
                if (s != null) {
                    val p = work[s]!!
                    grabDx = ev.x - p.cx * w
                    grabDy = ev.y - p.bottom * h
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false // two fingers: resize only
            MotionEvent.ACTION_MOVE -> {
                val s = selected
                if (dragging && s != null && ev.pointerCount == 1 && !scaler.isInProgress) {
                    val p = work[s]!!
                    update(s, p.copy(cx = (ev.x - grabDx) / w, bottom = (ev.y - grabDy) / h))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                // set it down with a little bounce, still selected
                selected?.let { items[it]?.animate()?.translationY(-activity.dp(3).toFloat())?.setDuration(120)?.start() }
            }
        }
        return true
    }
}
