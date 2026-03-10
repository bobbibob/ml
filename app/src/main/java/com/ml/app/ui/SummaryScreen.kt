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
          onClick = {
            scope.launch {
              try {
                val idToken = GoogleAuthManager(ctx).signIn()
                if (!idToken.isNullOrBlank()) {
                  tasksVm.loginWithGoogleToken(idToken)
                }
              } catch (e: Exception) {
                tasksVm.setError("Ошибка входа: ${e.message ?: "unknown"}")
              }
            }
          },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(24.dp)
        ) {
          Text("Войти через Google")
        }
      }
    }
    return
  }

  BackHandler {
    if (showAdminScreen) {
      showAdminScreen = false
    } else if (showTasks.value) {
      showTasks.value = false
    } else {
      when (state.mode) {
        is ScreenMode.Details -> vm.backToTimeline()
        is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
        is ScreenMode.Stocks -> vm.backFromStocks()
        is ScreenMode.AddDailySummary -> vm.backFromAddDailySummary()
        else -> showExitAppDialog = true
      }
    }
  }

  fun openDatePicker(current: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
      ctx,
      { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
      current.year, current.monthValue - 1, current.dayOfMonth
    ).show()
  }

  val pullState = rememberPullRefreshState(
    refreshing = state.loading,
    onRefresh = { vm.syncIfChanged() }
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
      .pullRefresh(pullState)
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(bottom = 72.dp)) {

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(MercadoYellow)
          .padding(14.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
            if (showTasks.value) {
              Button(
                onClick = {
                  showAdminScreen = false
                  showTasks.value = false
                }
              ) {
                Text("ml")
              }

              Spacer(Modifier.width(24.dp))

              Text(
                text = "Задачи",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = TextBlack
              )
            } else {
              Text(
                text = "ml",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = TextBlack
              )

              Spacer(Modifier.width(24.dp))

              Button(
                onClick = {
                  showAdminScreen = false
                  showTasks.value = true
                }
              ) {
                Text("Задачи")
              }
            }

            Spacer(Modifier.weight(1f))
            Text(
              "Заказы: ${day.totalOrders}",
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
              color = MercadoBlue
            )

          }

          Spacer(Modifier.height(6.dp))
          Row(Modifier.fillMaxWidth()) {
            Text("Расход: ${fmtMoney(daySpend)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("Чистая прибыль: ${fmtMoney(dayNet)}", color = TextBlack, fontWeight = FontWeight.SemiBold)

          }

          Spacer(Modifier.height(10.dp))

          day.byBags.take(10).forEach { b ->
            val t = cardTypes[b.bagId] ?: CardType.CLASSIC
            val price = b.price ?: 0.0
            val net = ProfitCalc.netProfit(t, b.orders.toDouble(), price, b.spend, b.cogs)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              BagThumb(b.imagePath)
              Spacer(Modifier.width(10.dp))
              Column(Modifier.weight(1f)) {
                Text(
                  text = b.bagName,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  color = TextBlack,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "Заказы: ${b.orders} • Расход: ${fmtMoney(b.spend)} • Прибыль: ${fmtMoney(net)}",
                  color = Color.Gray,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
            Spacer(Modifier.height(6.dp))

          }
        }
  }

}
@Composable
private fun DetailsList(
  rows: List<BagDayRow>,
  cardTypes: Map<String, CardType>
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(rows) { r ->
      val type = cardTypes[r.bagId] ?: CardType.CLASSIC
      val price = r.price ?: 0.0
      val net = ProfitCalc.netProfit(type, r.totalOrders, price, r.totalSpend, r.cogs)

      Card(colors = CardDefaults.cardColors(containerColor = SoftGray), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {

          Row(verticalAlignment = Alignment.CenterVertically) {
            BagThumb(r.imagePath)
            Spacer(Modifier.width(12.dp))
            Text(
              text = r.bagName,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = TextBlack,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )

          }

          Spacer(Modifier.height(10.dp))

          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TypePill(text = "Классика", selected = type == CardType.CLASSIC)
            TypePill(text = "Премиум", selected = type == CardType.PREMIUM)

          }

          Spacer(Modifier.height(10.dp))

          Row(Modifier.fillMaxWidth()) {
            Text("Заказы: ${fmtInt(r.totalOrders)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("Расход: ${fmtMoney(r.totalSpend)}", color = TextBlack)

          }
          Spacer(Modifier.height(6.dp))
          Row(Modifier.fillMaxWidth()) {
            Text("Цена за заказ: ${fmtMoney(r.cpo)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("CTR: ${fmtPct(r.totalAds.ctr)} • CPC: ${fmtMoney(r.totalAds.cpc)}", color = TextBlack)

          }

          Spacer(Modifier.height(6.dp))
          Row(Modifier.fillMaxWidth()) {
            Text("Себест.: ${fmtMoney(r.cogs)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("Чистая прибыль: ${fmtMoney(net)}", color = TextBlack, fontWeight = FontWeight.SemiBold)

          }

          if (!r.hypothesis.isNullOrBlank() || r.price != null) {
            Spacer(Modifier.height(6.dp))
            Text(
              text = "${r.hypothesis ?: ""}${if (r.price != null) " • Цена: ${fmtMoney(r.price)}" else ""}",
              color = Color.Gray,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )

          }

          Spacer(Modifier.height(10.dp))

          if (r.ordersByColors.isNotEmpty()) {
            Text("Заказы по цветам", fontWeight = FontWeight.SemiBold, color = TextBlack)
            Spacer(Modifier.height(6.dp))
            r.ordersByColors.take(12).forEach { cv ->
              Row(Modifier.fillMaxWidth()) {
                Text(cv.color, modifier = Modifier.weight(1f), color = TextBlack)
                Text(fmtInt(cv.value), color = TextBlack, fontWeight = FontWeight.SemiBold)
              }
            }

          }

          if (r.stockByColors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Остаток по цветам", fontWeight = FontWeight.SemiBold, color = TextBlack)
            Spacer(Modifier.height(6.dp))
            r.stockByColors.take(12).forEach { cv ->
              Row(Modifier.fillMaxWidth()) {
                Text(cv.color, modifier = Modifier.weight(1f), color = TextBlack)
                Text(fmtInt(cv.value), color = TextBlack, fontWeight = FontWeight.SemiBold)
              }
            }

          }

          Spacer(Modifier.height(10.dp))

          val rkEmpty = r.rk.spend == 0.0 && r.rk.impressions == 0L && r.rk.clicks == 0L
          val igEmpty = r.ig.spend == 0.0 && r.ig.impressions == 0L && r.ig.clicks == 0L

          if (rkEmpty) {
            Text("Нет РК", color = Color.Gray)

          } else {
            Text(
              "РК: расход ${fmtMoney(r.rk.spend)} • показы ${r.rk.impressions} • клики ${r.rk.clicks} • CTR ${fmtPct(r.rk.ctr)} • CPC ${fmtMoney(r.rk.cpc)}",
              color = Color.Gray
            )

          }

          if (igEmpty) {
            Text("Нет Instagram", color = Color.Gray)

          } else {
            Text(
              "Instagram: расход ${fmtMoney(r.ig.spend)} • показы ${r.ig.impressions} • клики ${r.ig.clicks} • CTR ${fmtPct(r.ig.ctr)} • CPC ${fmtMoney(r.ig.cpc)}",
              color = Color.Gray
            )

          }
        }
      }
    }
  }
}

@Composable
private fun TypePill(text: String, selected: Boolean) {
  val bg = if (selected) MercadoYellow else ChipGray
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .background(bg)
      .padding(horizontal = 14.dp, vertical = 8.dp)
  ) {
    Text(text = text, color = TextBlack, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun BagThumb(absPath: String?) {
  val shape = RoundedCornerShape(12.dp)
  val size = 56.dp

  if (!absPath.isNullOrBlank() && File(absPath).exists()) {
    AsyncImage(
      model = File(absPath),
      contentDescription = null,
      modifier = Modifier.size(size).clip(shape)
    )
  } else {
    Box(modifier = Modifier.size(size).clip(shape).background(Color(0xFFEAEAEA)))
  }
}

@Composable
private fun ArticleBottomBar(
  onArticleClick: () -> Unit,
  onAddSummaryClick: () -> Unit,
  onStocksClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    tonalElevation = 6.dp,
    shadowElevation = 8.dp
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(
        onClick = onArticleClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Артикулы")
      }

      Button(
        onClick = onAddSummaryClick,
        modifier = Modifier.width(56.dp).height(56.dp),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(0.dp)
      ) {
        Text("+")
      }

      Button(
        onClick = onStocksClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Остатки")
      }
    }
  }
}





@Composable
private fun AdminLoadingScreen() {
  val transition = rememberInfiniteTransition(label = "adminLoading")
  val rotation = transition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "adminGearRotation"
  )

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Icon(
        imageVector = Icons.Filled.Settings,
        contentDescription = "Загрузка",
        tint = Color.Gray,
        modifier = Modifier
          .size(56.dp)
          .rotate(rotation.value)
      )

      Text(
        text = "Загрузка...",
        color = Color.Gray,
        style = MaterialTheme.typography.titleMedium
      )
    }
  }
}

@Composable
private fun AdminScreen(
  adminTab: String,
  onTabChange: (String) -> Unit,
  onBack: () -> Unit,
  users: List<com.ml.app.data.remote.dto.UserDto>,
  tasks: List<com.ml.app.data.remote.dto.TaskDto>,
  history: List<com.ml.app.data.remote.dto.HistoryItemDto>,
  error: String?,
  onChangeRole: (String, String) -> Unit,
  onDeleteUser: (String) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
      else -> AdminUsersTab(
        users = users,
        onChangeRole = onChangeRole,
        onDeleteUser = onDeleteUser
      )
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
