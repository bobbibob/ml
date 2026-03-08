package com.ml.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ml.app.auth.GoogleAuthManager
import kotlinx.coroutines.launch

private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)

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
            state.error?.let {
                Text("Ошибка: $it")
                Spacer(modifier = Modifier.height(12.dp))
            }

            state.info?.let {
                Text(it)
                Spacer(modifier = Modifier.height(12.dp))
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Войти через Google")
            }
        }
        return
    }

    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                state.error?.let {
                    Text("Ошибка: $it")
                }

                state.info?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MercadoBlue),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (state.currentUser.display_name.firstOrNull()?.uppercase() ?: "U"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

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
                vm.createTask(
                    title = taskTitle,
                    description = taskDescription,
                    assigneeUserId = state.currentUser.user_id
                )
                taskTitle = ""
                taskDescription = ""
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Создать тестовую задачу себе")
        }

        Button(
            onClick = { vm.loadMyTasks() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("Обновить мои задачи")
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (state.myTasks.isEmpty()) {
            Text("Задач пока нет")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.myTasks) { task ->
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
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = task.description,
                                    color = TextBlack
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Статус: ${task.status}", color = TextBlack)
                            Text("Исполнитель: ${task.assignee_name}", color = TextBlack)

                            if (!task.completed_by_name.isNullOrBlank() &&
                                task.completed_by_user_id != task.assignee_user_id
                            ) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Отметил выполненной: ${task.completed_by_name}",
                                    color = TextBlack
                                )
                            }

                            if (task.status == "open") {
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { vm.completeTask(task.task_id) },
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("Выполнено")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
