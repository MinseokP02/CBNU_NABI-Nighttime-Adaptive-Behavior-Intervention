package com.cbnu.nabi.ui

import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.cbnu.nabi.R
import com.cbnu.nabi.data.HealthConnectSource
import com.cbnu.nabi.data.ScreenUsageSource

/**
 * 마이페이지 › 데이터 접근 권한: connect Health Connect (sleep + exercise) and 사용 정보 접근 (screen time
 * before sleep). The statuses are re-read whenever a permission screen returns.
 */
object DataAccessDialog {

    private class Row(val root: LinearLayout, val status: TextView, val action: TextView)

    fun show(activity: MainActivity) {
        val dialog = Dialog(activity)
        val box = Modals.box(activity)
        box.addView(activity.icon(R.drawable.ic_shield, Palette.lavender, 25))
        box.addView(activity.label("데이터 접근 권한", 19f, title = true).apply { setPadding(0, activity.dp(8), 0, 0) })
        box.addView(activity.label("연결하면 수면 일지를 자동으로 채우고, 취침 전 화면 사용 시간을 분석에 함께 보내요.", 11.5f, Palette.muted)
            .apply { setPadding(0, activity.dp(6), 0, activity.dp(12)); setLineSpacing(activity.dp(2).toFloat(), 1f) })

        val health = row(activity, R.drawable.ic_heart, "Health Connect", "수면 기록 · 운동 기록 읽기")
        val usage = row(activity, R.drawable.ic_clock, "사용 정보 접근", "잠들기 전 2시간 화면 사용 시간")
        box.addView(health.root)
        box.addView(usage.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = activity.dp(8) })

        fun refresh() {
            activity.bg({
                Triple(HealthConnectSource.status(activity), HealthConnectSource.grantedPermissions(activity), ScreenUsageSource.hasAccess(activity))
            }) { r ->
                val (hcStatus, granted, usageOk) = r.getOrNull() ?: return@bg
                val hcAll = granted.containsAll(HealthConnectSource.PERMISSIONS)
                when {
                    hcStatus == HealthConnectSource.Status.UNAVAILABLE -> set(health, "이 기기에서 지원하지 않아요", null) {}
                    hcStatus == HealthConnectSource.Status.NEEDS_UPDATE -> set(health, "Health Connect 설치·업데이트가 필요해요", "설치") {
                        runCatching { activity.startActivity(HealthConnectSource.installIntent()) }
                    }
                    hcAll -> set(health, "연결됨", "관리") { activity.requestHealthPermissions() }
                    granted.isNotEmpty() -> set(health, "일부만 허용됨", "연결") { activity.requestHealthPermissions() }
                    else -> set(health, "연결 안 됨", "연결") { activity.requestHealthPermissions() }
                }
                if (usageOk) set(usage, "허용됨", "관리") { openUsage(activity) }
                else set(usage, "허용 안 됨", "허용") { openUsage(activity) }
            }
        }
        refresh()
        activity.onDataAccessChanged = { if (dialog.isShowing) refresh() }

        box.addView(Modals.buttons(activity, null, {}, "닫기") { dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(16) })
        Modals.show(activity, dialog, box)
    }

    private fun openUsage(activity: MainActivity) {
        runCatching { activity.startActivity(ScreenUsageSource.settingsIntent(activity)) }.onFailure {
            // Some builds reject the package URI: fall back to the plain list.
            runCatching { activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { Toast.makeText(activity, "사용 정보 접근 설정을 열지 못했어요", Toast.LENGTH_SHORT).show() }
        }
        Toast.makeText(activity, "'나비'의 사용 정보 접근을 켜 주세요", Toast.LENGTH_LONG).show()
    }

    private fun row(activity: MainActivity, iconRes: Int, title: String, sub: String): Row {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(11), activity.dp(10), activity.dp(10), activity.dp(10))
            background = activity.cardBg(Color.parseColor("#FFFCF9"), 16, Color.parseColor("#EADDD3"))
        }
        root.addView(activity.icon(iconRes, Palette.lavenderDeep, 20))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(activity.label(title, 12.5f, bold = true))
        texts.addView(activity.label(sub, 10f, Palette.muted).apply { setPadding(0, activity.dp(3), 0, 0) })
        val status = activity.label("확인 중…", 10f, Palette.lavenderDeep, bold = true).apply { setPadding(0, activity.dp(4), 0, 0) }
        texts.addView(status)
        root.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(9) })
        val action = Modals.button(activity, "", true) {}.apply { textSize = 12f; visibility = android.view.View.GONE }
        root.addView(action, LinearLayout.LayoutParams(activity.dp(56), activity.dp(34)))
        return Row(root, status, action)
    }

    private fun set(row: Row, status: String, action: String?, onClick: () -> Unit) {
        row.status.text = status
        row.status.setTextColor(if (status == "연결됨" || status == "허용됨") Color.parseColor("#4E9A6B") else Palette.lavenderDeep)
        row.action.visibility = if (action == null) android.view.View.GONE else android.view.View.VISIBLE
        row.action.text = action ?: ""
        row.action.setOnClickListener { onClick() }
    }
}
