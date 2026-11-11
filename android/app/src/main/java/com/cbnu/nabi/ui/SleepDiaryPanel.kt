package com.cbnu.nabi.ui

import android.view.View

/** Left / 수면일지 tab (FR-03). Skeleton only — the input form comes in a later commit. */
class SleepDiaryPanel(activity: MainActivity) {

    val view: View

    init {
        val page = activity.page()
        page.heading("수면일지")
        page.text("수면일지 작성 화면을 준비 중이에요.", 14f, 0.7f)
        view = activity.scrollPage(page)
    }
}
