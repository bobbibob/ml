package com.ml.app.data.remote.dto

data class TaskDto(
    val task_id: String,
    val title: String,
    val description: String?,
    val status: String,
    val created_at: String,
    val updated_at: String,
    val created_by_user_id: String,
    val assignee_user_id: String,
    val completed_by_user_id: String?,
    val completed_at: String?,
    val cancelled_by_user_id: String?,
    val cancelled_at: String?,
    val created_by_name: String,
    val assignee_name: String,
    val completed_by_name: String?,
    val cancelled_by_name: String?,
    val reminder_type: String? = null,
    val reminder_interval_minutes: Int? = null,
    val reminder_time_of_day: String? = null,
    val notification_status: String? = null,
    val is_urgent: Int = 0
)
