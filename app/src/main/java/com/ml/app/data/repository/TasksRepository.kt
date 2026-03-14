package com.ml.app.data.repository

import org.json.JSONObject

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
import com.ml.app.data.remote.request.TaskReminderRequest

class TasksRepository(
    private val api: MlApiService
) {

    suspend fun getUsers(): AppResult<List<UserDto>> {
        return when (val result = safeApiCall { api.getUsers() }) {
            is AppResult.Error -> result
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.UsersResponse
                if (body.ok) {
                    AppResult.Success(body.users)
                } else {
                    AppResult.Error("Failed to load users")
                }
            }
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        assigneeUserId: String,
        reminderType: String? = null,
        reminderIntervalMinutes: Int? = null,
        reminderTimeOfDay: String? = null
    ): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.createTask(
                    CreateTaskRequest(
                        title = title,
                        description = description,
                        assignee_user_id = assigneeUserId,
                        reminder_type = reminderType,
                        reminder_interval_minutes = reminderIntervalMinutes,
                        reminder_time_of_day = reminderTimeOfDay
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.CreateTaskResponse
                val taskId = body.task_id
                if (body.ok && taskId != null) {
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
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.TasksResponse
                if (body.ok) {
                    AppResult.Success(body.tasks)
                } else {
                    AppResult.Error("Failed to load tasks")
                }
            }
        }
    }


    suspend fun getTaskById(taskId: String): AppResult<TaskDto> {
        return try {
            val raw = api.getTaskByIdRaw(taskId)
            val json = JSONObject(raw.string())
            if (!json.optBoolean("ok")) {
                return AppResult.Error(json.optString("error", "Не удалось загрузить задачу"))
            }

            val t = json.optJSONObject("task")
                ?: return AppResult.Error("Задача не найдена")

            val task = TaskDto(
                task_id = t.optString("task_id"),
                title = t.optString("title"),
                description = t.optString("description").takeIf { it.isNotBlank() },
                status = t.optString("status"),
                created_at = t.optString("created_at"),
                updated_at = t.optString("updated_at"),
                created_by_user_id = t.optString("created_by_user_id"),
                assignee_user_id = t.optString("assignee_user_id"),
                completed_by_user_id = t.optString("completed_by_user_id").takeIf { it.isNotBlank() },
                completed_at = t.optString("completed_at").takeIf { it.isNotBlank() },
                cancelled_by_user_id = t.optString("cancelled_by_user_id").takeIf { it.isNotBlank() },
                cancelled_at = t.optString("cancelled_at").takeIf { it.isNotBlank() },
                created_by_name = t.optString("created_by_name"),
                assignee_name = t.optString("assignee_name"),
                completed_by_name = t.optString("completed_by_name").takeIf { it.isNotBlank() },
                cancelled_by_name = t.optString("cancelled_by_name").takeIf { it.isNotBlank() },
                reminder_type = t.optString("reminder_type").takeIf { it.isNotBlank() },
                reminder_interval_minutes = if (t.has("reminder_interval_minutes") && !t.isNull("reminder_interval_minutes")) t.optInt("reminder_interval_minutes") else null,
                reminder_time_of_day = t.optString("reminder_time_of_day").takeIf { it.isNotBlank() }
            )

            AppResult.Success(task)
        } catch (t: Throwable) {
            AppResult.Error(t.message ?: "Не удалось загрузить задачу")
        }
    }

    suspend fun getAllTasks(): AppResult<List<TaskDto>> {
        return when (val result = safeApiCall { api.getAllTasks() }) {
            is AppResult.Error -> result
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.TasksResponse
                if (body.ok) {
                    AppResult.Success(body.tasks)
                } else {
                    AppResult.Error("Failed to load tasks")
                }
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
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.CompleteTaskResponse
                val id = body.task_id
                if (body.ok && id != null) {
                    AppResult.Success(id)
                } else {
                    AppResult.Error("Failed to complete task")
                }
            }
        }
    }


    suspend fun remindTask(taskId: String): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.taskReminder(TaskReminderRequest(task_id = taskId))
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.BasicOkResponse
                if (body.ok) {
                    AppResult.Success("Напоминание отправлено")
                } else {
                    AppResult.Error(body.error ?: "Не удалось отправить напоминание")
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
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.CancelTaskResponse
                val id = body.task_id
                if (body.ok && id != null) {
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
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.ReassignTaskResponse
                val id = body.task_id
                if (body.ok && id != null) {
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
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.HistoryResponse
                if (body.ok) {
                    AppResult.Success(body.history)
                } else {
                    AppResult.Error("Failed to load history")
                }
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
            is AppResult.Success<*> -> {
                val body = result.data as com.ml.app.data.remote.response.ChangeRoleResponse
                val changedUserId = body.user_id
                val changedRole = body.role

                if (body.ok && changedUserId != null && changedRole != null) {
                    AppResult.Success(changedUserId to changedRole)
                } else {
                    AppResult.Error("Failed to change role")
                }
            }
        }
    }


    suspend fun updateTask(
        taskId: String,
        title: String,
        description: String,
        assigneeUserId: String,
        reminderType: String? = null,
        reminderIntervalMinutes: Int? = null,
        reminderTimeOfDay: String? = null
    ): AppResult<Unit> {
        return try {
            val raw = api.updateTaskRaw(
                mapOf(
                    "task_id" to taskId,
                    "title" to title,
                    "description" to description,
                    "assignee_user_id" to assigneeUserId
                )
            )
            val json = JSONObject(raw.string())
            if (json.optBoolean("ok")) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(json.optString("error", "Ошибка изменения задачи"))
            }
        } catch (t: Throwable) {
            AppResult.Error(t.message ?: "Ошибка изменения задачи")
        }
    }

    suspend fun deleteTask(taskId: String): AppResult<Unit> {
        return try {
            val raw = api.deleteTaskRaw(
                mapOf(
                    "task_id" to taskId
                )
            )
            val json = JSONObject(raw.string())
            if (json.optBoolean("ok")) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(json.optString("error", "Ошибка удаления задачи"))
            }
        } catch (t: Throwable) {
            AppResult.Error(t.message ?: "Ошибка удаления задачи")
        }
    }


    suspend fun changeUserRole(userId: String, role: String): AppResult<Unit> {
        return try {
            val raw = api.changeRoleRaw(
                mapOf(
                    "user_id" to userId,
                    "role" to role
                )
            )
            val json = JSONObject(raw.string())
            if (json.optBoolean("ok")) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(json.optString("error", "Ошибка смены роли"))
            }
        } catch (t: Throwable) {
            AppResult.Error(t.message ?: "Ошибка смены роли")
        }
    }

    suspend fun deleteUser(userId: String): AppResult<Unit> {
        return try {
            val raw = api.deleteUserRaw(
                mapOf(
                    "user_id" to userId
                )
            )
            val json = JSONObject(raw.string())
            if (json.optBoolean("ok")) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(json.optString("error", "Ошибка удаления пользователя"))
            }
        } catch (t: Throwable) {
            AppResult.Error(t.message ?: "Ошибка удаления пользователя")
        }
    }

    suspend fun sendPush(
        userId: String?,
        title: String,
        body: String
    ): AppResult<String> {
        return when (
            val result = safeApiCall {
                api.sendPushRaw(
                    mapOf(
                        "user_id" to (userId ?: ""),
                        "title" to title,
                        "body" to body
                    )
                )
            }
        ) {
            is AppResult.Error -> result
            is AppResult.Success<*> -> {
                val raw = (result.data as okhttp3.ResponseBody).string()
                val json = JSONObject(raw)
                if (json.optBoolean("ok")) {
                    val sent = json.optInt("sent", 0)
                    val debug = json.optString("fcm_debug", "")
                    AppResult.Success(
                        buildString {
                            append(if (sent > 0) "Уведомление отправлено: $sent" else "Уведомление отправлено")
                            if (debug.isNotBlank()) {
                                append("\n")
                                append(debug)
                            }
                        }
                    )
                } else {
                    AppResult.Error(json.optString("error", "Не удалось отправить уведомление"))
                }
            }
        }
    }

}
