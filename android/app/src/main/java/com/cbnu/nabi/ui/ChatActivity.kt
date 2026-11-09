package com.cbnu.nabi.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.ChatMessage
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.pet.PetService

/**
 * Chat with the cat (API-15/16). The backend builds the context (risk, interventions),
 * the internal LLM server writes the reply, and both pass safety checks before reaching here.
 * Each reply's `cat_action` is forwarded to the overlay cat so it reacts on screen.
 */
class ChatActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = ApiClient(prefs)
        if (!prefs.loggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "${prefs.catName}와 대화"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "나비는 의료 진단을 하지 않아요. 위급할 때는 109(자살예방), 1577-0199(정신건강), 119로 연락하세요."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        input = EditText(this).apply {
            hint = "오늘 잠은 어땠어?"
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        send = Button(this).apply {
            text = "보내기"
            setOnClickListener { sendMessage() }
        }
        bar.addView(send)
        root.addView(bar)

        setContentView(root)
        loadHistory()
    }

    private fun loadHistory() {
        if (prefs.conversationId == null) {
            addBubble("냥! 오늘 하루는 어땠어? 잠 이야기든 걱정이든 편하게 말해줘.", fromUser = false)
            return
        }
        bg({ api.chatHistory() }) { r ->
            r.onSuccess { msgs ->
                if (msgs.isEmpty()) addBubble("냥! 오늘 하루는 어땠어?", fromUser = false)
                for (m in msgs) addBubble(m.content, m.fromUser, crisis = isCrisis(m))
            }.onFailure { addSystem(it.friendly()) }
        }
    }

    private fun isCrisis(m: ChatMessage) = !m.fromUser && m.safetyStatus == "CRISIS"

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || sending) return
        if (text.length > 1000) { addSystem("메시지는 1000자까지 보낼 수 있어요"); return }
        sending = true
        send.isEnabled = false
        input.setText("")
        addBubble(text, fromUser = true)
        val thinking = addBubble("…", fromUser = false)
        PetService.show(this, CatAction.SIT, "음… 생각 중이다냥")

        bg({ api.sendChat(text, "SIT") }) { r ->
            sending = false
            send.isEnabled = true
            r.onSuccess { reply ->
                val crisis = reply.safetyStatus == "CRISIS"
                thinking.text = reply.reply
                if (crisis) styleBubble(thinking, fromUser = false, crisis = true)
                // A crisis reply must stay calm: never play a playful animation with it.
                PetService.show(this, if (crisis) CatAction.COMFORT else reply.action, reply.reply)
            }.onFailure {
                list.removeView(thinking)
                addSystem(it.friendly())
                if (!prefs.loggedIn) { startActivity(Intent(this, MainActivity::class.java)); finish() }
            }
            scrollToEnd()
        }
    }

    // ---------- bubbles ----------
    private fun addBubble(text: String, fromUser: Boolean, crisis: Boolean = false): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Palette.ink)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setTextIsSelectable(true)
        }
        styleBubble(tv, fromUser, crisis)
        list.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            gravity = if (fromUser) Gravity.END else Gravity.START
        })
        scrollToEnd()
        return tv
    }

    private fun styleBubble(tv: TextView, fromUser: Boolean, crisis: Boolean) {
        tv.background = rounded(
            when {
                crisis -> android.graphics.Color.parseColor("#FDE2E0")
                fromUser -> Palette.cat
                else -> Palette.cream
            }, 16
        )
    }

    private fun addSystem(text: String) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.7f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
}
