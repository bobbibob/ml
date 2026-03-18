package com.ml.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ml.app.core.network.ApiModule
import com.ml.app.data.repository.TasksRepository
import com.ml.app.data.session.PrefsSessionStorage
import com.ml.app.core.result.AppResult

class DeleteTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id")?.trim().orEmpty()
        if (taskId.isBlank()) return Result.failure()

        val session = PrefsSessionStorage(applicationContext)
        if (session.getToken().isNullOrBlank()) {
            return Result.retry()
        }

        val api = ApiModule.createApi(
            baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
            sessionStorage = session
        )
        val repo = TasksRepository(api)

        return when (val res = repo.deleteTask(taskId)) {
            is AppResult.Success -> Result.success()
            is AppResult.Error -> {
                val msg = res.message.lowercase()
                if ("not found" in msg || "task not found" in msg || "task_not_found" in msg) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        }
    }
}
