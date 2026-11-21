package com.cbnu.nabi.data


import com.cbnu.nabi.R
import com.cbnu.nabi.tr
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * Response models for the NABI backend (the .py files in backend/app/schemas).
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
            name = j.optString("name", tr(R.string.nabi)),
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
                catName = cat.optString("name", tr(R.string.nabi)),
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

/** API-06 GET /sleep-records item. Times keep the offset the app sent. */
data class SleepRecordItem(
    val sleepDate: LocalDate,
    val bedtime: OffsetDateTime,
    val finalAwakening: OffsetDateTime,
    val sleepOnsetLatencyMin: Int,
    val wakeAfterSleepOnsetMin: Int,
    val totalSleepMin: Int,
    val sleepEfficiency: Double,
    val sleepQuality: Int?,
) {
    companion object {
        fun list(j: JSONObject): List<SleepRecordItem> =
            (j.optJSONArray("items") ?: JSONArray()).objects().map {
                SleepRecordItem(
                    sleepDate = LocalDate.parse(it.getString("sleep_date")),
                    bedtime = OffsetDateTime.parse(it.getString("bedtime")),
                    finalAwakening = OffsetDateTime.parse(it.getString("final_awakening")),
                    sleepOnsetLatencyMin = it.optInt("sleep_onset_latency_min"),
                    wakeAfterSleepOnsetMin = it.optInt("wake_after_sleep_onset_min"),
                    totalSleepMin = it.optInt("total_sleep_time_min"),
                    sleepEfficiency = it.optDouble("sleep_efficiency"),
                    sleepQuality = if (it.isNull("sleep_quality")) null else it.optInt("sleep_quality"),
                )
            }
    }
}

/** API-08 GET /behavior-records item. null = not recorded. */
data class BehaviorRecordItem(
    val recordDate: LocalDate,
    val caffeineAfterCutoff: Boolean?,
    val napDurationMin: Int?,
    val exerciseDurationMin: Int?,
) {
    companion object {
        fun list(j: JSONObject): List<BehaviorRecordItem> =
            (j.optJSONArray("items") ?: JSONArray()).objects().map {
                BehaviorRecordItem(
                    recordDate = LocalDate.parse(it.getString("record_date")),
                    caffeineAfterCutoff = if (it.isNull("caffeine_after_cutoff")) null else it.optBoolean("caffeine_after_cutoff"),
                    napDurationMin = if (it.isNull("nap_duration_min")) null else it.optInt("nap_duration_min"),
                    exerciseDurationMin = if (it.isNull("exercise_duration_min")) null else it.optInt("exercise_duration_min"),
                )
            }
    }
}

/** One metric of API-10 (`metrics.<name>`). bedtime / wake_time are minutes from the anchor clock. */
data class MetricScore(val recentValue: Double, val baselineMedian: Double?)

/** API-10 GET /analysis/risk */
data class RiskAnalysis(
    val baselineStatus: String,
    val riskLevel: String,
    val riskFactors: List<String>,
    val metrics: Map<String, MetricScore>,
    /** Metric names the server flagged in factor_details (e.g. "sleep_efficiency"). */
    val flaggedMetrics: Set<String>,
) {
    companion object {
        fun from(j: JSONObject): RiskAnalysis {
            val m = j.optJSONObject("metrics") ?: JSONObject()
            val metrics = m.keys().asSequence().associateWith { k ->
                val o = m.getJSONObject(k)
                MetricScore(o.optDouble("recent_value"), if (o.isNull("baseline_median") || !o.has("baseline_median")) null else o.optDouble("baseline_median"))
            }
            val factors = j.optJSONArray("risk_factors") ?: JSONArray()
            return RiskAnalysis(
                baselineStatus = j.optString("baseline_status"),
                riskLevel = j.optString("risk_level"),
                riskFactors = (0 until factors.length()).map { factors.getString(it) },
                metrics = metrics,
                flaggedMetrics = (j.optJSONArray("factor_details") ?: JSONArray()).objects().map { it.optString("metric") }.toSet(),
            )
        }
    }
}

/** API-12 GET /interventions/recommendations */
data class Recommendation(
    val interventionId: Int?,
    val code: String,
    val category: String,
    val title: String,
    val message: String,
    val missionId: Int?,
    /** LLM explanation when asked for (falls back to [message] on the server). */
    val explanation: String?,
) {
    companion object {
        fun from(j: JSONObject) = Recommendation(
            interventionId = if (j.isNull("intervention_id")) null else j.optInt("intervention_id"),
            code = j.optString("code"),
            category = j.optString("category"),
            title = j.optString("title"),
            message = j.optString("message"),
            missionId = if (j.isNull("mission_id")) null else j.optInt("mission_id"),
            explanation = j.str("explanation")?.ifBlank { null },
        )
    }
}

/** GET /analysis/trends — one value per night/day plus the server's usual range. */
data class Trends(
    val days: Int,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val baselineReady: Boolean,
    val metrics: List<TrendMetric>,
) {
    companion object {
        private fun date(j: JSONObject, k: String) = j.str(k)?.let(LocalDate::parse)
        fun from(j: JSONObject) = Trends(
            days = j.optInt("days"),
            startDate = date(j, "start_date"),
            endDate = date(j, "end_date"),
            baselineReady = j.optString("baseline_status") == "ready",
            metrics = (j.optJSONArray("metrics") ?: JSONArray()).objects().map { m ->
                val band = m.optJSONObject("band")
                val latest = m.optJSONObject("latest")
                TrendMetric(
                    metric = m.optString("metric"),
                    domain = m.optString("domain"),
                    unit = m.optString("unit"),
                    anchor = m.str("anchor"),
                    points = (m.optJSONArray("points") ?: JSONArray()).objects()
                        .map { TrendPoint(LocalDate.parse(it.getString("date")), it.getDouble("value")) },
                    band = band?.let { TrendBand(it.getDouble("median"), it.getDouble("low"), it.getDouble("high")) },
                    reference = if (m.isNull("reference") || !m.has("reference")) null else m.getDouble("reference"),
                    latestStatus = latest?.optString("status"),
                )
            },
        )
    }
}

data class TrendPoint(val date: LocalDate, val value: Double)
data class TrendBand(val median: Double, val low: Double, val high: Double)

/**
 * [unit]: clock (minutes from [anchor] "HH:MM"), minutes, ratio (0..1).
 * [latestStatus]: normal / changed / improved / unknown, judged by the server.
 */
data class TrendMetric(
    val metric: String,
    val domain: String,
    val unit: String,
    val anchor: String?,
    val points: List<TrendPoint>,
    val band: TrendBand?,
    val reference: Double?,
    val latestStatus: String?,
)

/** API-19 GET /health */
data class Health(val status: String, val database: String, val llm: String) {
    companion object {
        fun from(j: JSONObject) = Health(j.optString("status"), j.optString("database"), j.optString("llm"))
    }
}

/** Friendly Korean labels for state enums shown on the home screen. */
object Labels {
    fun risk(s: String) = when (s) {
        "HIGH" -> tr(R.string.high); "MODERATE" -> tr(R.string.moderate); "LOW" -> tr(R.string.low); else -> tr(R.string.not_assessed)
    }

    fun sleep(s: String) = when (s) {
        "DETERIORATING" -> tr(R.string.getting_worse); "IMPROVING" -> tr(R.string.improving); "STABLE" -> tr(R.string.stable); else -> tr(R.string.collecting_records)
    }

    fun behavior(s: String) = when (s) {
        "AT_RISK" -> tr(R.string.needs_attention); "CAUTION" -> tr(R.string.some_caution); "HEALTHY" -> tr(R.string.good); else -> tr(R.string.collecting_records)
    }

    fun factor(s: String) = when (s) {
        "PROLONGED_SLEEP_ONSET" -> tr(R.string.long_time_to_fall_asleep)
        "FREQUENT_NIGHT_AWAKENING" -> tr(R.string.frequent_night_waking)
        "LOW_SLEEP_EFFICIENCY" -> tr(R.string.low_sleep_efficiency)
        "SHORT_SLEEP_DURATION" -> tr(R.string.short_sleep)
        "IRREGULAR_BEDTIME" -> tr(R.string.irregular_bedtime)
        "IRREGULAR_WAKE_TIME" -> tr(R.string.irregular_wake_time)
        "POOR_SLEEP_QUALITY" -> tr(R.string.poor_sleep_quality)
        "LATE_CAFFEINE" -> tr(R.string.late_caffeine)
        "EXCESSIVE_PRE_BED_SCREEN" -> tr(R.string.lots_of_screen_time_before_bed)
        "LONG_DAYTIME_NAP" -> tr(R.string.long_naps)
        "HIGH_STRESS" -> tr(R.string.high_stress)
        "LOW_PHYSICAL_ACTIVITY" -> tr(R.string.low_activity)
        else -> s
    }
}

/** One finished walk (GET /walks item; no coordinates in the list). */
data class WalkItem(
    val walkId: Int,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime,
    val durationSec: Int,
    val distanceM: Double,
    val targetMinutes: Int?,
)

/** GET /walks: newest first, plus totals for the same filter. */
data class WalkHistory(val items: List<WalkItem>, val walks: Int, val totalDistanceM: Double, val totalDurationSec: Int) {
    companion object {
        fun from(j: JSONObject): WalkHistory {
            val items = (j.optJSONArray("items") ?: JSONArray()).objects().map {
                WalkItem(
                    walkId = it.optInt("walk_id"),
                    startedAt = OffsetDateTime.parse(it.getString("started_at")),
                    endedAt = OffsetDateTime.parse(it.getString("ended_at")),
                    durationSec = it.optInt("duration_sec"),
                    distanceM = it.optDouble("distance_m"),
                    targetMinutes = if (it.isNull("target_minutes")) null else it.optInt("target_minutes"),
                )
            }
            val s = j.optJSONObject("summary") ?: JSONObject()
            return WalkHistory(items, s.optInt("walks", items.size), s.optDouble("total_distance_m", 0.0), s.optInt("total_duration_sec"))
        }
    }
}
