package com.ml.app.data.remote.request

data class RegisterRequest(
    val email: String,
    val password: String,
    val display_name: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class GoogleLoginRequest(
    val id_token: String
)
