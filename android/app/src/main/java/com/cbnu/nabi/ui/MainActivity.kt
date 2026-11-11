package com.cbnu.nabi.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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

    private enum class Tab(val label: String) { SLEEP("수면일지"), HOME("홈"), PROFILE("내 정보") }

    private var loginPanel: LoginPanel? = null
    private var sleepPanel: SleepDiaryPanel? = null
    private var homePanel: HomePanel? = null
    private var profilePanel: ProfilePanel? = null

    private var currentTab = Tab.HOME
    private val tabPanes = mutableMapOf<Tab, View>()
    private val tabButtons = mutableMapOf<Tab, TextView>()
    private var tabDefaultColors: ColorStateList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        homePanel?.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab.name)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
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

    /** Home loaded the user state (cat name etc.); refresh what the profile shows. */
    fun onUserStateLoaded() {
        profilePanel?.update()
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
        tabPanes.values.forEach { it.detach(); it.visibility = View.VISIBLE }

        return if (isTablet()) buildThreePane() else buildTabs()
    }

    /** FR-01-03: provisional split rule — device's shortest side >= 600dp. Confirm on real devices. */
    private fun isTablet(): Boolean = resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP

    private fun buildThreePane(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Tab.values().forEachIndexed { i, tab ->
            if (i > 0) row.addView(divider(), LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
            row.addView(tabPanes.getValue(tab), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    private fun buildTabs(): View {
        val content = FrameLayout(this)
        for (tab in Tab.values()) {
            content.addView(tabPanes.getValue(tab), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabButtons.clear()
        for (tab in Tab.values()) {
            val btn = TextView(this).apply {
                text = tab.label
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setOnClickListener { selectTab(tab) }
            }
            if (tabDefaultColors == null) tabDefaultColors = btn.textColors
            tabButtons[tab] = btn
            bar.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        selectTab(currentTab)
        return root
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        for ((t, pane) in tabPanes) pane.visibility = if (t == tab) View.VISIBLE else View.GONE
        for ((t, btn) in tabButtons) {
            val selected = t == tab
            if (selected) btn.setTextColor(Palette.cat) else btn.setTextColor(tabDefaultColors)
            btn.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun divider() = View(this).apply { setBackgroundColor(Palette.divider) }

    companion object {
        private const val KEY_TAB = "tab"
        private const val TABLET_MIN_DP = 600
    }
}
