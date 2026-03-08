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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ml.app.auth.GoogleAuthManager
import kotlinx.coroutines.launch

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
            }

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

    LaunchedEffect(state.currentUser.user_id) {
        vm.loadMyTasks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        state.error?.let {
            Text(
                text = "Ошибка: $it",
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

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
                                Text(
                                    text = task.description,
                                    color = TextBlack,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            Text(
                                text = "Статус: ${task.status}",
                                color = TextBlack,
                                modifier = Modifier.padding(top = 8.dp)
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

                            if (task.status == "open") {
                                Button(
                                    onClick = { vm.completeTask(task.task_id) },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.padding(top = 12.dp)
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
