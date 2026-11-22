package com.cbnu.nabi.ui


import com.cbnu.nabi.tr
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.cbnu.nabi.R

/**
 * Privacy rationale that Health Connect opens from its permission screen / settings
 * (ACTION_SHOW_PERMISSIONS_RATIONALE, and VIEW_PERMISSION_USAGE on Android 14+).
 */
class HealthPermissionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = page()
        page.root.setBackgroundColor(Palette.page)
        page.root.setPadding(dp(22), dp(36), dp(22), dp(24))
        page.root.addView(icon(R.drawable.ic_shield, Palette.lavenderDeep, 32))
        page.add(label(tr(R.string.health_data_nabi_reads), 20f, title = true).apply { setPadding(0, dp(12), 0, dp(10)) })
        page.add(label(TEXT, 13f, Palette.ink).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        page.root.addView(Modals.button(this, tr(R.string.ok), true) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(24) })
        setContentView(scrollPage(page))
    }

    companion object {
        val TEXT: String get() =
            tr(R.string.sleep_sleepsession_fills_in_your_sleep) +
            tr(R.string.exercise_exercisesession_fills_in_yesterday_s) +
            tr(R.string.nabi_only_reads_and_never_writes) +
            tr(R.string.original_records_are_never_sent_you)
    }
}
