package com.ml.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ml.app.auth.GoogleAuthManager
import kotlinx.coroutines.launch

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

    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.currentUser?.let { user ->
            Text("${user.display_name} • ${user.role}")
        } ?: run {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val idToken = GoogleAuthManager(ctx).signIn()
                            if (!idToken.isNullOrBlank()) {
                                vm.loginWithGoogleToken(idToken)
                            }
                        } catch (_: Exception) {
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Войти через Google")
            }
        }

        state.error?.let {
            Text("Ошибка: $it")
        }

        state.info?.let {
            Text(it)
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                val assigneeId = state.currentUser?.user_id.orEmpty()
                vm.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    assigneeUserId = assigneeId
                )
                taskTitle = ""
                taskDescription = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.currentUser != null
        ) {
            Text("Создать тестовую задачу себе")
        }

        Button(
            onClick = { vm.loadMyTasks() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.currentUser != null
        ) {
            Text("Обновить мои задачи")
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.myTasks.isEmpty()) {
            Text("Задач пока нет")
        } else {
            state.myTasks.forEach { task ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    if (!task.description.isNullOrBlank()) {
                        Text(task.description)
                    }
                    Text("Статус: ${task.status}")
                    Text("Исполнитель: ${task.assignee_name}")

                    if (!task.completed_by_name.isNullOrBlank() &&
                        task.completed_by_user_id != task.assignee_user_id
                    ) {
                        Text("Отметил выполненной: ${task.completed_by_name}")
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = { vm.completeTask(task.task_id) },
                        enabled = task.status == "open"
                    ) {
                        Text("Выполнено")
                    }
                }
            }
        }
    }
}
