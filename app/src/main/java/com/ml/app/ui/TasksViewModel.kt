package com.ml.app.ui

import com.ml.app.BuildConfig

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.ml.app.core.network.ApiModule
import com.ml.app.core.result.AppResult
import com.ml.app.data.remote.dto.HistoryItemDto
import com.ml.app.data.remote.dto.TaskDto
import com.ml.app.data.remote.dto.UserDto
import com.ml.app.data.repository.AuthRepository
import com.ml.app.data.repository.TasksRepository
import com.ml.app.data.session.PrefsSessionStorage
import com.ml.app.notifications.UrgentTaskNotifier
import com.ml.app.work.TaskSyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class TasksUiState(
    val loading: Boolean = false,
    val loadingTasks: Boolean = false,
    val loadingUsers: Boolean = false,
    val creatingTask: Boolean = false,
    val currentUser: UserDto? = null,
    val error: String? = null,
    val info: String? = null,
    val myTasks: List<TaskDto> = emptyList(),
    val allTasks: List<TaskDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val history: List<HistoryItemDto> = emptyList(),
    val selectedTab: String = "my",
    val lastTasksTab: String = "my",
    val openedTaskFromPush: TaskDto? = null
)

class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val session = PrefsSessionStorage(app.applicationContext)
    private val api = ApiModule.createApi(
        baseUrl = BuildConfig.TASKS_API_BASE_URL,
        sessionStorage = session
    )
    private val authRepo = AuthRepository(api, session)
    private val tasksRepo = TasksRepository(api)

    var state by mutableStateOf(TasksUiState())
        private set

    private fun markTaskCompletedLocally(taskId: String) {
        val now = java.time.OffsetDateTime.now().toString()

        fun patch(list: List<TaskDto>): List<TaskDto> {
            return list.map { task ->
                if (task.task_id == taskId) {
                    task.copy(
                        status = "done",
                        completed_by_user_id = state.currentUser?.user_id,
                        completed_at = now,
                        updated_at = now
                    )
                } else {
                    task
                }
            }
        }

        state = state.copy(
            myTasks = patch(state.myTasks),
            allTasks = patch(state.allTasks)
        )
    }

    private fun removeTaskLocally(taskId: String) {
        state = state.copy(
            myTasks = state.myTasks.filterNot { it.task_id == taskId },
            allTasks = state.allTasks.filterNot { it.task_id == taskId }
        )
    }

    private fun replaceOptimisticTaskId(tempTaskId: String, realTaskId: String) {
        fun patch(list: List<TaskDto>): List<TaskDto> {
            return list.map { task ->
                if (task.task_id == tempTaskId) task.copy(task_id = realTaskId) else task
            }
        }

        state = state.copy(
            myTasks = patch(state.myTasks),
            allTasks = patch(state.allTasks)
        )
    }

    private fun insertCreatedTaskLocally(
        tempTaskId: String,
        title: String,
        description: String,
        assigneeUserId: String,
        reminderType: String?,
        reminderIntervalMinutes: Int?,
        reminderTimeOfDay: String?,
        isUrgent: Boolean
    ) {
        val now = java.time.OffsetDateTime.now().toString()
        val me = state.currentUser ?: return
        val assignee = state.users.firstOrNull { it.user_id == assigneeUserId }

        val optimisticTask = TaskDto(
            task_id = tempTaskId,
            title = title,
            description = description,
            status = "open",
            created_at = now,
            updated_at = now,
            created_by_user_id = me.user_id,
            assignee_user_id = assigneeUserId,
            completed_by_user_id = null,
            completed_at = null,
            cancelled_by_user_id = null,
            cancelled_at = null,
            created_by_name = me.display_name,
            assignee_name = assignee?.display_name ?: assigneeUserId,
            completed_by_name = null,
            cancelled_by_name = null,
            reminder_type = reminderType,
            reminder_interval_minutes = reminderIntervalMinutes,
            reminder_time_of_day = reminderTimeOfDay,
            notification_status = "sent",
            is_urgent = if (isUrgent) 1 else 0
        )

        state = state.copy(
            myTasks = listOf(optimisticTask) + state.myTasks.filterNot { it.task_id == tempTaskId },
            allTasks = if (me.role == "plus" || me.role == "admin") {
                listOf(optimisticTask) + state.allTasks.filterNot { it.task_id == tempTaskId }
            } else {
                state.allTasks
            }
        )
    }


    private fun syncFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener
            val token = task.result ?: return@addOnCompleteListener
            viewModelScope.launch {
            state = state.copy(
                creatingTask = false,
                info = "Задача поставлена в очередь",
                error = null,
                selectedTab = targetTab
            )
            refreshAllInBackground()
        }

    fun completeTask(taskId: String) {
        markTaskCompletedLocally(taskId)
        UrgentTaskNotifier.cancel(getApplication<Application>().applicationContext, taskId)

        viewModelScope.launch {
            when (val res = tasksRepo.completeTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(info = "Задача выполнена", error = null)
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    enqueuePendingOperation {
                        when (tasksRepo.completeTask(taskId)) {
                            is AppResult.Success -> true
                            is AppResult.Error -> false
                        }
                    }

                    state = state.copy(
                        error = null,
                        info = "Выполнение сохранено локально. Отправлю при обновлении."
                    )
                }
            }
        }
    }

    fun updateTask(
        taskId: String,
        title: String,
        description: String,
        assigneeUserId: String,
        reminderType: String? = null,
        reminderIntervalMinutes: Int? = null,
        reminderTimeOfDay: String? = null,
        isUrgent: Boolean = false
    ) {
        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(error = "Заполни название и исполнителя")
            return
        }

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.updateTask(taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay, isUrgent)) {
                is AppResult.Success -> {
                    state = state.copy(loading = false, info = "Задача обновлена")
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    enqueuePendingOperation {
                        when (tasksRepo.updateTask(taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay, isUrgent)) {
                            is AppResult.Success -> true
                            is AppResult.Error -> false
                        }
                    }

                    state = state.copy(
                        loading = false,
                        error = null,
                        info = "Изменение сохранено локально. Отправлю при обновлении."
                    )
                }
            }
        }
    }

    
      fun deleteTask(taskId: String) {
        removeTaskLocally(taskId)
        UrgentTaskNotifier.cancel(getApplication<Application>().applicationContext, taskId)

        viewModelScope.launch {
            state = state.copy(
                loading = true,
                error = null,
                info = "Удаляю задачу..."
            )

            when (val res = tasksRepo.deleteTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        error = null,
                        info = "Задача удалена"
                    )
                    refreshAllInBackground()
                }

                is AppResult.Error -> {
                    enqueuePendingOperation {
                        when (tasksRepo.deleteTask(taskId)) {
                            is AppResult.Success -> true
                            is AppResult.Error -> false
                        }
                    }

                    schedulePendingOperationsFlush()

                    state = state.copy(
                        loading = false,
                        error = null,
                        info = "Задача скрыта локально. Удалю на сервере при обновлении."
                    )
                }
            }
        }
    }

    fun updateOwnDisplayName(displayName: String) {
        if (displayName.isBlank()) {
            state = state.copy(error = "Имя не может быть пустым")
            return
        }

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = authRepo.updateProfile(displayName.trim())) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        currentUser = res.data,
                        error = null,
                        info = "Имя обновлено"
                    )
                }
                is AppResult.Error -> {
                    state = state.copy(
                        loading = false,
                        error = res.message
                    )
                }
            }
        }
    }

    fun adminChangeUserRole(userId: String, role: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.changeUserRole(userId, role)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        users = state.users.map {
                            if (it.user_id == userId) it.copy(role = role) else it
                        },
                        error = null,
                        info = "Роль обновлена"
                    )
                }
                is AppResult.Error -> {
                    val msg = res.message.lowercase()
                    if ("timeout" in msg) {
                        state = state.copy(
                            loading = false,
                            error = null,
                            info = "Загрузка в ожидании данных"
                        )
                    } else {
                        state = state.copy(
                            loading = false,
                            error = res.message
                        )
                    }
                }
            }
        }
    }

    fun adminDeleteUser(userId: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.deleteUser(userId)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        users = state.users.filterNot { it.user_id == userId },
                        error = null,
                        info = "Пользователь удалён"
                    )
                }
                is AppResult.Error -> {
                    val msg = res.message.lowercase()
                    if ("timeout" in msg) {
                        state = state.copy(
                            loading = false,
                            error = null,
                            info = "Загрузка в ожидании данных"
                        )
                    } else {
                        state = state.copy(
                            loading = false,
                            error = res.message
                        )
                    }
                }
            }
        }
    }
}
