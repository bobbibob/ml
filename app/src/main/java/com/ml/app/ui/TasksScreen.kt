package com.ml.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import coil.compose.AsyncImage
import com.ml.app.auth.GoogleAuthManager
import com.ml.app.data.remote.dto.TaskDto
import com.ml.app.data.remote.dto.UserDto
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val TextBlack = Color(0xFF111111)
private val DoneGreen = Color(0xFF2E7D32)

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


private fun reminderPayload(option: ReminderOption?): Triple<String?, Int?, String?> {
    return when (option?.key) {
        "10m" -> Triple("interval", 10, null)
        "20m" -> Triple("interval", 20, null)
        "30m" -> Triple("interval", 30, null)
        "1h" -> Triple("interval", 60, null)
        "2h" -> Triple("interval", 120, null)
        "morning" -> Triple("daily_time", null, "10:00")
        "evening" -> Triple("daily_time", null, "18:00")
        else -> Triple(null, null, null)
    }
}

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
            "all" -> if (state.currentUser.role == "plus" || state.currentUser.role == "admin") {
                vm.loadAllTasks()
            } else {
                vm.loadMyTasks()
            }
            else -> vm.loadMyTasks()
        }
    }

    when (state.selectedTab) {
        "create" -> CreateTaskWizard(
            vm = vm,
            onCancel = { vm.selectTab("my") }
        )

        "all" -> TasksListTab(
            titleWhenEmpty = "Задач пока нет",
            tasks = state.allTasks,
            error = state.error,
            info = state.info,
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
            info = state.info,
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
private fun CreateTaskWizard(
    vm: TasksViewModel,
    onCancel: () -> Unit
) {
    val state = vm.state
    var step by remember { mutableStateOf(CreateTaskStep.Assignee) }
    var selectedAssigneeId by remember { mutableStateOf("") }
    var selectedReminder by remember { mutableStateOf<ReminderOption?>(null) }
    var taskTitle by remember { mutableStateOf("") }
    var taskDescription by remember { mutableStateOf("") }

    BackHandler {
        onCancel()
    }

    when (step) {
        CreateTaskStep.Assignee -> CreateTaskAssigneeStep(
            users = state.users,
            error = state.error,
            info = state.info,
            onCancel = onCancel,
            onChoose = {
                selectedAssigneeId = it
                step = CreateTaskStep.Reminder
            }
        )

        CreateTaskStep.Reminder -> CreateTaskReminderStep(
            selected = selectedReminder,
            onCancel = onCancel,
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
            info = state.info,
            onTitleChange = { taskTitle = it },
            onDescriptionChange = { taskDescription = it },
            onCancel = onCancel,
            onDone = {
                val reminderText = selectedReminder?.title ?: ""
                val finalDescription = buildString {
                    append(taskDescription.trim())
                    if (reminderText.isNotBlank()) {
                        if (isNotBlank()) append("

")
                        append("Напоминание: ")
                        append(reminderText)
                    }
                }
                val payload = reminderPayload(selectedReminder)

                vm.createTask(
                    title = taskTitle.trim(),
                    description = finalDescription,
                    assigneeUserId = selectedAssigneeId,
                    reminderType = payload.first,
                    reminderIntervalMinutes = payload.second,
                    reminderTimeOfDay = payload.third
                )
            }
        )
    }
}

@Composable
private fun CreateTaskAssigneeStep(
    users: List<UserDto>,
    error: String?,
    info: String?,
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
        }

        Text(
            text = "Выберите исполнителя",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        info?.let {
            Text(it, color = Color.Gray)
        }

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
                    colors = CardDefaults.cardColors(containerColor = Color.White)
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
                            fontWeight = FontWeight.Bold,
                            color = TextBlack
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
            Button(
                onClick = onNext,
                enabled = selected != null
            ) {
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
                        containerColor = if (selected?.key == option.key) Color(0xFFE8DDF7) else Color.White
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
    info: String?,
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
        }

        Text(
            text = "Данные задачи",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        info?.let {
            Text(it, color = Color.Gray)
        }

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

@Composable
private fun TasksListTab(
    titleWhenEmpty: String,
    tasks: List<TaskDto>,
    error: String?,
    info: String?,
    currentUserId: String,
    currentUserRole: String,
    onComplete: (String) -> Unit,
    onEdit: () -> Unit,
    onSaveEdit: (String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    users: List<UserDto>,
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

        info?.let {
            Text(
                text = it,
                modifier = Modifier.padding(bottom = 12.dp),
                color = Color.Gray
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

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users) { user ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { assigneeUserId = user.user_id },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (assigneeUserId == user.user_id) Color(0xFFE8DDF7) else Color.White
                                )
                            ) {
                                Text(
                                    text = "${user.display_name} (${user.email})",
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
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
