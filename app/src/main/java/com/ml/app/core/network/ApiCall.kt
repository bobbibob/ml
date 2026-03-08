package com.ml.app.core.network

import com.ml.app.core.result.AppResult
import retrofit2.HttpException
import java.io.IOException

suspend fun <T> safeApiCall(
    block: suspend () -> T
): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (e: HttpException) {
        AppResult.Error(
            message = "HTTP ${e.code()} ${e.message()}",
            code = e.code()
        )
    } catch (e: IOException) {
        AppResult.Error(
            message = "Network error: ${e.message ?: "unknown"}"
        )
    } catch (e: Exception) {
        AppResult.Error(
            message = e.message ?: "Unknown error"
        )
    }
}
