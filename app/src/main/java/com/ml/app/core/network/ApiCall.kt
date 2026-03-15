package com.ml.app.core.network

import com.ml.app.core.result.AppResult
import retrofit2.HttpException
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException

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
    } catch (e: UnknownHostException) {
        AppResult.Error("Нет соединения с сервером. Проверь интернет.")
    } catch (e: SocketTimeoutException) {
        AppResult.Error("Сервер отвечает слишком долго. Повтори ещё раз.")
    } catch (e: IOException) {
        AppResult.Error("Ошибка сети. Проверь интернет и повтори.")
    } catch (e: Exception) {
        AppResult.Error(
            message = e.message ?: "Unknown error"
        )
    }
}
