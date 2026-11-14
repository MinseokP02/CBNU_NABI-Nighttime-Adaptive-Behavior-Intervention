package com.cbnu.nabi.ui

import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.cbnu.nabi.pet.PetService

/**
 * Login / register screen shown before the main layout (FR-02-01). Signing up asks for the ISI
 * through the 7-question [IsiSurvey] (the score is no longer typed in by hand).
 */
class LoginPanel(private val activity: MainActivity, private val onLoggedIn: () -> Unit) {

    private val emailInput: EditText
    private val pwInput: EditText
    private val isiStatus: TextView
    private val catNameInput: EditText
    private val authStatus: TextView
    private val server: ServerSection
    private var isi: Int? = null

    val view: View

    init {
        val page = activity.page()
        page.add(TextView(activity).apply {
            text = "나비"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
        })
        page.text("밤마다 곁에 있는 수면 코치 고양이예요.\n나비는 화면 위를 돌아다니고, 꾹 누르면 대화할 수 있어요.", 15f)

        page.heading("로그인")
        emailInput = page.add(EditText(activity).apply {
            hint = "이메일"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        })
        pwInput = page.add(EditText(activity).apply {
            hint = "비밀번호 (10자 이상)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        })
        page.text("회원가입할 때만", 12f, 0.7f)
        isiStatus = page.text("ISI 검사: 회원가입을 누르면 7문항 검사를 진행해요", 13f)
        catNameInput = page.add(EditText(activity).apply {
            hint = "고양이 이름 (기본: 나비)"
            isSingleLine = true
        })
        authStatus = page.text("", 13f)
        page.buttonRow("로그인" to ::login, "회원가입" to ::register)

        server = ServerSection(activity, page, "서버 설정 (개발용)")
        view = activity.scrollPage(page)
    }

    private fun login() {
        val email = emailInput.text.toString().trim()
        val pw = pwInput.text.toString()
        if (email.isEmpty() || pw.isEmpty()) { authStatus.text = "이메일과 비밀번호를 입력해 주세요"; return }
        server.save()
        authStatus.text = "로그인 중…"
        activity.bg({ activity.api.login(email, pw) }) { r ->
            r.onSuccess { done() }.onFailure { authStatus.text = it.friendly() }
        }
    }

    private fun register() {
        val email = emailInput.text.toString().trim()
        val pw = pwInput.text.toString()
        when {
            email.isEmpty() -> { authStatus.text = "이메일을 입력해 주세요"; return }
            pw.length < 10 -> { authStatus.text = "비밀번호는 10자 이상이어야 해요"; return }
        }
        val score = isi
        if (score == null) {
            // ISI first; confirming the result continues the sign-up.
            IsiSurvey(activity, "이 결과로 가입하기") { total ->
                isi = total
                isiStatus.text = "ISI 검사 결과: ${total}점 · ${IsiSurvey.band(total)}"
                register()
            }.show()
            return
        }
        val catName = catNameInput.text.toString().trim().ifEmpty { "나비" }
        server.save()
        authStatus.text = "가입 중…"
        activity.bg({
            activity.api.register(email, pw, score, catName)
            activity.api.login(email, pw)
        }) { r ->
            r.onSuccess {
                // Shown in 마이페이지 until the app can read GET /users/me (isi_score).
                ProfileStore.rememberIsi(activity, email, score)
                done()
            }.onFailure { authStatus.text = it.friendly() }
        }
    }

    private fun done() {
        pwInput.setText("")
        authStatus.text = ""
        onLoggedIn()
        PetService.sync(activity)
    }
}
