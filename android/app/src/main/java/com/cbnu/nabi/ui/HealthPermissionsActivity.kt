package com.cbnu.nabi.ui

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
        page.add(label("나비가 읽는 건강 데이터", 20f, title = true).apply { setPadding(0, dp(12), 0, dp(10)) })
        page.add(label(TEXT, 13f, Palette.ink).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        page.root.addView(Modals.button(this, "확인", true) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(24) })
        setContentView(scrollPage(page))
    }

    companion object {
        const val TEXT =
            "• 수면(SleepSession): 수면 일지의 취침·기상 시각, 잠들기까지 걸린 시간, 밤중 각성 시간을 자동으로 채워요.\n\n" +
            "• 운동(ExerciseSession): 전날 운동한 시간을 생활 습관 항목에 채워요.\n\n" +
            "읽기만 하고 Health Connect에 쓰지 않아요. 기록을 저장할 때 위 값만 NABI 서버로 보내 개인 기준선·위험 분석에 쓰고, " +
            "원본 기록은 서버에 보내지 않아요. 권한은 Health Connect 설정에서 언제든 끌 수 있어요."
    }
}
