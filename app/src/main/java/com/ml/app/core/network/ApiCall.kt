package com.ml.app.core.network

import com.ml.app.core.result.AppResult
import retrofit2.HttpException
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay

suspend fun <T> safeApiCall(
    block: suspend () -> T
): AppResult<T> {
    suspend fun runOnce(): T = block()

    return try {
        AppResult.Success(runOnce())
    } catch (e: HttpException) {
        AppResult.Error(
            message = "HTTP ${e.code()} ${e.message()}",
            code = e.code()
        )
    } catch (e: UnknownHostException) {
        delay(1200)
        try {
            AppResult.Success(runOnce())
        } catch (_: UnknownHostException) {
            AppResult.Error("Нет соединения с сервером. Проверь интернет.")
        } catch (_: IOException) {
            AppResult.Error("Нет соединения с сервером. Проверь интернет.")
        } catch (inner: Exception) {
            AppResult.Error(inner.message ?: "Нет соединения с сервером")
        }
    } catch (e: SocketTimeoutException) {
        delay(800)
        try {
            AppResult.Success(runOnce())
        } catch (_: SocketTimeoutException) {
            AppResult.Error("Сервер отвечает слишком долго. Повтори ещё раз.")
        } catch (_: IOException) {
            AppResult.Error("Сервер отвечает слишком долго. Повтори ещё раз.")
        } catch (inner: Exception) {
            AppResult.Error(inner.message ?: "Сервер отвечает слишком долго")
        }
    } catch (e: IOException) {
        delay(800)
        try {
            AppResult.Success(runOnce())
        } catch (_: IOException) {
            AppResult.Error("Ошибка сети. Проверь интернет и повтори.")
        } catch (inner: Exception) {
            AppResult.Error(inner.message ?: "Ошибка сети")
        }
    } catch (e: Exception) {
        AppResult.Error(
            message = e.message ?: "Unknown error"
        )
    }
}
