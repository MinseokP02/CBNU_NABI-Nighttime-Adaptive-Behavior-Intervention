package com.cbnu.nabi.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.R
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.ChatMessage
import com.cbnu.nabi.data.Prefs
import java.time.LocalTime

/**
 * Chat with the cat (API-15/16), shown as the Figma "나비와 대화하기" bottom panel over the app
 * (FR-05-04: long-pressing Nabi still opens this ChatActivity). The backend builds the context,
 * the internal LLM server writes the reply, and both pass safety checks before reaching here.
 * Each reply's `cat_action` goes to whichever cat is visible (room cat or overlay).
 */
class ChatActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var backdrop: View
    private var sending = false
    private var closing = false
    /** Opened from the tutorial (꾹 누르기): show the chat, but nothing can be typed or sent. */
    private var preview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = ApiClient(prefs)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        if (!prefs.loggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val root = FrameLayout(this)
        backdrop = View(this).apply {
            setBackgroundColor(Color.argb(107, 54, 38, 32))
            alpha = 0f
            setOnClickListener { close() }
        }
        root.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FBF5EF"))
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), Color.parseColor("#DFCCBD"))
            }
            elevation = dp(10).toFloat()
            isClickable = true // taps inside the panel never reach the backdrop
        }
        panel.addView(dragZone(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
        panel.addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        // Safety notice kept from the original chat screen (instead of Figma's demo-only note).
        panel.addView(label("나비는 의료 진단을 하지 않아요.\n위급할 때는 109(자살예방), 1577-0199(정신건강), 119로 연락하세요.", 9.5f,
            Color.parseColor("#806E65"), bold = true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.parseColor("#F4ECE7"))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(16), dp(15), dp(12)) }
        scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(inputArea())

        val h = resources.displayMetrics.heightPixels
        normalH = maxOf((h * 0.82f).toInt().coerceAtMost(dp(720)), minOf(dp(470), h - dp(40)))
        val panelW = minOf(resources.displayMetrics.widthPixels, dp(BottomSheet.MAX_WIDTH_DP))
        root.addView(panel, FrameLayout.LayoutParams(panelW, normalH).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
        setContentView(root)
        blurApp(true)

        // slide up
        panel.translationY = h.toFloat()
        panel.post {
            panel.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
            backdrop.animate().alpha(1f).setDuration(220).start()
        }
        loadHistory()
    }

    // ---------------- panel parts ----------------
    private fun dragZone() = FrameLayout(this).apply {
        addView(View(this@ChatActivity).apply { background = rounded(Color.parseColor("#B7ACA5"), 99) },
            FrameLayout.LayoutParams(dp(47), dp(5), Gravity.CENTER))
        contentDescription = "끌어올려 크게 보기, 끌어내려 닫기"
        setOnTouchListener(sheetDrag)
    }

    /**
     * Drag the handle or the title bar: down closes (past 72dp), up grows the panel to full screen.
     * The panel snaps to its normal or full height when released.
     */
    @SuppressLint("ClickableViewAccessibility")
    private val sheetDrag = View.OnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragStartY = ev.rawY; dragStartH = panel.height; dragMoved = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - dragStartY
                if (!dragMoved && kotlin.math.abs(dy) < dp(6)) return@OnTouchListener true
                dragMoved = true
                val wanted = dragStartH - dy
                if (wanted >= normalH) {
                    setPanelHeight(wanted.toInt().coerceAtMost(fullH()))
                    panel.translationY = 0f
                } else {
                    setPanelHeight(normalH)
                    panel.translationY = normalH - wanted
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragMoved) {
                when {
                    panel.translationY > dp(72) -> close()
                    panel.height > (normalH + fullH()) / 2 -> animateHeight(fullH())
                    else -> { animateHeight(normalH); panel.animate().translationY(0f).setDuration(160).start() }
                }
            }
        }
        true
    }
    private var dragStartY = 0f
    private var dragStartH = 0
    private var dragMoved = false
    private var normalH = 0

    private fun fullH() = (panel.parent as View).height

    private fun setPanelHeight(h: Int) {
        val lp = panel.layoutParams
        if (lp.height != h) { lp.height = h; panel.layoutParams = lp }
    }

    private fun animateHeight(to: Int) {
        android.animation.ValueAnimator.ofInt(panel.height, to).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { setPanelHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun header() = FrameLayout(this).apply {
        setOnTouchListener(sheetDrag)
        addView(FrameLayout(this@ChatActivity).apply {
            addView(icon(R.drawable.ic_chevron_down, Palette.ink, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            contentDescription = "대화 닫기"
            setOnClickListener { close() }
        }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(9) })
        addView(label("${prefs.catName}와 대화하기", 19f, title = true),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(View(this@ChatActivity).apply { setBackgroundColor(Color.parseColor("#EADCD3")) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM))
    }

    private fun inputArea() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(dp(12), dp(10), dp(12), dp(12))
        setBackgroundColor(Color.argb(250, 255, 250, 246))
        input = EditText(this@ChatActivity).apply {
            hint = "${prefs.catName}에게 이야기해 보세요..."
            textSize = 13f
            maxLines = 4
            minHeight = dp(44)
            typeface = Fonts.body(this@ChatActivity)
            setTextColor(Palette.ink)
            setHintTextColor(Color.parseColor("#A4938A"))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = cardBg(Color.parseColor("#FFFDFB"), 18, Color.parseColor("#DFD0C7"))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false }
            setOnFocusChangeListener { _, f ->
                background = cardBg(Color.parseColor("#FFFDFB"), 18, Color.parseColor(if (f) "#AA8BCA" else "#DFD0C7"))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = updateSend()
            })
        }
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        send = FrameLayout(this@ChatActivity).apply {
            background = rounded(Color.parseColor("#9D7BC4"), 999)
            elevation = dp(3).toFloat()
            addView(icon(R.drawable.ic_send, Color.WHITE, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            contentDescription = "메시지 전송"
            setOnClickListener { sendMessage() }
        }
        addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        updateSend()
        if (preview) {
            input.isEnabled = false
            input.isFocusable = false
            input.hint = "튜토리얼 중에는 메시지를 보낼 수 없어요"
        }
    }

    private fun updateSend() {
        val ok = input.text.isNotBlank() && !sending
        send.isEnabled = ok
        send.alpha = if (ok) 1f else 0.4f
    }

    /**
     * Blur the NABI screen behind the panel (Android 12+). Phones like the S20+ have no system
     * window blur, so the app's own window is blurred directly. Over other apps it only dims.
     */
    private fun blurApp(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val decor = MainActivity.current?.get()?.window?.decorView ?: return
        val r = dp(14).toFloat()
        decor.setRenderEffect(if (on) android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP) else null)
    }

    override fun onDestroy() {
        blurApp(false)
        super.onDestroy()
    }

    private fun close() {
        if (closing) return
        closing = true
        blurApp(false)
        panel.animate().translationY(panel.height.toFloat()).setDuration(220).start()
        backdrop.animate().alpha(0f).setDuration(220).withEndAction {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    // ---------------- conversation (unchanged behaviour) ----------------
    private fun loadHistory() {
        if (prefs.conversationId == null) {
            addBubble("냥! 오늘 하루는 어땠어? 잠 이야기든 걱정이든 편하게 말해줘.", fromUser = false, time = now())
            return
        }
        bg({ api.chatHistory() }) { r ->
            r.onSuccess { msgs ->
                if (msgs.isEmpty()) addBubble("냥! 오늘 하루는 어땠어?", fromUser = false, time = now())
                for (m in msgs) addBubble(m.content, m.fromUser, crisis = isCrisis(m))
            }.onFailure { addSystem(it.friendly()) }
        }
    }

    private fun isCrisis(m: ChatMessage) = !m.fromUser && m.safetyStatus == "CRISIS"

    private fun sendMessage() {
        if (preview) return
        val text = input.text.toString().trim()
        if (text.isEmpty() || sending) return
        if (text.length > 1000) { addSystem("메시지는 1000자까지 보낼 수 있어요"); return }
        sending = true
        input.setText("")
        updateSend()
        addBubble(text, fromUser = true, time = now())
        val thinking = addBubble("…", fromUser = false, time = now())
        CatPresence.react(this, CatAction.SIT, "음… 생각 중이다냥")

        bg({ api.sendChat(text, "SIT") }) { r ->
            sending = false
            updateSend()
            r.onSuccess { reply ->
                val crisis = reply.safetyStatus == "CRISIS"
                thinking.text = reply.reply
                if (crisis) styleBubble(thinking, fromUser = false, crisis = true)
                // A crisis reply must stay calm: never play a playful animation with it.
                CatPresence.react(this, if (crisis) CatAction.COMFORT else reply.action, reply.reply)
            }.onFailure {
                (thinking.parent?.parent as? View)?.let { list.removeView(it) }
                addSystem(it.friendly())
                if (!prefs.loggedIn) { startActivity(Intent(this, MainActivity::class.java)); finish() }
            }
            scrollToEnd()
        }
    }

    private fun now(): String {
        val t = LocalTime.now()
        return "${if (t.hour >= 12) "오후" else "오전"} ${(t.hour % 12).let { if (it == 0) 12 else it }}:%02d".format(t.minute)
    }

    // ---------- bubbles (Figma chat-message) ----------
    /** Returns the bubble's TextView so a "…" placeholder can be replaced by the reply. */
    private fun addBubble(text: String, fromUser: Boolean, crisis: Boolean = false, time: String? = null): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END or Gravity.BOTTOM else Gravity.START or Gravity.BOTTOM
        }
        if (!fromUser) row.addView(FrameLayout(this).apply {
            background = rounded(Color.parseColor("#E9DDF2"), 999)
            addView(icon(R.drawable.ic_chat, Color.parseColor("#80629F"), 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(29), dp(29)).apply { rightMargin = dp(7) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = label(text, 13f).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
            maxWidth = minOf((resources.displayMetrics.widthPixels * 0.72f).toInt(), dp(290))
            setTextIsSelectable(true)
        }
        styleBubble(tv, fromUser, crisis)
        col.addView(tv)
        if (time != null) col.addView(label(time, 9f, Color.parseColor("#9A8B83")).apply {
            setPadding(dp(2), dp(3), dp(2), 0)
            gravity = if (fromUser) Gravity.END else Gravity.START
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(col)
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) })
        scrollToEnd()
        return tv
    }

    private fun styleBubble(tv: TextView, fromUser: Boolean, crisis: Boolean) {
        val r = dp(16).toFloat(); val s = dp(5).toFloat()
        tv.background = GradientDrawable().apply {
            when {
                crisis -> { setColor(Color.parseColor("#FDE2E0")); setStroke(dp(1), Color.parseColor("#F0C4BF")) }
                fromUser -> { setColor(Color.parseColor("#E9DEF2")); setStroke(dp(1), Color.parseColor("#D9C9E6")) }
                else -> { setColor(Color.parseColor("#FFFAF5")); setStroke(dp(1), Color.parseColor("#EADBD0")) }
            }
            // tail corner: bottom-left for Nabi, bottom-right for the user
            cornerRadii = if (fromUser) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
        }
    }

    private fun addSystem(text: String) {
        list.addView(label(text, 11f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }

    companion object {
        /** Tutorial preview: the panel opens as usual, but typing and sending are turned off. */
        const val EXTRA_PREVIEW = "chat_preview"
    }
}
