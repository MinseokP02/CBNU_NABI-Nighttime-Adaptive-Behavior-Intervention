package com.cbnu.nabi.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cbnu.nabi.R
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.Prefs

/**
 * Adaptive main screen (FR-01).
 * - Logged out: login screen ([LoginPanel]).
 * - Phone: one panel at a time with a bottom tab bar 수면일지 / 홈 / 내 정보, starting on 홈.
 * - Tablet: 수면일지 | 홈 | 내 정보 side by side.
 *
 * Panels are created once and only moved between layouts, so typed input survives rotation and
 * layout switches (FR-01-04). The manifest lets this activity handle configuration changes itself.
 */
class MainActivity : Activity() {

    lateinit var prefs: Prefs
        private set
    lateinit var api: ApiClient
        private set

    private enum class Tab(val label: String, val icon: Int) {
        SLEEP("수면 일지", R.drawable.ic_book),
        HOME("홈", R.drawable.ic_home),
        PROFILE("마이페이지", R.drawable.ic_user),
    }

    private var loginPanel: LoginPanel? = null
    private var sleepPanel: SleepDiaryPanel? = null
    private var homePanel: HomePanel? = null
    private var profilePanel: ProfilePanel? = null

    private var currentTab = Tab.HOME
    private val tabPanes = mutableMapOf<Tab, View>()
    private val tabButtons = mutableMapOf<Tab, LinearLayout>()
    private var tabBar: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitOnPhones()
        CatPresence.install(application)
        current = java.lang.ref.WeakReference(this)
        prefs = Prefs(this)
        api = ApiClient(prefs)
        currentTab = savedInstanceState?.getString(KEY_TAB)
            ?.let { name -> Tab.values().firstOrNull { it.name == name } } ?: Tab.HOME

        render()
        if (savedInstanceState == null) showProposalFrom(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.loggedIn) {
            if (loginPanel?.view?.parent == null) showLogin() // logged out elsewhere (e.g. token expired)
            return
        }
        profilePanel?.update()
        sleepPanel?.refresh()
        homePanel?.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab.name)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        // Destroyed mid-tutorial (e.g. while the preview chat was on top): don't leave later chats read-only.
        if (tutorial != null) NabiTutorial.chatPreview = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    /** 상점 › 내 아이템 › 방 꾸미기 comes back here with [EXTRA_DECORATE]. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        showProposalFrom(intent)
        if (intent.getBooleanExtra(EXTRA_DECORATE, false) && prefs.loggedIn) {
            if (tabButtons.isNotEmpty()) selectTab(Tab.HOME)
            homePanel?.enterDecorate()
        }
    }

    // ---------------- 나비의 제안 (patent [0045]) ----------------
    /**
     * Entry point for the cat / intervention / API owners: show "나비의 제안" for [p].
     * From outside an activity use [MissionProposals.open], which lands here.
     */
    fun showMissionProposal(p: MissionProposal) {
        if (!prefs.loggedIn) return
        MissionProposalModal(this, p) { response ->
            MissionProposalStore(this, prefs.email).record(p, response)
            if (response == ProposalResponse.ACCEPTED) when (p.kind.flow) {
                ProposalFlow.TRACKABLE -> {
                    Toast.makeText(this, "나의 미션에 추가했어요", Toast.LENGTH_SHORT).show()
                    homePanel?.onMissionsChanged()
                }
                ProposalFlow.SLEEP_DIARY -> openSleepRecord()
                ProposalFlow.NOTICE -> Unit // acknowledged only; brightness control is not done here
            }
        }.show()
    }

    /**
     * Connection point to the permission settings (used when an AUTO mission can't read its data).
     * For now this opens the app's system settings, like 마이페이지 › 데이터 접근 권한.
     */
    fun openPermissionSettings() {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName")))
    }

    /** A mission's state changed (e.g. an AUTO check was reported): refresh 나의 미션 and the count. */
    fun onMissionsChanged() {
        homePanel?.onMissionsChanged()
    }

    private fun showProposalFrom(intent: android.content.Intent?) {
        val p = MissionProposals.from(intent) ?: return
        MissionProposals.clear(intent)
        window.decorView.post { showMissionProposal(p) }
    }

    /** Hide the floating tab bar while decorating the room (phone only). */
    fun setTabBarVisible(visible: Boolean) {
        tabBar?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Back from a 마이페이지 sub-page or the decorate mode returns before leaving the app. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // While the tutorial runs, back only goes to the previous step (never leaves the app).
        tutorial?.let { it.back(); return }
        if (profilePanel?.handleBack() == true) return
        if (homePanel?.handleBack() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------- 튜토리얼 (도움말) ----------------
    /** Screens a tutorial step can ask for. On a tablet all three are already side by side. */
    enum class Screen { HOME, DIARY, PROFILE }

    private var tutorial: TutorialOverlay? = null
    val tutorialHome: HomePanel? get() = homePanel
    val tutorialDiary: SleepDiaryPanel? get() = sleepPanel
    val tutorialProfile: ProfilePanel? get() = profilePanel

    /**
     * Start the coach-mark tutorial over the real screens. From 마이페이지 › 도움말 ([returnHere] =
     * true) it comes back to the tab the user was on; the automatic first run ends on 홈.
     */
    fun startTutorial(returnHere: Boolean = true) {
        if (tutorial != null || !prefs.loggedIn || homePanel == null) return
        val from = currentTab
        tutorial = TutorialOverlay(this, NabiTutorial.steps()) {
            tutorial = null
            homePanel?.closeAnalysis()
            TutorialPrefs.markDone(this) // finished or skipped; watching again never resets it
            // Ended by a logout (showLogin): the login screen is up, so keep currentTab = HOME.
            if (prefs.loggedIn && tabButtons.isNotEmpty()) selectTab(if (returnHere) from else Tab.HOME)
        }.also { it.start() }
    }

    /** [analysisSheet]: the step explains the 나의 수면 분석 sheet, so open it; otherwise close it. */
    fun showScreenForTutorial(s: Screen, analysisSheet: Boolean = false) {
        if (s == Screen.PROFILE) profilePanel?.showMainForTutorial()
        if (tabButtons.isNotEmpty()) selectTab(when (s) { Screen.HOME -> Tab.HOME; Screen.DIARY -> Tab.SLEEP; Screen.PROFILE -> Tab.PROFILE })
        if (analysisSheet) homePanel?.openAnalysisForTutorial { tutorial?.back() }
        else homePanel?.closeAnalysis()
    }

    /** Release builds only: the first time the main screens are ready on this device. */
    private fun maybeAutoStartTutorial() {
        if (!TutorialConfig.AUTO_START_ON_FIRST_USE || TutorialPrefs.isDone(this)) return
        // Wait until the panels are laid out and the room cat has appeared.
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed && prefs.loggedIn && !TutorialPrefs.isDone(this)) startTutorial(returnHere = false)
        }, 900)
    }

    // ---------------- called by panels ----------------

    /** Go back to the login screen and drop the logged-in panels so the next user starts clean. */
    fun showLogin() {
        tutorial?.end()
        homePanel?.closeAnalysis() // the sheet is a separate window: it would stay over the login screen
        sleepPanel = null
        homePanel = null
        profilePanel = null
        currentTab = Tab.HOME
        render()
    }

    /** "기록하기" on the home analysis card: the sleep record sheet (FR-03). */
    fun openSleepRecord(date: java.time.LocalDate = java.time.LocalDate.now()) {
        SleepRecordSheet(this, date) {
            Toast.makeText(this, "수면 기록을 저장했어요", Toast.LENGTH_SHORT).show()
            homePanel?.refresh()
            sleepPanel?.refresh()
        }.show()
    }

    /** Open the "나의 수면 분석" sheet (home card or the diary's 자세히 분석하기). */
    fun openAnalysis() {
        homePanel?.openAnalysis()
    }

    /** From the analysis sheet's 수면 일지 보기: if the diary is already showing, just stay there. */
    fun showDiaryFromAnalysis() {
        if (tabButtons.isNotEmpty() && currentTab == Tab.SLEEP) return
        showDiary()
    }

    /** Show the 수면 일지 tab on today (phone). On a tablet it is already visible on the left. */
    fun showDiary() {
        sleepPanel?.showToday()
        if (tabButtons.isNotEmpty()) selectTab(Tab.SLEEP)
    }

    /** The overlay switch in 마이페이지 changed: let the home room take or give back the cat. */
    fun onOverlayChanged() {
        homePanel?.onOverlayChanged()
    }

    private fun onLoggedIn() {
        render()
        homePanel?.refresh()
    }

    private fun logout() {
        prefs.logout()
        showLogin()
    }

    // ---------------- layout ----------------

    private fun render() {
        setContentView(if (prefs.loggedIn) buildMain() else buildLogin())
        if (prefs.loggedIn && tutorial == null) maybeAutoStartTutorial()
    }

    private fun buildLogin(): View {
        val panel = loginPanel ?: LoginPanel(this, ::onLoggedIn).also { loginPanel = it }
        return panel.view.apply { detach() }
    }

    private fun buildMain(): View {
        val sleep = sleepPanel ?: SleepDiaryPanel(this).also { sleepPanel = it }
        val home = homePanel ?: HomePanel(this).also { homePanel = it }
        val profile = profilePanel ?: ProfilePanel(this, ::logout).also { profilePanel = it }

        tabPanes.clear()
        tabPanes[Tab.SLEEP] = sleep.view
        tabPanes[Tab.HOME] = home.view
        tabPanes[Tab.PROFILE] = profile.view
        val tablet = isTablet()
        tabPanes.values.forEach {
            it.detach()
            it.visibility = View.VISIBLE
            // Phone: pages leave room so the floating tab bar never covers their end.
            // The home room draws edge to edge behind the bar on purpose (Figma).
            if (it !== home.view) {
                it.setPadding(0, 0, 0, if (tablet) 0 else dp(NAV_HEIGHT_DP + NAV_BOTTOM_DP + 12))
                (it as? ViewGroup)?.clipToPadding = false
            }
        }

        home.setTabBarShown(!tablet)
        return if (tablet) buildThreePane() else buildTabs()
    }

    /**
     * FR-01-03 split rule, set after testing a tablet-sized screen: 3 panes only on a tablet
     * (shortest side >= 600dp) held wide enough (current width >= 840dp, i.e. landscape).
     * Portrait tablets (~800dp → 266dp per pane) and phones in landscape use the bottom tabs.
     */
    private fun isTablet(): Boolean = resources.configuration.let {
        it.smallestScreenWidthDp >= TABLET_MIN_DP && it.screenWidthDp >= THREE_PANE_MIN_WIDTH_DP
    }

    private fun buildThreePane(): View {
        tabBar = null
        // No tabs here: forget the phone tab buttons, or selectTab() (방 꾸미기, analysis card)
        // would hide the other two panes and stretch home across the whole screen.
        tabButtons.clear()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Tab.values().forEachIndexed { i, tab ->
            if (i > 0) row.addView(divider(), LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
            row.addView(tabPanes.getValue(tab), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    /** Phone: pages fill the screen and the Figma floating tab bar sits on top near the bottom. */
    private fun buildTabs(): View {
        val root = FrameLayout(this)
        for (tab in Tab.values()) {
            root.addView(tabPanes.getValue(tab), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = cardBg(Color.argb(230, 255, 250, 246), 27, Color.argb(199, 185, 155, 137))
            elevation = dp(6).toFloat()
        }
        tabButtons.clear()
        tabBar = bar
        for (tab in Tab.values()) {
            val label = TextView(this).apply {
                text = tab.label
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.create(Fonts.body(this@MainActivity), Typeface.BOLD)
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(icon(tab.icon, NAV_TEXT, 22))
                addView(label)
                contentDescription = tab.label
                setOnClickListener { selectTab(tab) }
            }
            tabButtons[tab] = item
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(NAV_HEIGHT_DP)).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), 0, dp(16), dp(NAV_BOTTOM_DP))
        })

        selectTab(currentTab)
        return root
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        for ((t, pane) in tabPanes) pane.visibility = if (t == tab) View.VISIBLE else View.GONE
        for ((t, item) in tabButtons) {
            val selected = t == tab
            item.background = if (selected) rounded(Color.argb(240, 234, 224, 244), 22) else null
            (item.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(if (selected) Palette.lavenderDeep else NAV_TEXT)
            (item.getChildAt(1) as TextView).setTextColor(if (selected) Palette.ink else NAV_TEXT)
        }
    }

    private fun divider() = View(this).apply { setBackgroundColor(Palette.divider) }

    companion object {
        const val EXTRA_DECORATE = "decorate"
        /** The live main screen, so the chat panel can blur it. */
        var current: java.lang.ref.WeakReference<MainActivity>? = null
        private const val KEY_TAB = "tab"
        private const val THREE_PANE_MIN_WIDTH_DP = 840
        private const val NAV_HEIGHT_DP = 64
        private const val NAV_BOTTOM_DP = 9
        private val NAV_TEXT = Color.parseColor("#6F5D54")
    }
}
