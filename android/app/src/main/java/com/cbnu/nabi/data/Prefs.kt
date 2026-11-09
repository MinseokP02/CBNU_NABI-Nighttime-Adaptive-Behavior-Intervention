package com.cbnu.nabi.data

import android.content.Context
import com.cbnu.nabi.BuildConfig

/**
 * Small key-value store for app settings and the login token.
 * Note: the JWT is kept in plain SharedPreferences for development.
 * Before release, move it to EncryptedSharedPreferences / Android Keystore.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(K_URL, null) ?: BuildConfig.DEFAULT_BASE_URL
        set(v) = sp.edit().putString(K_URL, v.trim().trimEnd('/')).apply()

    var token: String?
        get() = sp.getString(K_TOKEN, null)
        set(v) = sp.edit().putString(K_TOKEN, v).apply()

    var email: String?
        get() = sp.getString(K_EMAIL, null)
        set(v) = sp.edit().putString(K_EMAIL, v).apply()

    /** Keeps one ongoing chat thread with the cat across app restarts. */
    var conversationId: String?
        get() = sp.getString(K_CONV, null)
        set(v) = sp.edit().putString(K_CONV, v).apply()

    var catName: String
        get() = sp.getString(K_CAT_NAME, null) ?: "나비"
        set(v) = sp.edit().putString(K_CAT_NAME, v).apply()

    var haptics: Boolean
        get() = sp.getBoolean(K_HAPTICS, true)
        set(v) = sp.edit().putBoolean(K_HAPTICS, v).apply()

    /** Local "how much the cat likes being petted" score from Mochi. Separate from server EXP. */
    var affection: Float
        get() = sp.getFloat(K_AFF, 50f)
        set(v) = sp.edit().putFloat(K_AFF, v).apply()

    val loggedIn: Boolean get() = !token.isNullOrBlank()

    fun logout() {
        sp.edit().remove(K_TOKEN).remove(K_CONV).remove(K_EMAIL).apply()
    }

    companion object {
        const val NAME = "nabi"
        private const val K_URL = "base_url"
        private const val K_TOKEN = "access_token"
        private const val K_EMAIL = "email"
        private const val K_CONV = "conversation_id"
        private const val K_CAT_NAME = "cat_name"
        private const val K_HAPTICS = "haptics"
        private const val K_AFF = "aff"
    }
}
