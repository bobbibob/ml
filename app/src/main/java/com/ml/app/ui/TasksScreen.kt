package com.ml.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ml.app.auth.GoogleAuthManager
import com.ml.app.data.remote.dto.TaskDto
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val TextBlack = Color(0xFF111111)
private val DoneGreen = Color(0xFF2E7D32)

private fun fmtTaskDateTime(v: String?): String {
    if (v.isNullOrBlank()) return ""
    return try {
        val dt = OffsetDateTime.parse(v)
        dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    } catch (_: Exception) {
        v
    }
}

@Composable
fun TasksScreen(
    onBack: () -> Unit,
    vm: TasksViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        vm.init()
    }

    val state = vm.state
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    if (state.currentUser == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            state.error?.let { Text("Ошибка: $it") }
            state.info?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        try {
                            val idToken = GoogleAuthManager(ctx).signIn()
                            if (!idToken.isNullOrBlank()) {
                                vm.loginWithGoogleToken(idToken)
                            }
                        } catch (e: Exception) {
                            vm.setError("Ошибка входа: ${e.message ?: "unknown"}")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Войти через Google")
            }
        }
        return
    }

    LaunchedEffect(state.currentUser.user_id, state.selectedTab) {
        when (state.selectedTab) {
            "create" -> Unit
            "all" -> if (state.currentUser.role == "plus" || state.currentUser.role == "admin") vm.loadAllTasks() else vm.loadMyTasks()
            else -> vm.loadMyTasks()
        }
    }

    if (state.selectedTab == "create") {
        BackHandler {
            if (state.currentUser.role == "plus" || state.currentUser.role == "admin") {
                vm.selectTab("all")
            } else {
                vm.selectTab("my")
            }
        }
    }

    when (state.selectedTab) {
        "create" -> CreateTaskTab(vm = vm)
        "all" -> TasksListTab(
            titleWhenEmpty = "Задач пока нет",
            tasks = state.allTasks,
            error = state.error,
            currentUserId = state.currentUser.user_id,
            currentUserRole = state.currentUser.role,
            onComplete = { vm.completeTask(it) },
            onEdit = { vm.loadUsers() },
            onSaveEdit = { taskId, title, description, assigneeUserId ->
                vm.updateTask(taskId, title, description, assigneeUserId)
            },
            onDelete = { vm.deleteTask(it) },
            users = state.users,
            state = state
        )
        else -> TasksListTab(
            titleWhenEmpty = "Задач пока нет",
            tasks = state.myTasks,
            error = state.error,
            currentUserId = state.currentUser.user_id,
            currentUserRole = state.currentUser.role,
            onComplete = { vm.completeTask(it) },
            onEdit = { vm.loadUsers() },
            onSaveEdit = { taskId, title, description, assigneeUserId ->
                vm.updateTask(taskId, title, description, assigneeUserId)
            },
            onDelete = { vm.deleteTask(it) },
            users = state.users,
            state = state
        )
    }
}

@Composable
private fun CreateTaskTab(vm: TasksViewModel) {
    val state = vm.state
    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }
    var assigneeExpanded by remember { mutableStateOf(false) }
    var assigneeUserId by remember(state.currentUser?.user_id) {
        mutableStateOf(state.currentUser?.user_id.orEmpty())
    }

    val assigneeUser = state.users.firstOrNull { it.user_id == assigneeUserId } ?: state.currentUser

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.error?.let { Text("Ошибка: $it") }
        state.info?.let { Text(it) }

        OutlinedTextField(
            value = taskTitle,
            onValueChange = { taskTitle = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название задачи") }
        )

        OutlinedTextField(
            value = taskDescription,
            onValueChange = { taskDescription = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Описание") }
        )

        Button(
            onClick = {
                if (state.users.isEmpty()) vm.loadUsers()
                assigneeExpanded = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("Исполнитель: ${assigneeUser?.display_name ?: "Выбрать"}")
        }

        DropdownMenu(
            expanded = assigneeExpanded,
            onDismissRequest = { assigneeExpanded = false }
        ) {
            state.users.forEach { user ->
                DropdownMenuItem(
                    text = { Text("${user.display_name} (${user.email})") },
                    onClick = {
                        assigneeUserId = user.user_id
                        assigneeExpanded = false
                    }
                )
            }
        }

        Button(
            onClick = {
                vm.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    assigneeUserId = assigneeUserId.ifBlank { state.currentUser?.user_id.orEmpty() }
                )
                taskTitle = ""
                taskDescription = ""
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Создать задачу")
        }
    }
}

@Composable
private fun TasksListTab(
    titleWhenEmpty: String,
    tasks: List<TaskDto>,
    error: String?,
    currentUserId: String,
    currentUserRole: String,
    onComplete: (String) -> Unit,
    onEdit: () -> Unit,
    onSaveEdit: (String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    users: List<com.ml.app.data.remote.dto.UserDto>,
    state: TasksUiState
) {
    var editTask by remember { mutableStateOf<TaskDto?>(null) }
    var deleteTask by remember { mutableStateOf<TaskDto?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        error?.let {
            Text(
                text = "Ошибка: $it",
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (state.loading && tasks.isEmpty()) {
            Text("Загружаем задачи...")
        } else if (tasks.isEmpty()) {
            Text(titleWhenEmpty)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks) { task ->
                    val canDelete = currentUserRole == "admin" || task.created_by_user_id == currentUserId
                    val canEdit = canDelete && task.status == "open"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Text(
                                text = task.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextBlack
                            )

                            if (!task.description.isNullOrBlank()) {
                                Text(
                                    text = task.description,
                                    color = TextBlack,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            Text(
                                text = "Создано: ${fmtTaskDateTime(task.created_at)}",
                                color = TextBlack,
                                modifier = Modifier.padding(top = 8.dp)
                            )

                            Text(
                                text = "Статус: ${task.status}",
                                color = TextBlack
                            )

                            Text(
                                text = "Исполнитель: ${task.assignee_name}",
                                color = TextBlack
                            )

                            Text(
                                text = "Создал: ${task.created_by_name}",
                                color = TextBlack
                            )

                            if (!task.completed_by_name.isNullOrBlank() &&
                                task.completed_by_user_id != task.assignee_user_id
                            ) {
                                Text(
                                    text = "Отметил выполненной: ${task.completed_by_name}",
                                    color = TextBlack,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            if (!task.completed_at.isNullOrBlank()) {
                                Text(
                                    text = "Выполнено: ${fmtTaskDateTime(task.completed_at)}",
                                    color = DoneGreen,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            if (task.status == "open") {
                                Button(
                                    onClick = { onComplete(task.task_id) },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text("Выполнено")
                                }
                            } else {
                                Text(
                                    text = "ВЫПОЛНЕНО",
                                    color = DoneGreen,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }

                            if (canEdit) {
                                Button(
                                    onClick = {
                                        onEdit()
                                        editTask = task
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.padding(top = 12.dp)
                                ) {
                                    Text("Редактировать")
                                }
                            }

                            if (canDelete) {
                                OutlinedButton(
                                    onClick = { deleteTask = task },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.padding(top = if (canEdit) 8.dp else 12.dp)
                                ) {
                                    Text("Удалить")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editTask?.let { task ->
        var title by remember(task.task_id) { mutableStateOf(task.title) }
        var description by remember(task.task_id) { mutableStateOf(task.description ?: "") }
        var assigneeUserId by remember(task.task_id) { mutableStateOf(task.assignee_user_id) }
        var expanded by remember(task.task_id) { mutableStateOf(false) }
        val assigneeUser = users.firstOrNull { it.user_id == assigneeUserId }

        AlertDialog(
            onDismissRequest = { editTask = null },
            title = { Text("Редактировать задачу") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название") }
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Описание") }
                    )

                    Button(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Исполнитель: ${assigneeUser?.display_name ?: task.assignee_name}")
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        users.forEach { user ->
                            DropdownMenuItem(
                                text = { Text("${user.display_name} (${user.email})") },
                                onClick = {
                                    assigneeUserId = user.user_id
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveEdit(task.task_id, title, description, assigneeUserId)
                        editTask = null
                    }
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { editTask = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    deleteTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTask = null },
            title = { Text("Удалить задачу?") },
            text = { Text(task.title) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(task.task_id)
                        deleteTask = null
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTask = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
