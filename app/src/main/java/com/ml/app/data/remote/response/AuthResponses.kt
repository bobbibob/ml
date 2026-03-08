package com.ml.app.data.remote.response

import com.ml.app.data.remote.dto.UserDto

data class RegisterResponse(
    val ok: Boolean,
    val user: UserDto?
)

data class LoginResponse(
    val ok: Boolean,
    val token: String?,
    val user: UserDto?
)

data class MeResponse(
    val ok: Boolean,
    val user: UserDto?
)
