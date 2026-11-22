package com.cbnu.nabi.walk

import android.content.Context
import com.cbnu.nabi.data.ApiClient
import com.cbnu.nabi.data.ApiException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finished walks that the server has not confirmed yet (offline, server down), per logged-in email.
 * Each is the exact POST /walks body; [flush] sends them in order and drops the ones the server took.
 */
class PendingWalks(context: Context, email: String?) {
    private val sp = context.getSharedPreferences("nabi_walk_pending_" + (email ?: "guest").hashCode(), Context.MODE_PRIVATE)

    @Synchronized
    fun add(body: JSONObject) {
        sp.edit().putString(K, read().put(body).toString()).commit()
    }

    fun count(): Int = read().length()

    /** Blocking. Returns how many were saved; rethrows the error if none could be saved. */
    @Synchronized
    fun flush(api: ApiClient): Int {
        val all = read()
        val left = JSONArray()
        var saved = 0
        var error: Exception? = null
        for (i in 0 until all.length()) {
            val body = all.getJSONObject(i)
            if (error != null) { left.put(body); continue } // server unreachable: keep the rest for later
            try {
                api.createWalk(body)
                saved++
            } catch (e: ApiException) {
                // Other 4xx = the server will never accept this body: drop it instead of retrying forever.
                if (e.status in 400..499 && e.status != 401 && e.status != 429) continue
                error = e
                left.put(body)
            }
        }
        sp.edit().putString(K, left.toString()).commit()
        if (saved == 0 && error != null) throw error
        return saved
    }

    private fun read() = runCatching { JSONArray(sp.getString(K, "[]")) }.getOrDefault(JSONArray())

    private companion object {
        const val K = "walks"
    }
}
