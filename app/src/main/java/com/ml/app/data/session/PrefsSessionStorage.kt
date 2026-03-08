package com.ml.app.data.session

import android.content.Context
import android.content.SharedPreferences

class PrefsSessionStorage(
    context: Context
) : SessionStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ml_tasks_session", Context.MODE_PRIVATE)

    override fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    override fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    override fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    override fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    override fun clearUserId() {
        prefs.edit().remove(KEY_USER_ID).apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
    }
}
