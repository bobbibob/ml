package com.ml.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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


private enum class CreateTaskStep {
    Assignee, Reminder, Details
}

private data class ReminderOption(
    val key: String,
    val title: String
)

private val ReminderOptions = listOf(
    ReminderOption("10m", "Каждые 10 минут"),
    ReminderOption("20m", "Каждые 20 минут"),
    ReminderOption("30m", "Каждые 30 минут"),
    ReminderOption("1h", "Каждый час"),
    ReminderOption("2h", "Каждые 2 часа"),
    ReminderOption("morning", "Только утром"),
    ReminderOption("evening", "Только вечером")
)

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
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Войти",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            state.error?.let {
                Text(
                    text = if (it.contains("timeout", ignoreCase = true)) "Подключение..." else "Ошибка: $it",
                    color = if (it.contains("timeout", ignoreCase = true)) Color.Gray else Color.Red,
                    modifier = Modifier.padding(bottom = 16.dp)
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
                modifier = Modifier.fillMaxWidth(),
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
        "create" -> CreateTaskWizard(vm = vm)
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
private fun CreateTaskWizard(vm: TasksViewModel) {
    val state = vm.state

    var step by remember { mutableStateOf(CreateTaskStep.Assignee) }
    var selectedAssigneeId by remember { mutableStateOf("") }
    var selectedReminder by remember { mutableStateOf<ReminderOption?>(null) }
    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }

    BackHandler {
        vm.selectTab("my")
    }

    when (step) {
        CreateTaskStep.Assignee -> CreateTaskAssigneeStep(
            users = state.users,
            error = state.error,
            onCancel = { vm.selectTab("my") },
            onChoose = {
                selectedAssigneeId = it
                step = CreateTaskStep.Reminder
            }
        )

        CreateTaskStep.Reminder -> CreateTaskReminderStep(
            selected = selectedReminder,
            onCancel = { vm.selectTab("my") },
            onChoose = { selectedReminder = it },
            onNext = {
                if (selectedReminder != null) {
                    step = CreateTaskStep.Details
                }
            }
        )

        CreateTaskStep.Details -> CreateTaskDetailsStep(
            title = taskTitle,
            description = taskDescription,
            error = state.error,
            onTitleChange = { taskTitle = it },
            onDescriptionChange = { taskDescription = it },
            onCancel = { vm.selectTab("my") },
            onDone = {
                vm.createTask(
                    title = taskTitle,
                    description = buildString {
                        append(taskDescription)
                        if (selectedReminder != null) {
                            if (isNotBlank()) append("

")
                            append("Напоминание: ")
                            append(selectedReminder!!.title)
                        }
                    },
                    assigneeUserId = selectedAssigneeId
                )
            }
        )
    }
}

@Composable
private fun CreateTaskAssigneeStep(
    users: List<com.ml.app.data.remote.dto.UserDto>,
    error: String?,
    onCancel: () -> Unit,
    onChoose: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Отмена")
            }
            Spacer(Modifier.weight(1f))
        }

        Text(
            text = "Выберите исполнителя",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        error?.let {
            if (!it.contains("timeout", ignoreCase = true)) {
                Text("Ошибка: $it", color = Color.Red)
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(users) { user ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(user.user_id) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EEF7))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!user.photo_url.isNullOrBlank()) {
                            AsyncImage(
                                model = user.photo_url,
                                contentDescription = user.display_name,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Button(
                                onClick = { onChoose(user.user_id) },
                                modifier = Modifier.size(72.dp),
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(user.display_name.take(1).ifBlank { "?" }.uppercase())
                            }
                        }

                        Text(
                            text = user.display_name,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTaskReminderStep(
    selected: ReminderOption?,
    onCancel: () -> Unit,
    onChoose: (ReminderOption) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Отмена")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext, enabled = selected != null) {
                Text("Далее")
            }
        }

        Text(
            text = "Частота напоминания",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(ReminderOptions) { option ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(option) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected?.key == option.key) Color(0xFFE8DDF7) else Color(0xFFF3EEF7)
                    )
                ) {
                    Text(
                        text = option.title,
                        modifier = Modifier.padding(18.dp),
                        fontWeight = if (selected?.key == option.key) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateTaskDetailsStep(
    title: String,
    description: String,
    error: String?,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Отмена")
            }
            Spacer(Modifier.weight(1f))
        }

        Text(
            text = "Данные задачи",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        error?.let {
            if (!it.contains("timeout", ignoreCase = true)) {
                Text("Ошибка: $it", color = Color.Red)
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Название") },
            singleLine = true
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Описание") },
            minLines = 4
        )

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            enabled = title.isNotBlank()
        ) {
            Text("Готово")
        }
    }
}
