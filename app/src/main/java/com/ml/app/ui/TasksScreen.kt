package com.ml.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
            "create" -> vm.loadUsers()
            "all" -> if (state.currentUser.role == "plus" || state.currentUser.role == "admin") vm.loadAllTasks() else vm.loadMyTasks()
            else -> vm.loadMyTasks()
        }
    }

    when (state.selectedTab) {
        "create" -> CreateTaskTab(vm = vm)
        "all" -> TasksListTab(
            titleWhenEmpty = "Задач пока нет",
            tasks = state.allTasks,
            error = state.error,
            onComplete = { vm.completeTask(it) }
        )
        else -> TasksListTab(
            titleWhenEmpty = "Задач пока нет",
            tasks = state.myTasks,
            error = state.error,
            onComplete = { vm.completeTask(it) }
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
                if (state.users.isEmpty()) {
                    vm.loadUsers()
                }
                assigneeExpanded = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = buildString {
                    append("Исполнитель: ")
                    append(assigneeUser?.display_name ?: "Выбрать")
                }
            )
        }

        DropdownMenu(
            expanded = assigneeExpanded,
            onDismissRequest = { assigneeExpanded = false }
        ) {
            state.users.forEach { user ->
                DropdownMenuItem(
                    text = {
                        Text("${user.display_name} (${user.email})")
                    },
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
    onComplete: (String) -> Unit
) {
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

        if (tasks.isEmpty()) {
            Text(titleWhenEmpty)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks) { task ->
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
                        }
                    }
                }
            }
        }
    }
}
