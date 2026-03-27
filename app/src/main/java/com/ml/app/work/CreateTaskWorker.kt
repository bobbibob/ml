package com.ml.app.work

import com.ml.app.BuildConfig

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ml.app.core.network.ApiModule
import com.ml.app.data.repository.TasksRepository
import com.ml.app.data.session.PrefsSessionStorage
import com.ml.app.core.result.AppResult

class CreateTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString("title")?.trim().orEmpty()
        val description = inputData.getString("description").orEmpty()
        val assigneeUserId = inputData.getString("assignee_user_id")?.trim().orEmpty()
        val reminderType = inputData.getString("reminder_type")?.trim()?.ifBlank { null }
        val reminderIntervalMinutes = inputData.getInt("reminder_interval_minutes", -1).let { if (it > 0) it else null }
        val reminderTimeOfDay = inputData.getString("reminder_time_of_day")?.trim()?.ifBlank { null }
        val isUrgent = inputData.getBoolean("is_urgent", false)
        val clientRequestId = inputData.getString("client_request_id")?.trim().orEmpty()

        if (title.isBlank() || assigneeUserId.isBlank() || clientRequestId.isBlank()) {
            return Result.failure()
        }

        val session = PrefsSessionStorage(applicationContext)
        if (session.getToken().isNullOrBlank()) {
            return Result.retry()
        }

        val api = ApiModule.createApi(
            baseUrl = "https://127.0.0.1/",
            sessionStorage = session
        )
        val repo = TasksRepository(api)

        return when (
            repo.createTask(
                title = title,
                description = description,
                assigneeUserId = assigneeUserId,
                reminderType = reminderType,
                reminderIntervalMinutes = reminderIntervalMinutes,
                reminderTimeOfDay = reminderTimeOfDay,
                isUrgent = isUrgent,
                clientRequestId = clientRequestId
            )
        ) {
            is AppResult.Success -> Result.success()
            is AppResult.Error -> Result.retry()
        }
    }
}
