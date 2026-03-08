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
        baseUrl = "http://ml.gamer.gd/api/",
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
        state = state.copy(selectedTab = tab)
    }

    fun refreshAll() {
        val user = state.currentUser ?: return
        loadMyTasks()
        loadUsers()
        if (user.role == "plus" || user.role == "admin") {
            loadAllTasks()
            loadHistory()
        }
    }

    fun loadMyTasks() {
        viewModelScope.launch {
            when (val res = tasksRepo.getMyTasks()) {
                is AppResult.Success -> state = state.copy(myTasks = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun loadAllTasks() {
        viewModelScope.launch {
            when (val res = tasksRepo.getAllTasks()) {
                is AppResult.Success -> state = state.copy(allTasks = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
            }
        }
    }

    fun loadUsers() {
        viewModelScope.launch {
            when (val res = tasksRepo.getUsers()) {
                is AppResult.Success -> state = state.copy(users = res.data, error = null)
                is AppResult.Error -> state = state.copy(error = res.message)
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
                    state = state.copy(loading = false, info = "Задача создана")
                    refreshAll()
                }
                is AppResult.Error -> {
                    state = state.copy(loading = false, error = res.message)
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
}
