package com.ml.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val currentUser: UserDto? = null,
    val error: String? = null,
    val info: String? = null,
    val myTasks: List<TaskDto> = emptyList(),
    val allTasks: List<TaskDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val history: List<HistoryItemDto> = emptyList(),
    val selectedTab: String = "my"
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

    fun init() {
        if (state.currentUser != null) return
        if (!authRepo.isLoggedIn()) return

        viewModelScope.launch {
            when (val res = authRepo.me()) {
                is AppResult.Success -> {
                    state = state.copy(currentUser = res.data, error = null)
                    refreshAll()
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
                    refreshAll()
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
        state = state.copy(selectedTab = tab, error = null, info = null)
        val user = state.currentUser
        when (tab) {
            "my" -> loadMyTasks()
            "all" -> if (user?.role == "plus" || user?.role == "admin") loadAllTasks()
        }
    }

    fun refreshAll() {
        val user = state.currentUser ?: return
        state = state.copy(error = null)
        when (state.selectedTab) {
            "all" -> if (user.role == "plus" || user.role == "admin") loadAllTasks() else loadMyTasks()
            else -> loadMyTasks()
        }
    }

    fun loadMyTasks() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            when (val res = tasksRepo.getMyTasks()) {
                is AppResult.Success -> state = state.copy(loading = false, myTasks = res.data, error = null)
                is AppResult.Error -> state = state.copy(loading = false, error = res.message)
            }
        }
    }

    fun loadAllTasks() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getAllTasks() }
                when (res) {
                    is AppResult.Success -> state = state.copy(loading = false, allTasks = res.data, error = null)
                    is AppResult.Error -> state = state.copy(loading = false, error = res.message)
                }
            } catch (_: Exception) {
                state = state.copy(loading = false, error = "Все задачи загружаются слишком долго")
            }
        }
    }

    fun loadUsers() {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null)
            when (val res = tasksRepo.getUsers()) {
                is AppResult.Success -> state = state.copy(loading = false, users = res.data, error = null)
                is AppResult.Error -> state = state.copy(loading = false, error = res.message)
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            when (val res = tasksRepo.getHistory()) {
                is AppResult.Success -> state = state.copy(history = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun createTask(title: String, description: String, assigneeUserId: String) {
        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(error = "Заполни название и исполнителя")
            return
        }

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.createTask(title, description, assigneeUserId)) {
                is AppResult.Success -> {
                    state = state.copy(
                        loading = false,
                        info = "Задача создана",
                        selectedTab = "my"
                    )
                    refreshAll()
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

    fun completeTask(taskId: String) {
        viewModelScope.launch {
            when (val res = tasksRepo.completeTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(info = "Задача выполнена", error = null)
                    refreshAll()
                }
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }


    fun updateTask(taskId: String, title: String, description: String, assigneeUserId: String) {
        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(error = "Заполни название и исполнителя")
            return
        }

        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.updateTask(taskId, title, description, assigneeUserId)) {
                is AppResult.Success -> {
                    state = state.copy(loading = false, info = "Задача обновлена")
                    refreshAll()
                }
                is AppResult.Error -> {
                    state = state.copy(loading = false, error = res.message)
                }
            }
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            state = state.copy(loading = true, error = null, info = null)
            when (val res = tasksRepo.deleteTask(taskId)) {
                is AppResult.Success -> {
                    state = state.copy(loading = false, info = "Задача удалена")
                    refreshAll()
                }
                is AppResult.Error -> {
                    state = state.copy(loading = false, error = res.message)
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

    fun setError(message: String) {
        state = state.copy(error = message)
    }
}
