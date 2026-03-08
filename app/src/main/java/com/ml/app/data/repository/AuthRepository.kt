package com.ml.app.data.repository

import com.ml.app.core.network.safeApiCall
import com.ml.app.core.result.AppResult
import com.ml.app.data.remote.api.MlApiService
import com.ml.app.data.remote.dto.UserDto
import com.ml.app.data.remote.request.GoogleLoginRequest
import com.ml.app.data.remote.request.LoginRequest
import com.ml.app.data.remote.request.RegisterRequest
import com.ml.app.data.session.SessionStorage

class AuthRepository(
    private val api: MlApiService,
    private val sessionStorage: SessionStorage
) {

    suspend fun googleLogin(idToken: String): AppResult<UserDto> {
        return when (
            val result = safeApiCall {
                api.googleLogin(GoogleLoginRequest(id_token = idToken))
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val body = result.data
                val token = body.token
                val user = body.user

                if (body.ok && token != null && user != null) {
                    sessionStorage.saveToken(token)
                    sessionStorage.saveUserId(user.user_id)
                    AppResult.Success(user)
                } else {
                    AppResult.Error("Google login failed")
                }
            }
        }
    }

    suspend fun register(
        email: String,
        password: String,
        displayName: String
    ): AppResult<UserDto> {
        return when (
            val result = safeApiCall {
                api.register(
                    RegisterRequest(
                        email = email,
                        password = password,
                        display_name = displayName
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val user = result.data.user
                if (result.data.ok && user != null) {
                    AppResult.Success(user)
                } else {
                    AppResult.Error("Registration failed")
                }
            }
        }
    }

    suspend fun login(
        email: String,
        password: String
    ): AppResult<UserDto> {
        return when (
            val result = safeApiCall {
                api.login(
                    LoginRequest(
                        email = email,
                        password = password
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val body = result.data
                val token = body.token
                val user = body.user

                if (body.ok && token != null && user != null) {
                    sessionStorage.saveToken(token)
                    sessionStorage.saveUserId(user.user_id)
                    AppResult.Success(user)
                } else {
                    AppResult.Error("Login failed")
                }
            }
        }
    }

    suspend fun me(): AppResult<UserDto> {
        return when (val result = safeApiCall { api.me() }) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val user = result.data.user
                if (result.data.ok && user != null) {
                    sessionStorage.saveUserId(user.user_id)
                    AppResult.Success(user)
                } else {
                    AppResult.Error("User not found")
                }
            }
        }
    }

    fun logout() {
        sessionStorage.clearToken()
        sessionStorage.clearUserId()
    }

    fun isLoggedIn(): Boolean = !sessionStorage.getToken().isNullOrBlank()
}
