package com.ml.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ml.app.data.remote.dto.HistoryItemDto
import com.ml.app.data.remote.dto.TaskDto
import com.ml.app.data.remote.dto.UserDto

@Composable
fun TasksScreen(
    onBack: () -> Unit,
    vm: TasksViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        vm.init()
    }

    val state = vm.state

    var loginEmail by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }

    var regName by remember { mutableStateOf("") }
    var regEmail by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }

    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }
    var selectedAssigneeId by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "Задачи",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onBack) {
                Text("Назад")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        state.error?.let {
            Text("Ошибка: $it")
            Spacer(modifier = Modifier.height(8.dp))
        }

        state.info?.let {
            Text(it)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.currentUser == null) {
            Text("Вход")
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = loginEmail,
                onValueChange = { loginEmail = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = loginPassword,
                onValueChange = { loginPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пароль") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { vm.login(loginEmail, loginPassword) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Войти")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider()
            Spacer(modifier = Modifier.height(24.dp))

            Text("Регистрация")
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = regName,
                onValueChange = { regName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Имя") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = regEmail,
                onValueChange = { regEmail = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = regPassword,
                onValueChange = { regPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пароль") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { vm.register(regName, regEmail, regPassword) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Создать пользователя")
            }

            return
        }

        val user = state.currentUser

        Text("${user.display_name} • ${user.role}")
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.selectTab("my") }) {
                Text("Мои")
            }

            OutlinedButton(onClick = { vm.selectTab("create") }) {
                Text("Создать")
            }

            if (user.role == "plus" || user.role == "admin") {
                OutlinedButton(onClick = { vm.selectTab("all") }) {
                    Text("Все")
                }

                OutlinedButton(onClick = { vm.selectTab("history") }) {
                    Text("История")
                }
            }

            if (user.role == "admin") {
                OutlinedButton(onClick = { vm.selectTab("users") }) {
                    Text("Пользователи")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (state.selectedTab) {
            "my" -> {
                Button(onClick = { vm.loadMyTasks() }) {
                    Text("Обновить")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TaskList(
                    tasks = state.myTasks,
                    currentUser = user,
                    users = state.users,
                    onComplete = { vm.completeTask(it) },
                    onCancel = { vm.cancelTask(it) },
                    onReassign = { taskId, assigneeId -> vm.reassignTask(taskId, assigneeId) }
                )
            }

            "all" -> {
                Button(onClick = { vm.loadAllTasks() }) {
                    Text("Обновить")
                }
                Spacer(modifier = Modifier.height(12.dp))
                TaskList(
                    tasks = state.allTasks,
                    currentUser = user,
                    users = state.users,
                    onComplete = { vm.completeTask(it) },
                    onCancel = { vm.cancelTask(it) },
                    onReassign = { taskId, assigneeId -> vm.reassignTask(taskId, assigneeId) }
                )
            }

            "create" -> {
                if (selectedAssigneeId.isBlank() && state.users.isNotEmpty()) {
                    selectedAssigneeId = state.users.first().user_id
                }

                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { taskTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название задачи") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = taskDescription,
                    onValueChange = { taskDescription = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Описание") }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Исполнитель:")

                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(state.users) { u ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${u.display_name} (${u.role})")
                            OutlinedButton(onClick = { selectedAssigneeId = u.user_id }) {
                                Text(if (selectedAssigneeId == u.user_id) "Выбран" else "Выбрать")
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        vm.createTask(
                            title = taskTitle,
                            description = taskDescription,
                            assigneeUserId = selectedAssigneeId
                        )
                        taskTitle = ""
                        taskDescription = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Создать задачу")
                }
            }

            "history" -> {
                Button(onClick = { vm.loadHistory() }) {
                    Text("Обновить")
                }
                Spacer(modifier = Modifier.height(12.dp))
                HistoryList(state.history)
            }

            "users" -> {
                Button(onClick = { vm.loadUsers() }) {
                    Text("Обновить")
                }
                Spacer(modifier = Modifier.height(12.dp))
                UsersList(
                    users = state.users,
                    onChangeRole = { userId, role -> vm.changeRole(userId, role) }
                )
            }
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<TaskDto>,
    currentUser: UserDto,
    users: List<UserDto>,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onReassign: (String, String) -> Unit
) {
    if (tasks.isEmpty()) {
        Text("Задач пока нет")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tasks) { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    if (!task.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(task.description)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Статус: ${task.status}")
                    Text("Создал: ${task.created_by_name}")
                    Text("Исполнитель: ${task.assignee_name}")

                    if (!task.completed_by_name.isNullOrBlank() &&
                        task.completed_by_user_id != task.assignee_user_id
                    ) {
                        Text("Отметил выполненной: ${task.completed_by_name}")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val canComplete =
                            (currentUser.role == "basic" && task.assignee_user_id == currentUser.user_id) ||
                            currentUser.role == "plus" ||
                            currentUser.role == "admin"

                        if (task.status == "open" && canComplete) {
                            Button(onClick = { onComplete(task.task_id) }) {
                                Text("Выполнено")
                            }
                        }

                        if (task.status == "open" && (currentUser.role == "plus" || currentUser.role == "admin")) {
                            OutlinedButton(onClick = { onCancel(task.task_id) }) {
                                Text("Отменить")
                            }
                        }
                    }

                    if (task.status == "open" && (currentUser.role == "plus" || currentUser.role == "admin")) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Переназначить:")

                        users.forEach { u ->
                            if (u.user_id != task.assignee_user_id) {
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(onClick = { onReassign(task.task_id, u.user_id) }) {
                                    Text(u.display_name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryList(items: List<HistoryItemDto>) {
    if (items.isEmpty()) {
        Text("История пуста")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(item.action_type, style = MaterialTheme.typography.titleMedium)
                    Text("Кто: ${item.actor_name}")
                    Text("Когда: ${item.created_at}")
                }
            }
        }
    }
}

@Composable
private fun UsersList(
    users: List<UserDto>,
    onChangeRole: (String, String) -> Unit
) {
    if (users.isEmpty()) {
        Text("Пользователей нет")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(users) { u ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(u.display_name, style = MaterialTheme.typography.titleMedium)
                    Text(u.email)
                    Text("Роль: ${u.role}")

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onChangeRole(u.user_id, "basic") }) {
                            Text("basic")
                        }
                        OutlinedButton(onClick = { onChangeRole(u.user_id, "plus") }) {
                            Text("plus")
                        }
                        OutlinedButton(onClick = { onChangeRole(u.user_id, "admin") }) {
                            Text("admin")
                        }
                    }
                }
            }
        }
    }
}
