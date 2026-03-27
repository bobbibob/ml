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
        baseUrl = "https://127.0.0.1/",
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
        val newLastTasksTab = when (tab) {
            "my", "all" -> tab
            else -> state.lastTasksTab
        }

        state = state.copy(
            selectedTab = tab,
            lastTasksTab = newLastTasksTab,
            error = null,
            info = null,
            creatingTask = false
        )

        val user = state.currentUser
        when (tab) {
            "my" -> loadMyTasks()
            "all" -> if (user?.role == "plus" || user?.role == "admin") loadAllTasks()
        }
    }

    fun refreshAllInBackground() {
        val user = state.currentUser ?: return

        viewModelScope.launch {
            val allPendingSynced = flushPendingOperationsBeforeRefresh()
            syncPendingCreatesBeforeRefresh()

            if (!allPendingSynced || pendingOperations.isNotEmpty()) {
                return@launch
            }

            state = state.copy(error = null)
            when (state.selectedTab) {
                "all" -> if (user.role == "plus" || user.role == "admin") loadAllTasks() else loadMyTasks()
                else -> loadMyTasks()
            }
        }
    }

    fun handlePushRefresh(taskId: String?, type: String?) {
        val user = state.currentUser ?: return

        if (!taskId.isNullOrBlank() && type == "task_deleted") {
            removeTaskLocally(taskId)
            UrgentTaskNotifier.cancel(getApplication<Application>().applicationContext, taskId)
        }

        viewModelScope.launch {
            delay(250)
            state = state.copy(error = null, info = null)

            kotlin.runCatching {
                when (val myRes = withTimeout(30000) { tasksRepo.getMyTasks() }) {
                    is AppResult.Success -> {
                        val optimisticMyTasks = state.myTasks.filter { it.task_id.startsWith("local_") }
                        val mergedMyTasks = optimisticMyTasks + myRes.data.filterNot { serverTask ->
                            optimisticMyTasks.any { it.task_id == serverTask.task_id }
                        }
                        state = state.copy(
                            myTasks = mergedMyTasks,
                            error = null,
                            info = null
                        )
                        syncUrgentNotifications(mergedMyTasks)
                    }
                    is AppResult.Error -> Unit
                }
            }

            if (user.role == "plus" || user.role == "admin") {
                kotlin.runCatching {
                    when (val allRes = withTimeout(30000) { tasksRepo.getAllTasks() }) {
                        is AppResult.Success -> {
                            val optimisticAllTasks = state.allTasks.filter { it.task_id.startsWith("local_") }
                            val mergedAllTasks = optimisticAllTasks + allRes.data.filterNot { serverTask ->
                                optimisticAllTasks.any { it.task_id == serverTask.task_id }
                            }
                            state = state.copy(
                                allTasks = mergedAllTasks,
                                error = null,
                                info = null
                            )
                        }
                        is AppResult.Error -> Unit
                    }
                }
            }
        }
    }


    private fun syncUrgentNotifications(tasks: List<TaskDto>) {
        val ctx = getApplication<Application>().applicationContext
        val currentUserId = state.currentUser?.user_id ?: return
        UrgentTaskNotifier.syncForTasks(ctx, tasks, currentUserId)
    }

    private val pendingOperations = mutableListOf<suspend () -> Boolean>()
    private var pendingFlushJob: Job? = null

    private fun enqueuePendingOperation(operation: suspend () -> Boolean) {
        pendingOperations.add(operation)
    }

    private fun schedulePendingOperationsFlush(delayMs: Long = 1200L) {
        if (pendingFlushJob?.isActive == true) return

        pendingFlushJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            flushPendingOperationsBeforeRefresh()
        }
    }

    private suspend fun flushPendingOperationsBeforeRefresh(): Boolean {
        if (pendingOperations.isEmpty()) return true

        val ops = pendingOperations.toList()
        pendingOperations.clear()

        var allSynced = true
        for (operation in ops) {
            val synced = try {
                operation()
            } catch (_: Exception) {
                false
            }

            if (!synced) {
                allSynced = false
                pendingOperations.add(operation)
            }
        }

        if (!allSynced) {
            state = state.copy(
                error = null,
                info = "Есть несинхронизированные изменения. Повторю позже."
            )
        }

        return allSynced
    }

    private suspend fun syncPendingCreatesBeforeRefresh() {
        val pendingLocalTasks = (state.myTasks + state.allTasks)
            .distinctBy { it.task_id }
            .filter { it.task_id.startsWith("local_") }

        for (task in pendingLocalTasks) {
            val clientRequestId = task.task_id.removePrefix("local_").trim()
            if (clientRequestId.isBlank()) continue

            when (
                val res = tasksRepo.createTask(
                    title = task.title,
                    description = task.description ?: "",
                    assigneeUserId = task.assignee_user_id,
                    reminderType = task.reminder_type,
                    reminderIntervalMinutes = task.reminder_interval_minutes,
                    reminderTimeOfDay = task.reminder_time_of_day,
                    isUrgent = task.is_urgent == 1,
                    clientRequestId = clientRequestId
                )
            ) {
                is AppResult.Success -> {
                    replaceOptimisticTaskId(task.task_id, res.data)
                }
                is AppResult.Error -> {
                    // оставляем локальную задачу как есть; refresh не должен её терять
                }
            }
        }
    }


    fun loadMyTasks() {
        if (state.loadingTasks) return

        viewModelScope.launch {
            state = state.copy(loadingTasks = true, error = null, info = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getMyTasks() }
                when (res) {
                    is AppResult.Success -> {
                        val optimisticMyTasks = state.myTasks.filter { it.task_id.startsWith("local_") }
                        val mergedMyTasks = optimisticMyTasks + res.data.filterNot { serverTask ->
                            optimisticMyTasks.any { it.task_id == serverTask.task_id }
                        }

                        state = state.copy(
                            loadingTasks = false,
                            myTasks = mergedMyTasks,
                            error = null,
                            info = null
                        )
                        syncUrgentNotifications(mergedMyTasks)
                    }
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
        if (state.loadingTasks) return

        viewModelScope.launch {
            state = state.copy(loadingTasks = true, error = null, info = null)
            try {
                val res = withTimeout(30000) { tasksRepo.getAllTasks() }
                when (res) {
                    is AppResult.Success -> {
                        val optimisticAllTasks = state.allTasks.filter { it.task_id.startsWith("local_") }
                        val mergedAllTasks = optimisticAllTasks + res.data.filterNot { serverTask ->
                            optimisticAllTasks.any { it.task_id == serverTask.task_id }
                        }

                        state = state.copy(
                            loadingTasks = false,
                            allTasks = mergedAllTasks,
                            error = null,
                            info = null
                        )
                    }
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
                    enqueuePendingOperation {
                        when (tasksRepo.remindTask(taskId)) {
                            is AppResult.Success -> true
                            is AppResult.Error -> false
                        }
                    }

                    state = state.copy(
                        loading = false,
                        error = null,
                        info = "Напоминание сохранено локально. Отправлю при обновлении."
                    )
                }
            }
        }
    }


    fun loadOpenedTaskFromPush(taskId: String) {
        viewModelScope.launch {
            repeat(4) { attempt ->
                when (val res = tasksRepo.getTaskById(taskId)) {
                    is AppResult.Success -> {
                        val freshTask = res.data

                        fun patch(list: List<TaskDto>): List<TaskDto> =
                            list.map { if (it.task_id == freshTask.task_id) freshTask else it }

                        state = state.copy(
                            openedTaskFromPush = freshTask,
                            myTasks = patch(state.myTasks),
                            allTasks = patch(state.allTasks),
                            error = null
                        )
                        val me = state.currentUser?.user_id
                        if (freshTask.assignee_user_id == me) {
                            kotlin.runCatching { tasksRepo.markTaskSeen(freshTask.task_id) }
                        }
                        if (freshTask.is_urgent == 1 && freshTask.assignee_user_id == me && freshTask.status == "open") {
                            UrgentTaskNotifier.show(getApplication<Application>().applicationContext, freshTask, me)
                        }
                        return@launch
                    }
                    is AppResult.Error -> {
                        if (attempt < 3) {
                            delay(150)
                        } else {
                            val msg = res.message.lowercase()
                            if ("404" in msg || "task_not_found" in msg || "не удалось загрузить задачу" in msg || "not found" in msg) {
                                state = state.copy(
                                    openedTaskFromPush = null,
                                    error = null
                                )
                                refreshAllInBackground()
                            } else {
                                state = state.copy(
                                    openedTaskFromPush = null,
                                    error = res.message
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearOpenedTaskFromPush() {
        state = state.copy(openedTaskFromPush = null)
    }

    fun markTaskSeen(taskId: String) {
        viewModelScope.launch {
            kotlin.runCatching {
                tasksRepo.markTaskSeen(taskId)
            }
        }
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
        reminderTimeOfDay: String? = null,
        isUrgent: Boolean = false
    ) {
        if (state.creatingTask) return

        if (title.isBlank() || assigneeUserId.isBlank()) {
            state = state.copy(
                error = "Заполни название и исполнителя",
                creatingTask = false
            )
            return
        }

        val cleanTitle = title.trim()
        val cleanDescription = description.trim()
        val clientRequestId = java.util.UUID.randomUUID().toString()
        val tempTaskId = "local_" + clientRequestId
        val targetTab = state.lastTasksTab

        insertCreatedTaskLocally(
            tempTaskId = tempTaskId,
            title = cleanTitle,
            description = cleanDescription,
            assigneeUserId = assigneeUserId,
            reminderType = reminderType,
            reminderIntervalMinutes = reminderIntervalMinutes,
            reminderTimeOfDay = reminderTimeOfDay,
            isUrgent = isUrgent
        )

        TaskSyncScheduler.enqueueCreate(
            context = getApplication<Application>().applicationContext,
            title = cleanTitle,
            description = cleanDescription,
            assigneeUserId = assigneeUserId,
            reminderType = reminderType,
            reminderIntervalMinutes = reminderIntervalMinutes,
            reminderTimeOfDay = reminderTimeOfDay,
            isUrgent = isUrgent,
            clientRequestId = clientRequestId
        )

        state = state.copy(
            creatingTask = true,
            error = null,
            info = "Создание задачи...",
            selectedTab = targetTab
        )

        viewModelScope.launch {
            when (
                val res = tasksRepo.createTask(
                    title = cleanTitle,
                    description = cleanDescription,
                    assigneeUserId = assigneeUserId,
                    reminderType = reminderType,
                    reminderIntervalMinutes = reminderIntervalMinutes,
                    reminderTimeOfDay = reminderTimeOfDay,
                    isUrgent = isUrgent,
                    clientRequestId = clientRequestId
                )
            ) {
                is AppResult.Success -> {
                    replaceOptimisticTaskId(tempTaskId, res.data)
                    state = state.copy(
                        creatingTask = false,
                        info = "Задача создана",
                        error = null,
                        selectedTab = targetTab
                    )
                    refreshAllInBackground()
                }
                is AppResult.Error -> {
                    val msg = res.message.lowercase()
                    if ("timeout" in msg || "слишком долго" in msg) {
                        state = state.copy(
                            creatingTask = false,
                            error = null,
                            info = "Задача ещё не подтверждена сервером",
                            selectedTab = targetTab
                        )

                        delay(1500)

                        when (
                            val retryRes = tasksRepo.createTask(
                                title = cleanTitle,
                                description = cleanDescription,
                                assigneeUserId = assigneeUserId,
                                reminderType = reminderType,
                                reminderIntervalMinutes = reminderIntervalMinutes,
                                reminderTimeOfDay = reminderTimeOfDay,
                                isUrgent = isUrgent,
                                clientRequestId = clientRequestId
                            )
                        ) {
                            is AppResult.Success -> {
                                replaceOptimisticTaskId(tempTaskId, retryRes.data)
                                state = state.copy(
                                    creatingTask = false,
                                    error = null,
                                    info = "Задача подтверждена сервером",
                                    selectedTab = targetTab
                                )
                                refreshAllInBackground()
                            }
                            is AppResult.Error -> {
                                enqueuePendingOperation {
                                    when (
                                        val pendingRes = tasksRepo.createTask(
                                            title = cleanTitle,
                                            description = cleanDescription,
                                            assigneeUserId = assigneeUserId,
                                            reminderType = reminderType,
                                            reminderIntervalMinutes = reminderIntervalMinutes,
                                            reminderTimeOfDay = reminderTimeOfDay,
                                            isUrgent = isUrgent,
                                            clientRequestId = clientRequestId
                                        )
                                    ) {
                                        is AppResult.Success -> {
                                            replaceOptimisticTaskId(tempTaskId, pendingRes.data)
                                            true
                                        }
                                        is AppResult.Error -> false
                                    }
                                }

                                state = state.copy(
                                    creatingTask = false,
                                    error = null,
                                    info = "Локальная задача сохранена. Отправлю при обновлении.",
                                    selectedTab = targetTab
                                )
                            }
                        }
                    } else {
                        enqueuePendingOperation {
                            when (
                                val pendingRes = tasksRepo.createTask(
                                    title = cleanTitle,
                                    description = cleanDescription,
                                    assigneeUserId = assigneeUserId,
                                    reminderType = reminderType,
                                    reminderIntervalMinutes = reminderIntervalMinutes,
                                    reminderTimeOfDay = reminderTimeOfDay,
                                    isUrgent = isUrgent,
                                    clientRequestId = clientRequestId
                                )
                            ) {
                                is AppResult.Success -> {
                                    replaceOptimisticTaskId(tempTaskId, pendingRes.data)
                                    true
                                }
                                is AppResult.Error -> false
                            }
                        }

                        state = state.copy(
                            creatingTask = false,
                            error = null,
                            info = "Локальная задача сохранена. Отправлю при обновлении.",
                            selectedTab = targetTab
                        )
                    }
                }
            }
        }
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
