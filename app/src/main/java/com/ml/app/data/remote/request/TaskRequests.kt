package com.ml.app.data.remote.request

data class CreateTaskRequest(
    val title: String,
    val description: String,
    val assignee_user_id: String,
    val reminder_type: String? = null,
    val reminder_interval_minutes: Int? = null,
    val reminder_time_of_day: String? = null
)

data class CompleteTaskRequest(
    val task_id: String
)

data class CancelTaskRequest(
    val task_id: String
)

data class ReassignTaskRequest(
    val task_id: String,
    val assignee_user_id: String
)

data class ChangeRoleRequest(
    val user_id: String,
    val role: String
)
