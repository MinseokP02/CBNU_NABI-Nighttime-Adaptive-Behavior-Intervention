package com.cbnu.nabi.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Prefs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws Nabi and runs its little brain. All cat geometry is in "cat units"
 * (about 1dp each), local to the point on the ground under the body, facing right.
 *
 * Look: a chubby "mochi" loaf cat - big round head that overlaps a tall rounded body,
 * tiny ears, wide-set dot eyes, an "ω" mouth, stubby paws and a striped tail hugging the rump.
 * Use [Skin.GINGER] (default) or [Skin.BLACK] (black body + yellow line art).
 *
 * Server hooks:
 *  - [applyAction] plays a backend/LLM `cat_action` right away, optionally with a line to say.
 *  - [setMood] biases the idle brain (e.g. sleepy at night, stays close when sleep risk is high).
 *  - Long-pressing the cat asks the host to open the chat screen.
 */
@SuppressLint("ViewConstructor")
class CatView(
    context: Context,
    private val host: PetHost,
    private var skin: Skin = Skin.GINGER
) : View(context) {

    /** Color set. Add more presets here. */
    class Skin(
        val body: Int,
        val dark: Int,     // far paws
        val belly: Int,    // belly patch (set equal to body for none)
        val earIn: Int,    // inner ear
        val ink: Int,      // outlines, eyes, mouth
        val halo: Int,     // soft sticker outline
        val stripe: Int,   // tail stripes
        val shine: Int,    // eye highlight
        val blush: Int,
        val nose: Int
    ) {
        companion object {
            val GINGER = Skin(
                body = Color.parseColor("#E8964A"),
                dark = Color.parseColor("#B9672C"),
                belly = Color.parseColor("#F8E6C8"),
                earIn = Color.parseColor("#F2A9A0"),
                ink = Color.parseColor("#2B2840"),
                halo = Color.argb(75, 185, 103, 44),
                stripe = Color.parseColor("#B9672C"),
                shine = Color.WHITE,
                blush = Color.argb(95, 240, 120, 140),
                nose = Color.parseColor("#E07B86")
            )
            val BLACK = Skin(
                body = Color.parseColor("#0B0B0B"),
                dark = Color.parseColor("#000000"),
                belly = Color.parseColor("#0B0B0B"),
                earIn = Color.parseColor("#F5E21A"),
                ink = Color.parseColor("#F5E21A"),
                halo = Color.argb(120, 150, 140, 0),
                stripe = Color.parseColor("#F5E21A"),
                shine = Color.TRANSPARENT,
                blush = Color.argb(60, 245, 226, 26),
                nose = Color.parseColor("#F5E21A")
            )
        }
    }

    private enum class St { WALK, SIT, IDLE, GROOM, SLEEP, HAPPY, ANNOYED, FLEE }
    private enum class Pose { STAND, SIT, SLEEP }
    private enum class Zone { HEAD, BACK, BELLY, TAIL, PAW }

    /** Static per-pose geometry (hit-test shapes + touch window bounds). Never allocated per frame. */
    private class Geo(
        val pose: Pose,
        val hcx: Float, val hcy: Float, val hr: Float,                    // head circle
        val bcx: Float, val bcy: Float, val brx: Float, val bry: Float,   // body ellipse
        val pawX0: Float, val pawX1: Float, val pawTop: Float,            // paw strip
        val bounds: FloatArray                                            // left, right, top, bottom
    )

    private class Part(
        val kind: Int, var x: Float, var y: Float,
        val vx: Float, val vy: Float, var life: Float, val max: Float
    )

    private companion object {
        const val BUBBLE_MAX_LINES = 5
        const val LONG_PRESS_SEC = 0.7f
        const val TAIL_N = 12
        const val HEAD_RX = 31f
        const val HEAD_RY = 29f
        const val FACE_DX = 2.5f
        const val FACE_DY = -1f
        const val INK_W = 2.4f
        const val STAND_LIFT = 0f      // standing body/head are lifted this much so the legs look longer
        const val FRONT_PAW_TOP = -15f
        const val FRONT_PAW_BOTTOM = 1f
        const val FRONT_PAW_RX = 6.5f
        const val HALO_W = 7.2f

        const val P_HEART = 0
        const val P_ANGER = 1
        const val P_Z = 2
        const val P_SWAT = 3

        const val RAD2DEG = (180.0 / PI).toFloat()
        val BUBBLE_INK = Color.parseColor("#2B2840")
        // Back stripe x positions (cat units) per pose
        val STRIPES_STAND = floatArrayOf(-52f, -43f, -34f, -25f)
        val STRIPES_SIT = floatArrayOf(-38f, -29f, -20f, -11f)
        val STRIPES_SLEEP = floatArrayOf(-32f, -22f, -12f, -2f, 8f)
        val HEART = Color.parseColor("#E5486A")
        val ANGER = Color.parseColor("#D9362B")
        val MOUTH = Color.parseColor("#5A2230")
        val SHADOW = Color.argb(55, 30, 20, 40)

        val FACE_SIGN = floatArrayOf(-1f, 1f)
        val TAIL_STRIPES = intArrayOf(4, 6, 8, 10)
        val LEG_X = floatArrayOf(-42f, -12f, -28f, 2f) // 0,1 = far legs, 2,3 = near legs

        // Ears as (baseA, tip, baseB), relative to the head center.
        val EAR_FRONT = floatArrayOf(10f, -25f, 29f, -31f, 26f, -12f)
        val EAR_BACK = floatArrayOf(-27f, -12f, -20f, -36f, -4f, -26f)
        val EAR_FRONT_FLAT = floatArrayOf(10f, -25f, 40f, -24f, 26f, -12f)
        val EAR_BACK_FLAT = floatArrayOf(-27f, -12f, -40f, -24f, -4f, -26f)

        private val GEO_STAND = Geo(
            Pose.STAND, 14f, -33f - STAND_LIFT, 31f, -18f, -32f - STAND_LIFT, 38f, 24f,
            -30f, 14f, -14f - STAND_LIFT,
            floatArrayOf(-78f, 50f, -76f - STAND_LIFT, 6f)
        )
        private val GEO_SIT = Geo(
            Pose.SIT, 14f, -46f, 31f, -14f, -46f, 34f, 38f, -16f, 14f, -15f,
            floatArrayOf(-76f, 50f, -92f, 6f)
        )
        private val GEO_SLEEP = Geo(
            Pose.SLEEP, 22f, -26f, 31f, -10f, -30f, 42f, 26f, 0f, 0f, 1f,
            floatArrayOf(-86f, 56f, -66f, 8f)
        )
        fun geoStand() = GEO_STAND
        fun geoSit() = GEO_SIT
        fun geoSleep() = GEO_SLEEP
    }

    private val dp = resources.displayMetrics.density
    val winW = (280 * dp).toInt()
    val winH = (310 * dp).toInt()
    private val ox = winW / 2f
    private val oy = winH - 20 * dp
    private val s = 1.05f * dp

    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { // speech bubble text
        typeface = Typeface.DEFAULT_BOLD
        textSize = 15 * dp
        color = BUBBLE_INK
    }
    private val zp = Paint(Paint.ANTI_ALIAS_FLAG).apply { // sleeping "z"
        typeface = Typeface.DEFAULT_BOLD
        color = BUBBLE_INK
    }
    private val rf = RectF()
    private val path = Path()
    private val bodyPath = Path()
    private val tailPath = Path()

    // Head silhouettes (head + ears) are identical in every pose, so build them once.
    private val headNormal = buildHead(flat = false)
    private val headFlat = buildHead(flat = true)
    private val earInner = buildInnerEars()

    // world state (screen px): ground point under the cat
    private var sw = 0
    private var sh = 0
    private var catX = 0f
    private var catY = 0f
    private var dir = 1f

    private var st = St.IDLE
    private var timer = 2f
    private var tx = 0f
    private var ty = 0f
    private var after = St.SIT
    private var phase = 0f
    private var t = 0f
    private var blinkT = 3f
    private var blink = 0f
    private var swatT = 0f
    private var hissT = 0f
    private var bellyOk = false
    private var purr = 0f
    private var purrT = 0f
    private var zT = 0f
    private var overstim = 0f
    private var aff = 50f
    private var saveT = 3f

    private var bubbleTxt: String? = null
    private var bubbleLines: List<String> = emptyList()
    private var bubbleLife = 0f
    private var bubbleMax = 1f
    private val parts = ArrayList<Part>()
    private val tailPts = FloatArray((TAIL_N + 1) * 2)

    private var ptrDown = false
    private var ptrZone: Zone? = null
    private var ptrLastX = 0f
    private var ptrLastY = 0f
    private var ptrAcc = 0f
    private var ptrDownT = 0f
    private var ptrTravel = 0f
    private var longFired = false

    /** Latest cat_action from GET /cat/state. Shapes what the cat does on its own. */
    private var mood = CatAction.IDLE

    private val prefs = Prefs(context)

    private var running = false
    private var lastNs = 0L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
            lastNs = now
            update(dt)
            pushLayout()
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        aff = prefs.affection
        onScreenChanged()
        catX = sw * 0.5f
        catY = sh * 0.72f
        buildTail()
        say("냥?", 1.6f)
    }

    // ---------------- lifecycle ----------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        prefs.affection = aff
        super.onDetachedFromWindow()
    }

    /** Stop animating while the window is hidden (saves battery for an always-on overlay). */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running || !isAttachedToWindow) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    fun setSkin(newSkin: Skin) {
        skin = newSkin
        invalidate()
    }

    fun onScreenChanged() {
        val p = host.screenSize()
        sw = p.x
        sh = p.y
        catX = catX.coerceIn(minX(), maxX())
        catY = catY.coerceIn(minY(), maxY())
        if (st == St.WALK || st == St.FLEE) {
            tx = tx.coerceIn(minX(), maxX())
            ty = ty.coerceIn(minY(), maxY())
        }
    }

    /** Called when the user taps "나비 부르기" while the cat is already out. */
    fun callOver() {
        say("냥?", 1.4f)
        walkTo(sw * 0.5f, sh * 0.7f, St.SIT)
    }

    /** Play a cat_action from the backend (chat reply, mission result, cat state). */
    fun applyAction(a: CatAction, line: String? = null) {
        when (a) {
            CatAction.WALK -> walkTo(randX(), randY(), St.SIT)
            CatAction.SIT -> set(St.SIT, 6f + rnd() * 3)
            CatAction.IDLE -> set(St.IDLE, 3f)
            CatAction.SLEEP -> {
                set(St.SLEEP, 20f + rnd() * 15)
                if (line == null) say("하암…", 1.4f)
            }
            CatAction.HAPPY -> {
                set(St.HAPPY, 3.5f)
                burst(P_HEART, 4)
            }
            CatAction.COMFORT -> walkTo(sw * 0.5f, sh * 0.62f, St.SIT)
        }
        if (line != null) say(line, readTime(line))
    }

    fun setMood(a: CatAction) { mood = a }

    /** Speech bubble only, without changing what the cat is doing. */
    fun speak(line: String) = say(line, readTime(line))

    private fun readTime(txt: String) = (1.6f + txt.length * 0.09f).coerceIn(1.6f, 10f)

    // ---------------- helpers ----------------
    private fun minX() = 60 * dp
    private fun maxX() = max(minX() + 1, sw - 60 * dp)
    private fun minY() = sh * 0.28f
    private fun maxY() = max(minY() + 1, sh - 28 * dp)

    private fun rnd() = Random.nextFloat()
    private fun <T> pick(vararg a: T): T = a[Random.nextInt(a.size)]
    private fun randX() = minX() + rnd() * (maxX() - minX())
    private fun randY(): Float {
        val u = rnd()
        return minY() + (maxY() - minY()) * (1f - u * u) // biased toward the bottom of the screen
    }

    private fun addAff(d: Float) { aff = (aff + d).coerceIn(0f, 100f) }

    private fun set(next: St, tm: Float) {
        st = next
        timer = tm
        if (next != St.HAPPY) bellyOk = false
    }

    private fun walkTo(x: Float, y: Float, then: St) {
        st = St.WALK
        tx = x.coerceIn(minX(), maxX())
        ty = y.coerceIn(minY(), maxY())
        after = then
    }

    private fun say(txt: String, dur: Float = 1.8f) {
        bubbleTxt = txt
        bubbleLines = wrap(txt)
        bubbleLife = dur
        bubbleMax = dur
    }

    /** Character-based wrapping (works for Korean, which has few spaces). */
    private fun wrap(txt: String): List<String> {
        val maxW = winW - 40 * dp
        val out = ArrayList<String>()
        for (para in txt.trim().split('\n')) {
            val line = StringBuilder()
            for (ch in para) {
                if (line.isNotEmpty() && bp.measureText(line.toString() + ch) > maxW) {
                    out.add(line.toString().trimEnd())
                    line.setLength(0)
                    if (ch == ' ') continue
                }
                line.append(ch)
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        if (out.size > BUBBLE_MAX_LINES) {
            val kept = out.subList(0, BUBBLE_MAX_LINES).toMutableList()
            kept[kept.lastIndex] = kept.last().dropLast(1) + "…"
            return kept
        }
        return out
    }

    private fun geo(): Geo = when (st) {
        St.SLEEP -> geoSleep()
        St.SIT, St.GROOM, St.HAPPY -> geoSit()
        else -> geoStand()
    }

    // Head position in view px (no allocation).
    private fun headX() = ox + geo().hcx * s * dir
    private fun headY() = oy + geo().hcy * s
    private fun headR() = geo().hr * s

    private fun burst(kind: Int, n: Int, x: Float = Float.NaN, y: Float = Float.NaN) {
        val hx = headX()
        val hy = headY() - headR()
        repeat(n) {
            parts.add(
                Part(
                    kind,
                    (if (x.isNaN()) hx else x) + (rnd() - .5f) * 30 * s,
                    (if (y.isNaN()) hy else y) + (rnd() - .5f) * 10 * s,
                    (rnd() - .5f) * 20 * s,
                    (-30f - rnd() * 30f) * s,
                    1.2f,
                    1.2f
                )
            )
        }
    }

    private fun waddleY(): Float =
        if (st == St.WALK || st == St.FLEE) abs(sin(phase)) * 2.0f else 0f

    // ---------------- brain ----------------
    private fun decide() {
        val r = rnd()
        when (mood) {
            CatAction.SLEEP -> when {
                r < 0.60f -> set(St.SLEEP, 20 + rnd() * 20)
                r < 0.80f -> set(St.GROOM, 3 + rnd() * 2)
                else -> walkTo(randX(), randY(), St.SLEEP)
            }
            CatAction.COMFORT -> {
                when {
                    r < 0.25f -> walkTo(sw * (0.35f + rnd() * 0.3f), sh * (0.55f + rnd() * 0.2f), St.SIT)
                    r < 0.70f -> set(St.SIT, 5 + rnd() * 5)
                    r < 0.85f -> set(St.GROOM, 3 + rnd() * 2)
                    else -> set(St.IDLE, 2 + rnd() * 2)
                }
                if (rnd() < 0.12f) say(pick("오늘 하루도 고생했어", "천천히 숨 쉬어 보자냥", "옆에 있을게"), 2.6f)
            }
            else -> {
                when {
                    r < 0.42f -> walkTo(randX(), randY(), pick(St.SIT, St.IDLE, St.GROOM))
                    r < 0.60f -> set(St.SIT, 3 + rnd() * 4)
                    r < 0.74f -> set(St.GROOM, 3 + rnd() * 2)
                    r < 0.86f -> {
                        set(St.SLEEP, 10 + rnd() * 10)
                        say("하암…", 1.4f)
                    }
                    else -> set(St.IDLE, 2 + rnd() * 2)
                }
                if (mood == CatAction.HAPPY && rnd() < 0.2f) {
                    say(pick("기분 좋다냥!", "냥냥~"))
                    burst(P_HEART, 1)
                } else if (aff > 55 && rnd() < 0.14f) {
                    say(pick("냥~", "냐앙", "먀"))
                }
            }
        }
    }

    private fun update(dt: Float) {
        t += dt
        timer -= dt
        overstim = max(0f, overstim - 1.2f * dt)
        swatT = max(0f, swatT - dt)
        hissT = max(0f, hissT - dt)
        val pressing = ptrDown && ptrZone != null
        if (pressing && st == St.HAPPY) timer = max(timer, 1f)

        // Long press (finger held still on the cat) opens the chat screen.
        if (ptrDown && !longFired && st != St.FLEE && ptrTravel < 12 * dp && t - ptrDownT > LONG_PRESS_SEC) {
            longFired = true
            ptrZone = null
            say("무슨 일이냥?", 1.4f)
            host.openChat()
        }

        if (st == St.WALK || st == St.FLEE) {
            val dx = tx - catX
            val dy = ty - catY
            val d = hypot(dx, dy)
            val speed = (if (st == St.FLEE) 260f else 70f) * dp
            if (d < speed * dt + 1) {
                catX = tx
                catY = ty
                if (st == St.FLEE) set(St.ANNOYED, 2.4f)
                else set(after, 2.5f + rnd() * 3)
            } else {
                catX += dx / d * speed * dt
                catY += dy / d * speed * dt
                if (abs(dx) > 2) dir = if (dx > 0) 1f else -1f
                phase += dt * (if (st == St.FLEE) 20f else 9f)
            }
        } else if (timer <= 0 && !pressing) {
            when (st) {
                St.HAPPY -> set(St.SIT, 2.5f)
                St.SLEEP -> {
                    set(St.IDLE, 2f)
                    say("으냐… 잘 잤다")
                }
                St.ANNOYED -> walkTo(randX(), randY(), St.GROOM)
                else -> decide()
            }
        }

        if (st == St.SLEEP) {
            zT -= dt
            if (zT < 0) {
                zT = 1.3f
                val r = headR()
                parts.add(Part(P_Z, headX() - r * 0.8f * dir, headY() - r * 0.85f, -8 * dp * dir, -18 * dp, 2f, 2f))
            }
        }
        blinkT -= dt
        if (blinkT < 0) {
            blink = .13f
            blinkT = 2 + rnd() * 4
        }
        blink = max(0f, blink - dt)

        val purrTarget = if (st == St.HAPPY || (st == St.SLEEP && pressing)) 1f else 0f
        purr += (purrTarget - purr) * min(1f, dt * 3)
        if (purr > 0.6f) {
            purrT -= dt
            if (purrT < 0) {
                purrT = 0.5f
                host.buzz(PetService.BUZZ_PURR)
            }
        }

        val it = parts.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.life -= dt
            q.x += q.vx * dt
            q.y += q.vy * dt
            if (q.life <= 0) it.remove()
        }
        if (bubbleTxt != null) {
            bubbleLife -= dt
            if (bubbleLife <= 0) bubbleTxt = null
        }

        saveT -= dt
        if (saveT < 0) {
            saveT = 5f
            prefs.affection = aff
        }

        buildTail() // keep tail points fresh for both drawing and touch hit-testing
    }

    // ---------------- touch ----------------
    private fun hit(px: Float, py: Float): Zone? {
        val lx = (px - ox) / (s * dir)
        val ly = (py - oy) / s
        val g = geo()
        if (hypot(lx - g.hcx, ly - g.hcy) < g.hr + 6f) return Zone.HEAD

        // Only the outer part of the tail counts (the root is hidden inside the body).
        for (i in 4..TAIL_N) {
            if (hypot(lx - tailPts[i * 2], ly - tailPts[i * 2 + 1]) < 9f) return Zone.TAIL
        }
        if (ly <= 5f && ly > g.pawTop && lx > g.pawX0 && lx < g.pawX1) return Zone.PAW

        val nx = (lx - g.bcx) / (g.brx + 6f)
        val ny = (ly - g.bcy) / (g.bry + 6f)
        if (nx * nx + ny * ny <= 1f) {
            return when (g.pose) {
                Pose.STAND -> if (ly > g.bcy + 12f) Zone.BELLY else Zone.BACK
                Pose.SIT -> if (ly > g.bcy + 18f && lx > g.bcx - 10f) Zone.BELLY else Zone.BACK
                Pose.SLEEP -> Zone.BACK
            }
        }
        return null
    }

    fun handleTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                ptrDown = true
                ptrLastX = x
                ptrLastY = y
                ptrAcc = 0f
                ptrDownT = t
                ptrTravel = 0f
                longFired = false
                ptrZone = if (st == St.FLEE) null else hit(x, y)
                ptrZone?.let { touchStart(it, x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!ptrDown) return
                val d = hypot(x - ptrLastX, y - ptrLastY)
                ptrLastX = x
                ptrLastY = y
                ptrTravel += d
                if (ptrZone == null) return
                val z = hit(x, y)
                if ((z == Zone.TAIL || z == Zone.BELLY) && z != ptrZone) {
                    ptrZone = z
                    touchStart(z, x, y)
                }
                ptrAcc += d
                val unit = 30 * dp
                while (ptrAcc > unit) {
                    val zone = ptrZone ?: break
                    ptrAcc -= unit
                    strokeUnit(zone, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ptrDown = false
                ptrZone = null
            }
        }
    }

    private fun setHappy() { if (st != St.HAPPY) set(St.HAPPY, 2.2f) }

    private fun annoy(d: Float) {
        addAff(d)
        set(St.ANNOYED, 1.4f)
        overstim = 0f
        ptrZone = null
        burst(P_ANGER, 1)
    }

    private fun swat(x: Float, y: Float) {
        swatT = .35f
        parts.add(Part(P_SWAT, x, y, 0f, 0f, .5f, .5f))
        host.buzz(PetService.BUZZ_SWAT)
    }

    private fun hiss(x: Float) {
        addAff(-10f)
        say("하악!!", 1.6f)
        hissT = .9f
        host.buzz(PetService.BUZZ_HISS)
        burst(P_ANGER, 2)
        st = St.FLEE
        tx = if (x < ox) maxX() else minX() // run away from the finger
        ty = randY()
        ptrZone = null
    }

    private fun touchStart(z: Zone, x: Float, y: Float) {
        if (st == St.ANNOYED || st == St.FLEE) {
            ptrZone = null
            return
        }
        if (st == St.SLEEP) {
            if (z == Zone.HEAD || z == Zone.BACK) {
                say("쿨… 골골…")
                addAff(1f)
                burst(P_HEART, 1)
            } else {
                say("…!? 왜 깨워")
                annoy(-3f)
            }
            return
        }
        when (z) {
            Zone.HEAD -> {
                setHappy()
                say(pick("골골골…", "거기, 거기", "냐앙~ 좋아", "턱도 긁어줘"))
                burst(P_HEART, 2)
                addAff(1f)
            }
            Zone.BACK -> {
                setHappy()
                say(pick("음~ 괜찮네", "쓰담쓰담 허락", "좀 더 해봐"))
            }
            Zone.PAW -> {
                say(pick("발은 내 거야", "…냥?", "젤리는 비싸"))
                addAff(-1f)
            }
            Zone.BELLY -> {
                if (aff > 72 && rnd() < 0.45f) {
                    setHappy()
                    bellyOk = true
                    say("이번만이다냥…")
                } else {
                    say(pick("냥!!", "함정이었다냥!", "배는 안 돼!"))
                    swat(x, y)
                    annoy(-6f)
                }
            }
            Zone.TAIL -> hiss(x)
        }
    }

    private fun strokeUnit(z: Zone, x: Float, y: Float) {
        if (st == St.ANNOYED || st == St.FLEE) return
        if (st == St.SLEEP) {
            if (z == Zone.HEAD || z == Zone.BACK) {
                addAff(.4f)
                if (rnd() < .3f) burst(P_HEART, 1, x, y)
            }
            return
        }
        when (z) {
            Zone.HEAD -> {
                addAff(1f)
                overstim += .35f
                if (rnd() < .5f) burst(P_HEART, 1, x, y)
            }
            Zone.BACK -> {
                addAff(.6f)
                overstim += 1f
                if (rnd() < .3f) burst(P_HEART, 1, x, y)
            }
            Zone.BELLY -> {
                if (!bellyOk) return
                overstim += 2.4f
                addAff(.5f)
            }
            else -> return
        }
        setHappy()
        if (rnd() < .07f) say(pick("골골골골…", "으냐~", "행복하다냥"))
        if (overstim > 9 + aff / 10) {
            say(pick("그만 만져!", "이제 됐다냥", "냥! 충분해"))
            swat(x, y)
            annoy(-3f)
        }
    }

    // ---------------- window placement ----------------
    private fun pushLayout() {
        val b = geo().bounds
        val left = if (dir > 0) b[0] else -b[1]
        val right = if (dir > 0) b[1] else -b[0]
        host.place(
            (catX - ox).toInt(), (catY - oy).toInt(),
            (catX + left * s).toInt(), (catY + b[2] * s).toInt(),
            ((right - left) * s).toInt(), ((b[3] - b[2]) * s).toInt()
        )
    }

    // ---------------- shape builders (run once) ----------------
    private fun earPoly(p: Path, e: FloatArray) {
        p.moveTo(e[0], e[1])
        p.lineTo(e[2], e[3])
        p.lineTo(e[4], e[5])
        p.close()
    }

    /** Round head + two small ears merged into one silhouette (so there is one clean outline). */
    private fun buildHead(flat: Boolean): Path {
        val head = Path()
        head.addOval(RectF(-HEAD_RX, -HEAD_RY, HEAD_RX, HEAD_RY), Path.Direction.CW)
        val ears = if (flat) arrayOf(EAR_FRONT_FLAT, EAR_BACK_FLAT) else arrayOf(EAR_FRONT, EAR_BACK)
        for (e in ears) {
            val ear = Path()
            earPoly(ear, e)
            head.op(ear, Path.Op.UNION) // API 19+
        }
        return head
    }

    private fun buildInnerEars(): Path {
        val p = Path()
        for (e in arrayOf(EAR_FRONT, EAR_BACK)) {
            val cx = (e[0] + e[2] + e[4]) / 3f
            val cy = (e[1] + e[3] + e[5]) / 3f
            for (k in 0..2) {
                val x = cx + (e[k * 2] - cx) * 0.55f
                val y = cy + (e[k * 2 + 1] - cy) * 0.55f
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        return p
    }

    // ---------------- drawing (seamless mochi vector style) ----------------
    private fun oval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, col: Int, outlineW: Float = 0f) {
        fp.color = col
        rf.set(x - rx, y - ry, x + rx, y + ry)
        c.drawOval(rf, fp)
        if (outlineW > 0f) {
            setStroke(outlineW, skin.ink)
            c.drawOval(rf, sp)
        }
    }

    private fun setStroke(w: Float, col: Int) {
        sp.strokeWidth = w
        sp.color = col
    }

    /** halo -> fill -> [decorate, clipped to the shape] -> ink outline. */
    private inline fun paintPart(c: Canvas, p: Path, decorate: () -> Unit) {
        setStroke(HALO_W, skin.halo)
        c.drawPath(p, sp)
        fp.color = skin.body
        c.drawPath(p, fp)
        c.save()
        c.clipPath(p)
        decorate()
        c.restore()
        setStroke(INK_W, skin.ink)
        c.drawPath(p, sp)
    }

    private fun pawPath(cx: Float, topY: Float, bottomY: Float, rx: Float, up: Boolean, closed: Boolean) {
        val r = min(rx, (bottomY - topY) * 0.65f)
        path.reset()
        if (!up) { // stubby standing paw: U shape, open top merges into the body
            path.moveTo(cx - rx, topY)
            path.lineTo(cx - rx, bottomY - r)
            path.quadTo(cx - rx, bottomY, cx, bottomY)
            path.quadTo(cx + rx, bottomY, cx + rx, bottomY - r)
            path.lineTo(cx + rx, topY)
        } else {   // raised paw: inverted U, open bottom (swatting / grooming)
            path.moveTo(cx - rx, bottomY)
            path.lineTo(cx - rx, topY + r)
            path.quadTo(cx - rx, topY, cx, topY)
            path.quadTo(cx + rx, topY, cx + rx, topY + r)
            path.lineTo(cx + rx, bottomY)
        }
        if (closed) path.close()
    }

    private fun drawPaw(
        c: Canvas, cx: Float, topY: Float, bottomY: Float, rx: Float,
        fillCol: Int, up: Boolean, halo: Boolean
    ) {
        pawPath(cx, topY, bottomY, rx, up, closed = true)
        if (halo) {
            setStroke(6.8f, skin.halo)
            c.drawPath(path, sp)
        }
        fp.color = fillCol
        c.drawPath(path, fp)
        pawPath(cx, topY, bottomY, rx, up, closed = false)
        setStroke(2.2f, skin.ink)
        c.drawPath(path, sp)
    }

    /** Sitting front paw resting in FRONT of the body: fully outlined rounded shape. */
    private fun drawFrontPaw(c: Canvas, cx: Float) {
        val rx = FRONT_PAW_RX
        val r = min(rx, (FRONT_PAW_BOTTOM - FRONT_PAW_TOP) / 2f)
        rf.set(cx - rx, FRONT_PAW_TOP, cx + rx, FRONT_PAW_BOTTOM)
        fp.color = skin.body
        c.drawRoundRect(rf, r, r, fp)
        setStroke(2.2f, skin.ink)
        c.drawRoundRect(rf, r, r, sp)
    }

    /** Tabby stripes across the back; call inside paintPart's decorate block (already clipped to the body). */
    private fun backStripes(c: Canvas, xs: FloatArray, y0: Float, y1: Float) {
        setStroke(3.4f, skin.stripe)
        for (x in xs) {
            path.reset()
            path.moveTo(x, y0)
            path.quadTo(x + 2f, (y0 + y1) / 2f, x - 1f, y1)
            c.drawPath(path, sp)
        }
    }

    /** Cubic Bezier tail control points: [x0, y0, x1, y1, x2, y2, x3, y3, width]. */
    private fun tailDef(): FloatArray = when (st) {
        St.SLEEP -> floatArrayOf(-34f, -14f, -52f, -8f, -68f, -8f, -76f, -22f, 9.0f)
        St.HAPPY -> floatArrayOf(-38f, -24f, -56f, -30f, -62f, -56f, -54f + sin(t * 3f) * 4f, -74f, 9.2f)
        St.SIT, St.GROOM -> floatArrayOf(
            -34f, -26f, -58f, -22f, -70f, -34f,
            -62f + sin(t * 1.4f) * 3f, -50f - max(0f, sin(t * 2.3f)) * 4f, 9.0f
        )
        St.FLEE -> floatArrayOf(-40f, -30f, -52f, -60f, -70f, -70f, -60f, -90f, 11f)
        St.ANNOYED -> floatArrayOf(-40f, -24f, -62f, -30f, -66f, -56f, -60f + sin(t * 13f) * 9f, -72f, 9.8f)
        else -> floatArrayOf(-44f, -16f, -66f, -18f, -70f, -40f, -58f + sin(t * 2f) * 3f, -54f, 9.6f)
    }

    private fun tailSide(i: Int, halfW: Float, side: Float) {
        val a = if (i < TAIL_N) i else TAIL_N - 1
        val dx = tailPts[(a + 1) * 2] - tailPts[a * 2]
        val dy = tailPts[(a + 1) * 2 + 1] - tailPts[a * 2 + 1]
        val len = max(0.001f, hypot(dx, dy))
        val w = halfW * (1f - (i / TAIL_N.toFloat()) * 0.14f) * side
        val px = tailPts[i * 2] - dy / len * w
        val py = tailPts[i * 2 + 1] + dx / len * w
        if (side > 0f && i == 0) tailPath.moveTo(px, py) else tailPath.lineTo(px, py)
    }

    /** Samples the tail curve into [tailPts] and builds the outline polygon in [tailPath]. */
    private fun buildTail() {
        val d = tailDef()
        val halfW = d[8] * 0.54f
        for (i in 0..TAIL_N) {
            val u = i / TAIL_N.toFloat()
            val m = 1f - u
            val b0 = m * m * m
            val b1 = 3f * m * m * u
            val b2 = 3f * m * u * u
            val b3 = u * u * u
            tailPts[i * 2] = b0 * d[0] + b1 * d[2] + b2 * d[4] + b3 * d[6]
            tailPts[i * 2 + 1] = b0 * d[1] + b1 * d[3] + b2 * d[5] + b3 * d[7]
        }
        if (geo().pose == Pose.STAND) { // follow the lifted standing body
            for (i in 0..TAIL_N) tailPts[i * 2 + 1] -= STAND_LIFT
        }

        tailPath.reset()
        for (i in 0..TAIL_N) tailSide(i, halfW, 1f)

        // Rounded tip: from the left edge of the last point to the right edge, bulging along the tangent.
        val tipX = tailPts[TAIL_N * 2]
        val tipY = tailPts[TAIL_N * 2 + 1]
        val dx = tipX - tailPts[(TAIL_N - 1) * 2]
        val dy = tipY - tailPts[(TAIL_N - 1) * 2 + 1]
        val len = max(0.001f, hypot(dx, dy))
        val ux = dx / len
        val uy = dy / len
        val w = halfW * (1f - 0.14f)
        tailPath.quadTo(tipX + ux * halfW * 1.5f, tipY + uy * halfW * 1.5f, tipX + uy * w, tipY - ux * w)

        for (i in TAIL_N - 1 downTo 0) tailSide(i, halfW, -1f)
        tailPath.close()
    }

    private fun drawTail(c: Canvas) {
        setStroke(7.2f, skin.halo)
        c.drawPath(tailPath, sp)
        fp.color = skin.body
        c.drawPath(tailPath, fp)

        c.save()
        c.clipPath(tailPath)
        setStroke(3.2f, skin.stripe)
        for (idx in TAIL_STRIPES) {
            val px = tailPts[idx * 2]
            val py = tailPts[idx * 2 + 1]
            val nx = tailPts[(idx + 1) * 2] - px
            val ny = tailPts[(idx + 1) * 2 + 1] - py
            val len = max(0.001f, hypot(nx, ny))
            val perpX = -ny / len * 14f
            val perpY = nx / len * 14f
            c.drawLine(px - perpX, py - perpY, px + perpX, py + perpY, sp)
        }
        c.restore()

        setStroke(2.2f, skin.ink)
        c.drawPath(tailPath, sp)
    }

    /** Dot eyes + rounded 'ω' mouth. Origin is the face center (caller translates). */
    private fun drawFace(c: Canvas) {
        var mode = when (st) {
            St.SLEEP -> 3
            St.HAPPY -> 2
            St.ANNOYED -> 1
            St.FLEE -> 4
            St.GROOM -> if (sin(t * 3f) > 0) 2 else 0
            else -> 0
        }
        if (blink > 0 && mode == 0) mode = 3

        val spread = if (geo().pose == Pose.SLEEP) 13f else 14f
        for (sx in FACE_SIGN) {
            val ex = sx * spread
            setStroke(2.2f, skin.ink)
            when (mode) {
                0 -> {
                    oval(c, ex, 0f, 2.6f, 2.9f, skin.ink)
                    oval(c, ex - 0.7f, -0.9f, 0.8f, 0.8f, skin.shine)
                }
                4 -> {
                    oval(c, ex, 0f, 3.8f, 4.0f, Color.WHITE, outlineW = 1.8f)
                    oval(c, ex, 0f, 1.9f, 1.9f, skin.ink)
                }
                1 -> {
                    oval(c, ex, 1f, 3.1f, 1.5f, skin.ink)
                    val slant = if (sx < 0) 2.2f else -2.2f
                    setStroke(2.2f, skin.ink)
                    c.drawLine(ex - 4.2f, -3.2f - slant * 0.5f, ex + 4.2f, -3.2f + slant * 0.5f, sp)
                }
                2 -> { // happy ^ ^
                    path.reset()
                    path.moveTo(ex - 4.2f, 1.5f)
                    path.quadTo(ex, -4.0f, ex + 4.2f, 1.5f)
                    c.drawPath(path, sp)
                }
                else -> { // closed (sleep / blink)
                    path.reset()
                    path.moveTo(ex - 4.2f, -1f)
                    path.quadTo(ex, 3.6f, ex + 4.2f, -1f)
                    c.drawPath(path, sp)
                }
            }
        }

        if (st == St.HAPPY || st == St.SLEEP) {
            oval(c, -spread - 3f, 5f, 3.6f, 2.1f, skin.blush)
            oval(c, spread + 3f, 5f, 3.6f, 2.1f, skin.blush)
        }

        // whiskers (3 per side, fanned out from the cheeks)
        setStroke(1.3f, skin.ink)
        for (sx in FACE_SIGN) {
            for (k in -1..1) {
                c.drawLine(sx * 16f, 5.5f + k * 1.5f, sx * 33f, 4f + k * 5f, sp)
            }
        }

        val my = 1.5f
        oval(c, 0f, my + 1.8f, 2.2f, 1.6f, skin.nose) // small nose right above the mouth
        if (hissT > 0 || swatT > 0) {
            oval(c, 0f, my + 6.8f, 4.0f, 3.2f, MOUTH, outlineW = 1.8f)
        } else {
            setStroke(2.1f, skin.ink)
            path.reset()
            path.moveTo(-4.6f, my + 4.2f)
            path.cubicTo(-4.4f, my + 7.4f, -0.5f, my + 7.2f, 0f, my + 3.6f)
            path.cubicTo(0.5f, my + 7.2f, 4.4f, my + 7.4f, 4.6f, my + 4.2f)
            c.drawPath(path, sp)
        }
    }

    /** Big round head (drawn over the body) with its face. [hx],[hy] = head center in cat units. */
    private fun drawHead(c: Canvas, hx: Float, hy: Float, tilt: Float, flat: Boolean) {
        c.save()
        c.translate(hx, hy)
        c.rotate(tilt * RAD2DEG)
        val p = if (flat) headFlat else headNormal

        setStroke(6.4f, skin.halo)
        c.drawPath(p, sp)
        fp.color = skin.body
        c.drawPath(p, fp)

        // forehead tabby stripes (same color as the tail stripes)
        c.save()
        c.clipPath(p)
        setStroke(3f, skin.stripe)
        for (k in -1..1) {
            val sx = FACE_DX + k * 7f
            val len = if (k == 0) 16f else 12f
            path.reset()
            path.moveTo(sx, -HEAD_RY - 2f)
            path.quadTo(sx + k * 1.2f, -HEAD_RY + len * 0.5f, sx + k * 0.5f, -HEAD_RY + len)
            c.drawPath(path, sp)
        }
        c.restore()

        if (!flat) {
            fp.color = skin.earIn
            c.drawPath(earInner, fp)
        }
        setStroke(INK_W, skin.ink)
        c.drawPath(p, sp)

        c.translate(FACE_DX, FACE_DY)
        drawFace(c)
        c.restore()
    }

    private fun drawLeg(c: Canvas, i: Int) {
        val moving = st == St.WALK || st == St.FLEE
        val far = i < 2
        val a = phase + (i % 2) * PI.toFloat() + (if (far) 0f else PI.toFloat())
        val swing = if (moving) sin(a) * 5.0f else 0f
        val lift = if (moving) max(0f, -cos(a)) * 4.2f else 0f
        val off = if (far) -2.5f else 0f
        drawPaw(
            c, LEG_X[i] + swing, -12f - STAND_LIFT - lift + off, -lift + off,
            if (far) 5.8f else 6.2f, if (far) skin.dark else skin.body,
            up = false, halo = far
        )
    }

    private fun drawStand(c: Canvas, breathe: Float, flat: Boolean) {
        val waddle = waddleY()
        drawTail(c)
        drawLeg(c, 0)
        drawLeg(c, 1)

        // Round loaf body, kept lower than the head so the silhouette stays cute.
        c.save()
        c.translate(0f, -waddle - STAND_LIFT)
        bodyPath.reset()
        bodyPath.moveTo(12f, -8f)
        bodyPath.cubicTo(-4f, -1f, -38f, -1f, -50f, -12f)
        bodyPath.cubicTo(-62f, -24f, -62f, -46f, -48f, -55f - breathe)
        bodyPath.cubicTo(-34f, -64f - breathe, 2f, -62f - breathe, 14f, -48f)
        bodyPath.lineTo(18f, -26f)
        bodyPath.close()
        paintPart(c, bodyPath) {
            oval(c, -16f, -6f, 32f, 8f, skin.belly)
            backStripes(c, STRIPES_STAND, -66f, -34f)
        }
        c.restore()

        drawLeg(c, 2)
        drawLeg(c, 3)

        val g = geoStand()
        val tilt = (if (st == St.IDLE) sin(t * 1.3f) * 0.05f else 0f) +
            (if (waddle > 0f) sin(phase) * 0.03f else 0f)
        drawHead(c, g.hcx, g.hcy - waddle, tilt, flat)

        if (swatT > 0) drawPaw(c, 58f, -40f - STAND_LIFT, -22f - STAND_LIFT, 6f, skin.body, up = true, halo = true)
    }

    private fun drawSit(c: Canvas, breathe: Float, flat: Boolean) {
        drawTail(c) // behind the body: the body outline stays and hides the tail root

        bodyPath.reset()
        bodyPath.moveTo(14f, -14f)
        bodyPath.cubicTo(0f, -4f, -32f, -4f, -42f, -16f)
        bodyPath.cubicTo(-52f, -30f, -50f, -60f, -36f, -74f - breathe)
        bodyPath.cubicTo(-24f, -86f - breathe, 0f, -84f - breathe, 14f, -62f)
        bodyPath.lineTo(20f, -30f)
        bodyPath.close()
        paintPart(c, bodyPath) {
            oval(c, 4f, -14f, 22f, 14f, skin.belly)
            backStripes(c, STRIPES_SIT, -90f, -52f)
        }

        // front paws sit in front of the body (head is drawn after, so the chin overlaps them)
        drawFrontPaw(c, -8f)
        if (swatT <= 0 && st != St.GROOM) drawFrontPaw(c, 7f)

        val g = geoSit()
        val tilt = when (st) {
            St.HAPPY -> 0.09f + sin(t * 2f) * 0.04f
            St.GROOM -> 0.14f + sin(t * 6f) * 0.05f
            St.IDLE -> sin(t * 1.3f) * 0.06f
            else -> 0f
        }
        drawHead(c, g.hcx, g.hcy, tilt, flat)

        when {
            swatT > 0 -> drawPaw(c, 52f, -52f, -34f, 6f, skin.body, up = true, halo = true)
            st == St.GROOM -> drawPaw(
                c, 26f + cos(t * 6f) * 2f, -52f + sin(t * 6f) * 2.5f, -30f, 5.6f,
                skin.body, up = true, halo = false
            )
        }
    }

    private fun drawSleep(c: Canvas, breathe: Float) {
        drawTail(c)

        bodyPath.reset()
        bodyPath.moveTo(26f, -10f)
        bodyPath.cubicTo(12f, -3f, -36f, -3f, -47f, -13f)
        bodyPath.cubicTo(-57f, -24f, -53f, -44f, -39f, -50f - breathe)
        bodyPath.cubicTo(-24f, -60f - breathe, 12f, -60f - breathe, 28f, -38f)
        bodyPath.lineTo(30f, -20f)
        bodyPath.close()
        paintPart(c, bodyPath) { backStripes(c, STRIPES_SLEEP, -66f, -40f) }

        val g = geoSleep()
        drawHead(c, g.hcx, g.hcy + breathe * 0.5f, 0.1f + sin(t * 1.5f) * 0.01f, false)
    }

    private fun drawCat(c: Canvas) {
        val g = geo()
        val shadowRx = when (g.pose) {
            Pose.SIT -> 40f
            Pose.SLEEP -> 52f
            Pose.STAND -> 50f
        }
        val shadowY = if (g.pose == Pose.SLEEP) oy + 3f else oy + 1f
        oval(c, ox, shadowY, shadowRx * s, 7.5f * s, SHADOW)

        c.save()
        c.translate(ox, oy)
        c.scale(s * dir, s)

        val breathe = sin(t * (if (st == St.SLEEP) 1.5f else 2.4f)) * (if (st == St.SLEEP) 1.2f else 0.6f)
        val flat = st == St.ANNOYED || st == St.FLEE || hissT > 0

        when (g.pose) {
            Pose.STAND -> drawStand(c, breathe, flat)
            Pose.SIT -> drawSit(c, breathe, flat)
            Pose.SLEEP -> drawSleep(c, breathe)
        }
        c.restore()
    }

    private fun heart(c: Canvas, x: Float, y: Float, z: Float, alpha: Int) {
        path.reset()
        path.moveTo(x, y + z * .3f)
        path.cubicTo(x, y, x - z * .5f, y, x - z * .5f, y + z * .3f)
        path.cubicTo(x - z * .5f, y + z * .6f, x, y + z * .8f, x, y + z)
        path.cubicTo(x, y + z * .8f, x + z * .5f, y + z * .6f, x + z * .5f, y + z * .3f)
        path.cubicTo(x + z * .5f, y, x, y, x, y + z * .3f)
        fp.color = HEART
        fp.alpha = alpha
        c.drawPath(path, fp)
        fp.alpha = 255
    }

    private fun drawParts(c: Canvas) {
        for (q in parts) {
            val a = min(1f, q.life / q.max * 1.6f)
            val alpha = (a * 255).toInt().coerceIn(0, 255)
            when (q.kind) {
                P_HEART -> heart(c, q.x, q.y, 14 * s, alpha)
                P_ANGER -> {
                    setStroke(3 * dp, ANGER)
                    sp.alpha = alpha
                    val z = 8 * s
                    for (i in 0 until 4) {
                        c.save()
                        c.translate(q.x, q.y)
                        c.rotate(i * 90f)
                        path.reset()
                        path.moveTo(z * .35f, -z)
                        path.quadTo(z * .35f, -z * .35f, z, -z * .35f)
                        c.drawPath(path, sp)
                        c.restore()
                    }
                }
                P_Z -> {
                    zp.alpha = (alpha * .7f).toInt()
                    zp.textSize = (14 + (1f - q.life / q.max) * 10) * s
                    c.drawText("z", q.x, q.y, zp)
                }
                P_SWAT -> {
                    setStroke(2.5f * dp, ANGER)
                    sp.alpha = alpha
                    val z = 22 * s
                    for (i in -1..1) {
                        c.drawLine(
                            q.x - z * .6f + i * 7 * dp, q.y - z * .6f,
                            q.x + z * .6f + i * 7 * dp, q.y + z * .6f, sp
                        )
                    }
                }
            }
            sp.alpha = 255
        }
    }

    private fun drawBubble(c: Canvas) {
        if (bubbleTxt == null || bubbleLines.isEmpty()) return
        val hx = headX()
        val hy = headY()
        val hr = headR()
        val a = min(1f, min(bubbleLife / .25f, (bubbleMax - bubbleLife) / .12f + .2f)).coerceIn(0f, 1f)
        val alpha = (a * 255).toInt()
        val fm = bp.fontMetrics
        val lineH = (fm.descent - fm.ascent) + 2 * dp
        var tw = 0f
        for (l in bubbleLines) tw = max(tw, bp.measureText(l))
        val padX = 14 * dp
        val padY = 8 * dp
        val bw = tw + padX * 2
        val bh = lineH * bubbleLines.size + padY * 2

        val lo = max(4 * dp, ox - catX + 4 * dp)
        val hi = min(winW - bw - 4 * dp, sw - catX + ox - bw - 4 * dp)
        val want = hx - bw / 2
        val bx = if (hi >= lo) want.coerceIn(lo, hi) else lo
        val by = max(2 * dp, hy - hr * 1.25f - bh - 18 * dp) // clear of the ears
        val radius = min(bh / 2, 16 * dp)

        rf.set(bx, by, bx + bw, by + bh)
        fp.color = Color.WHITE
        fp.alpha = alpha
        c.drawRoundRect(rf, radius, radius, fp)
        setStroke(1 * dp, Color.argb(40, 0, 0, 0))
        sp.alpha = (alpha * 40) / 255
        c.drawRoundRect(rf, radius, radius, sp)

        val tipX = hx.coerceIn(bx + 16 * dp, bx + bw - 16 * dp)
        path.reset()
        path.moveTo(tipX - 7 * dp, by + bh - 1)
        path.lineTo(tipX + 2 * dp, by + bh + 9 * dp)
        path.lineTo(tipX + 7 * dp, by + bh - 1)
        path.close()
        c.drawPath(path, fp)

        fp.alpha = 255
        sp.alpha = 255
        bp.alpha = alpha
        var y = by + padY - fm.ascent
        for (l in bubbleLines) {
            c.drawText(l, bx + padX, y, bp)
            y += lineH
        }
        bp.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        drawCat(canvas)
        drawParts(canvas)
        drawBubble(canvas)
    }
}
