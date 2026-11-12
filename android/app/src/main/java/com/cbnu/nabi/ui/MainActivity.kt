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
        CatPresence.install(application)
        current = java.lang.ref.WeakReference(this)
        prefs = Prefs(this)
        api = ApiClient(prefs)
        currentTab = savedInstanceState?.getString(KEY_TAB)
            ?.let { name -> Tab.values().firstOrNull { it.name == name } } ?: Tab.HOME

        render()

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
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    /** 상점 › 내 아이템 › 방 꾸미기 comes back here with [EXTRA_DECORATE]. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_DECORATE, false) && prefs.loggedIn) {
            if (tabButtons.isNotEmpty()) selectTab(Tab.HOME)
            homePanel?.enterDecorate()
        }
    }

    /** Hide the floating tab bar while decorating the room (phone only). */
    fun setTabBarVisible(visible: Boolean) {
        tabBar?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Back from a 마이페이지 sub-page or the decorate mode returns before leaving the app. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (profilePanel?.handleBack() == true) return
        if (homePanel?.handleBack() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------- called by panels ----------------

    /** Go back to the login screen and drop the logged-in panels so the next user starts clean. */
    fun showLogin() {
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

    /** Show the 수면 일지 tab (phone). On a tablet it is already visible on the left. */
    fun showDiary() {
        sleepPanel?.showToday()
        if (tabButtons.isNotEmpty()) selectTab(Tab.SLEEP)
    }

    /** The overlay switch in 마이페이지 changed: let the home room take or give back the cat. */
    fun onOverlayChanged() {
        homePanel?.onOverlayChanged()
    }

    /** Home loaded the user state; refresh what the profile and the diary show. */
    fun onUserStateLoaded(state: com.cbnu.nabi.data.UserState) {
        profilePanel?.update()
        sleepPanel?.setState(state)
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

        return if (tablet) buildThreePane() else buildTabs()
    }

    /** FR-01-03: provisional split rule — device's shortest side >= 600dp. Confirm on real devices. */
    private fun isTablet(): Boolean = resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP

    private fun buildThreePane(): View {
        tabBar = null
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
        private const val TABLET_MIN_DP = 600
        private const val NAV_HEIGHT_DP = 64
        private const val NAV_BOTTOM_DP = 9
        private val NAV_TEXT = Color.parseColor("#6F5D54")
    }
}
