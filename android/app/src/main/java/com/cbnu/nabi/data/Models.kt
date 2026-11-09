package com.cbnu.nabi.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Response models for the NABI backend (backend/app/schemas/*.py).
 * Parsed with the built-in org.json so the app needs no extra libraries.
 */

/** cat_action values shared by backend and LLM server (schemas/chat.py CatAction). */
enum class CatAction {
    IDLE, SIT, WALK, SLEEP, HAPPY, COMFORT;

    companion object {
        fun parse(s: String?): CatAction = entries.firstOrNull { it.name == s } ?: IDLE
    }
}

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** API-17 GET /cat/state */
data class CatState(
    val catId: Int,
    val name: String,
    val level: Int,
    val exp: Int,
    val expToNextLevel: Int,
    val action: CatAction,
    val reason: String,
) {
    companion object {
        fun from(j: JSONObject) = CatState(
            catId = j.optInt("cat_id"),
            name = j.optString("name", "나비"),
            level = j.optInt("level", 1),
            exp = j.optInt("exp"),
            expToNextLevel = j.optInt("exp_to_next_level"),
            action = CatAction.parse(j.str("cat_action")),
            reason = j.optString("reason"),
        )
    }
}

/** API-04 GET /users/me/state */
data class UserState(
    val baselineStatus: String,
    val baselineValidDays: Int,
    val sleepState: String,
    val behaviorState: String,
    val riskLevel: String,
    val riskFactors: List<String>,
    val catName: String,
    val catLevel: Int,
    val catExp: Int,
    val catAction: CatAction,
) {
    companion object {
        fun from(j: JSONObject): UserState {
            val cat = j.optJSONObject("cat") ?: JSONObject()
            val factors = j.optJSONArray("risk_factors") ?: JSONArray()
            return UserState(
                baselineStatus = j.optString("baseline_status"),
                baselineValidDays = j.optInt("baseline_valid_days"),
                sleepState = j.optString("sleep_state"),
                behaviorState = j.optString("behavior_state"),
                riskLevel = j.optString("risk_level"),
                riskFactors = (0 until factors.length()).map { factors.getString(it) },
                catName = cat.optString("name", "나비"),
                catLevel = cat.optInt("level", 1),
                catExp = cat.optInt("exp"),
                catAction = CatAction.parse(cat.str("cat_action")),
            )
        }
    }
}

/** API-13 GET /missions/active */
data class Mission(
    val assignmentId: Int,
    val missionId: Int,
    val category: String,
    val title: String,
    val description: String,
    val verificationType: String,
    val rewardExp: Int,
    val status: String,
    val deadline: String,
) {
    companion object {
        fun list(a: JSONArray) = a.objects().map {
            Mission(
                assignmentId = it.optInt("assignment_id"),
                missionId = it.optInt("mission_id"),
                category = it.optString("category"),
                title = it.optString("title"),
                description = it.optString("description"),
                verificationType = it.optString("verification_type"),
                rewardExp = it.optInt("reward_exp"),
                status = it.optString("status"),
                deadline = it.optString("deadline"),
            )
        }
    }
}

/** API-14 POST /missions/{id}/complete */
data class MissionCompletion(
    val verified: Boolean,
    val rewardExp: Int,
    val catLevel: Int,
    val catExp: Int,
    val idempotent: Boolean,
    val message: String,
    val action: CatAction,
) {
    companion object {
        fun from(j: JSONObject) = MissionCompletion(
            verified = j.optBoolean("verified"),
            rewardExp = j.optInt("reward_exp"),
            catLevel = j.optInt("cat_level"),
            catExp = j.optInt("cat_exp"),
            idempotent = j.optBoolean("idempotent"),
            message = j.optString("message"),
            action = CatAction.parse(j.str("cat_action")),
        )
    }
}

/** API-15 POST /chat/messages */
data class ChatReply(
    val messageId: String,
    val conversationId: String,
    val reply: String,
    val emotion: String,
    val action: CatAction,
    val safetyStatus: String,
    val source: String,
) {
    companion object {
        fun from(j: JSONObject) = ChatReply(
            messageId = j.optString("message_id"),
            conversationId = j.optString("conversation_id"),
            reply = j.optString("reply"),
            emotion = j.optString("emotion"),
            action = CatAction.parse(j.str("cat_action")),
            safetyStatus = j.optString("safety_status"),
            source = j.optString("source"),
        )
    }
}

/** API-16 GET /chat/history item */
data class ChatMessage(
    val role: String,
    val content: String,
    val safetyStatus: String?,
) {
    val fromUser: Boolean get() = role == "user"

    companion object {
        fun list(j: JSONObject): List<ChatMessage> =
            (j.optJSONArray("messages") ?: JSONArray()).objects().map {
                ChatMessage(it.optString("role"), it.optString("content"), it.str("safety_status"))
            }
    }
}

/** API-19 GET /health */
data class Health(val status: String, val database: String, val llm: String) {
    companion object {
        fun from(j: JSONObject) = Health(j.optString("status"), j.optString("database"), j.optString("llm"))
    }
}

/** Friendly Korean labels for state enums shown on the home screen. */
object Labels {
    fun risk(s: String) = when (s) {
        "HIGH" -> "높음"; "MODERATE" -> "보통"; "LOW" -> "낮음"; else -> "판단 전"
    }

    fun sleep(s: String) = when (s) {
        "DETERIORATING" -> "나빠지는 중"; "IMPROVING" -> "좋아지는 중"; "STABLE" -> "안정"; else -> "기록 수집 중"
    }

    fun behavior(s: String) = when (s) {
        "AT_RISK" -> "주의 필요"; "CAUTION" -> "조금 주의"; "HEALTHY" -> "좋음"; else -> "기록 수집 중"
    }

    fun factor(s: String) = when (s) {
        "PROLONGED_SLEEP_ONSET" -> "잠들기까지 오래 걸림"
        "FREQUENT_NIGHT_AWAKENING" -> "밤중에 자주 깸"
        "LOW_SLEEP_EFFICIENCY" -> "수면 효율 낮음"
        "SHORT_SLEEP_DURATION" -> "수면 시간 짧음"
        "IRREGULAR_BEDTIME" -> "취침 시각 불규칙"
        "IRREGULAR_WAKE_TIME" -> "기상 시각 불규칙"
        "POOR_SLEEP_QUALITY" -> "수면의 질 낮음"
        "LATE_CAFFEINE" -> "늦은 카페인"
        "EXCESSIVE_PRE_BED_SCREEN" -> "자기 전 화면 사용 많음"
        "LONG_DAYTIME_NAP" -> "긴 낮잠"
        "HIGH_STRESS" -> "스트레스 높음"
        "LOW_PHYSICAL_ACTIVITY" -> "활동량 적음"
        else -> s
    }
}
