package com.ml.app.ui
import androidx.compose.foundation.clickable

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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



private fun cleanTaskDescriptionForEdit(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return value
        .lines()
        .joinToString("\n")
        .trim()
}

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
private fun SelectedAssigneeHeader(
    user: UserDto?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = user != null,
        modifier = modifier
    ) {
        if (user == null) return@AnimatedVisibility
        val avatarSize by animateDpAsState(targetValue = 46.dp, label = "selectedAvatarSize")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!user.photo_url.isNullOrBlank()) {
                AsyncImage(
                    model = user.photo_url,
                    contentDescription = user.display_name,
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                )
            } else {
                Button(
                    onClick = {},
                    modifier = Modifier.size(avatarSize),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(user.display_name.take(1).ifBlank { "?" }.uppercase())
                }
            }

            Column {
                Text(
                    text = user.display_name,
                    fontWeight = FontWeight.Bold,
                    color = TextBlack
                )
                Text(
                    text = "Исполнитель",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun TasksScreen(
    onBack: () -> Unit,
    vm: TasksViewModel = viewModel(),
    initialOpenTaskId: String? = null,
    openSignal: Int = 0
) {
    LaunchedEffect(Unit) {
        vm.init()
    }

    val state = vm.state
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pushedTask by remember { mutableStateOf<TaskDto?>(null) }
    var showPushedTaskDetails by remember { mutableStateOf(false) }

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
            "create" -> {
                if (state.users.isEmpty() && !state.loadingUsers) {
                    vm.loadUsers(force = false)
                }
            }
            "all" -> if (state.currentUser.role == "plus" || state.currentUser.role == "admin") {
                vm.loadAllTasks()
            } else {
                vm.loadMyTasks()
            }
            else -> vm.loadMyTasks()
        }
    }

    LaunchedEffect(openSignal, initialOpenTaskId, state.currentUser.user_id) {
        if (!initialOpenTaskId.isNullOrBlank()) {
            vm.selectTab("my")

            val localTask = (state.myTasks + state.allTasks).firstOrNull { it.task_id == initialOpenTaskId }
            if (localTask != null) {
                pushedTask = localTask
                showPushedTaskDetails = true
            } else {
                vm.loadOpenedTaskFromPush(initialOpenTaskId)
            }
        }
    }

    LaunchedEffect(state.openedTaskFromPush?.task_id) {
        val target = state.openedTaskFromPush ?: return@LaunchedEffect
        pushedTask = target
        showPushedTaskDetails = true
        vm.clearOpenedTaskFromPush()
    }

    LaunchedEffect(initialOpenTaskId, state.myTasks, state.allTasks) {
        if (initialOpenTaskId.isNullOrBlank()) return@LaunchedEffect
        if (showPushedTaskDetails || pushedTask != null) return@LaunchedEffect

        val localTask = (state.myTasks + state.allTasks).firstOrNull { it.task_id == initialOpenTaskId }
        if (localTask != null) {
            pushedTask = localTask
            showPushedTaskDetails = true
        }
    }

        if (showPushedTaskDetails && pushedTask != null && state.currentUser != null) {
        val task = pushedTask!!

        AlertDialog(
            onDismissRequest = {
                showPushedTaskDetails = false
                pushedTask = null
                vm.selectTab("my")
            },
            title = {
                Text(task.title)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!task.description.isNullOrBlank()) {
                        Text(task.description)
                    }
                    Text("Создал: ${task.created_by_name}")
                    Text("Статус: ${if (task.status == "open") "Открыта" else "Выполнена"}")
                    if (!task.completed_at.isNullOrBlank()) {
                        Text("Выполнено: ${fmtTaskDateTime(task.completed_at)}")
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            showPushedTaskDetails = false
                            pushedTask = null
                            vm.selectTab("my")
                        },
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {}
        )
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
            onRemind = { vm.remindTask(it) },
            onEdit = { vm.loadUsers() },
            onSaveEdit = { taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay ->
                vm.updateTask(taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay)
            },
            onDelete = { vm.deleteTask(it) },
            users = state.users,
            uiState = state,
            initialOpenTaskId = if (state.selectedTab == "my") initialOpenTaskId else null,
            openSignal = openSignal,
            onConsumedOpenedTaskFromPush = { vm.clearOpenedTaskFromPush() }
        )

        else -> TasksListTab(
            titleWhenEmpty = "Задач пока нет",
            tasks = state.myTasks,
            error = state.error,
            info = state.info,
            currentUserId = state.currentUser.user_id,
            currentUserRole = state.currentUser.role,
            onComplete = { vm.completeTask(it) },
            onRemind = { vm.remindTask(it) },
            onEdit = { vm.loadUsers() },
            onSaveEdit = { taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay ->
                vm.updateTask(taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay)
            },
            onDelete = { vm.deleteTask(it) },
            users = state.users,
            uiState = state,
            initialOpenTaskId = initialOpenTaskId,
            openSignal = openSignal,
            onConsumedOpenedTaskFromPush = { vm.clearOpenedTaskFromPush() }
        )
    }
}

@Composable
private fun TaskDetailsDialog(
    task: TaskDto,
    canEdit: Boolean,
    canDelete: Boolean,
    canRemind: Boolean,
    onDismiss: () -> Unit,
    onComplete: (String) -> Unit,
    onRemind: (String) -> Unit,
    onEdit: (TaskDto) -> Unit,
    onDelete: (TaskDto) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(task.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!task.description.isNullOrBlank()) {
                    Text(task.description)
                }

                Text("Создал: ${task.created_by_name}")
                Text("Статус: ${if (task.status == "open") "Открыта" else "Выполнена"}")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (task.status == "open") {
                            Button(
                                onClick = { onComplete(task.task_id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Выполнено")
                            }
                        }

                        if (canEdit) {
                            Button(
                                onClick = { onEdit(task) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Редактировать")
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (task.status == "open" && canRemind) {
                            OutlinedButton(
                                onClick = { onRemind(task.task_id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Напомнить")
                            }
                        }

                        if (canDelete) {
                            OutlinedButton(
                                onClick = { onDelete(task) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    )
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

    val selectedAssigneeUser = state.users.firstOrNull { it.user_id == selectedAssigneeId }

    BackHandler {
        onCancel()
    }

    when (step) {
        CreateTaskStep.Assignee -> CreateTaskAssigneeStep(
            users = state.users,
            selectedUser = selectedAssigneeUser,
            error = state.error,
            info = state.info,
            onCancel = onCancel,
            onChoose = {
                selectedAssigneeId = it
                step = CreateTaskStep.Reminder
            }
        )

        CreateTaskStep.Reminder -> CreateTaskReminderStep(
            selectedUser = selectedAssigneeUser,
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
            selectedUser = selectedAssigneeUser,
            title = taskTitle,
            description = taskDescription,
            error = state.error,
            info = state.info,
            loading = state.creatingTask,
            onTitleChange = { taskTitle = it },
            onDescriptionChange = { taskDescription = it },
            onCancel = onCancel,
            onDone = {
                val reminderText = selectedReminder?.title ?: ""
                val finalDescription = buildString {
                    append(taskDescription.trim())
                    if (reminderText.isNotBlank()) {
                        if (isNotBlank()) append("\n\n")
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
    selectedUser: UserDto?,
    error: String?,
    info: String?,
    onCancel: () -> Unit,
    onChoose: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var animatingUserId by remember { mutableStateOf<String?>(null) }

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

            SelectedAssigneeHeader(user = selectedUser)
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
                val isSelected = animatingUserId == user.user_id
                val alpha by animateFloatAsState(
                    targetValue = when {
                        animatingUserId == null -> 1f
                        isSelected -> 1f
                        else -> 0.18f
                    },
                    animationSpec = tween(durationMillis = 220),
                    label = "assigneeAlpha"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.12f else 1f,
                    animationSpec = tween(durationMillis = 220),
                    label = "assigneeScale"
                )
                val avatarSize by animateDpAsState(
                    targetValue = if (isSelected) 88.dp else 72.dp,
                    animationSpec = tween(durationMillis = 220),
                    label = "assigneeAvatarSize"
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable(enabled = animatingUserId == null) {
                            animatingUserId = user.user_id
                            scope.launch {
                                delay(520)
                                onChoose(user.user_id)
                            }
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFFE8DDF7) else Color.White
                    )
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
                                    .size(avatarSize)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color(0xFF7E57C2) else Color.Transparent,
                                        shape = CircleShape
                                    )
                            )
                        } else {
                            Button(
                                onClick = {},
                                modifier = Modifier
                                    .size(avatarSize)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color(0xFF7E57C2) else Color.Transparent,
                                        shape = CircleShape
                                    ),
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
    selectedUser: UserDto?,
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

            SelectedAssigneeHeader(user = selectedUser)

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
    selectedUser: UserDto?,
    title: String,
    description: String,
    error: String?,
    info: String?,
    loading: Boolean,
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

            SelectedAssigneeHeader(user = selectedUser)
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
            enabled = title.isNotBlank() && !loading
        ) {
            Text(if (loading) "Создаём..." else "Готово")
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
    onRemind: (String) -> Unit,
    onEdit: () -> Unit,
    onSaveEdit: (String, String, String, String, String?, Int?, String?) -> Unit,
    onDelete: (String) -> Unit,
    users: List<UserDto>,
    uiState: TasksUiState,
    initialOpenTaskId: String? = null,
    openSignal: Int = 0,
    onConsumedOpenedTaskFromPush: () -> Unit = {}
) {
    var editTask by remember { mutableStateOf<TaskDto?>(null) }
    var deleteTask by remember { mutableStateOf<TaskDto?>(null) }
    var showEditWizard by remember { mutableStateOf(false) }
    var openedTask by remember { mutableStateOf<TaskDto?>(null) }
    var showTaskDetails by remember { mutableStateOf(false) }

    LaunchedEffect(openSignal, initialOpenTaskId, tasks) {
        // Открытие по push теперь идёт через загрузку задачи по task_id с сервера.
    }

    LaunchedEffect(uiState.openedTaskFromPush?.task_id) {
        // Открытие задачи по push теперь обрабатывается на уровне TasksScreen.
    }


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

        if (uiState.loadingTasks && tasks.isEmpty()) {
            Text("Загружаем задачи...")
        } else if (tasks.isEmpty()) {
            Text(titleWhenEmpty)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks) { task ->
                    val isAdmin = currentUserRole == "admin"
                    val isAuthor = task.created_by_user_id == currentUserId
                    val isAssignee = task.assignee_user_id == currentUserId

                    val canDelete = isAdmin || isAuthor
                    val canEdit = isAdmin && task.status == "open"
                    val canRemind = task.status == "open" && (isAdmin || isAuthor)
                    val canComplete = task.status == "open" && (isAdmin || isAssignee)

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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (canComplete) {
                                            Button(
                                                onClick = { onComplete(task.task_id) },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Выполнено")
                                            }
                                        }

                                        if (canEdit) {
                                            Button(
                                                onClick = {
                                                    onEdit()
                                                    editTask = task
                                                    showEditWizard = true
                                                },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Редактировать")
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (canRemind) {
                                            OutlinedButton(
                                                onClick = { onRemind(task.task_id) },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Напомнить")
                                            }
                                        }

                                        if (canDelete) {
                                            OutlinedButton(
                                                onClick = { deleteTask = task },
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.fillMaxWidth()
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
            }
        }
    }

      if (showTaskDetails && openedTask != null) {
          val task = openedTask!!
          val canDelete = currentUserRole == "admin" || task.created_by_user_id == currentUserId
          val canEdit = canDelete && task.status == "open"
          val canRemind = currentUserRole == "admin" || task.created_by_user_id == currentUserId

          TaskDetailsDialog(
              task = task,
              canEdit = canEdit,
              canDelete = canDelete,
              onDismiss = {
                  showTaskDetails = false
                  openedTask = null
              },
              onComplete = {
                  showTaskDetails = false
                  onComplete(it)
              },
              onRemind = { onRemind(it) },
              canRemind = canRemind,
              onEdit = {
                  showTaskDetails = false
                  onEdit()
                  editTask = it
                  showEditWizard = true
              },
              onDelete = {
                  showTaskDetails = false
                  deleteTask = it
              }
          )
      }


      if (showEditWizard && editTask != null) {
          EditTaskWizard(
              task = editTask!!,
              users = users,
              error = error,
              info = info,
              onCancel = {
                  showEditWizard = false
                  editTask = null
              },
              onSave = { taskId: String, title: String, description: String, assigneeUserId: String, reminderType: String?, reminderIntervalMinutes: Int?, reminderTimeOfDay: String? ->
                  onSaveEdit(taskId, title, description, assigneeUserId, reminderType, reminderIntervalMinutes, reminderTimeOfDay)
                  showEditWizard = false
                  editTask = null
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


@Composable
private fun EditTaskWizard(
    task: TaskDto,
    users: List<UserDto>,
    error: String?,
    info: String?,
    onCancel: () -> Unit,
    onSave: (String, String, String, String, String?, Int?, String?) -> Unit
) {
    var step by remember(task.task_id) { mutableStateOf(1) }
    var assigneeUserId by remember(task.task_id) { mutableStateOf(task.assignee_user_id) }
    var selectedReminder by remember(task.task_id) {
        mutableStateOf<ReminderOption?>(
            when {
                task.reminder_type == "interval" && task.reminder_interval_minutes == 10 -> ReminderOptions.firstOrNull { it.key == "10m" }
                task.reminder_type == "interval" && task.reminder_interval_minutes == 20 -> ReminderOptions.firstOrNull { it.key == "20m" }
                task.reminder_type == "interval" && task.reminder_interval_minutes == 30 -> ReminderOptions.firstOrNull { it.key == "30m" }
                task.reminder_type == "interval" && task.reminder_interval_minutes == 60 -> ReminderOptions.firstOrNull { it.key == "1h" }
                task.reminder_type == "interval" && task.reminder_interval_minutes == 120 -> ReminderOptions.firstOrNull { it.key == "2h" }
                task.reminder_type == "daily_time" && task.reminder_time_of_day == "10:00" -> ReminderOptions.firstOrNull { it.key == "morning" }
                task.reminder_type == "daily_time" && task.reminder_time_of_day == "18:00" -> ReminderOptions.firstOrNull { it.key == "evening" }
                else -> null
            }
        )
    }
    var title by remember(task.task_id) { mutableStateOf(task.title) }
    var description by remember(task.task_id) { mutableStateOf(cleanTaskDescriptionForEdit(task.description)) }

    val selectedUser = users.firstOrNull { it.user_id == assigneeUserId }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7F4FB)
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

                    selectedUser?.let {
                        SelectedAssigneeHeader(user = it)
                    }
                }

                Text(
                    text = "Редактировать задачу",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                info?.let { Text(it, color = Color.Gray) }
                error?.let {
                    if (!it.contains("timeout", ignoreCase = true)) {
                        Text("Ошибка: $it", color = Color.Red)
                    }
                }

                when (step) {
                    1 -> {
                        Text(
                            text = "Исполнитель",
                            fontWeight = FontWeight.Bold,
                            color = TextBlack
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(users) { user ->
                                val selected = assigneeUserId == user.user_id
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { assigneeUserId = user.user_id },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) Color(0xFFE8DDF7) else Color.White
                                    )
                                ) {
                                    Text(
                                        text = "${user.display_name}\n(${user.email})",
                                        modifier = Modifier.padding(14.dp),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { step = 2 },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = assigneeUserId.isNotBlank(),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("Далее")
                        }
                    }

                    2 -> {
                        Text(
                            text = "Частота напоминания",
                            fontWeight = FontWeight.Bold,
                            color = TextBlack
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(ReminderOptions) { option ->
                                val selected = selectedReminder?.key == option.key
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedReminder = option },
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) Color(0xFFE8DDF7) else Color.White
                                    )
                                ) {
                                    Text(
                                        text = option.title,
                                        modifier = Modifier.padding(14.dp),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { step = 1 },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text("Назад")
                            }

                            Button(
                                onClick = { step = 3 },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text("Далее")
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = "Данные задачи",
                            fontWeight = FontWeight.Bold,
                            color = TextBlack
                        )

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название") },
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Описание") },
                            minLines = 5
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { step = 2 },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text("Назад")
                            }

                            Button(
                                onClick = {
                                    val payload = reminderPayload(selectedReminder)
                                    onSave(
                                        task.task_id,
                                        title.trim(),
                                        description.trim(),
                                        assigneeUserId,
                                        payload.first,
                                        payload.second,
                                        payload.third
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp),
                                enabled = title.isNotBlank() && assigneeUserId.isNotBlank()
                            ) {
                                Text("Сохранить")
                            }
                        }
                    }
                }
            }
        }
    }
}
