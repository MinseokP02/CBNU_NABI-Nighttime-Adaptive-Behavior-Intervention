package com.cbnu.nabi.ui

import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.cbnu.nabi.data.CatAction
import com.cbnu.nabi.data.Labels
import com.cbnu.nabi.data.Mission
import com.cbnu.nabi.data.UserState
import com.cbnu.nabi.pet.PetService

/**
 * Center / 홈 tab (FR-04~06). For now it holds the existing state card, chat button and missions
 * moved from MainActivity unchanged; analysis cards, 나비의 공간 and the shop come in later commits.
 */
class HomePanel(private val activity: MainActivity) {

    private val stateText: TextView
    private val missionList: LinearLayout

    val view: View

    init {
        val page = activity.page()
        page.heading("오늘의 나")
        stateText = page.add(TextView(activity).apply {
            textSize = 15f
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
            background = activity.rounded(Palette.card)
        })
        page.buttonRow(
            "나비와 대화" to { activity.startActivity(Intent(activity, ChatActivity::class.java)) },
            "새로고침" to ::refresh,
        )

        page.heading("오늘의 미션")
        missionList = page.add(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL })

        view = activity.scrollPage(page)
    }

    fun refresh() {
        stateText.text = "불러오는 중…"
        activity.bg({ activity.api.userState() to activity.api.activeMissions() }) { r ->
            r.onSuccess { (state, missions) ->
                renderState(state)
                renderMissions(missions)
                activity.onUserStateLoaded()
            }.onFailure {
                stateText.text = it.friendly()
                if (!activity.prefs.loggedIn) activity.showLogin() // token expired (401)
            }
        }
    }

    private fun renderState(s: UserState) {
        activity.prefs.catName = s.catName
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
            missionList.addView(TextView(activity).apply { text = "지금은 진행 중인 미션이 없어요"; alpha = 0.7f })
            return
        }
        for (m in list) {
            val card = Column(activity, 6)
            card.root.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(14))
            card.root.background = activity.rounded(Palette.card)
            card.add(TextView(activity).apply { text = m.title; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
            card.text(m.description, 14f)
            card.text("보상 EXP ${m.rewardExp} · 기한 ${m.deadline.take(16).replace('T', ' ')}", 12f, 0.7f)
            val btn = card.add(Button(activity).apply { text = "완료했어요" }, matchWidth = false)
            btn.setOnClickListener {
                btn.isEnabled = false
                activity.bg({ activity.api.completeMission(m.missionId) }) { r ->
                    btn.isEnabled = true
                    r.onSuccess { res ->
                        Toast.makeText(activity, res.message, Toast.LENGTH_LONG).show()
                        PetService.show(activity, res.action, res.message)
                        refresh()
                    }.onFailure {
                        Toast.makeText(activity, it.friendly(), Toast.LENGTH_LONG).show()
                        PetService.show(activity, CatAction.COMFORT, "괜찮아, 기록하고 다시 해보자냥")
                    }
                }
            }
            missionList.addView(card.root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(10) })
        }
    }
}
