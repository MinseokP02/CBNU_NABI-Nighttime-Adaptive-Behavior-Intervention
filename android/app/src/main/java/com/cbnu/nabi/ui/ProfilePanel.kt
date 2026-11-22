package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
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
import com.cbnu.nabi.alarm.NabiAlarms
import com.cbnu.nabi.pet.PetService
import java.time.LocalTime

/**
 * Right / 마이페이지 (FR-07), following the Figma screens:
 * main (account card, 수면 목표, 초기 ISI, 나비 및 앱 설정) → 내 정보 (account, logout)
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

    // The view the tutorial points at (rebuilt with the page, so always read it fresh).
    var goalCard: View? = null
        private set

    /** Tutorial: make sure the main page (수면 목표, 도움말) is the one showing. */
    fun showMainForTutorial() { if (page != Page.MAIN) show(Page.MAIN) }

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
            contentDescription = tr(R.string.back_to_my_page)
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
        col.add(activity.label(tr(R.string.my_page), 26f, title = true))
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
            texts.addView(activity.label(email ?: tr(R.string.not_logged_in), 15f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            texts.addView(activity.label(if (email != null) tr(R.string.view_my_info) else tr(R.string.no_linked_email), 11f, Palette.muted)
                .apply { setPadding(0, activity.dp(4), 0, 0) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(12) })
            addView(activity.icon(R.drawable.ic_chevron_right, Palette.muted, 20))
            contentDescription = tr(R.string.my_info)
            setOnClickListener { show(Page.ACCOUNT) }
        }
        col.add(account)

        // 나의 수면 목표 (FR-07-02)
        val s = store
        val goal = card()
        goal.addView(heading(R.drawable.ic_target, tr(R.string.my_sleep_goals), pill(R.drawable.ic_edit, tr(R.string.edit_goals)) { openGoalModal() }))
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, activity.dp(10), 0, 0) }
        grid.addView(goalBox(R.drawable.ic_moon, Palette.lavender, tr(R.string.target_bedtime), s.goalBedtime),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        grid.addView(goalBox(R.drawable.ic_sun, Color.parseColor("#E8A33D"), tr(R.string.target_wake_time), s.goalWakeTime),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(7) })
        goal.addView(grid)
        // 추천 수면 일정 (patent [0042]) → centred modal; the goal edit above is unchanged.
        goal.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(12), activity.dp(11), activity.dp(10), activity.dp(11))
            background = activity.cardBg(Color.parseColor("#FFFCF9"), 14, Color.parseColor("#EADDD3"))
            addView(activity.label(tr(R.string.recommended_sleep_schedule), 13f, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(2) })
            addView(chevron())
            contentDescription = tr(R.string.view_recommended_sleep_schedule)
            setOnClickListener { RecommendedScheduleModal(activity, onApplied = { show(Page.MAIN) }).show() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(9) })
        col.add(goal)
        goalCard = goal

        // 초기 ISI 평가 결과 (FR-07-01)
        val isi = card()
        val info = FrameLayout(activity).apply {
            addView(activity.icon(R.drawable.ic_info, Palette.lavender, 20), FrameLayout.LayoutParams(activity.dp(20), activity.dp(20), Gravity.CENTER))
            contentDescription = tr(R.string.about_isi)
            setOnClickListener {
                Modals.info(activity, R.drawable.ic_info, tr(R.string.about_isi),
                    tr(R.string.isi_measures_how_severe_insomnia_symptoms_2))
            }
            layoutParams = LinearLayout.LayoutParams(activity.dp(32), activity.dp(32))
        }
        isi.addView(heading(R.drawable.ic_calendar, tr(R.string.initial_isi_result), info))
        val score = s.isiScore
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(13), activity.dp(13), activity.dp(13), activity.dp(13))
            background = activity.rounded(Color.parseColor("#F8F1F7"), 16)
        }
        if (score == null) {
            box.addView(activity.label(tr(R.string.no_result), 14f, bold = true))
            box.addView(activity.label(tr(R.string.isi_score_from_sign_up_isn), 11f, Palette.muted).apply { setPadding(0, activity.dp(5), 0, 0) })
        } else {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(activity.label(tr(R.string.pts_2, score), 24f, Palette.lavenderDeep, title = true))
            row.addView(activity.label(tr(R.string.n_28), 11f, Palette.muted))
            box.addView(row)
            box.addView(activity.label(interpretIsi(score), 13f, bold = true).apply { setPadding(0, activity.dp(6), 0, 0) })
            box.addView(activity.label(tr(R.string.your_insomnia_symptom_level_from_initial), 11f, Palette.muted).apply { setPadding(0, activity.dp(4), 0, 0) })
        }
        isi.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(9) })
        col.add(isi)

        // 나비 및 앱 설정 (FR-07-04, FR-05-08)
        val settings = card()
        settings.addView(heading(R.drawable.ic_settings, tr(R.string.nabi_app_settings)))
        val canOverlay = Settings.canDrawOverlays(activity)
        val overlayToggle = ToggleSwitch(activity, PetService.isRunning) { on -> setOverlay(on) }
        val hapticToggle = ToggleSwitch(activity, activity.prefs.haptics) { on -> activity.prefs.haptics = on; true }
        settings.addView(list(
            settingRow(R.drawable.ic_cat_silhouette, tr(R.string.keep_nabi_on_screen),
                if (canOverlay) tr(R.string.show_nabi_on_top_of_other) else tr(R.string.system_permission_is_needed), overlayToggle),
            settingRow(R.drawable.ic_heart, tr(R.string.vibrate_when_touched), tr(R.string.feel_purring_and_hissing_as_vibration), hapticToggle),
            settingRow(R.drawable.ic_shield, tr(R.string.data_access), tr(R.string.check_health_activity_and_usage_permissions), chevron()) { openPermissions() },
            settingRow(R.drawable.ic_bell, tr(R.string.notifications), tr(R.string.set_sleep_and_activity_reminders), chevron()) { show(Page.NOTIFICATIONS) },
            settingRow(R.drawable.ic_help, tr(R.string.help), tr(R.string.watch_nabi_tutorial_again), chevron()) { activity.startTutorial() },
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
        texts.addView(activity.label(value?.let { Modals.format12(LocalTime.parse(it)) } ?: tr(R.string.not_set), 14f).apply { setPadding(0, activity.dp(6), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
    }

    /** Standard ISI bands (same as the Figma design). Shown with the "not a diagnosis" note. */
    private fun interpretIsi(score: Int) = IsiSurvey.band(score)

    /** Overlay switch: existing PetService start/stop. Without the permission, explain and offer the settings. */
    private fun setOverlay(on: Boolean): Boolean {
        val intent = Intent(activity, PetService::class.java)
        if (on) {
            if (!Settings.canDrawOverlays(activity)) {
                Modals.info(activity, R.drawable.ic_cat_silhouette, tr(R.string.display_over_apps_permission_needed),
                    tr(R.string.to_show_nabi_on_top_of),
                    tr(R.string.open_settings) to {
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

    private fun openPermissions() = DataAccessDialog.show(activity)

    // ---------------- 수면 목표 수정 modal (FR-07-02/03) ----------------
    private fun openGoalModal() {
        val s = store
        var bed: LocalTime? = s.goalBedtime?.let(LocalTime::parse)
        var wake: LocalTime? = s.goalWakeTime?.let(LocalTime::parse)
        val dialog = Dialog(activity)
        val box = Modals.box(activity)
        box.addView(activity.icon(R.drawable.ic_target, Palette.lavender, 25))
        box.addView(activity.label(tr(R.string.edit_sleep_goals), 19f, title = true).apply { setPadding(0, activity.dp(8), 0, 0) })
        box.addView(activity.label(tr(R.string.your_target_times_kept_separately_from), 11.5f, Palette.muted).apply { setPadding(0, activity.dp(5), 0, 0) })

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
            bedValue.text = bed?.let(Modals::format12) ?: tr(R.string.pick_time)
            wakeValue.text = wake?.let(Modals::format12) ?: tr(R.string.pick_time)
        }
        val behind = listOf(dialog.window?.decorView)
        val (bedRow, bv) = field(R.drawable.ic_moon, Palette.lavender, tr(R.string.target_bedtime)) {
            Modals.timePicker(activity, tr(R.string.target_bedtime), bed ?: LocalTime.of(23, 0), behind) { bed = it; refresh() }
        }
        val (wakeRow, wv) = field(R.drawable.ic_sun, Color.parseColor("#E8A33D"), tr(R.string.target_wake_time)) {
            Modals.timePicker(activity, tr(R.string.target_wake_time), wake ?: LocalTime.of(7, 0), behind) { wake = it; refresh() }
        }
        bedValue = bv; wakeValue = wv
        box.addView(bedRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(14) })
        box.addView(wakeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        val error = activity.label("", 11f, Color.parseColor("#B45656"), bold = true).apply { visibility = View.GONE; setPadding(0, activity.dp(10), 0, 0) }
        box.addView(error)
        refresh()

        lateinit var saveBtn: TextView
        val buttons = Modals.buttons(activity, tr(R.string.cancel), { dialog.dismiss() }, tr(R.string.save_goals)) {
            val b = bed; val w = wake
            if (b == null || w == null) {
                error.text = tr(R.string.set_both_your_target_bedtime_and); error.visibility = View.VISIBLE
                return@buttons
            }
            error.visibility = View.GONE
            saveBtn.text = tr(R.string.saving); saveBtn.isEnabled = false
            val ok = store.saveGoals("%02d:%02d".format(b.hour, b.minute), "%02d:%02d".format(w.hour, w.minute))
            if (ok) {
                saveBtn.text = tr(R.string.saved)
                saveBtn.postDelayed({ dialog.dismiss(); show(Page.MAIN) }, 600)
            } else {
                saveBtn.text = tr(R.string.save_goals); saveBtn.isEnabled = true
                error.text = tr(R.string.couldn_t_save_your_sleep_goals); error.visibility = View.VISIBLE
            }
        }
        saveBtn = (buttons as LinearLayout).getChildAt(1) as TextView
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(16) })
        Modals.show(activity, dialog, box)
    }

    // ---------------- 내 정보 ----------------
    private fun buildAccount(col: Column) {
        subHeader(col, tr(R.string.my_info))
        val email = activity.prefs.email
        val hero = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, activity.dp(14), 0, activity.dp(6))
            addView(avatar(76, 37))
            addView(activity.label(email ?: tr(R.string.not_logged_in), 18f, title = true).apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(10), 0, 0) })
            addView(activity.label(if (email != null) tr(R.string.logged_in_account) else tr(R.string.no_linked_email), 11f, Palette.muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, activity.dp(4), 0, 0) })
        }
        col.add(hero)

        val info = card()
        info.addView(activity.label(tr(R.string.account), 17f, title = true))
        info.addView(infoLine(tr(R.string.email), email ?: tr(R.string.not_registered)))
        info.addView(infoLine(tr(R.string.name_or_nickname), tr(R.string.not_registered)))
        info.addView(activity.label(tr(R.string.editing_account_info_is_coming_soon), 10.5f, Palette.muted).apply { setPadding(0, activity.dp(10), 0, 0) })
        col.add(info)

        // Debug builds only: open "나비의 제안" with example data to check the UI. Kept apart from
        // real data (marked as previews, not saved, never shown automatically).
        if (com.cbnu.nabi.BuildConfig.DEBUG) {
            val preview = card()
            preview.addView(activity.label(tr(R.string.mission_suggestion_preview_dev), 15f, title = true))
            preview.addView(activity.label(tr(R.string.examples_for_checking_ui_not_real), 10.5f, Palette.muted).apply { setPadding(0, activity.dp(4), 0, activity.dp(4)) })
            MissionProposalStore.previewSamples().forEach { p ->
                preview.addView(activity.label("• ${p.title}", 12.5f, Palette.lavenderDeep, bold = true).apply {
                    setPadding(0, activity.dp(9), 0, activity.dp(9))
                    setOnClickListener { activity.showMissionProposal(p) }
                })
            }
            preview.addView(activity.label(tr(R.string.recommended_sleep_schedule_sample_data), 12.5f, Palette.lavenderDeep, bold = true).apply {
                setPadding(0, activity.dp(9), 0, activity.dp(9))
                setOnClickListener {
                    RecommendedScheduleModal(activity, onApplied = {}, previewSchedule = SleepScheduleRecommendations.previewSample).show()
                }
            })
            col.add(preview)
        }

        col.add(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = activity.dp(46)
            background = activity.cardBg(Color.parseColor("#FFF9F6"), 16, Color.parseColor("#E4C5C0"))
            addView(activity.icon(R.drawable.ic_logout, Color.parseColor("#A15E5B"), 18))
            addView(activity.label(tr(R.string.log_out), 13f, Color.parseColor("#A15E5B"), bold = true),
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
        subHeader(col, tr(R.string.notifications), tr(R.string.set_sleep_and_activity_reminders_nabi))
        val s = store
        // A fixed note, and in the same spot a small pill that pops up after each toggle and fades
        // away (like the "나비를 터치해 보세요" hint on home).
        val note = activity.label(tr(R.string.settings_are_saved_on_this_device), 11f, Palette.muted).apply { gravity = Gravity.CENTER }
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

        fun permissionCard(iconRes: Int, title: String, body: String, open: () -> Unit) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(11), activity.dp(11), activity.dp(11), activity.dp(11))
            background = activity.cardBg(Color.parseColor("#F2EAF7"), 17, Color.parseColor("#D9C9E6"))
            addView(settingIcon(iconRes))
            val t = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            t.addView(activity.label(title, 12.5f, bold = true))
            t.addView(activity.label(body, 10.5f, Palette.muted).apply { setPadding(0, activity.dp(3), 0, 0) })
            addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(9) })
            addView(chevron())
            setOnClickListener { open() }
        }
        val granted = Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            col.add(permissionCard(R.drawable.ic_bell, tr(R.string.system_notification_permission_needed), tr(R.string.check_permission_in_app_settings)) {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            })
        }
        // Android 12+: without "알람 및 리마인더" the alarms still ring, just possibly a few minutes late.
        if (!NabiAlarms.canScheduleExact(activity) && Build.VERSION.SDK_INT >= 31) {
            col.add(permissionCard(R.drawable.ic_clock, tr(R.string.exact_alarm_permission_needed), tr(R.string.allow_it_so_wake_up_alarm)) {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
                }
            })
        }

        val saved: (Boolean) -> Unit = { ok ->
            toast.text = if (ok) tr(R.string.settings_saved) else tr(R.string.couldn_t_save_settings)
            toast.animate().cancel(); note.animate().cancel()
            note.animate().alpha(0f).setDuration(150).start()
            toast.animate().alpha(1f).setStartDelay(0).setDuration(180).withEndAction {
                toast.animate().alpha(0f).setStartDelay(1400).setDuration(300).withEndAction {
                    note.animate().alpha(1f).setDuration(200).start()
                }.start()
            }.start()
        }
        val rows = listOf(
            Triple(ProfileStore.N_SLEEP_PREP, tr(R.string.bedtime_prep) to tr(R.string.reminds_you_to_get_ready_around), R.drawable.ic_moon),
            Triple(ProfileStore.N_DIARY, tr(R.string.sleep_diary_reminder) to tr(R.string.reminds_you_if_you_haven_t), R.drawable.ic_book),
            Triple(ProfileStore.N_MISSION, tr(R.string.activity_mission_reminders) to tr(R.string.in_afternoon_reminds_you_of_remaining), R.drawable.ic_target),
            Triple(ProfileStore.N_WAKE_ALARM, tr(R.string.wake_up_alarm) to tr(R.string.nabi_wakes_you_with_sound_and), R.drawable.ic_sun),
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
        masterCard.addView(settingRow(null, tr(R.string.receive_notifications), tr(R.string.turn_all_nabi_notifications_on_or), master).apply { setPadding(0, 0, 0, 0) })
        col.add(masterCard)

        subCard.addView(activity.label(tr(R.string.sleep_activity_reminders_2), 16f, title = true).apply { setPadding(activity.dp(4), activity.dp(6), 0, 0) })
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
        contentDescription = tr(R.string.switch_text)
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
