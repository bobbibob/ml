package com.ml.app.data.remote.response

import com.ml.app.data.remote.dto.HistoryItemDto
import com.ml.app.data.remote.dto.TaskDto
import com.ml.app.data.remote.dto.UserDto

data class CreateTaskResponse(
    val ok: Boolean,
    val task_id: String?
)

data class TasksResponse(
    val ok: Boolean,
    val tasks: List<TaskDto>
)

data class CompleteTaskResponse(
    val ok: Boolean,
    val task_id: String?
)

data class CancelTaskResponse(
    val ok: Boolean,
    val task_id: String?
)

data class ReassignTaskResponse(
    val ok: Boolean,
    val task_id: String?
)

data class HistoryResponse(
    val ok: Boolean,
    val history: List<HistoryItemDto>
)

data class UsersResponse(
    val ok: Boolean,
    val users: List<UserDto>
)

data class ChangeRoleResponse(
    val ok: Boolean,
    val user_id: String?,
    val role: String?
)
