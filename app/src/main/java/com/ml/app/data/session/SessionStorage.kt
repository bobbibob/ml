package com.ml.app.data.session

interface SessionStorage {
    fun getToken(): String?
    fun saveToken(token: String)
    fun clearToken()

    fun getUserId(): String?
    fun saveUserId(userId: String)
    fun clearUserId()
}
