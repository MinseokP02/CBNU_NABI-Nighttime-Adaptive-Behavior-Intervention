package com.cbnu.nabi.data


import com.cbnu.nabi.R
import com.cbnu.nabi.tr
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Error from the backend's `{"error": {"code", "message"}}` body, or a network failure (status 0). */
class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/**
 * Blocking HTTP client for the NABI backend (`/api/v1`). Call it from a background thread
 * (see [com.cbnu.nabi.ui.bg]). The Android app only talks to the backend; the LLM server is
 * internal and reached through `/chat/messages` and mission completion.
 */
class ApiClient(private val prefs: Prefs) {

    // ---------- auth (API-01, 02) ----------
    fun register(email: String, password: String, isiScore: Int, catName: String) {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("isi_score", isiScore)
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("cat_name", catName)
        request("POST", "/auth/register", body, auth = false)
    }

    fun login(email: String, password: String) {
        val res = JSONObject(request("POST", "/auth/login", JSONObject().put("email", email).put("password", password), auth = false))
        prefs.token = res.getString("access_token")
        prefs.email = email
        prefs.conversationId = null
    }

    // ---------- home ----------
    fun health(): Health = Health.from(JSONObject(request("GET", "/health", auth = false)))
    fun userState(): UserState = UserState.from(JSONObject(request("GET", "/users/me/state")))
    fun catState(): CatState = CatState.from(JSONObject(request("GET", "/cat/state")))

    // ---------- missions (API-13, 14) ----------
    fun activeMissions(): List<Mission> = Mission.list(JSONArray(request("GET", "/missions/active")))
    fun completeMission(missionId: Int): MissionCompletion =
        MissionCompletion.from(JSONObject(request("POST", "/missions/$missionId/complete")))

    // ---------- chat (API-15, 16) ----------
    fun sendChat(message: String, catAnimation: String? = null): ChatReply {
        val body = JSONObject().put("message", message)
        prefs.conversationId?.let { body.put("conversation_id", it) }
        body.put("client_context", JSONObject().put("screen", "chat").apply {
            if (catAnimation != null) put("cat_animation", catAnimation)
        })
        val reply = ChatReply.from(JSONObject(request("POST", "/chat/messages", body)))
        if (reply.conversationId.isNotBlank()) prefs.conversationId = reply.conversationId
        return reply
    }

    fun chatHistory(limit: Int = 50): List<ChatMessage> {
        val q = StringBuilder("/chat/history?limit=$limit")
        prefs.conversationId?.let { q.append("&conversation_id=").append(URLEncoder.encode(it, "UTF-8")) }
        return ChatMessage.list(JSONObject(request("GET", q.toString())))
    }

    // ---------- records (API-05, 07) — ready for the sleep diary screens ----------
    /** Times must carry the user's zone. `sleep_date` is the night: local date of (bedtime − 12h). */
    fun createSleepRecord(
        bedtime: ZonedDateTime,
        sleepOnsetLatencyMin: Int,
        wakeAfterSleepOnsetMin: Int,
        finalAwakening: ZonedDateTime,
        outOfBedTime: ZonedDateTime,
        sleepQuality: Int?,
    ): JSONObject {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val body = JSONObject()
            .put("sleep_date", bedtime.minusHours(12).toLocalDate().toString())
            .put("bedtime", bedtime.format(iso))
            .put("sleep_onset_latency_min", sleepOnsetLatencyMin)
            .put("wake_after_sleep_onset_min", wakeAfterSleepOnsetMin)
            .put("final_awakening", finalAwakening.format(iso))
            .put("out_of_bed_time", outOfBedTime.format(iso))
        if (sleepQuality != null) body.put("sleep_quality", sleepQuality)
        return JSONObject(request("POST", "/sleep-records", body))
    }

    /** Only non-null fields are sent; the server stores missing ones as "not recorded". */
    fun createBehaviorRecord(
        date: LocalDate,
        caffeineAfterCutoff: Boolean? = null,
        screenBeforeBedMin: Int? = null,
        napDurationMin: Int? = null,
        exerciseDurationMin: Int? = null,
        stressLevel: Int? = null,
        relaxationCompleted: Boolean? = null,
    ): JSONObject {
        val body = JSONObject().put("record_date", date.toString())
        caffeineAfterCutoff?.let { body.put("caffeine_after_cutoff", it) }
        screenBeforeBedMin?.let { body.put("screen_before_bed_min", it) }
        napDurationMin?.let { body.put("nap_duration_min", it) }
        exerciseDurationMin?.let { body.put("exercise_duration_min", it) }
        stressLevel?.let { body.put("stress_level", it) }
        relaxationCompleted?.let { body.put("relaxation_completed", it) }
        return JSONObject(request("POST", "/behavior-records", body))
    }

    /** API-06. Newest first; the server caps [limit] at 100. */
    fun sleepRecords(start: LocalDate, end: LocalDate, limit: Int = 100): List<SleepRecordItem> =
        SleepRecordItem.list(JSONObject(request("GET", "/sleep-records?start_date=$start&end_date=$end&limit=$limit")))

    /** API-08. Newest first; the server caps [limit] at 100. */
    fun behaviorRecords(start: LocalDate, end: LocalDate, limit: Int = 100): List<BehaviorRecordItem> =
        BehaviorRecordItem.list(JSONObject(request("GET", "/behavior-records?start_date=$start&end_date=$end&limit=$limit")))

    // ---------- walks (산책 내역) ----------
    /** POST /walks body. [route] / [path] are [lat, lon] pairs (thin them first; the server takes at most 2000 points). */
    fun walkBody(
        startedAt: ZonedDateTime,
        endedAt: ZonedDateTime,
        distanceM: Double,
        targetMinutes: Int?,
        plannedDistanceM: Double?,
        route: List<DoubleArray>,
        path: List<DoubleArray>,
        proposalId: String?,
    ): JSONObject {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun pts(list: List<DoubleArray>) = JSONArray().apply {
            list.forEach { put(JSONArray().put(Math.round(it[0] * 1e6) / 1e6).put(Math.round(it[1] * 1e6) / 1e6)) }
        }
        val body = JSONObject()
            .put("started_at", startedAt.format(iso))
            .put("ended_at", endedAt.format(iso))
            .put("distance_m", Math.round(distanceM * 10) / 10.0)
            .put("planned_route", pts(route))
            .put("path", pts(path))
        targetMinutes?.let { body.put("target_minutes", it) }
        plannedDistanceM?.let { body.put("planned_distance_m", Math.round(it * 10) / 10.0) }
        proposalId?.let { body.put("proposal_id", it.take(64)) }
        return body
    }

    /** Sends a body from [walkBody] (also used to re-send a walk kept on the device after a failure). */
    fun createWalk(body: JSONObject): JSONObject = JSONObject(request("POST", "/walks", body))

    fun walks(limit: Int = 20): WalkHistory = WalkHistory.from(JSONObject(request("GET", "/walks?limit=$limit")))

    // ---------- analysis / intervention (API-10, 12) ----------
    /** Per-day values for the trend charts ([days] 7..60). */
    fun trends(days: Int = 14): Trends = Trends.from(JSONObject(request("GET", "/analysis/trends?days=$days")))

    fun risk(window: Int = 7): RiskAnalysis = RiskAnalysis.from(JSONObject(request("GET", "/analysis/risk?window=$window")))

    /** With [includeExplanation] the backend asks the LLM server for a friendly explanation (fallback text otherwise). */
    fun recommendation(includeExplanation: Boolean = true): Recommendation =
        Recommendation.from(JSONObject(request("GET", "/interventions/recommendations?include_explanation=$includeExplanation")))

    // ---------- transport ----------
    private fun request(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String {
        val conn = try {
            URL(prefs.baseUrl + API_PREFIX + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw ApiException(0, "BAD_URL", tr(R.string.check_server_address, prefs.baseUrl))
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 25_000 // backend waits up to LLM_TIMEOUT_SECONDS (12s) for the LLM
            conn.setRequestProperty("Accept", "application/json")
            // The server answers user-facing text (catalog, fallback, LLM replies) in the app's language.
            conn.setRequestProperty("Accept-Language",
                com.cbnu.nabi.NabiApp.appContext.resources.configuration.locales[0].toLanguageTag())
            if (auth) prefs.token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                if (status == 401 && auth) prefs.logout()
                throw parseError(status, text)
            }
            return text
        } catch (e: IOException) {
            throw ApiException(0, "NETWORK_ERROR", tr(R.string.can_t_reach_server, prefs.baseUrl))
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(status: Int, text: String): ApiException {
        var code = "HTTP_$status"
        var msg: String? = null
        try {
            val err = JSONObject(text).optJSONObject("error")
            if (err != null) {
                code = err.optString("code", code)
                msg = err.optString("message").ifBlank { null }
            }
        } catch (_: JSONException) {
        }
        val friendly = when (code) {
            "UNAUTHORIZED" -> if (msg?.contains("incorrect") == true) tr(R.string.wrong_email_or_password) else tr(R.string.please_log_in_again)
            "DUPLICATE_RECORD" -> if (msg?.contains("Email") == true) tr(R.string.this_email_is_already_registered) else tr(R.string.record_already_exists)
            "VALIDATION_ERROR" -> tr(R.string.please_check_your_input) + (msg?.let { " ($it)" } ?: "")
            "RATE_LIMITED" -> tr(R.string.take_short_break_then_talk_to)
            "LLM_UNAVAILABLE" -> tr(R.string.nabi_can_t_answer_right_now)
            "MISSION_NOT_VERIFIED" -> tr(R.string.no_record_meets_mission_goal_yet)
            "MISSION_EXPIRED" -> tr(R.string.this_mission_has_expired)
            else -> msg ?: tr(R.string.couldn_t_process_request, status)
        }
        return ApiException(status, code, friendly)
    }

    companion object {
        const val API_PREFIX = "/api/v1"
    }
}
