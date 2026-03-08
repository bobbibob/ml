package com.ml.app.data.repository

import com.ml.app.core.network.safeApiCall
import com.ml.app.core.result.AppResult
import com.ml.app.data.remote.api.MlApiService
import com.ml.app.data.remote.dto.HistoryItemDto
import com.ml.app.data.remote.dto.TaskDto
import com.ml.app.data.remote.dto.UserDto
import com.ml.app.data.remote.request.CancelTaskRequest
import com.ml.app.data.remote.request.ChangeRoleRequest
import com.ml.app.data.remote.request.CompleteTaskRequest
import com.ml.app.data.remote.request.CreateTaskRequest
import com.ml.app.data.remote.request.ReassignTaskRequest

class TasksRepository(
    private val api: MlApiService
) {

    suspend fun getUsers(): AppResult<List<UserDto>> {
        return when (val result = safeApiCall { api.getUsers() }) {
            is AppResult.Error -> result
            is AppResult.Success -> if (result.data.ok) {
                AppResult.Success(result.data.users)
            } else {
                AppResult.Error("Failed to load users")
            }
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        assigneeUserId: String
    ): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.createTask(
                    CreateTaskRequest(
                        title = title,
                        description = description,
                        assignee_user_id = assigneeUserId
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val taskId = result.data.task_id
                if (result.data.ok && taskId != null) {
                    AppResult.Success(taskId)
                } else {
                    AppResult.Error("Failed to create task")
                }
            }
        }
    }

    suspend fun getMyTasks(): AppResult<List<TaskDto>> {
        return when (val result = safeApiCall { api.getMyTasks() }) {
            is AppResult.Error -> result
            is AppResult.Success -> if (result.data.ok) {
                AppResult.Success(result.data.tasks)
            } else {
                AppResult.Error("Failed to load tasks")
            }
        }
    }

    suspend fun getAllTasks(): AppResult<List<TaskDto>> {
        return when (val result = safeApiCall { api.getAllTasks() }) {
            is AppResult.Error -> result
            is AppResult.Success -> if (result.data.ok) {
                AppResult.Success(result.data.tasks)
            } else {
                AppResult.Error("Failed to load tasks")
            }
        }
    }

    suspend fun completeTask(taskId: String): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.completeTask(CompleteTaskRequest(task_id = taskId))
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val id = result.data.task_id
                if (result.data.ok && id != null) {
                    AppResult.Success(id)
                } else {
                    AppResult.Error("Failed to complete task")
                }
            }
        }
    }

    suspend fun cancelTask(taskId: String): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.cancelTask(CancelTaskRequest(task_id = taskId))
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val id = result.data.task_id
                if (result.data.ok && id != null) {
                    AppResult.Success(id)
                } else {
                    AppResult.Error("Failed to cancel task")
                }
            }
        }
    }

    suspend fun reassignTask(
        taskId: String,
        assigneeUserId: String
    ): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.reassignTask(
                    ReassignTaskRequest(
                        task_id = taskId,
                        assignee_user_id = assigneeUserId
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val id = result.data.task_id
                if (result.data.ok && id != null) {
                    AppResult.Success(id)
                } else {
                    AppResult.Error("Failed to reassign task")
                }
            }
        }
    }

    suspend fun getHistory(): AppResult<List<HistoryItemDto>> {
        return when (val result = safeApiCall { api.getHistory() }) {
            is AppResult.Error -> result
            is AppResult.Success -> if (result.data.ok) {
                AppResult.Success(result.data.history)
            } else {
                AppResult.Error("Failed to load history")
            }
        }
    }

    suspend fun changeRole(
        userId: String,
        role: String
    ): AppResult<Pair<String, String>> {
        return when (
            val result = safeApiCall {
                api.changeRole(ChangeRoleRequest(user_id = userId, role = role))
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success -> {
                val changedUserId = result.data.user_id
                val changedRole = result.data.role

                if (result.data.ok && changedUserId != null && changedRole != null) {
                    AppResult.Success(changedUserId to changedRole)
                } else {
                    AppResult.Error("Failed to change role")
                }
            }
        }
    }
}
