package com.ml.app.ui

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
    val openedTaskFromPush: TaskDto? = null
)

class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val session = PrefsSessionStorage(app.applicationContext)
    private val api = ApiModule.createApi(
        baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
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


    private fun syncFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) return@addOnCompleteListener
            val token = task.result ?: return@addOnCompleteListener
            viewModelScope.launch {
                authRepo.saveFcmToken(token)
            }
        }
    }

    fun setError(message: String) {
        state = state.copy(error = message)
    }

    fun init() {
        if (state.currentUser != null) return
        if (!authRepo.isLoggedIn()) return

        viewModelScope.launch {
            when (val res = authRepo.me()) {
                is AppResult.Success -> {
                    state = state.copy(currentUser = res.data, error = null)
                    syncFcmToken()
                    refreshAllInBackground()
                    loadUsers(force = false)
                }
                is AppResult.Error -> {
                    authRepo.logout()
                    state = state.copy(currentUser = null, error = res.message)
                }
            }
        }
    }

    fun loginWithGoogleToken(idToken: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = authRepo.googleLogin(idToken)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        currentUser = res.data,
                        info = "Вход выполнен"
                    )
                    syncFcmToken()
                    refreshAllInBackground()
                    loadUsers(force = false)
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

    fun sendPush(userId: String?, title: String, body: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.sendPush(userId, title, body)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        error = null,
                        info = res.data
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

    fun logout() {
        authRepo.logout()
        state = TasksUiState()
    }

    fun selectTab(tab: String) {
        state = state.copy(selectedTab = tab, error = null, info = null, creatingTask = false)
        val user = state.currentUser
        when (tab) {
            "my" -> loadMyTasks()
            "all" -> if (user?.role == "plus" || user?.role == "admin") loadAllTasks()
        }
    }

    fun refreshAllInBackground() {
        val user = state.currentUser ?: return
        state = state.copy(error = null)
        when (state.selectedTab) {
            "all" -> if (user.role == "plus" || user.role == "admin") loadAllTasks() else loadMyTasks()
            else -> loadMyTasks()
        }
    }

    private fun refreshAllInBackground() {
        viewModelScope.launch {
            kotlin.runCatching { refreshAllInBackground() }
        }
    }

    fun loadMyTasks() {
        viewModelScope.launch {
            state = state.copy(loadingTasks = true, error = null, info = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getMyTasks() }
                when (res) {
                    is AppResult.Success -> state = state.copy(
                        loadingTasks = false,
                        myTasks = res.data,
                        error = null,
                        info = null
                    )
                    is AppResult.Error -> {
                        val msg = res.message.lowercase()
                        if ("timeout" in msg) {
                            state = state.copy(
                                loadingTasks = false,
                                error = null,
                                info = "Загрузка в ожидании данных"
                            )
                        } else {
                            state = state.copy(
                                loadingTasks = false,
                                error = res.message
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                state = state.copy(
                    loadingTasks = false,
                    error = null,
                    info = "Загрузка в ожидании данных"
                )
            }
        }
    }

    fun loadAllTasks() {
        viewModelScope.launch {
            state = state.copy(loadingTasks = true, error = null, info = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getAllTasks() }
                when (res) {
                    is AppResult.Success -> state = state.copy(
                        loadingTasks = false,
                        allTasks = res.data,
                        error = null,
                        info = null
                    )
                    is AppResult.Error -> {
                        val msg = res.message.lowercase()
                        if ("timeout" in msg) {
                            state = state.copy(
                                loadingTasks = false,
                                error = null,
                                info = "Загрузка в ожидании данных"
                            )
                        } else {
                            state = state.copy(
                                loadingTasks = false,
                                error = res.message
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                state = state.copy(
                    loadingTasks = false,
                    error = null,
                    info = "Загрузка в ожидании данных"
                )
            }
        }
    }

    fun loadUsers(force: Boolean = false) {
        if (state.loadingUsers) return
        if (!force && state.users.isNotEmpty()) return

        viewModelScope.launch {
            state = state.copy(loadingUsers = true, error = null, info = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getUsers() }
                when (res) {
                    is AppResult.Success -> state = state.copy(
                        loadingUsers = false,
                        users = res.data,
                        error = null,
                        info = null
                    )
                    is AppResult.Error -> {
                        val msg = res.message.lowercase()
                        if ("timeout" in msg) {
                            state = state.copy(
                                loadingUsers = false,
                                error = null,
                                info = "Загрузка исполнителей…"
                            )
                        } else {
                            state = state.copy(
                                loadingUsers = false,
                                error = res.message
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                state = state.copy(
                    loadingUsers = false,
                    error = null,
                    info = "Загрузка исполнителей…"
                )
            }
        }
    }


    fun remindTask(taskId: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.remindTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        error = null,
                        info = res.data
                    )
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    val msg = res.message.lowercase()
                    if ("timeout" in msg) {
                        state = state.copy(
                            loading = false,
                            error = null,
                            info = "Напоминание отправляется"
                        )
                        refreshAllInBackground()
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


    fun loadOpenedTaskFromPush(taskId: String) {
        viewModelScope.launch {
            when (val res = tasksRepo.getTaskById(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(
                        openedTaskFromPush = res.data,
                        error = null
                    )
                }
                is AppResult.Error -> {
                    state = state.copy(
                        openedTaskFromPush = null,
                        error = res.message
                    )
                }
            }
        }
    }

    fun clearOpenedTaskFromPush() {
        state = state.copy(openedTaskFromPush = null)
    }

    fun loadHistory() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getHistory() }
                when (res) {
                    is AppResult.Success -> state = state.copy(
                        loading = false,
                        history = res.data,
                        error = null,
                        info = null
                    )
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
            } catch (_: Exception) {
                state = state.copy(
                    loading = false,
                    error = null,
                    info = "Загрузка в ожидании данных"
                )
            }
        }
    }

    fun createTask(
        title: String,
        description: String,
        assigneeUserId: String,
        reminderType: String? = null,
        reminderIntervalMinutes: Int? = null,
        reminderTimeOfDay: String? = null
    ) {
        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(
                error = "Заполни название и исполнителя",
                creatingTask = false
            )
            return
        }

        viewModelScope.launch {
            state = state.copy(
                creatingTask = true,
                error = null,
                info = null
            )

            when (
                val res = tasksRepo.createTask(
                    title,
                    description,
                    assigneeUserId,
                    reminderType,
                    reminderIntervalMinutes,
                    reminderTimeOfDay
                )
            ) {
                is AppResult.Success -> {
                    state = state.copy(
                        creatingTask = false,
                        info = "Задача создана",
                        error = null,
                        selectedTab = "my"
                    )
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    state = state.copy(
                        creatingTask = false,
                        error = res.message
                    )
                }
            }
        }
    }

    fun completeTask(taskId: String) {
        markTaskCompletedLocally(taskId)

        viewModelScope.launch {
            when (val res = tasksRepo.completeTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(info = "Задача выполнена", error = null)
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    state = state.copy(error = res.message)
                    refreshAllInBackground()
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
        reminderTimeOfDay: String? = null
    ) {
        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(error = "Заполни название и исполнителя")
            return
        }

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.updateTask(taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay)) {
                is AppResult.Success -> {
                    state = state.copy(loading = false, info = "Задача обновлена")
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    state = state.copy(loading = false, error = res.message)
                }
            }
        }
    }

    fun deleteTask(taskId: String) {
        removeTaskLocally(taskId)

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
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
                    val msg = res.message.lowercase()
                    if ("timeout" in msg) {
                        state = state.copy(
                            loading = false,
                            error = null,
                            info = "Удаление выполняется"
                        )
                        refreshAllInBackground()
                    } else {
                        state = state.copy(
                            loading = false,
                            error = res.message
                        )
                        refreshAllInBackground()
                    }
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
