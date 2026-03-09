package com.ml.app.data.remote.request

data class CreateTaskRequest(
    val title: String,
    val description: String,
    val assignee_user_id: String,
    val reminder_type: String? = null,
    val reminder_interval_minutes: Int? = null,
    val reminder_time_of_day: String? = null
)
