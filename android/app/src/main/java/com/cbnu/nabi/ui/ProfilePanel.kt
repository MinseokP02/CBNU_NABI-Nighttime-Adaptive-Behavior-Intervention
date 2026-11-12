package com.cbnu.nabi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import com.cbnu.nabi.pet.PetService

/**
 * Right / 내 정보 tab (FR-07). Basic info, the overlay on/off setting (FR-05-08, FR-07-04),
 * the server address (개발 설정, FR-02-03) and logout (FR-07-05).
 */
class ProfilePanel(private val activity: MainActivity, private val onLogout: () -> Unit) {

    private val emailText: TextView
    private val catNameText: TextView
    private val overlayStatus: TextView
    private val permBtn: Button
    private val overlaySwitch: Switch
    private val overlayListener = CompoundButton.OnCheckedChangeListener { _, on -> setOverlay(on) }

    val view: View

    init {
        val page = activity.page()
        page.heading("마이페이지")
        emailText = page.text("", 15f)
        catNameText = page.text("", 15f)

        page.heading("화면 위 나비")
        overlayStatus = page.text()
        permBtn = page.add(Button(activity).apply {
            text = "다른 앱 위에 표시 권한 허용"
            setOnClickListener {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                )
            }
        })
        overlaySwitch = page.add(Switch(activity).apply { text = "다른 앱 위에 나비 표시" })
        page.add(CheckBox(activity).apply {
            text = "만질 때 진동으로 반응하기 (골골송, 하악질)"
            isChecked = activity.prefs.haptics
            setOnCheckedChangeListener { _, on -> activity.prefs.haptics = on }
        })
        page.text("머리·턱은 좋아하고, 등은 적당히, 배는 기분 따라, 꼬리는 절대 싫어해요.", 13f, 0.7f)

        ServerSection(activity, page, "개발 설정 · 서버")

        page.add(Button(activity).apply {
            text = "로그아웃"
            setOnClickListener { onLogout() }
        })

        view = activity.scrollPage(page)
        update()
    }

    /** Re-read stored info and overlay permission/state (called on resume and after the home state loads). */
    fun update() {
        emailText.text = "이메일: ${activity.prefs.email ?: "-"}"
        catNameText.text = "고양이 이름: ${activity.prefs.catName}"

        val ok = Settings.canDrawOverlays(activity)
        permBtn.visibility = if (ok) View.GONE else View.VISIBLE
        overlaySwitch.isEnabled = ok
        overlaySwitch.setOnCheckedChangeListener(null)
        overlaySwitch.isChecked = PetService.isRunning
        overlaySwitch.setOnCheckedChangeListener(overlayListener)
        overlayStatus.text = if (ok) "아래 스위치로 나비를 켜고 끌 수 있어요."
        else "먼저 '다른 앱 위에 표시' 권한을 켜 주세요."
    }

    private fun setOverlay(on: Boolean) {
        val intent = Intent(activity, PetService::class.java)
        if (on) {
            activity.startForegroundService(intent)
            activity.moveTaskToBack(true)
        } else {
            activity.stopService(intent)
        }
    }
}
