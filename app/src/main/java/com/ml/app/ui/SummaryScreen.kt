@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.ml.app.ui

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.material3.AlertDialog

import android.app.Activity

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ml.app.domain.*
import com.ml.app.auth.GoogleAuthManager
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.ml.app.ui.TasksScreen
import androidx.compose.foundation.shape.CircleShape

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)
private val SoftGray = Color(0xFFF7F7F7)
private val ChipGray = Color(0xFFEAEAEA)

private fun fmtInt(v: Double): String = v.roundToInt().toString()
private fun fmtMoney(v: Double): String = String.format("%.2f", v)
private fun fmtPct(v01: Double): String = String.format("%.2f%%", v01 * 100.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val tasksVm: TasksViewModel = viewModel()
  val showTasks = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  var accountMenuExpanded by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var draftDisplayName by remember { mutableStateOf("") }
    var showAdminDialog by remember { mutableStateOf(false) }
    var showAdminScreen by remember { mutableStateOf(false) }
    var adminTab by remember { mutableStateOf("users") }

  val state by vm.state.collectAsState()
  val activity = (LocalContext.current as? Activity)
  val scope = rememberCoroutineScope()
  var showExitAppDialog by remember { mutableStateOf(false) }
  val ctx = LocalContext.current

  LaunchedEffect(Unit) {
    tasksVm.init()
  }

  LaunchedEffect(tasksVm.state.currentUser?.display_name) {
    val user = tasksVm.state.currentUser
    if (user != null && user.display_name.isBlank()) {
      draftDisplayName = user.email.substringBefore("@").ifBlank { "Пользователь" }
      showEditNameDialog = true
    }
  }

  LaunchedEffect(tasksVm.state.currentUser?.user_id) {
    if (tasksVm.state.currentUser != null) {
      vm.init()
    }
  }

  if (tasksVm.state.currentUser == null) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.White),
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Text(
          text = "Войти",
          style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
          color = TextBlack
        )

        tasksVm.state.error?.let {
          Text("Ошибка: $it", color = Color.Red)
        }

        tasksVm.state.info?.let {
          Text(it, color = TextBlack)
        }

        Button(
          onClick = { onTabChange("users") },
          modifier = Modifier.weight(1f)
        ) { Text("Пользователи") }

        Button(
          onClick = { onTabChange("tasks") },
          modifier = Modifier.weight(1f)
        ) { Text("Задачи") }

        Button(
          onClick = { onTabChange("history") },
          modifier = Modifier.weight(1f)
        ) { Text("История") }

        Button(
          onClick = { onTabChange("push") },
          modifier = Modifier.weight(1f)
        ) { Text("Push") }
    }

    error?.takeIf { it.isNotBlank() }?.let {
      val waiting = it.contains("Загрузка в ожидании данных", ignoreCase = true)
      Text(
        text = if (waiting) "Загрузка в ожидании данных" else it,
        color = if (waiting) Color.Gray else Color.Red
      )
    }

    when (adminTab) {
      "tasks" -> AdminTasksTab(tasks)
      "history" -> AdminHistoryTab(history)
      "push" -> AdminPushTab(
        users = users,
        onSendPush = onSendPush
      )
      else -> AdminUsersTab(
        users = users,
        onChangeRole = onChangeRole,
        onDeleteUser = onDeleteUser
      )
    }
  }
}


@Composable
private fun AdminPushTab(
  users: List<com.ml.app.data.remote.dto.UserDto>,
  onSendPush: (String?, String, String) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }
  var selectedUserId by remember { mutableStateOf<String?>(null) }
  var selectedLabel by remember { mutableStateOf("Всем") }
  var title by remember { mutableStateOf("") }
  var body by remember { mutableStateOf("") }

  Column(
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Box {
      Button(onClick = { expanded = true }) {
        Text("Кому: $selectedLabel")
      }

      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
      ) {
        DropdownMenuItem(
          text = { Text("Всем") },
          onClick = {
            selectedUserId = null
            selectedLabel = "Всем"
            expanded = false
          }
        )

        users.forEach { u ->
          val label = u.display_name.ifBlank { u.email }
          DropdownMenuItem(
            text = { Text(label) },
            onClick = {
              selectedUserId = u.user_id
              selectedLabel = label
              expanded = false
            }
          )
        }
      }
    }

    OutlinedTextField(
      value = title,
      onValueChange = { title = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Заголовок") },
      singleLine = true
    )

    OutlinedTextField(
      value = body,
      onValueChange = { body = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("Текст уведомления") },
      minLines = 4
    )

    Button(
      onClick = {
        onSendPush(selectedUserId, title.trim(), body.trim())
      },
      enabled = title.isNotBlank() && body.isNotBlank(),
      modifier = Modifier.fillMaxWidth()
    ) {
      Text("Отправить уведомление")
    }
  }
}

@Composable
private fun AdminUsersTab(
  users: List<com.ml.app.data.remote.dto.UserDto>,
  onChangeRole: (String, String) -> Unit,
  onDeleteUser: (String) -> Unit
) {
  if (users.isEmpty()) {
    Text("Пользователей пока нет")
    return
  }

  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(users) { user ->
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            if (!user.photo_url.isNullOrBlank()) {
              AsyncImage(
                model = user.photo_url,
                contentDescription = user.display_name,
                modifier = Modifier
                  .size(52.dp)
                  .clip(CircleShape)
              )
            } else {
              Button(
                onClick = {},
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
              ) {
                Text(user.display_name.take(1).ifBlank { "?" }.uppercase())
              }
            }

            Column(modifier = Modifier.weight(1f)) {
              Text(user.display_name, fontWeight = FontWeight.Bold)
              Text(user.email)
              Text("Роль: ${user.role}")
            }
          }

          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              if (user.role != "basic") {
                Button(
                  onClick = { onChangeRole(user.user_id, "basic") },
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Сделать basic")
                }
              }

              if (user.role != "plus") {
                Button(
                  onClick = { onChangeRole(user.user_id, "plus") },
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Сделать plus")
                }
              }

              if (user.role != "admin") {
                Button(
                  onClick = { onChangeRole(user.user_id, "admin") },
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Сделать admin")
                }
              }
            }

            OutlinedButton(
              onClick = { onDeleteUser(user.user_id) },
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

@Composable
private fun AdminTasksTab(
  tasks: List<com.ml.app.data.remote.dto.TaskDto>
) {
  if (tasks.isEmpty()) {
    Text("Задач пока нет")
    return
  }

  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(tasks) { task ->
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(task.title, fontWeight = FontWeight.Bold)
          if (!task.description.isNullOrBlank()) {
            Text(task.description)
          }
          Text("Статус: ${task.status}")
          Text("Создал: ${task.created_by_name}")
          Text("Исполнитель: ${task.assignee_name}")
        }
      }
    }
  }
}

@Composable
private fun AdminHistoryTab(
  history: List<com.ml.app.data.remote.dto.HistoryItemDto>
) {
  if (history.isEmpty()) {
    Text("История пока пуста")
    return
  }

  LazyColumn(
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(history) { item ->
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(item.action_type, fontWeight = FontWeight.Bold)
          Text("Сущность: ${item.entity_type}")
          Text("ID: ${item.entity_id}")
          Text("Когда: ${item.created_at}")
        }
      }
    }
  }
}
