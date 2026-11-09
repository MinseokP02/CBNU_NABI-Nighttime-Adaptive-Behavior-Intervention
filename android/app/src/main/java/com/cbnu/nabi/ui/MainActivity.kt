package com.cbnu.nabi.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Labels
import com.cbnu.nabi.data.Mission
import com.cbnu.nabi.data.Prefs
import com.cbnu.nabi.data.UserState
import com.cbnu.nabi.pet.PetService

/**
 * Home screen: server address, login/register, today's state and missions, and the overlay cat.
 * The cat itself lives in [PetService]; this screen only starts/stops it and pushes reactions.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: ApiClient

    // server
    private lateinit var urlInput: EditText
    private lateinit var serverStatus: TextView

    // auth
    private lateinit var authBox: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var pwInput: EditText
    private lateinit var isiInput: EditText
    private lateinit var catNameInput: EditText
    private lateinit var authStatus: TextView

    // logged in
    private lateinit var homeBox: LinearLayout
    private lateinit var whoText: TextView
    private lateinit var stateText: TextView
    private lateinit var missionList: LinearLayout

    // overlay
    private lateinit var overlayStatus: TextView
    private lateinit var permBtn: Button
    private lateinit var callBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = ApiClient(prefs)

        val page = Column(this, 12)
        page.root.setPadding(dp(24), dp(40), dp(24), dp(32))

        page.add(TextView(this).apply {
            text = "나비"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
        })
        page.text("밤마다 곁에 있는 수면 코치 고양이예요.\n나비는 화면 위를 돌아다니고, 꾹 누르면 대화할 수 있어요.", 15f)

        buildOverlaySection(page)
        buildServerSection(page)
        buildAuthSection(page)
        buildHomeSection(page)

        setContentView(ScrollView(this).apply { addView(page.root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = Settings.canDrawOverlays(this)
        permBtn.visibility = if (ok) View.GONE else View.VISIBLE
        callBtn.isEnabled = ok
        overlayStatus.text = if (ok) "준비 완료. '나비 부르기'를 누르세요."
        else "먼저 '다른 앱 위에 표시' 권한을 켜 주세요."
        showAuthState()
        if (prefs.loggedIn) refreshHome()
    }

    // ---------------- overlay ----------------
    private fun buildOverlaySection(page: Column) {
        page.heading("화면 위 나비")
        overlayStatus = page.text()
        permBtn = page.add(Button(this).apply {
            text = "다른 앱 위에 표시 권한 허용"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        callBtn = page.add(Button(this).apply {
            text = "나비 부르기"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, PetService::class.java))
                moveTaskToBack(true)
            }
        })
        page.add(Button(this).apply {
            text = "나비 숨기기"
            setOnClickListener { stopService(Intent(this@MainActivity, PetService::class.java)) }
        })
        page.add(CheckBox(this).apply {
            text = "만질 때 진동으로 반응하기 (골골송, 하악질)"
            isChecked = prefs.haptics
            setOnCheckedChangeListener { _, on -> prefs.haptics = on }
        })
        page.text("머리·턱은 좋아하고, 등은 적당히, 배는 기분 따라, 꼬리는 절대 싫어해요.", 13f, 0.7f)
    }

    // ---------------- server ----------------
    private fun buildServerSection(page: Column) {
        page.heading("서버")
        urlInput = page.add(EditText(this).apply {
            setText(prefs.baseUrl)
            hint = "http://10.0.2.2:8000"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        })
        page.text("에뮬레이터: http://10.0.2.2:8000 · USB 연결 폰: adb reverse tcp:8000 tcp:8000 후 http://127.0.0.1:8000", 12f, 0.7f)
        serverStatus = page.text("", 13f)
        page.add(Button(this).apply {
            text = "연결 확인"
            setOnClickListener {
                prefs.baseUrl = urlInput.text.toString()
                serverStatus.text = "확인 중…"
                bg({ api.health() }) { r ->
                    serverStatus.text = r.fold(
                        { "서버 ${it.status} · DB ${it.database} · LLM ${it.llm}" },
                        { it.friendly() }
                    )
                }
            }
        })
    }

    // ---------------- auth ----------------
    private fun buildAuthSection(page: Column) {
        val box = Column(this, 8)
        authBox = page.add(box.root)
        box.heading("로그인")
        emailInput = box.add(EditText(this).apply {
            hint = "이메일"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        })
        pwInput = box.add(EditText(this).apply {
            hint = "비밀번호 (10자 이상)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        })
        box.text("회원가입할 때만", 12f, 0.7f)
        isiInput = box.add(EditText(this).apply {
            hint = "ISI 점수 (0~28)"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        })
        catNameInput = box.add(EditText(this).apply {
            hint = "고양이 이름 (기본: 나비)"
            isSingleLine = true
        })
        authStatus = box.text("", 13f)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        rowBtn("로그인") { login() }
        rowBtn("회원가입") { register() }
        box.add(row)
    }

    private fun login() {
        val email = emailInput.text.toString().trim()
        val pw = pwInput.text.toString()
        if (email.isEmpty() || pw.isEmpty()) { authStatus.text = "이메일과 비밀번호를 입력해 주세요"; return }
        prefs.baseUrl = urlInput.text.toString()
        authStatus.text = "로그인 중…"
        bg({ api.login(email, pw) }) { r ->
            r.onSuccess {
                pwInput.setText("")
                authStatus.text = ""
                showAuthState()
                refreshHome()
                PetService.sync(this)
            }.onFailure { authStatus.text = it.friendly() }
        }
    }

    private fun register() {
        val email = emailInput.text.toString().trim()
        val pw = pwInput.text.toString()
        val isi = isiInput.text.toString().toIntOrNull()
        val catName = catNameInput.text.toString().trim().ifEmpty { "나비" }
        when {
            email.isEmpty() -> { authStatus.text = "이메일을 입력해 주세요"; return }
            pw.length < 10 -> { authStatus.text = "비밀번호는 10자 이상이어야 해요"; return }
            isi == null || isi !in 0..28 -> { authStatus.text = "ISI 점수(0~28)를 입력해 주세요"; return }
        }
        prefs.baseUrl = urlInput.text.toString()
        authStatus.text = "가입 중…"
        bg({ api.register(email, pw, isi!!, catName); api.login(email, pw) }) { r ->
            r.onSuccess {
                pwInput.setText("")
                authStatus.text = ""
                showAuthState()
                refreshHome()
                PetService.sync(this)
            }.onFailure { authStatus.text = it.friendly() }
        }
    }

    private fun showAuthState() {
        authBox.visibility = if (prefs.loggedIn) View.GONE else View.VISIBLE
        homeBox.visibility = if (prefs.loggedIn) View.VISIBLE else View.GONE
        whoText.text = "${prefs.email ?: ""} 로그인됨"
    }

    // ---------------- home (logged in) ----------------
    private fun buildHomeSection(page: Column) {
        val box = Column(this, 10)
        homeBox = page.add(box.root)
        box.heading("오늘의 나")
        whoText = box.text("", 13f, 0.7f)
        stateText = box.add(TextView(this).apply {
            textSize = 15f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Palette.card)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        rowBtn("나비와 대화") { startActivity(Intent(this, ChatActivity::class.java)) }
        rowBtn("새로고침") { refreshHome() }
        box.add(row)

        box.heading("오늘의 미션")
        missionList = box.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })

        box.add(Button(this).apply {
            text = "로그아웃"
            setOnClickListener {
                prefs.logout()
                showAuthState()
            }
        })
    }

    private fun refreshHome() {
        stateText.text = "불러오는 중…"
        bg({ api.userState() to api.activeMissions() }) { r ->
            r.onSuccess { (state, missions) ->
                renderState(state)
                renderMissions(missions)
            }.onFailure {
                stateText.text = it.friendly()
                if (!prefs.loggedIn) showAuthState() // token expired (401)
            }
        }
    }

    private fun renderState(s: UserState) {
        prefs.catName = s.catName
        val factors = if (s.riskFactors.isEmpty()) "없음" else s.riskFactors.joinToString(", ") { Labels.factor(it) }
        val baseline = if (s.baselineStatus == "ready") "준비됨" else "수집 중 (유효 ${s.baselineValidDays}일)"
        stateText.text = buildString {
            append("${s.catName} · Lv.${s.catLevel} (EXP ${s.catExp})\n\n")
            append("위험도: ${Labels.risk(s.riskLevel)}\n")
            append("수면 상태: ${Labels.sleep(s.sleepState)}\n")
            append("생활 습관: ${Labels.behavior(s.behaviorState)}\n")
            append("살펴볼 점: $factors\n")
            append("개인 기준선: $baseline")
        }
    }

    private fun renderMissions(list: List<Mission>) {
        missionList.removeAllViews()
        if (list.isEmpty()) {
            missionList.addView(TextView(this).apply { text = "지금은 진행 중인 미션이 없어요"; alpha = 0.7f })
            return
        }
        for (m in list) {
            val card = Column(this, 6)
            card.root.setPadding(dp(16), dp(8), dp(16), dp(14))
            card.root.background = rounded(Palette.card)
            card.add(TextView(this).apply { text = m.title; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
            card.text(m.description, 14f)
            card.text("보상 EXP ${m.rewardExp} · 기한 ${m.deadline.take(16).replace('T', ' ')}", 12f, 0.7f)
            val btn = card.add(Button(this).apply { text = "완료했어요" }, matchWidth = false)
            btn.setOnClickListener {
                btn.isEnabled = false
                bg({ api.completeMission(m.missionId) }) { r ->
                    btn.isEnabled = true
                    r.onSuccess { res ->
                        Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
                        PetService.show(this, res.action, res.message)
                        refreshHome()
                    }.onFailure {
                        Toast.makeText(this, it.friendly(), Toast.LENGTH_LONG).show()
                        PetService.show(this, CatAction.COMFORT, "괜찮아, 기록하고 다시 해보자냥")
                    }
                }
            }
            missionList.addView(card.root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
    }
}
