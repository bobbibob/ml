package com.ml.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

object TaskSyncScheduler {

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun enqueueCreate(
        context: Context,
        title: String,
        description: String,
        assigneeUserId: String,
        reminderType: String?,
        reminderIntervalMinutes: Int?,
        reminderTimeOfDay: String?,
        isUrgent: Boolean,
        clientRequestId: String
    ) {
        val data = Data.Builder()
            .putString("title", title)
            .putString("description", description)
            .putString("assignee_user_id", assigneeUserId)
            .putString("reminder_type", reminderType)
            .putInt("reminder_interval_minutes", reminderIntervalMinutes ?: -1)
            .putString("reminder_time_of_day", reminderTimeOfDay)
            .putBoolean("is_urgent", isUrgent)
            .putString("client_request_id", clientRequestId)
            .build()

        val req = OneTimeWorkRequestBuilder<CreateTaskWorker>()
            .setConstraints(networkConstraints())
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "task_create_$clientRequestId",
            ExistingWorkPolicy.KEEP,
            req
        )
    }

    fun enqueueDelete(
        context: Context,
        taskId: String
    ) {
        val data = Data.Builder()
            .putString("task_id", taskId)
            .build()

        val req = OneTimeWorkRequestBuilder<DeleteTaskWorker>()
            .setConstraints(networkConstraints())
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "task_delete_$taskId",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
