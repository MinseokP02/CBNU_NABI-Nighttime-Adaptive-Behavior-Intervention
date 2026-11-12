package com.cbnu.nabi.ui

import android.content.Context
import java.time.LocalTime

/*
 * 추천 수면 일정 (patent [0042] 수면 일정 제안부) — data side only.
 *
 * The app never calculates a recommendation. The schedule owner plugs a [provider] in (server API,
 * analysis result); until then it answers [RecommendationResult.None] and the modal shows its empty
 * state. Applying reuses the existing sleep-goal save ([ProfileStore.saveGoals]).
 */

/** One recommended schedule. [reason] is optional and only shown when the provider gives one. */
data class RecommendedSchedule(
    val bedtime: LocalTime,
    val wakeTime: LocalTime,
    val reason: String? = null,
    /** UI preview data (debug only) — applying it never changes the real goal. */
    val preview: Boolean = false,
)

sealed class RecommendationResult {
    data class Ready(val schedule: RecommendedSchedule) : RecommendationResult()
    /** No recommendation yet (not enough records, or no data source connected). */
    object None : RecommendationResult()
    data class Failed(val message: String? = null) : RecommendationResult()
}

object SleepScheduleRecommendations {

    /**
     * Where recommendations come from. Runs on a background thread, so it may call the network.
     * Default: no data source → None. The schedule owner replaces it, e.g.
     * `SleepScheduleRecommendations.provider = { ctx -> /* GET ... */ RecommendationResult.Ready(...) }`.
     */
    @Volatile
    var provider: (Context) -> RecommendationResult = { RecommendationResult.None }

    /**
     * How "적용하기" saves. Default: the existing local sleep-goal save. Returns true only when it
     * really saved. The schedule owner can swap in a server call that also updates the goal.
     */
    @Volatile
    var applier: (Context, String?, RecommendedSchedule) -> Boolean = { ctx, email, s ->
        ProfileStore(ctx, email).saveGoals(hhmm(s.bedtime), hhmm(s.wakeTime))
    }

    fun hhmm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}
