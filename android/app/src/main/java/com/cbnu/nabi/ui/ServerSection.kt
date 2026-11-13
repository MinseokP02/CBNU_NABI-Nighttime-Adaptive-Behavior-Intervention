package com.cbnu.nabi.ui

import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Server address + connection check (existing feature, FR-02-03).
 * Shown on the login screen and in the 개발 설정 area of 내 정보.
 */
class ServerSection(private val activity: MainActivity, page: Column, title: String) {

    private val urlInput: EditText
    private val status: TextView

    init {
        page.heading(title)
        urlInput = page.add(EditText(activity).apply {
            setText(activity.prefs.baseUrl)
            hint = "http://10.0.2.2:8000"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        })
        page.text("에뮬레이터: http://10.0.2.2:8000 · USB 연결 폰: adb reverse tcp:8000 tcp:8000 후 http://127.0.0.1:8000", 12f, 0.7f)
        status = page.text("", 13f)
        page.add(Button(activity).apply {
            text = "연결 확인"
            setOnClickListener { check() }
        })
    }

    /** Store the typed address so the next API call uses it. */
    fun save() {
        activity.prefs.baseUrl = urlInput.text.toString()
    }

    private fun check() {
        save()
        status.text = "확인 중…"
        activity.bg({ activity.api.health() }) { r ->
            status.text = r.fold(
                { "서버 ${it.status} · DB ${it.database} · LLM ${it.llm}" },
                { it.friendly() }
            )
        }
    }
}
