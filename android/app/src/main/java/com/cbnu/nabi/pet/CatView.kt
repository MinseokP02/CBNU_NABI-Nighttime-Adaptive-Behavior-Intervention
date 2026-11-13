package com.cbnu.nabi.pet

import android.annotation.SuppressLint
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Prefs
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws Nabi and runs its little brain (ported from Mochi). All cat geometry is in "cat units"
 * (about 1dp each), local to the point on the ground under the body, facing right.
 *
 * Server hooks:
 *  - [applyAction] plays a backend/LLM `cat_action` right away, optionally with a line to say.
 *  - [setMood] biases the idle brain (e.g. sleepy at night, stays close when sleep risk is high).
 *  - Long-pressing the cat asks the host to open the chat screen.
 */
@SuppressLint("ViewConstructor")
class CatView(context: Context, private val host: PetHost) : View(context) {

    private enum class St { WALK, SIT, IDLE, GROOM, SLEEP, HAPPY, ANNOYED, FLEE }
    private enum class Pose { STAND, SIT, SLEEP }
    private enum class Zone { HEAD, BACK, BELLY, TAIL, PAW }

    private class Geo(
        val pose: Pose,
        val bcx: Float, val bcy: Float, val brx: Float, val bry: Float,
        val hcx: Float, val hcy: Float, val hr: Float
    )

    private class Part(
        val kind: Int, var x: Float, var y: Float,
        val vx: Float, val vy: Float, var life: Float, val max: Float
    )

    private companion object {
        // touch box per pose, in cat units: left, right, top, bottom
        val BOUNDS_STAND = floatArrayOf(-84f, 70f, -104f, 6f)
        val BOUNDS_SIT = floatArrayOf(-54f, 44f, -116f, 6f)
        val BOUNDS_SLEEP = floatArrayOf(-48f, 58f, -48f, 8f)
        const val BUBBLE_MAX_LINES = 5
        const val LONG_PRESS_SEC = 0.7f
    }

    private val dp = resources.displayMetrics.density
    // Taller/wider than Mochi so multi-line LLM replies fit above the head.
    val winW = (280 * dp).toInt()
    val winH = (310 * dp).toInt()
    private val ox = winW / 2f
    private val oy = winH - 20 * dp
    private val s = 0.95f * dp

    // palette
    private val cCat = Color.parseColor("#E8964A")
    private val cDark = Color.parseColor("#B9672C")
    private val cCream = Color.parseColor("#F8E6C8")
    private val cEarIn = Color.parseColor("#F2A9A0")
    private val cNose = Color.parseColor("#E07B86")
    private val cEye = Color.parseColor("#2E3A2A")
    private val cHeart = Color.parseColor("#E5486A")
    private val cAnger = Color.parseColor("#D9362B")
    private val cInk = Color.parseColor("#2B2840")
    private val cMouth = Color.parseColor("#5A2230")
    private val cShadow = Color.argb(60, 30, 20, 40)
    private val cBlush = Color.argb(90, 240, 120, 140)
    private val cWhisker = Color.argb(115, 60, 40, 30)

    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textSize = 16 * dp; color = cInk
    }
    private val rf = RectF()
    private val path = Path()

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
    private val tailPts = FloatArray(26)

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
        say("냥?", 1.6f)
    }

    // ---------------- lifecycle ----------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true; lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        prefs.affection = aff
        super.onDetachedFromWindow()
    }

    fun onScreenChanged() {
        val p = host.screenSize()
        sw = p.x; sh = p.y
        catX = catX.coerceIn(minX(), maxX())
        catY = catY.coerceIn(minY(), maxY())
        if (st == St.WALK || st == St.FLEE) { tx = tx.coerceIn(minX(), maxX()); ty = ty.coerceIn(minY(), maxY()) }
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
            CatAction.SLEEP -> { set(St.SLEEP, 20f + rnd() * 15); if (line == null) say("하암…", 1.4f) }
            CatAction.HAPPY -> { set(St.HAPPY, 3.5f); burst(0, 4) }
            // Come close to the middle of the screen and sit by the user.
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
    private fun randY(): Float { val u = rnd(); return minY() + (maxY() - minY()) * (1 - u * u) }

    private fun addAff(d: Float) { aff = (aff + d).coerceIn(0f, 100f) }
    private fun set(s2: St, tm: Float) { st = s2; timer = tm; if (s2 != St.HAPPY) bellyOk = false }
    private fun walkTo(x: Float, y: Float, then: St) {
        st = St.WALK; tx = x.coerceIn(minX(), maxX()); ty = y.coerceIn(minY(), maxY()); after = then
    }
    private fun say(txt: String, dur: Float = 1.8f) {
        bubbleTxt = txt; bubbleLines = wrap(txt); bubbleLife = dur; bubbleMax = dur
    }

    /** Character-based wrapping (works for Korean, which has few spaces). */
    private fun wrap(txt: String): List<String> {
        tp.textSize = 15 * dp
        val maxW = winW - 40 * dp
        val out = ArrayList<String>()
        for (para in txt.trim().split('\n')) {
            val line = StringBuilder()
            for (ch in para) {
                if (line.isNotEmpty() && tp.measureText(line.toString() + ch) > maxW) {
                    out.add(line.toString().trimEnd()); line.setLength(0)
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
        St.SLEEP -> Geo(Pose.SLEEP, 0f, -20f, 40f, 19f, 31f, -21f, 19f)
        St.SIT, St.GROOM, St.HAPPY -> Geo(Pose.SIT, -4f, -34f, 26f, 32f, 10f, -78f, 22f)
        else -> Geo(Pose.STAND, 0f, -40f, 42f, 22f, 42f, -62f, 22f)
    }

    /** Head position in view px. */
    private fun headView(): FloatArray {
        val g = geo()
        return floatArrayOf(ox + g.hcx * s * dir, oy + g.hcy * s, g.hr * s)
    }

    private fun burst(kind: Int, n: Int, x: Float? = null, y: Float? = null) {
        val h = headView()
        repeat(n) {
            parts.add(Part(kind,
                (x ?: h[0]) + (rnd() - .5f) * 30 * s,
                (y ?: (h[1] - h[2])) + (rnd() - .5f) * 10 * s,
                (rnd() - .5f) * 20 * s, (-30 - rnd() * 30) * s, 1.2f, 1.2f))
        }
    }

    // ---------------- brain ----------------
    private fun decide() {
        val r = rnd()
        when (mood) {
            // Night (server says SLEEP): mostly naps, a short stretch now and then.
            CatAction.SLEEP -> when {
                r < 0.60f -> set(St.SLEEP, 20 + rnd() * 20)
                r < 0.80f -> set(St.GROOM, 3 + rnd() * 2)
                else -> walkTo(randX(), randY(), St.SLEEP)
            }
            // Sleep risk is up: stay near the middle, sit calmly, sometimes say something gentle.
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
                    r < 0.86f -> { set(St.SLEEP, 10 + rnd() * 10); say("하암…", 1.4f) }
                    else -> set(St.IDLE, 2 + rnd() * 2)
                }
                if (mood == CatAction.HAPPY && rnd() < 0.2f) { say(pick("기분 좋다냥!", "냥냥~")); burst(0, 1) }
                else if (aff > 55 && rnd() < 0.14f) say(pick("냥~", "냐앙", "먀"))
            }
        }
    }

    private fun update(dt: Float) {
        t += dt; timer -= dt
        overstim = max(0f, overstim - 1.2f * dt)
        swatT = max(0f, swatT - dt); hissT = max(0f, hissT - dt)
        val pressing = ptrDown && ptrZone != null
        if (pressing && st == St.HAPPY) timer = max(timer, 1f)

        // Long press (finger held still on the cat) opens the chat screen.
        if (ptrDown && !longFired && st != St.FLEE && ptrTravel < 12 * dp && t - ptrDownT > LONG_PRESS_SEC) {
            longFired = true; ptrZone = null
            say("무슨 일이냥?", 1.4f)
            host.openChat()
        }

        if (st == St.WALK || st == St.FLEE) {
            val dx = tx - catX; val dy = ty - catY; val d = hypot(dx, dy)
            val speed = (if (st == St.FLEE) 260f else 70f) * dp
            if (d < speed * dt + 1) {
                catX = tx; catY = ty
                if (st == St.FLEE) set(St.ANNOYED, 2.4f)
                else set(after, 2.5f + rnd() * 3)
            } else {
                catX += dx / d * speed * dt; catY += dy / d * speed * dt
                if (abs(dx) > 2) dir = if (dx > 0) 1f else -1f
                phase += dt * (if (st == St.FLEE) 20f else 9f)
            }
        } else if (timer <= 0 && !pressing) {
            when (st) {
                St.HAPPY -> set(St.SIT, 2.5f)
                St.SLEEP -> { set(St.IDLE, 2f); say("으냐… 잘 잤다") }
                St.ANNOYED -> walkTo(randX(), randY(), St.GROOM)
                else -> decide()
            }
        }

        if (st == St.SLEEP) {
            zT -= dt
            if (zT < 0) {
                zT = 1.3f
                val h = headView()
                parts.add(Part(2, h[0] + h[2] * dir, h[1] - h[2], 8 * dp * dir, -18 * dp, 2f, 2f))
            }
        }
        blinkT -= dt
        if (blinkT < 0) { blink = .13f; blinkT = 2 + rnd() * 4 }
        blink = max(0f, blink - dt)

        val purrTarget = if (st == St.HAPPY || (st == St.SLEEP && pressing)) 1f else 0f
        purr += (purrTarget - purr) * min(1f, dt * 3)
        if (purr > 0.6f) {
            purrT -= dt
            if (purrT < 0) { purrT = 0.5f; host.buzz(PetService.BUZZ_PURR) }
        }

        val it = parts.iterator()
        while (it.hasNext()) {
            val q = it.next()
            q.life -= dt; q.x += q.vx * dt; q.y += q.vy * dt
            if (q.life <= 0) it.remove()
        }
        if (bubbleTxt != null) { bubbleLife -= dt; if (bubbleLife <= 0) bubbleTxt = null }

        saveT -= dt
        if (saveT < 0) { saveT = 5f; prefs.affection = aff }
    }

    // ---------------- touch ----------------
    private fun hit(px: Float, py: Float): Zone? {
        val lx = (px - ox) / (s * dir); val ly = (py - oy) / s
        val g = geo()
        if (hypot(lx - g.hcx, ly - g.hcy) < g.hr + 9) return Zone.HEAD
        var i = 0
        while (i < tailPts.size) {
            if (hypot(lx - tailPts[i], ly - tailPts[i + 1]) < 13) return Zone.TAIL
            i += 2
        }
        val nx = (lx - g.bcx) / (g.brx + 6); val ny = (ly - g.bcy) / (g.bry + 6)
        if (nx * nx + ny * ny <= 1) {
            return when (g.pose) {
                Pose.STAND -> if (ly > g.bcy + 6) Zone.BELLY else Zone.BACK
                Pose.SIT -> if (lx > g.bcx + 5 && ly > g.bcy - 6) Zone.BELLY else Zone.BACK
                Pose.SLEEP -> Zone.BACK
            }
        }
        if (g.pose == Pose.STAND && ly <= 4 && ly > g.bcy && lx > -34 && lx < 42) return Zone.PAW
        if (g.pose == Pose.SIT && ly <= 4 && ly > -48 && lx > 0 && lx < 26) return Zone.PAW
        return null
    }

    fun handleTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                ptrDown = true; ptrLastX = x; ptrLastY = y; ptrAcc = 0f
                ptrDownT = t; ptrTravel = 0f; longFired = false
                ptrZone = if (st == St.FLEE) null else hit(x, y)
                ptrZone?.let { touchStart(it, x, y) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!ptrDown) return
                val d = hypot(x - ptrLastX, y - ptrLastY)
                ptrLastX = x; ptrLastY = y
                ptrTravel += d
                if (ptrZone == null) return
                val z = hit(x, y)
                if ((z == Zone.TAIL || z == Zone.BELLY) && z != ptrZone) {
                    ptrZone = z; touchStart(z, x, y)
                }
                ptrAcc += d
                val unit = 30 * dp
                while (ptrZone != null && ptrAcc > unit) {
                    ptrAcc -= unit
                    strokeUnit(ptrZone!!, x, y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ptrDown = false; ptrZone = null }
        }
    }

    private fun setHappy() { if (st != St.HAPPY) set(St.HAPPY, 2.2f) }

    private fun annoy(d: Float) {
        addAff(d); set(St.ANNOYED, 1.4f); overstim = 0f; ptrZone = null
        burst(1, 1)
    }

    private fun swat(x: Float, y: Float) {
        swatT = .35f
        parts.add(Part(3, x, y, 0f, 0f, .5f, .5f))
        host.buzz(PetService.BUZZ_SWAT)
    }

    private fun hiss(x: Float) {
        addAff(-10f); say("하악!!", 1.6f); hissT = .9f
        host.buzz(PetService.BUZZ_HISS); burst(1, 2)
        val catScreenX = catX
        val touchScreenX = catX + (x - ox)
        st = St.FLEE
        tx = if (touchScreenX < catScreenX) maxX() else minX()
        ty = randY()
        ptrZone = null
    }

    private fun touchStart(z: Zone, x: Float, y: Float) {
        if (st == St.ANNOYED || st == St.FLEE) { ptrZone = null; return }
        if (st == St.SLEEP) {
            if (z == Zone.HEAD || z == Zone.BACK) { say("쿨… 골골…"); addAff(1f); burst(0, 1) }
            else { say("…!? 왜 깨워"); annoy(-3f) }
            return
        }
        when (z) {
            Zone.HEAD -> { setHappy(); say(pick("골골골…", "거기, 거기", "냐앙~ 좋아", "턱도 긁어줘")); burst(0, 2); addAff(1f) }
            Zone.BACK -> { setHappy(); say(pick("음~ 괜찮네", "쓰담쓰담 허락", "좀 더 해봐")) }
            Zone.PAW -> { say(pick("발은 내 거야", "…냥?", "젤리는 비싸")); addAff(-1f) }
            Zone.BELLY -> {
                if (aff > 72 && rnd() < 0.45f) { setHappy(); bellyOk = true; say("이번만이다냥…") }
                else { say(pick("냥!!", "함정이었다냥!", "배는 안 돼!")); swat(x, y); annoy(-6f) }
            }
            Zone.TAIL -> hiss(x)
        }
    }

    private fun strokeUnit(z: Zone, x: Float, y: Float) {
        if (st == St.ANNOYED || st == St.FLEE) return
        if (st == St.SLEEP) {
            if (z == Zone.HEAD || z == Zone.BACK) { addAff(.4f); if (rnd() < .3f) burst(0, 1, x, y) }
            return
        }
        when (z) {
            Zone.HEAD -> { addAff(1f); overstim += .35f; if (rnd() < .5f) burst(0, 1, x, y) }
            Zone.BACK -> { addAff(.6f); overstim += 1f; if (rnd() < .3f) burst(0, 1, x, y) }
            Zone.BELLY -> { if (!bellyOk) return; overstim += 2.4f; addAff(.5f) }
            else -> return
        }
        setHappy()
        if (rnd() < .07f) say(pick("골골골골…", "으냐~", "행복하다냥"))
        if (overstim > 9 + aff / 10) {
            say(pick("그만 만져!", "이제 됐다냥", "냥! 충분해"))
            swat(x, y); annoy(-3f)
        }
    }

    // ---------------- window placement ----------------
    private fun pushLayout() {
        val b = when (geo().pose) {
            Pose.STAND -> BOUNDS_STAND
            Pose.SIT -> BOUNDS_SIT
            Pose.SLEEP -> BOUNDS_SLEEP
        }
        val bx0 = b[0]; val bx1 = b[1]; val by0 = b[2]; val by1 = b[3]
        val left = if (dir > 0) bx0 else -bx1
        val right = if (dir > 0) bx1 else -bx0
        host.place(
            (catX - ox).toInt(), (catY - oy).toInt(),
            (catX + left * s).toInt(), (catY + by0 * s).toInt(),
            ((right - left) * s).toInt(), ((by1 - by0) * s).toInt()
        )
    }

    // ---------------- drawing ----------------
    private fun oval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, col: Int) {
        fp.color = col; rf.set(x - rx, y - ry, x + rx, y + ry); c.drawOval(rf, fp)
    }

    private fun stroke(c: Canvas, w: Float, col: Int) { sp.strokeWidth = w; sp.color = col }

    private fun leg(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, col: Int) {
        stroke(c, 9f, col); c.drawLine(x0, y0, x1, y1, sp)
        oval(c, x1 + 1.5f, y1 - 1f, 6f, 3.6f, col)
    }

    private fun tri(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, col: Int) {
        path.reset(); path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.close()
        fp.color = col; c.drawPath(path, fp)
    }

    private fun tailDef(): FloatArray = when (st) {
        St.SLEEP -> floatArrayOf(-36f, -14f, -44f, 10f, 30f, 4f, 8f)
        St.HAPPY -> floatArrayOf(-26f, -30f, -50f, -70f, -34f + sin(t * 3) * 4, -108f, 8f)
        St.SIT, St.GROOM -> floatArrayOf(-26f, -8f, -42f, 8f, 24f + sin(t * 1.4f) * 3, -2f - max(0f, sin(t * 2.3f)) * 6, 8f)
        St.FLEE -> floatArrayOf(-38f, -46f, -62f, -58f, -58f, -104f, 15f)
        St.ANNOYED -> floatArrayOf(-38f, -44f, -66f, -50f, -76f + sin(t * 13) * 16, -78f, 11f)
        else -> floatArrayOf(-38f, -44f, -68f, -44f, -76f, -82f + sin(t * 2) * 6, 8f)
    }

    private fun drawTail(c: Canvas) {
        val d = tailDef()
        for (i in 0..12) {
            val u = i / 12f; val a = (1 - u) * (1 - u); val b = 2 * (1 - u) * u; val cc = u * u
            tailPts[i * 2] = a * d[0] + b * d[2] + cc * d[4]
            tailPts[i * 2 + 1] = a * d[1] + b * d[3] + cc * d[5]
        }
        path.reset(); path.moveTo(d[0], d[1]); path.quadTo(d[2], d[3], d[4], d[5])
        stroke(c, d[6], cCat); c.drawPath(path, sp)
        stroke(c, d[6], cDark); c.drawLine(tailPts[20], tailPts[21], d[4], d[5], sp)
    }

    private fun bodyWithStripes(c: Canvas, g: Geo, breathe: Float) {
        val ry = g.bry + breathe
        rf.set(g.bcx - g.brx, g.bcy - ry, g.bcx + g.brx, g.bcy + ry)
        path.reset(); path.addOval(rf, Path.Direction.CW)
        fp.color = cCat; c.drawPath(path, fp)
        c.save(); c.clipPath(path)
        oval(c, g.bcx + g.brx * .2f, g.bcy + g.bry * .75f, g.brx * .7f, g.bry * .45f, cCream)
        stroke(c, 3.5f, cDark)
        for (k in floatArrayOf(-.5f, -.15f, .2f)) {
            val x = g.bcx + g.brx * k
            path.reset(); path.moveTo(x, g.bcy - g.bry)
            path.quadTo(x + 5, g.bcy - g.bry * .4f, x - 2, g.bcy + g.bry * .05f)
            c.drawPath(path, sp)
        }
        c.restore()
    }

    private fun drawHead(c: Canvas, g: Geo) {
        val r = g.hr
        c.save(); c.translate(g.hcx, g.hcy)
        val tilt = when (st) {
            St.HAPPY -> .14f + sin(t * 2) * .04f
            St.GROOM -> .25f + sin(t * 6) * .08f
            St.IDLE -> sin(t * 1.3f) * .12f
            else -> 0f
        }
        c.rotate(Math.toDegrees(tilt.toDouble()).toFloat())

        val flat = st == St.ANNOYED || st == St.FLEE || hissT > 0
        // back ear then front ear: base1, base2, tip
        val ears = if (flat) arrayOf(
            floatArrayOf(-r * .8f, -r * .4f, -2f, -r * .95f, -r - 8, -r * .55f),
            floatArrayOf(2f, -r * .95f, r * .85f, -r * .4f, r + 9, -r * .6f)
        ) else arrayOf(
            floatArrayOf(-r * .85f, -r * .4f, -1f, -r * .95f, -r * .55f, -r - 13),
            floatArrayOf(3f, -r * .95f, r * .85f, -r * .4f, r * .6f, -r - 13)
        )
        tri(c, ears[0][0], ears[0][1], ears[0][4], ears[0][5], ears[0][2], ears[0][3], cDark)
        val e = ears[1]
        tri(c, e[0], e[1], e[4], e[5], e[2], e[3], cCat)
        val mx = (e[0] + e[2] + e[4]) / 3; val my = (e[1] + e[3] + e[5]) / 3
        tri(c, e[0] * .55f + mx * .45f, e[1] * .55f + my * .45f,
            e[4] * .6f + mx * .4f, e[5] * .6f + my * .4f,
            e[2] * .55f + mx * .45f, e[3] * .55f + my * .45f, cEarIn)

        oval(c, 0f, 0f, r, r * .94f, cCat)
        oval(c, -r * .55f, r * .35f, r * .42f, r * .36f, cCat)
        stroke(c, 2.6f, cDark)
        for (x in floatArrayOf(-6f, 0f, 6f)) c.drawLine(x - 2, -r * .92f, x, -r * .6f, sp)
        oval(c, r * .5f, r * .38f, r * .42f, r * .3f, cCream)

        // eyes
        var mode = when (st) {
            St.SLEEP -> 3
            St.HAPPY -> 2
            St.ANNOYED -> 1
            St.FLEE -> 4
            St.GROOM -> if (sin(t * 3) > 0) 2 else 0
            else -> 0
        }
        if (blink > 0 && mode == 0) mode = 3
        for (ex in floatArrayOf(r * .05f, r * .62f)) {
            stroke(c, 2.2f, cEye)
            when (mode) {
                0, 4 -> {
                    val k = if (mode == 4) 1.2f else 1f
                    oval(c, ex, -r * .12f, 3.6f * k, 4.7f * k, cEye)
                    oval(c, ex + 1.2f, -r * .12f - 1.6f, 1.2f, 1.2f, Color.WHITE)
                }
                1 -> {
                    oval(c, ex, -r * .08f, 4f, 1.7f, cEye)
                    val inner = ex > 5
                    c.drawLine(ex - 5, -r * .3f + (if (inner) 2 else -1), ex + 5, -r * .3f + (if (inner) -1 else 2), sp)
                }
                2 -> {
                    path.reset(); path.moveTo(ex - 4.5f, -r * .06f); path.quadTo(ex, -r * .34f, ex + 4.5f, -r * .06f)
                    c.drawPath(path, sp)
                }
                else -> {
                    path.reset(); path.moveTo(ex - 4.5f, -r * .12f); path.quadTo(ex, 2f, ex + 4.5f, -r * .12f)
                    c.drawPath(path, sp)
                }
            }
        }
        if (st == St.HAPPY || st == St.SLEEP) {
            oval(c, r * .05f, r * .25f, 4f, 2.5f, cBlush)
            oval(c, r * .72f, r * .22f, 3.5f, 2.3f, cBlush)
        }
        // nose + mouth
        tri(c, r * .52f, r * .16f, r * .72f, r * .16f, r * .62f, r * .28f, cNose)
        if (hissT > 0 || swatT > 0) oval(c, r * .6f, r * .5f, 4.5f, 3.6f, cMouth)
        else {
            stroke(c, 1.4f, cEye)
            path.reset()
            path.moveTo(r * .62f, r * .28f); path.quadTo(r * .55f, r * .45f, r * .44f, r * .38f)
            path.moveTo(r * .62f, r * .28f); path.quadTo(r * .69f, r * .45f, r * .8f, r * .38f)
            c.drawPath(path, sp)
        }
        stroke(c, 1f, cWhisker)
        for (dy in floatArrayOf(-3f, 2f, 7f)) c.drawLine(r * .85f, r * .35f, r * 1.65f, r * .35f + dy * 1.4f, sp)
        c.restore()
    }

    private fun drawCat(c: Canvas) {
        val g = geo()
        // shadow (view space)
        oval(c, ox, oy + 1, (if (g.pose == Pose.SIT) 34f else 48f) * s, 8 * s, cShadow)

        c.save(); c.translate(ox, oy); c.scale(s * dir, s)
        val breathe = sin(t * (if (st == St.SLEEP) 1.5f else 2.4f)) * (if (st == St.SLEEP) 1.4f else .6f)
        when (g.pose) {
            Pose.STAND -> {
                drawTail(c)
                val moving = st == St.WALK || st == St.FLEE
                val xs = floatArrayOf(-26f, 24f, -14f, 34f)
                val far = booleanArrayOf(true, false, false, true)
                fun legAt(i: Int, col: Int, shift: Float) {
                    val a = phase + (i % 2) * PI.toFloat() + shift
                    val swing = if (moving) sin(a) * 7 else 0f
                    val lift = if (moving) max(0f, -cos(a)) * 4 else 0f
                    leg(c, xs[i], -32f, xs[i] + swing, -lift, col)
                }
                for (i in 0..3) if (far[i]) legAt(i, cDark, 0f)
                bodyWithStripes(c, g, breathe)
                for (i in 0..3) if (!far[i]) legAt(i, cCat, PI.toFloat())
                drawHead(c, g)
                if (swatT > 0) leg(c, 34f, -44f, 58f, -60f, cCat)
            }
            Pose.SIT -> {
                if (st == St.HAPPY) drawTail(c)
                leg(c, 14f, -44f, 14f, 0f, cDark)
                bodyWithStripes(c, g, breathe)
                oval(c, -14f, -16f, 18f, 16f, cCat)
                stroke(c, 3f, cDark)
                rf.set(-25f, -27f, -3f, -5f)
                c.drawArc(rf, Math.toDegrees(-2.4).toFloat(), Math.toDegrees(1.8).toFloat(), false, sp)
                if (st != St.HAPPY) drawTail(c)
                when {
                    swatT > 0 -> leg(c, 8f, -46f, 36f, -66f, cCat)
                    st == St.GROOM -> leg(c, 8f, -46f, g.hcx + 18, g.hcy + 14 + sin(t * 6) * 3, cCat)
                    else -> leg(c, 6f, -44f, 6f, 0f, cCat)
                }
                drawHead(c, g)
                if (st == St.GROOM) oval(c, g.hcx + 19, g.hcy + 15 + sin(t * 6) * 3, 6f, 4.5f, cCat)
            }
            Pose.SLEEP -> {
                val ry = g.bry + breathe
                rf.set(g.bcx - g.brx, g.bcy - ry, g.bcx + g.brx, g.bcy + ry)
                path.reset(); path.addOval(rf, Path.Direction.CW)
                fp.color = cCat; c.drawPath(path, fp)
                c.save(); c.clipPath(path)
                stroke(c, 3.5f, cDark)
                for (k in floatArrayOf(-.55f, -.2f, .15f)) {
                    val x = g.bcx + g.brx * k
                    val p = Path(); p.moveTo(x, g.bcy - g.bry - 2); p.quadTo(x + 5, g.bcy - g.bry * .3f, x - 1, g.bcy)
                    c.drawPath(p, sp)
                }
                c.restore()
                drawHead(c, g)
                drawTail(c)
            }
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
        fp.color = cHeart; fp.alpha = alpha; c.drawPath(path, fp); fp.alpha = 255
    }

    private fun drawParts(c: Canvas) {
        for (q in parts) {
            val a = min(1f, q.life / q.max * 1.6f)
            val alpha = (a * 255).toInt().coerceIn(0, 255)
            when (q.kind) {
                0 -> heart(c, q.x, q.y, 14 * s, alpha)
                1 -> {
                    stroke(c, 3 * dp, cAnger); val z = 8 * s
                    for (i in 0 until 4) {
                        c.save(); c.translate(q.x, q.y); c.rotate(i * 90f)
                        path.reset(); path.moveTo(z * .35f, -z); path.quadTo(z * .35f, -z * .35f, z, -z * .35f)
                        sp.alpha = alpha; c.drawPath(path, sp); c.restore()
                    }
                }
                2 -> {
                    tp.color = cInk; tp.alpha = (alpha * .7f).toInt()
                    tp.textSize = (14 + (1 - q.life / q.max) * 10) * s
                    c.drawText("z", q.x, q.y, tp)
                    tp.alpha = 255
                }
                3 -> {
                    stroke(c, 2.5f * dp, cAnger); sp.alpha = alpha; val z = 22 * s
                    for (i in -1..1) c.drawLine(q.x - z * .6f + i * 7 * dp, q.y - z * .6f, q.x + z * .6f + i * 7 * dp, q.y + z * .6f, sp)
                }
            }
            sp.alpha = 255
        }
    }

    private fun drawBubble(c: Canvas) {
        if (bubbleTxt == null || bubbleLines.isEmpty()) return
        val h = headView()
        val a = min(1f, min(bubbleLife / .25f, (bubbleMax - bubbleLife) / .12f + .2f)).coerceIn(0f, 1f)
        val alpha = (a * 255).toInt()
        tp.textSize = 15 * dp; tp.color = cInk
        val fm = tp.fontMetrics
        val lineH = (fm.descent - fm.ascent) + 2 * dp
        var tw = 0f
        for (l in bubbleLines) tw = max(tw, tp.measureText(l))
        val padX = 14 * dp; val padY = 8 * dp
        val bw = tw + padX * 2
        val bh = lineH * bubbleLines.size + padY * 2
        // Keep the bubble inside both this window and the visible screen.
        val lo = max(4 * dp, ox - catX + 4 * dp)
        val hi = min(winW - bw - 4 * dp, sw - catX + ox - bw - 4 * dp)
        val want = h[0] - bw / 2
        val bx = if (hi >= lo) want.coerceIn(lo, hi) else lo
        val by = max(2 * dp, h[1] - h[2] - bh - 24 * dp)
        val radius = min(bh / 2, 16 * dp)
        rf.set(bx, by, bx + bw, by + bh)
        fp.color = Color.WHITE; fp.alpha = alpha
        c.drawRoundRect(rf, radius, radius, fp)
        stroke(c, 1 * dp, Color.argb(40, 0, 0, 0)); sp.alpha = (alpha * 40) / 255
        c.drawRoundRect(rf, radius, radius, sp)
        val tipX = h[0].coerceIn(bx + 16 * dp, bx + bw - 16 * dp)
        path.reset()
        path.moveTo(tipX - 7 * dp, by + bh - 1); path.lineTo(tipX + 2 * dp, by + bh + 9 * dp); path.lineTo(tipX + 7 * dp, by + bh - 1)
        path.close()
        c.drawPath(path, fp)
        fp.alpha = 255; sp.alpha = 255
        tp.alpha = alpha
        var y = by + padY - fm.ascent
        for (l in bubbleLines) { c.drawText(l, bx + padX, y, tp); y += lineH }
        tp.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        drawCat(canvas)
        drawParts(canvas)
        drawBubble(canvas)
    }
}
