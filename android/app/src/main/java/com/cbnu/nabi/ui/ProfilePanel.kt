package com.cbnu.nabi.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cbnu.nabi.R
import com.cbnu.nabi.pet.PetService
import java.time.LocalTime

/**
 * Right / 마이페이지 (FR-07), following the Figma screens:
 * main (account card, 수면 목표, 초기 ISI, 나비 및 앱 설정) → 내 정보 (account, 개발 설정, logout)
 * and 알림 설정 sub-pages, plus the 수면 목표 수정 modal.
 */
class ProfilePanel(private val activity: MainActivity, private val onLogout: () -> Unit) {

    private enum class Page { MAIN, ACCOUNT, NOTIFICATIONS }

    private val store get() = ProfileStore(activity, activity.prefs.email)
    private val root = FrameLayout(activity)
    private var page = Page.MAIN

    val view: View get() = root

    init { show(Page.MAIN) }

    /** Re-read stored values and permissions (on resume, after the home state loads). */
    fun update() = show(page)

    /** System back: sub-page → main. Returns true if handled. */
    fun handleBack(): Boolean {
        if (page == Page.MAIN) return false
        show(Page.MAIN)
        return true
    }

    private fun show(p: Page) {
        val scrollY = (root.getChildAt(0) as? ScrollView)?.takeIf { p == page }?.scrollY ?: 0
        page = p
        root.removeAllViews()
        val col = activity.page()
        when (p) {
            Page.MAIN -> buildMain(col)
            Page.ACCOUNT -> buildAccount(col)
            Page.NOTIFICATIONS -> buildNotifications(col)
        }
        val scroll = activity.scrollPage(col)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    // ---------------- shared bits ----------------
    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
        background = activity.cardBg(Palette.card, 20, Color.parseColor("#E6D4C8"))
        elevation = activity.dp(1).toFloat()
    }

    private fun heading(iconRes: Int, title: String, right: View? = null) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(activity.icon(iconRes, Palette.lavender, 21))
        addView(activity.label(title, 17f, title = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(7) })
        if (right != null) addView(right)
    }

    private fun pill(iconRes: Int, text: String, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
        background = activity.rounded(Palette.lavenderSoft, 11)
        addView(activity.icon(iconRes, Color.parseColor("#71568D"), 13))
        addView(activity.label(text, 11f, Color.parseColor("#71568D"), bold = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(4) })
        setOnClickListener { onClick() }
    }

    private fun avatar(sizeDp: Int, iconDp: Int) = FrameLayout(activity).apply {
        background = activity.cardBg(Palette.lavenderSoft, 999, Color.parseColor("#DDCFF0"))
        addView(activity.icon(R.drawable.ic_user, Color.parseColor("#7E649D"), iconDp),
            FrameLayout.LayoutParams(activity.dp(iconDp), activity.dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(activity.dp(sizeDp), activity.dp(sizeDp))
    }

    private fun settingIcon(res: Int) = FrameLayout(activity).apply {
        background = activity.rounded(Color.parseColor("#F1E9F6"), 11)
        addView(activity.icon(res, Color.parseColor("#9475B7"), 20), FrameLayout.LayoutParams(activity.dp(20), activity.dp(20), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(activity.dp(33), activity.dp(33))
    }

    /** A settings list row: icon, title + subtitle, and [right] (toggle or chevron). */
    private fun settingRow(iconRes: Int?, title: String, sub: String, right: View, onClick: (() -> Unit)? = null) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(11), activity.dp(11), activity.dp(11), activity.dp(11))
            if (iconRes != null) addView(settingIcon(iconRes))
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(activity.label(title, 12.5f, bold = true))
            texts.addView(activity.label(sub, 10.5f, Palette.muted).apply { setPadding(0, activity.dp(4), 0, 0) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = if (iconRes != null) activity.dp(10) else 0; rightMargin = activity.dp(8)
            })
            addView(right)
            if (onClick != null) setOnClickListener { onClick() }
        }

    private fun chevron() = activity.icon(R.drawable.ic_chevron_right, Palette.muted, 18)

    /** Bordered list box with hairlines between rows. */
    private fun list(vararg rows: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.cardBg(Color.parseColor("#FFFCF9"), 16, Color.parseColor("#EADDD3"))
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(activity).apply { setBackgroundColor(Color.parseColor("#EFE3DA")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
            addView(r)
        }
    }

    private fun subHeader(col: Column, title: String, desc: String? = null) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(FrameLayout(activity).apply {
            background = activity.cardBg(Palette.card, 12, Color.parseColor("#E6D4C8"))
            addView(activity.icon(R.drawable.ic_chevron_left, Palette.ink, 22), FrameLayout.LayoutParams(activity.dp(22), activity.dp(22), Gravity.CENTER))
            contentDescription = "마이페이지로 돌아가기"
            setOnClickListener { show(Page.MAIN) }
        }, LinearLayout.LayoutParams(activity.dp(36), activity.dp(36)))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(activity.label(title, 22f, title = true))
        if (desc != null) texts.addView(activity.label(desc, 11f, Palette.muted).apply { setPadding(0, activity.dp(4), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(10) })
        col.add(row)
    }

    // ---------------- main ----------------
    private fun buildMain(col: Column) {
        col.add(activity.label("마이페이지", 26f, title = true))
        val email = activity.prefs.email

        // account card → 내 정보
        val account = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
            background = activity.cardBg(Palette.card, 20, Color.parseColor("#E6D4C8"))
            elevation = activity.dp(1).toFloat()
            addView(avatar(52, 28))
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(activity.label(email ?: "로그인 정보 없음", 15f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            texts.addView(activity.label(if (email != null) "내 정보 보기" else "연결된 이메일이 없습니다", 11f, Palette.muted)
                .apply { setPadding(0, activity.dp(4), 0, 0) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(12) })
            addView(activity.icon(R.drawable.ic_chevron_right, Palette.muted, 20))
            contentDescription = "내 정보"
            setOnClickListener { show(Page.ACCOUNT) }
        }
        col.add(account)

        // 나의 수면 목표 (FR-07-02)
        val s = store
        val goal = card()
        goal.addView(heading(R.drawable.ic_target, "나의 수면 목표", pill(R.drawable.ic_edit, "목표 수정") { openGoalModal() }))
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, activity.dp(10), 0, 0) }
        grid.addView(goalBox(R.drawable.ic_moon, Palette.lavender, "목표 취침 시각", s.goalBedtime),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        grid.addView(goalBox(R.drawable.ic_sun, Color.parseColor("#E8A33D"), "목표 기상 시각", s.goalWakeTime),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(7) })
        goal.addView(grid)
        col.add(goal)

        // 초기 ISI 평가 결과 (FR-07-01)
        val isi = card()
        val info = FrameLayout(activity).apply {
            addView(activity.icon(R.drawable.ic_info, Palette.lavender, 20), FrameLayout.LayoutParams(activity.dp(20), activity.dp(20), Gravity.CENTER))
            contentDescription = "ISI 평가 안내"
            setOnClickListener {
                Modals.info(activity, R.drawable.ic_info, "ISI 평가 안내",
                    "ISI는 불면 증상의 심각도를 파악하기 위한 설문이며, 의학적 진단을 대신하지 않습니다.")
            }
            layoutParams = LinearLayout.LayoutParams(activity.dp(32), activity.dp(32))
        }
        isi.addView(heading(R.drawable.ic_calendar, "초기 ISI 평가 결과", info))
        val score = s.isiScore
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(13), activity.dp(13), activity.dp(13), activity.dp(13))
            background = activity.rounded(Color.parseColor("#F8F1F7"), 16)
        }
        if (score == null) {
            box.addView(activity.label("평가 결과가 없습니다", 14f, bold = true))
            box.addView(activity.label("회원가입 때 입력한 ISI 점수가 이 기기에 없어요.", 11f, Palette.muted).apply { setPadding(0, activity.dp(5), 0, 0) })
        } else {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(activity.label("${score}점", 24f, Palette.lavenderDeep, title = true))
            row.addView(activity.label(" / 28점", 11f, Palette.muted))
            box.addView(row)
            box.addView(activity.label(interpretIsi(score), 13f, bold = true).apply { setPadding(0, activity.dp(6), 0, 0) })
            box.addView(activity.label("초기 ISI 설문에 따른 불면 증상 수준이에요.", 11f, Palette.muted).apply { setPadding(0, activity.dp(4), 0, 0) })
        }
        isi.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(9) })
        col.add(isi)

        // 나비 및 앱 설정 (FR-07-04, FR-05-08)
        val settings = card()
        settings.addView(heading(R.drawable.ic_settings, "나비 및 앱 설정"))
        val canOverlay = Settings.canDrawOverlays(activity)
        val overlayToggle = ToggleSwitch(activity, PetService.isRunning) { on -> setOverlay(on) }
        val hapticToggle = ToggleSwitch(activity, activity.prefs.haptics) { on -> activity.prefs.haptics = on; true }
        settings.addView(list(
            settingRow(R.drawable.ic_cat_silhouette, "나비 화면 상주",
                if (canOverlay) "다른 앱 위에도 나비를 표시해요." else "시스템 권한 연결이 필요해요.", overlayToggle),
            settingRow(R.drawable.ic_heart, "만질 때 진동 반응", "골골송·하악질을 진동으로 느껴요.", hapticToggle),
            settingRow(R.drawable.ic_shield, "데이터 접근 권한", "건강·활동·사용 정보 권한을 확인해요.", chevron()) { openPermissions() },
            settingRow(R.drawable.ic_bell, "알림 설정", "수면 및 활동 안내를 설정해요.", chevron()) { show(Page.NOTIFICATIONS) },
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(10) })
        col.add(settings)
    }

    private fun goalBox(iconRes: Int, iconColor: Int, name: String, value: String?) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(10), activity.dp(12), activity.dp(10), activity.dp(12))
        background = activity.cardBg(Color.parseColor("#FFFCF9"), 16, Color.parseColor("#EADDD3"))
        addView(activity.icon(iconRes, iconColor, 22))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(activity.label(name, 10f, Palette.muted))
        texts.addView(activity.label(value?.let { Modals.format12(LocalTime.parse(it)) } ?: "설정 안 됨", 14f).apply { setPadding(0, activity.dp(6), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
    }

    /** Standard ISI bands (same as the Figma design). Shown with the "not a diagnosis" note. */
    private fun interpretIsi(score: Int) = when {
        score <= 7 -> "임상적으로 유의한 불면 증상 없음"
        score <= 14 -> "역치하 불면"
        score <= 21 -> "중등도 임상적 불면"
        else -> "심한 임상적 불면"
    }

    /** Overlay switch: existing PetService start/stop. Without the permission, explain and offer the settings. */
    private fun setOverlay(on: Boolean): Boolean {
        val intent = Intent(activity, PetService::class.java)
        if (on) {
            if (!Settings.canDrawOverlays(activity)) {
                Modals.info(activity, R.drawable.ic_cat_silhouette, "화면 상주 권한이 필요해요",
                    "다른 앱 위에 나비를 표시하려면 Android 시스템 설정에서 화면 위 표시 권한을 허용해 주세요.",
                    "설정 열기" to {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
                    })
                return false
            }
            activity.startForegroundService(intent)
            activity.moveTaskToBack(true)
        } else {
            activity.stopService(intent)
            // The service stops asynchronously; then the cat lives in the home room again.
            root.postDelayed({ activity.onOverlayChanged() }, 400)
        }
        return true
    }

    private fun openPermissions() {
        Modals.info(activity, R.drawable.ic_shield, "데이터 접근 권한",
            "건강 데이터·신체활동·사용 정보 연동은 준비 중이에요. 지금 앱이 가진 권한은 시스템 설정에서 확인할 수 있어요.",
            "앱 권한 설정 열기" to {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            })
    }

    // ---------------- 수면 목표 수정 modal (FR-07-02/03) ----------------
    private fun openGoalModal() {
        val s = store
        var bed: LocalTime? = s.goalBedtime?.let(LocalTime::parse)
        var wake: LocalTime? = s.goalWakeTime?.let(LocalTime::parse)
        val dialog = Dialog(activity)
        val box = Modals.box(activity)
        box.addView(activity.icon(R.drawable.ic_target, Palette.lavender, 25))
        box.addView(activity.label("수면 목표 수정", 19f, title = true).apply { setPadding(0, activity.dp(8), 0, 0) })
        box.addView(activity.label("수면 기록과 별도로 관리되는 나의 목표 시간이에요.", 11.5f, Palette.muted).apply { setPadding(0, activity.dp(5), 0, 0) })

        lateinit var bedValue: TextView
        lateinit var wakeValue: TextView
        fun field(iconRes: Int, color: Int, name: String, onClick: () -> Unit): Pair<View, TextView> {
            val v = activity.label("", 14f, bold = true)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(12), activity.dp(11), activity.dp(10), activity.dp(11))
                background = activity.cardBg(Color.parseColor("#FFFCF9"), 14, Color.parseColor("#EADDD3"))
                addView(activity.icon(iconRes, color, 22))
                val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(activity.label(name, 10f, Palette.muted))
                texts.addView(v.apply { setPadding(0, activity.dp(4), 0, 0) })
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(10) })
                addView(activity.icon(R.drawable.ic_chevron_right, Palette.muted, 17))
                setOnClickListener { onClick() }
            }
            return row to v
        }
        fun refresh() {
            bedValue.text = bed?.let(Modals::format12) ?: "시간 선택"
            wakeValue.text = wake?.let(Modals::format12) ?: "시간 선택"
        }
        val behind = listOf(dialog.window?.decorView)
        val (bedRow, bv) = field(R.drawable.ic_moon, Palette.lavender, "목표 취침 시각") {
            Modals.timePicker(activity, "목표 취침 시각", bed ?: LocalTime.of(23, 0), behind) { bed = it; refresh() }
        }
        val (wakeRow, wv) = field(R.drawable.ic_sun, Color.parseColor("#E8A33D"), "목표 기상 시각") {
            Modals.timePicker(activity, "목표 기상 시각", wake ?: LocalTime.of(7, 0), behind) { wake = it; refresh() }
        }
        bedValue = bv; wakeValue = wv
        box.addView(bedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(14) })
        box.addView(wakeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        val error = activity.label("", 11f, Color.parseColor("#B45656"), bold = true).apply { visibility = View.GONE; setPadding(0, activity.dp(10), 0, 0) }
        box.addView(error)
        refresh()

        lateinit var saveBtn: TextView
        val buttons = Modals.buttons(activity, "취소", { dialog.dismiss() }, "목표 저장") {
            val b = bed; val w = wake
            if (b == null || w == null) {
                error.text = "목표 취침 시각과 기상 시각을 모두 설정해 주세요."; error.visibility = View.VISIBLE
                return@buttons
            }
            error.visibility = View.GONE
            saveBtn.text = "저장 중…"; saveBtn.isEnabled = false
            val ok = store.saveGoals("%02d:%02d".format(b.hour, b.minute), "%02d:%02d".format(w.hour, w.minute))
            if (ok) {
                saveBtn.text = "저장 완료"
                saveBtn.postDelayed({ dialog.dismiss(); show(Page.MAIN) }, 600)
            } else {
                saveBtn.text = "목표 저장"; saveBtn.isEnabled = true
                error.text = "수면 목표를 저장하지 못했어요."; error.visibility = View.VISIBLE
            }
        }
        saveBtn = (buttons as LinearLayout).getChildAt(1) as TextView
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(16) })
        Modals.show(activity, dialog, box)
    }

    // ---------------- 내 정보 ----------------
    private fun buildAccount(col: Column) {
        subHeader(col, "내 정보")
        val email = activity.prefs.email
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, activity.dp(14), 0, activity.dp(6))
            addView(avatar(76, 37))
            addView(activity.label(email ?: "로그인 정보 없음", 18f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(10), 0, 0) })
            addView(activity.label(if (email != null) "로그인된 계정" else "연결된 이메일이 없습니다", 11f, Palette.muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(4), 0, 0) })
        }
        col.add(hero)

        val info = card()
        info.addView(activity.label("계정 정보", 17f, title = true))
        info.addView(infoLine("이메일", email ?: "등록된 정보 없음"))
        info.addView(infoLine("이름 또는 닉네임", "등록된 정보 없음"))
        info.addView(activity.label("계정 정보 수정은 준비 중이에요.", 10.5f, Palette.muted).apply { setPadding(0, activity.dp(10), 0, 0) })
        col.add(info)

        // FR-02-03: server address / connection check stays reachable here.
        val dev = card()
        val devCol = Column(activity, 8)
        ServerSection(activity, devCol, "개발 설정 · 서버")
        dev.addView(devCol.root)
        col.add(dev)

        col.add(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = activity.dp(46)
            background = activity.cardBg(Color.parseColor("#FFF9F6"), 16, Color.parseColor("#E4C5C0"))
            addView(activity.icon(R.drawable.ic_logout, Color.parseColor("#A15E5B"), 18))
            addView(activity.label("로그아웃", 13f, Color.parseColor("#A15E5B"), bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(7) })
            setOnClickListener { page = Page.MAIN; onLogout() }
        })
    }

    private fun infoLine(name: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, activity.dp(12), 0, 0)
        addView(activity.label(name, 12f, Palette.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(activity.label(value, 12.5f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }

    // ---------------- 알림 설정 ----------------
    private fun buildNotifications(col: Column) {
        subHeader(col, "알림 설정", "나비가 전하는 수면 및 활동 안내를 설정해요.")
        val s = store
        // A fixed note, and in the same spot a small pill that pops up after each toggle and fades
        // away (like the "나비를 터치해 보세요" hint on home).
        val note = activity.label("알림 기능은 준비 중이에요. 설정은 이 기기에 저장돼요.", 11f, Palette.muted).apply { gravity = Gravity.CENTER }
        val toast = activity.label("", 11f, Palette.lavenderDeep, bold = true).apply {
            setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
            background = activity.cardBg(Color.argb(240, 255, 250, 246), 999, Color.parseColor("#D9C9E6"))
            alpha = 0f
        }
        val status = FrameLayout(activity).apply {
            addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            minimumHeight = activity.dp(30)
        }

        val granted = Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            col.add(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(11), activity.dp(11), activity.dp(11), activity.dp(11))
                background = activity.cardBg(Color.parseColor("#F2EAF7"), 17, Color.parseColor("#D9C9E6"))
                addView(settingIcon(R.drawable.ic_bell))
                val t = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                t.addView(activity.label("운영체제 알림 권한이 필요해요", 12.5f, bold = true))
                t.addView(activity.label("앱 설정에서 권한을 확인해 주세요.", 10.5f, Palette.muted).apply { setPadding(0, activity.dp(3), 0, 0) })
                addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(9) })
                addView(chevron())
                setOnClickListener {
                    activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                }
            })
        }

        val saved: (Boolean) -> Unit = { ok ->
            toast.text = if (ok) "설정이 저장되었어요" else "설정을 저장하지 못했어요"
            toast.animate().cancel(); note.animate().cancel()
            note.animate().alpha(0f).setDuration(150).start()
            toast.animate().alpha(1f).setStartDelay(0).setDuration(180).withEndAction {
                toast.animate().alpha(0f).setStartDelay(1400).setDuration(300).withEndAction {
                    note.animate().alpha(1f).setDuration(200).start()
                }.start()
            }.start()
        }
        val rows = listOf(
            Triple(ProfileStore.N_SLEEP_PREP, "취침 준비 안내" to "목표 취침 시각에 맞춰 수면 준비를 안내해요.", R.drawable.ic_moon),
            Triple(ProfileStore.N_DIARY, "수면 일지 작성 알림" to "기상 후 작성하지 않은 수면 일지를 안내해요.", R.drawable.ic_book),
            Triple(ProfileStore.N_MISSION, "활동 및 미션 안내" to "활동이 부족할 때 스트레칭이나 산책을 안내해요.", R.drawable.ic_target),
        )
        val subToggles = mutableListOf<ToggleSwitch>()
        val subCard = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun applyMaster(on: Boolean) {
            subCard.alpha = if (on) 1f else 0.5f
            subToggles.forEach { it.isEnabled = on }
        }
        val master = ToggleSwitch(activity, s.notif(ProfileStore.N_MASTER)) { on ->
            val ok = store.setNotif(ProfileStore.N_MASTER, on); saved(ok); if (ok) applyMaster(on); ok
        }
        val masterCard = card()
        masterCard.addView(settingRow(null, "알림 받기", "모든 NABI 알림 사용 여부를 설정해요.", master).apply { setPadding(0, 0, 0, 0) })
        col.add(masterCard)

        subCard.addView(activity.label("수면 및 활동 알림", 16f, title = true).apply { setPadding(activity.dp(4), activity.dp(6), 0, 0) })
        val rowViews = rows.map { (key, texts, icon) ->
            val t = ToggleSwitch(activity, s.notif(key)) { on -> val ok = store.setNotif(key, on); saved(ok); ok }
            subToggles += t
            settingRow(icon, texts.first, texts.second, t)
        }
        subCard.addView(list(*rowViews.toTypedArray()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(10) })
        col.add(subCard)
        applyMaster(s.notif(ProfileStore.N_MASTER))
        col.add(status)
    }
}

/**
 * Figma settings toggle (42×25 pill, lavender when on). [onChange] returns whether the new state
 * was accepted; if not, the switch springs back.
 */
class ToggleSwitch(
    private val activity: MainActivity,
    initial: Boolean,
    private val onChange: (Boolean) -> Boolean,
) : FrameLayout(activity) {

    private var checked = initial
    private val knob = View(activity).apply { background = activity.rounded(Color.WHITE, 999); elevation = activity.dp(1).toFloat() }

    init {
        addView(knob, LayoutParams(activity.dp(19), activity.dp(19), Gravity.CENTER_VERTICAL))
        layoutParams = LinearLayout.LayoutParams(activity.dp(42), activity.dp(25))
        setPadding(activity.dp(3), 0, activity.dp(3), 0)
        contentDescription = "스위치"
        setOnClickListener {
            val next = !checked
            if (onChange(next)) { checked = next; render(true) }
        }
        render(false)
    }

    private fun render(animate: Boolean) {
        background = activity.rounded(if (checked) Palette.lavender else Color.parseColor("#D9CFCA"), 999)
        val x = if (checked) activity.dp(42 - 6 - 19).toFloat() else 0f
        if (animate) knob.animate().translationX(x).setDuration(150).start() else knob.translationX = x
        isSelected = checked
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.6f
    }
}
