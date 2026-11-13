package com.cbnu.nabi.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cbnu.nabi.BuildConfig

/*
 * Game-style onboarding tutorial: coach marks over the real screens (dim + spotlight + speech card).
 * It only points at existing UI — no data is changed while it runs (touches are blocked, except on
 * the cat in steps 1–2 so the user can really pet / long-press it with the existing behaviour).
 */

/** Auto start rule. Debug builds: never automatic (open it from 마이페이지 › 도움말).
 *  Release builds: automatic once, the first time the main screen is shown on this device. */
object TutorialConfig {
    val AUTO_START_ON_FIRST_USE: Boolean = !BuildConfig.DEBUG
}

/** "Seen the tutorial" flag, per device (not per account). Cleared only by clearing app data / reinstalling. */
object TutorialPrefs {
    private const val NAME = "nabi_tutorial"
    private const val K_DONE = "done"
    fun isDone(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(K_DONE, false)
    fun markDone(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply()
}

enum class TutorialGesture { NONE, TAP, LONG_PRESS }

class TutorialStep(
    val title: String,
    val body: String,
    /** Which screen to show first (tab on phones; all panes are visible on tablets). */
    val screen: MainActivity.Screen,
    /** The real views to spotlight. Evaluated after the screen is shown. */
    val targets: (MainActivity) -> List<View>,
    val gesture: TutorialGesture = TutorialGesture.NONE,
    /** Let touches inside the spotlight reach the app (only for the cat). */
    val passThrough: Boolean = false,
    /** Optional extra line depending on the current state (e.g. the cat is outside). */
    val note: (MainActivity) -> String? = { null },
    /** Scroll so the targets are visible before measuring. */
    val reveal: Boolean = false,
    /** The target is inside the 나의 수면 분석 sheet (opened for this step). */
    val inSheet: Boolean = false,
    /** Keep the card at the top of the screen (the cat walks around the floor below). */
    val cardAtTop: Boolean = false,
)

object NabiTutorial {
    /** True during the 꾹 누르기 step: the chat then opens read-only (see [ChatActivity.EXTRA_PREVIEW]). */
    var chatPreview = false

    fun steps(): List<TutorialStep> = listOf(
        TutorialStep("나비와 인사해 볼까요?", "나비를 톡 터치해 보세요! 나비가 반응해 줄 거예요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.tutorialCatTarget) },
            TutorialGesture.TAP, passThrough = true, note = ::catNote, cardAtTop = true),
        TutorialStep("나비와 대화할 수 있어요!", "나비를 꾹 길게 누르면 대화창이 열려요.\n지금은 구경만 할 수 있어요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.tutorialCatTarget) },
            TutorialGesture.LONG_PRESS, passThrough = true, note = ::catNote, cardAtTop = true),
        TutorialStep("오늘의 수면을 기록해요", "잠든 시간과 일어난 시간, 수면 상태를 기록할 수 있어요.",
            MainActivity.Screen.DIARY, { listOfNotNull(it.tutorialDiary?.recordButton) }, reveal = true),
        TutorialStep("나의 수면 패턴을 확인해요", "기록된 수면 정보를 바탕으로 나의 수면 패턴을 살펴볼 수 있어요.\n카드를 누르면 자세한 분석이 열려요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.analysisCard) }),
        TutorialStep("지금 나의 수면 상태예요", "평소 나의 수면 패턴과 비교해 지금 상태를 알려 주고, 달라진 점은 '살펴볼 점'에 보여 줘요.\n처음 며칠은 나의 평소 패턴을 알아가는 기간이에요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.tutorialAnalysis?.statusView) }, reveal = true, inSheet = true),
        TutorialStep("최근 7일의 흐름을 봐요", "그래프로 매일의 총 수면시간과 수면 효율이 어떻게 달라졌는지 확인할 수 있어요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.tutorialAnalysis?.trendView) }, reveal = true, inSheet = true),
        TutorialStep("평소와 비교해 봐요", "최근 기록을 평소 나의 수면과 비교하고, 그 아래에서 수면에 영향을 주는 생활 습관도 살펴볼 수 있어요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.tutorialAnalysis?.compareView) }, reveal = true, inSheet = true),
        TutorialStep("나비와 함께 작은 미션에 도전해요", "나비가 제안하는 미션을 확인하고 수락할 수 있어요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.missionButton) }),
        TutorialStep("나비를 위한 선물을 골라 보세요", "모은 코인으로 나비를 위한 아이템을 구매할 수 있어요.",
            MainActivity.Screen.HOME, { listOfNotNull(it.tutorialHome?.storeButton) }),
        TutorialStep("나에게 맞는 수면 습관을 만들어 가요",
            "목표 취침·기상 시각을 정해 보세요.\n'추천 수면 일정'에서는 나비가 추천하는 일정도 받아 볼 수 있어요.",
            MainActivity.Screen.PROFILE, { listOfNotNull(it.tutorialProfile?.goalCard) }, reveal = true),
    )

    private fun catNote(a: MainActivity): String? =
        if (a.tutorialHome?.catInRoom != false) null
        else "지금은 나비가 밖에서 놀고 있어요. 이 버튼으로 나비를 불러올 수 있어요."
}

/** The coach-mark layer, added on top of MainActivity's window. */
@SuppressLint("ViewConstructor")
class TutorialOverlay(
    private val activity: MainActivity,
    private val steps: List<TutorialStep>,
    private val onEnd: () -> Unit,
) : FrameLayout(activity) {

    private val d = resources.displayMetrics.density
    private var index = 0
    private var holes: List<RectF> = emptyList()
    private var animating = false
    private var passing = false
    private var ended = false
    private var cardTop = 0f

    private val dim = Paint().apply { color = Color.argb(160, 38, 26, 46) }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.parseColor("#D9C6F0") }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f * d; color = Color.argb(70, 201, 181, 230) }

    private val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = activity.cardBg(Color.parseColor("#FFFBF6"), 22, Color.parseColor("#E6D4C8"))
        elevation = 10f * d
        isClickable = true
    }
    private val arrow = Pointer()
    private val finger = Finger()
    private var fingerAnim: ValueAnimator? = null

    private val preDraw = ViewTreeObserver.OnPreDrawListener { if (!animating) track(); true }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null) // needed for the CLEAR holes
        addView(arrow, LayoutParams(dp(18), dp(10)))
        addView(finger, LayoutParams(dp(56), dp(56)))
        addView(card, LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(32), dp(360)), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.alpha = 0f; arrow.alpha = 0f; finger.alpha = 0f
    }

    fun start() {
        val decor = activity.window.decorView as ViewGroup
        decor.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        alpha = 0f
        animate().alpha(1f).setDuration(220).start()
        viewTreeObserver.addOnPreDrawListener(preDraw)
        go(0)
    }

    /** System back: previous step (never leaves the app). Always handled while running. */
    fun back() { if (!ended && index > 0) go(index - 1) }

    fun end() {
        if (ended) return
        ended = true
        NabiTutorial.chatPreview = false
        fingerAnim?.cancel()
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        animate().alpha(0f).setDuration(200).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onEnd()
        }.start()
    }

    // ---------------- steps ----------------
    private fun go(i: Int) {
        index = i
        val s = steps[i]
        NabiTutorial.chatPreview = s.gesture == TutorialGesture.LONG_PRESS
        // Hold the spotlight still until showStep() animates it (track() would otherwise jump to
        // the new targets while the screen is still switching).
        animating = true
        fingerAnim?.cancel()
        card.animate().alpha(0f).setDuration(120).start()
        arrow.animate().alpha(0f).setDuration(120).start()
        finger.animate().alpha(0f).setDuration(120).start()
        val appWindow = activity.window.decorView as ViewGroup
        val wasInSheet = parent !== appWindow
        // Leaving the analysis sheet: come back to the app window before the sheet closes.
        if (!s.inSheet && wasInSheet) moveTo(appWindow)
        activity.showScreenForTutorial(s.screen, s.inSheet)
        // Let the screen switch / the sheet slide up and lay out before measuring the real targets.
        postDelayed({
            if (ended || index != i) return@postDelayed
            // The sheet is its own window on top of the app, so the coach marks move into it.
            if (s.inSheet) activity.tutorialHome?.tutorialAnalysis?.sheetWindow?.let { if (parent !== it) moveTo(it) }
            if (s.reveal) s.targets(activity).firstOrNull()?.let { reveal(it) }
            postDelayed({ if (!ended && index == i) showStep(s) }, if (s.reveal) 320L else 0L)
        }, if (s.inSheet && !wasInSheet) 420L else 140L)
    }

    private fun moveTo(host: ViewGroup) {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        (parent as? ViewGroup)?.removeView(this)
        host.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        viewTreeObserver.addOnPreDrawListener(preDraw)
        holes = emptyList() // other window, other coordinates: grow the spotlight again
        invalidate()
    }

    private fun showStep(s: TutorialStep) {
        val to = measure(s)
        val from = if (holes.isEmpty()) to.map { RectF(it.centerX(), it.centerY(), it.centerX(), it.centerY()) } else holes
        animateHoles(from, to)
        buildCard(s)
        card.post { placeCard(s, to); startFinger(s, to.firstOrNull()) }
    }

    /** Scroll the page (or the sheet) that holds [v] so [v] sits near its top. */
    private fun reveal(v: View) {
        var top = 0
        var cur: View = v
        while (true) {
            val p = cur.parent as? View ?: return
            top += cur.top
            if (p is android.widget.ScrollView) { p.smoothScrollTo(0, (top - dp(12)).coerceAtLeast(0)); return }
            cur = p
        }
    }

    private fun measure(s: TutorialStep): List<RectF> {
        val me = IntArray(2).also { getLocationInWindow(it) }
        val pad = if (s.passThrough) 14f * d else 7f * d
        return s.targets(activity).filter { it.isShown && it.width > 0 }.map { v ->
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            RectF(loc[0] - me[0] - pad, loc[1] - me[1] - pad,
                loc[0] - me[0] + v.width * v.scaleX + pad, loc[1] - me[1] + v.height * v.scaleY + pad)
        }
    }

    /** Follow targets that move (the walking cat, scrolling, rotation). */
    private fun track() {
        if (ended) return
        val now = measure(steps[index])
        if (now.size == holes.size && now.zip(holes).all { (a, b) -> kotlin.math.abs(a.left - b.left) < 1 && kotlin.math.abs(a.top - b.top) < 1 &&
                    kotlin.math.abs(a.right - b.right) < 1 && kotlin.math.abs(a.bottom - b.bottom) < 1 }) return
        holes = now
        invalidate()
        now.firstOrNull()?.let { positionFinger(it); positionArrow(it) }
    }

    private fun animateHoles(from: List<RectF>, to: List<RectF>) {
        animating = true
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                holes = to.mapIndexed { k, b ->
                    val a = from.getOrElse(k) { from.lastOrNull() ?: b }
                    RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
                }
                invalidate()
            }
            doOnEnd { animating = false }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = f()
    })

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        val r = 18f * d
        holes.forEach { c.drawRoundRect(it, r, r, clear) }
        holes.forEach { c.drawRoundRect(it, r, r, glow); c.drawRoundRect(it, r, r, ring) }
    }

    // ---------------- card ----------------
    private fun buildCard(s: TutorialStep) {
        card.removeAllViews()
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(activity.label("${index + 1} / ${steps.size}", 11f, Palette.lavenderDeep, bold = true).apply {
            setPadding(dp(9), dp(3), dp(9), dp(3)); background = activity.rounded(Palette.lavenderSoft, 999)
        })
        top.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(activity.label("건너뛰기", 12f, Palette.muted, bold = true).apply {
            setPadding(dp(10), dp(6), 0, dp(6)); setOnClickListener { end() }
        })
        card.addView(top)
        card.addView(activity.label(s.title, 18f, title = true).apply { setPadding(0, dp(10), 0, 0) })
        if (s.gesture == TutorialGesture.LONG_PRESS) card.addView(activity.label("꾹 누르기", 12f, Color.WHITE, bold = true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4)); background = activity.rounded(Color.parseColor("#9D7BC4"), 999)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(activity.label(s.body, 13f, Palette.ink).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        s.note(activity)?.let { card.addView(activity.label(it, 11.5f, Palette.muted).apply { setPadding(0, dp(6), 0, 0) }) }

        val nav = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(activity.label("이전", 13f, Palette.muted, bold = true).apply {
            setPadding(dp(4), dp(10), dp(16), dp(10))
            visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
            setOnClickListener { back() }
        })
        nav.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        val last = index == steps.lastIndex
        nav.addView(Modals.button(activity, if (last) "시작하기" else "다음", true) { if (last) end() else go(index + 1) }.apply {
            setPadding(dp(22), 0, dp(22), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        card.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    /** Below the spotlight if it fits, otherwise above — never over the target. */
    private fun placeCard(s: TutorialStep, to: List<RectF>) {
        val target = to.reduceOrNull { a, b -> RectF(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
        val ch = card.height.toFloat()
        val margin = 14f * d
        val below = target == null || height - target.bottom > ch + margin * 2 + dp(20)
        val above = target != null && target.top - margin - dp(8) - ch >= dp(16)
        val y = when {
            s.cardAtTop -> contentTop() + dp(12) // above the room floor, so the walking cat never goes behind it
            target == null -> (height - ch) / 2f
            below -> target.bottom + margin + dp(8)
            above -> target.top - margin - dp(8) - ch
            else -> height - ch - dp(16).toFloat() // a very tall target: the card sits at the bottom edge
        }
        card.x = (width - card.width) / 2f
        card.translationY = 0f
        card.y = y + dp(10)
        card.animate().alpha(1f).y(y).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        cardTop = y
        arrow.pointUp = below
        to.firstOrNull()?.let { positionArrow(it) }
        val showArrow = target != null && !s.cardAtTop && (below || above)
        arrow.animate().alpha(if (showArrow) 1f else 0f).setStartDelay(80).setDuration(200).start()
    }

    /** Top of the app's content (below the status bar) in this layer's coordinates. */
    private fun contentTop(): Float {
        val me = IntArray(2).also { getLocationInWindow(it) }
        val content = IntArray(2).also { activity.findViewById<View>(android.R.id.content).getLocationInWindow(it) }
        return (content[1] - me[1]).coerceAtLeast(0).toFloat()
    }

    private fun positionArrow(hole: RectF) {
        val aw = arrow.width.takeIf { it > 0 } ?: dp(18)
        val cx = hole.centerX().coerceIn(card.x + dp(28), card.x + card.width - dp(28))
        arrow.x = cx - aw / 2f
        arrow.y = if (arrow.pointUp) cardTop - dp(9) else cardTop + card.height - dp(1)
    }

    // ---------------- finger (tap / long-press hint) ----------------
    private fun startFinger(s: TutorialStep, hole: RectF?) {
        fingerAnim?.cancel()
        if (hole == null || s.gesture == TutorialGesture.NONE) { finger.alpha = 0f; return }
        positionFinger(hole)
        finger.long = s.gesture == TutorialGesture.LONG_PRESS
        finger.animate().alpha(1f).setDuration(200).start()
        fingerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (finger.long) 1600 else 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { finger.phase = it.animatedValue as Float; finger.invalidate() }
            start()
        }
    }

    private fun positionFinger(hole: RectF) {
        finger.x = hole.centerX() - dp(28) + dp(10)
        finger.y = hole.centerY() - dp(28) + dp(12)
    }

    // ---------------- touches ----------------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val s = steps[index]
            val inCard = ev.x >= card.x && ev.x <= card.x + card.width && ev.y >= card.y && ev.y <= card.y + card.height
            passing = s.passThrough && activity.tutorialHome?.catInRoom == true && !animating && !inCard &&
                    holes.firstOrNull()?.contains(ev.x, ev.y) == true
        }
        // The cat in steps 1–2: let the gesture through to the real cat (existing pet / long-press).
        if (passing) return false
        super.dispatchTouchEvent(ev)
        return true // everything else is blocked so nothing real gets tapped
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = true

    private fun dp(v: Int) = (v * d).toInt()

    /** Small triangle joining the card to the spotlight. */
    private inner class Pointer : View(activity) {
        var pointUp = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFBF6") }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val path = Path().apply {
                if (pointUp) { moveTo(0f, h); lineTo(w / 2, 0f); lineTo(w, h) } else { moveTo(0f, 0f); lineTo(w / 2, h); lineTo(w, 0f) }
                close()
            }
            c.drawPath(path, p)
        }
    }

    /** Touch hint: TAP = a quick press with a ripple; LONG_PRESS = held press with a filling ring. */
    private inner class Finger : View(activity) {
        var phase = 0f
        var long = false
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.parseColor("#9D7BC4") }
        private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#C9B5E6") }
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val base = 11f * d
            if (!long) {
                // press at 0–0.2, ripple 0.15–0.7
                val press = if (phase < 0.2f) 1f - 0.18f * kotlin.math.sin(phase / 0.2f * Math.PI).toFloat() else 1f
                if (phase in 0.15f..0.7f) {
                    val t = (phase - 0.15f) / 0.55f
                    ripple.alpha = ((1f - t) * 200).toInt()
                    c.drawCircle(cx, cy, base + t * 16f * d, ripple)
                }
                c.drawCircle(cx, cy, base * press, dot); c.drawCircle(cx, cy, base * press, edge)
            } else {
                // hold: pressed dot + ring filling over 0.1–0.8, then release
                val held = phase in 0.1f..0.85f
                val r = if (held) base * 0.86f else base
                if (phase > 0.1f) {
                    val t = ((phase - 0.1f) / 0.7f).coerceIn(0f, 1f)
                    box.set(cx - base - 8f * d, cy - base - 8f * d, cx + base + 8f * d, cy + base + 8f * d)
                    arc.alpha = if (phase > 0.85f) ((1f - (phase - 0.85f) / 0.15f) * 255).toInt() else 255
                    c.drawArc(box, -90f, 360f * t, false, arc)
                }
                c.drawCircle(cx, cy, r, dot); c.drawCircle(cx, cy, r, edge)
            }
        }
    }
}
