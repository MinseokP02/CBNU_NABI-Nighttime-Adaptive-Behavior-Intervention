package com.cbnu.nabi.ui


import com.cbnu.nabi.R
import com.cbnu.nabi.tr
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
    private var isi: Int? = null

    val view: View

    init {
        val page = activity.page()
        page.add(TextView(activity).apply {
            text = tr(R.string.nabi)
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
        })
        page.text(tr(R.string.sleep_coach_cat_who_stays_with), 15f)

        page.heading(tr(R.string.log_in))
        emailInput = page.add(EditText(activity).apply {
            hint = tr(R.string.email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        })
        pwInput = page.add(EditText(activity).apply {
            hint = tr(R.string.password_10_characters)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        })
        page.text(tr(R.string.sign_up_only), 12f, 0.7f)
        isiStatus = page.text(tr(R.string.isi_test_tapping_sign_up_starts), 13f)
        catNameInput = page.add(EditText(activity).apply {
            hint = tr(R.string.cat_name_default_nabi)
            isSingleLine = true
        })
        authStatus = page.text("", 13f)
        page.buttonRow(tr(R.string.log_in) to ::login, tr(R.string.sign_up) to ::register)

        view = activity.scrollPage(page)
        DemoMode.prefillLogin(activity, emailInput, pwInput)
    }

    private fun login() {
        val email = emailInput.text.toString().trim()
        val pw = pwInput.text.toString()
        if (email.isEmpty() || pw.isEmpty()) { authStatus.text = tr(R.string.enter_your_email_and_password); return }
        authStatus.text = tr(R.string.logging_in)
        activity.bg({ activity.api.login(email, pw) }) { r ->
            r.onSuccess { done() }.onFailure { authStatus.text = it.friendly() }
        }
    }

    private fun register() {
        val email = emailInput.text.toString().trim()
        val pw = pwInput.text.toString()
        when {
            email.isEmpty() -> { authStatus.text = tr(R.string.enter_your_email); return }
            pw.length < 10 -> { authStatus.text = tr(R.string.password_must_be_at_least_10); return }
        }
        val score = isi
        if (score == null) {
            // ISI first; confirming the result continues the sign-up.
            IsiSurvey(activity, tr(R.string.sign_up_with_this_result)) { total ->
                isi = total
                isiStatus.text = tr(R.string.isi_result_pts, total, IsiSurvey.band(total))
                register()
            }.show()
            return
        }
        val catName = catNameInput.text.toString().trim().ifEmpty { tr(R.string.nabi) }
        authStatus.text = tr(R.string.signing_up)
        activity.bg({
            activity.api.register(email, pw, score, catName)
            activity.api.login(email, pw)
            DemoMode.seedAfterRegister(activity.api) // debug demo only; no-op otherwise
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
