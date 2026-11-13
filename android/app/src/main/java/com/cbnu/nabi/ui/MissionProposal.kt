package com.cbnu.nabi.ui

import android.content.Context
import android.content.Intent
import com.cbnu.nabi.R
import com.cbnu.nabi.data.Recommendation
import org.json.JSONArray
import org.json.JSONObject

/*
 * "나비의 제안" — a behaviour intervention offered to the user (patent [0043], [0045]).
 *
 * The app does NOT decide when to propose anything (no step counting, risk calculation, timers or
 * rate limits here — that is the intervention/server owner's part). Whoever decides calls
 * [MissionProposals.open] with a [MissionProposal]; the modal shows it and the user's response is
 * recorded for that owner to pick up ([MissionProposalStore.responses]).
 */

/** How a proposal behaves after "수락하기". */
enum class ProposalFlow {
    /** Becomes "진행 중" in 나의 미션, then "완료" with 완료했어요 (e.g. 산책, 스트레칭). */
    TRACKABLE,
    /** A heads-up only (화면 밝기 감소 예고): accepting just acknowledges it, nothing to complete. */
    NOTICE,
    /** Opens the existing sleep record sheet (수면일지 작성 요청, [0036]). */
    SLEEP_DIARY,
}

/** The intervention set from patent [0043]. Title/description below are defaults; data may override them. */
enum class ProposalKind(val flow: ProposalFlow, val iconRes: Int, val defaultTitle: String, val defaultDescription: String) {
    WALK(ProposalFlow.TRACKABLE, R.drawable.ic_paw, "가볍게 산책하기",
        "한동안 움직임이 적었어요.\n잠깐 걸으며 몸을 깨워 볼까요?"),
    STRETCH(ProposalFlow.TRACKABLE, R.drawable.ic_sparkle, "가벼운 스트레칭",
        "오래 앉아 있었어요.\n잠시 일어나 몸을 쭉 펴 볼까요?"),
    DIM_LIGHTS(ProposalFlow.TRACKABLE, R.drawable.ic_moon, "조명 낮추기",
        "잠들 준비를 할 시간이에요.\n방의 조명을 조금 어둡게 해 볼까요?"),
    BRIGHTNESS_NOTICE(ProposalFlow.NOTICE, R.drawable.ic_bell, "화면 밝기 낮추기 안내",
        "곧 잠들 시간이에요.\n화면이 조금씩 어두워질 거예요."),
    PUT_DOWN_PHONE(ProposalFlow.TRACKABLE, R.drawable.ic_bed, "휴대폰 내려놓기",
        "잘 시간이 지났어요.\n휴대폰을 내려놓고 눈을 감아 볼까요?"),
    SUNLIGHT(ProposalFlow.TRACKABLE, R.drawable.ic_sun, "햇빛 쬐기",
        "좋은 아침이에요.\n창가에서 잠깐 햇빛을 쬐며 하루를 시작해 볼까요?"),
    SLEEP_DIARY(ProposalFlow.SLEEP_DIARY, R.drawable.ic_book, "수면일지 작성하기",
        "어젯밤 잠은 어땠나요?\n잊기 전에 수면일지를 남겨 주세요."),
}

/**
 * How an accepted mission is confirmed. Comes with the mission data (decided by the team), never
 * from the mission's name. Not provided → MANUAL, i.e. the existing 완료했어요 button.
 */
enum class VerifyMode {
    /** Confirmed from collected data (steps, phone usage, saved diary) — no 완료했어요 button. */
    AUTO,
    /** The user confirms with 완료했어요. */
    MANUAL,
}

/** Where an AUTO mission's check stands. Only the data owner moves it beyond NOT_CONNECTED. */
enum class AutoCheck {
    /** No data source is connected yet — the app must not say it is checking. */
    NOT_CONNECTED,
    /** Data is connected and the check is running. */
    CHECKING,
    /** Permission missing or the data couldn't be read — never auto-completed. */
    UNAVAILABLE,
}

/** One proposal. [id] identifies it for the response record (server id when it comes from the API). */
data class MissionProposal(
    val id: String,
    val kind: ProposalKind,
    val title: String = kind.defaultTitle,
    val description: String = kind.defaultDescription,
    val verify: VerifyMode = VerifyMode.MANUAL,
    /** UI preview data (debug only) — never stored with the user's real missions. */
    val preview: Boolean = false,
    /**
     * Set when the proposal comes from GET /interventions/recommendations with a mission: that
     * mission is tracked by the server (GET /missions/active, POST /missions/{id}/complete), so
     * accepting it does not add a local copy to 나의 미션.
     */
    val serverMissionId: Int? = null,
)

/** Patent [0030]: 수락 / 나중에 / 무시. Closing the modal any other way records nothing. */
enum class ProposalResponse { ACCEPTED, LATER, DISMISSED }

/** Entry point for the cat / intervention / API owners. */
object MissionProposals {
    private const val EXTRA_ID = "proposal_id"
    private const val EXTRA_KIND = "proposal_kind"
    private const val EXTRA_TITLE = "proposal_title"
    private const val EXTRA_DESC = "proposal_desc"
    private const val EXTRA_VERIFY = "proposal_verify"
    private const val EXTRA_MISSION = "proposal_server_mission"

    /**
     * Show "나비의 제안" for [p]. Works from anywhere (e.g. the overlay cat's service after the user
     * taps a cat that has a pending proposal): brings the app forward and opens the modal.
     */
    fun open(context: Context, p: MissionProposal) {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ID, p.id).putExtra(EXTRA_KIND, p.kind.name)
            .putExtra(EXTRA_TITLE, p.title).putExtra(EXTRA_DESC, p.description)
            .putExtra(EXTRA_VERIFY, p.verify.name)
            .apply { p.serverMissionId?.let { putExtra(EXTRA_MISSION, it) } })
    }

    /**
     * For the data owner (steps / phone usage / diary): report how an AUTO mission's check is going.
     * [progress] is optional real progress text (e.g. "1,240보 / 목표 3,000보"); leave it null
     * when there is no real number. Refreshes 나의 미션 if the app is open.
     */
    fun reportAutoCheck(context: Context, email: String?, id: String, state: AutoCheck, progress: String? = null) {
        MissionProposalStore(context, email).setAutoCheck(id, state, progress)
        notifyChanged()
    }

    /** For the data owner: the AUTO mission's goal was confirmed from data → 완료. */
    fun reportAutoCompleted(context: Context, email: String?, id: String) {
        MissionProposalStore(context, email).completeById(id)
        notifyChanged()
    }

    private fun notifyChanged() {
        MainActivity.current?.get()?.let { a -> a.runOnUiThread { a.onMissionsChanged() } }
    }

    /** Reads a proposal put on an intent by [open]; null if there is none. */
    fun from(intent: Intent?): MissionProposal? {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return null
        val kind = runCatching { ProposalKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }.getOrNull() ?: return null
        val verify = runCatching { VerifyMode.valueOf(intent.getStringExtra(EXTRA_VERIFY) ?: "") }.getOrDefault(VerifyMode.MANUAL)
        val missionId = intent.getIntExtra(EXTRA_MISSION, -1).takeIf { it >= 0 }
        return MissionProposal(id, kind,
            intent.getStringExtra(EXTRA_TITLE) ?: kind.defaultTitle,
            intent.getStringExtra(EXTRA_DESC) ?: kind.defaultDescription, verify, serverMissionId = missionId)
    }

    /**
     * The server's recommendation (API-12) as a proposal. The intervention code only picks the icon;
     * title and text come from the server (LLM explanation, or its fixed message as fallback).
     */
    fun fromRecommendation(rec: Recommendation): MissionProposal {
        val kind = when (rec.code) {
            "SH_DAYTIME_ACTIVITY" -> ProposalKind.WALK
            "RX_BREATHING", "SH_CAFFEINE_CUTOFF" -> ProposalKind.STRETCH
            "SS_CONSISTENT_BEDTIME", "CG_WORRY_NOTE" -> ProposalKind.DIM_LIGHTS
            "SS_FIXED_WAKE_TIME", "SH_NAP_LIMIT" -> ProposalKind.SUNLIGHT
            else -> ProposalKind.PUT_DOWN_PHONE // SC_*, SH_SCREEN_OFF
        }
        return MissionProposal(
            id = "server-${rec.interventionId}",
            kind = kind,
            title = rec.title.ifBlank { kind.defaultTitle },
            description = rec.explanation ?: rec.message.ifBlank { kind.defaultDescription },
            serverMissionId = rec.missionId,
        )
    }

    /** Consume it so a rotation / re-delivery doesn't show the same proposal twice. */
    fun clear(intent: Intent?) {
        intent?.removeExtra(EXTRA_ID)
    }
}

/**
 * A proposal the user accepted: shown in 나의 미션 as 진행 중 → 완료. For AUTO missions [autoCheck]
 * and [progress] come only from the data owner ([MissionProposals.reportAutoCheck]).
 */
data class AcceptedMission(
    val proposal: MissionProposal,
    val done: Boolean,
    val autoCheck: AutoCheck = AutoCheck.NOT_CONNECTED,
    val progress: String? = null,
)

/**
 * Local record of accepted proposals and of every response, per logged-in email. Nothing is sent
 * to the server (no API yet) — the API owner can read [responses] / [accepted] to sync later.
 * Preview proposals live in memory only, apart from the real data.
 */
class MissionProposalStore(context: Context, email: String?) {
    private val sp = context.getSharedPreferences("nabi_proposals_" + (email ?: "guest").hashCode(), Context.MODE_PRIVATE)

    fun accepted(): List<AcceptedMission> = readAccepted() + previewAccepted

    fun record(p: MissionProposal, r: ProposalResponse) {
        if (p.preview) {
            if (r == ProposalResponse.ACCEPTED && p.kind.flow == ProposalFlow.TRACKABLE) previewAccepted.add(AcceptedMission(p, false))
            return
        }
        val log = JSONArray(sp.getString(K_RESPONSES, "[]"))
        log.put(JSONObject().put("id", p.id).put("kind", p.kind.name).put("response", r.name).put("at", System.currentTimeMillis()))
        val e = sp.edit().putString(K_RESPONSES, log.toString())
        if (r == ProposalResponse.ACCEPTED && p.kind.flow == ProposalFlow.TRACKABLE && p.serverMissionId == null) {
            val list = readAccepted().filterNot { it.proposal.id == p.id } + AcceptedMission(p, false)
            e.putString(K_ACCEPTED, writeAccepted(list))
        }
        e.apply()
    }

    /** 완료했어요 on a MANUAL mission. */
    fun complete(p: MissionProposal) = completeById(p.id)

    fun completeById(id: String) = update(id) { it.copy(done = true) }

    /** Data owner's report for an AUTO mission; never marks it done by itself. */
    fun setAutoCheck(id: String, state: AutoCheck, progress: String?) =
        update(id) { it.copy(autoCheck = state, progress = progress) }

    private fun update(id: String, change: (AcceptedMission) -> AcceptedMission) {
        previewAccepted.replaceAll { if (it.proposal.id == id) change(it) else it }
        val list = readAccepted()
        if (list.none { it.proposal.id == id }) return
        sp.edit().putString(K_ACCEPTED, writeAccepted(list.map { if (it.proposal.id == id) change(it) else it })).apply()
    }

    /** Id of the server proposal already shown today, or null. */
    fun shownToday(): String? =
        sp.getString(K_SHOWN, null)?.split('|')?.takeIf { it.size == 2 && it[0] == java.time.LocalDate.now().toString() }?.get(1)

    fun markShownToday(id: String) {
        sp.edit().putString(K_SHOWN, "${java.time.LocalDate.now()}|$id").apply()
    }

    /** Every response so far: id, kind, ACCEPTED / LATER / DISMISSED, time (ms). For syncing later. */
    fun responses(): List<JSONObject> = JSONArray(sp.getString(K_RESPONSES, "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun readAccepted(): List<AcceptedMission> {
        val a = JSONArray(sp.getString(K_ACCEPTED, "[]"))
        return (0 until a.length()).mapNotNull { i ->
            val j = a.getJSONObject(i)
            val kind = runCatching { ProposalKind.valueOf(j.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            val verify = runCatching { VerifyMode.valueOf(j.optString("verify")) }.getOrDefault(VerifyMode.MANUAL)
            val check = runCatching { AutoCheck.valueOf(j.optString("autoCheck")) }.getOrDefault(AutoCheck.NOT_CONNECTED)
            AcceptedMission(
                MissionProposal(j.getString("id"), kind, j.getString("title"), j.getString("description"), verify),
                j.optBoolean("done"), check, if (j.isNull("progress")) null else j.optString("progress"),
            )
        }
    }

    private fun writeAccepted(list: List<AcceptedMission>) = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("id", it.proposal.id).put("kind", it.proposal.kind.name)
                .put("title", it.proposal.title).put("description", it.proposal.description)
                .put("verify", it.proposal.verify.name).put("done", it.done)
                .put("autoCheck", it.autoCheck.name).put("progress", it.progress ?: JSONObject.NULL))
        }
    }.toString()

    companion object {
        private const val K_ACCEPTED = "accepted"
        private const val K_RESPONSES = "responses"
        private const val K_SHOWN = "server_shown"
        private val previewAccepted = mutableListOf<AcceptedMission>()

        /**
         * Debug-only examples for checking the modal; one per kind, clearly marked as previews.
         * The verify modes here are only sample data to show both UIs — the real mode comes with
         * each mission from the team's data, not from its kind.
         */
        fun previewSamples(): List<MissionProposal> = ProposalKind.values().map {
            val verify = if (it == ProposalKind.WALK || it == ProposalKind.PUT_DOWN_PHONE) VerifyMode.AUTO else VerifyMode.MANUAL
            MissionProposal("preview-${it.name}", it, verify = verify, preview = true)
        }
    }
}
