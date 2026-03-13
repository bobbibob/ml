@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.ml.app.ui

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.material3.AlertDialog

import android.app.Activity

import android.app.DatePickerDialog
import android.widget.Toast
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

import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close

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
import com.ml.app.data.SQLiteRepo
import com.ml.app.core.result.AppResult
import com.ml.app.data.repository.DailySummarySyncRepository
import com.ml.app.data.session.PrefsSessionStorage
import com.ml.app.core.network.ApiModule
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
fun SummaryScreen(
  vm: SummaryViewModel = viewModel(),
  openTasksSignal: Int = 0,
  initialTaskId: String? = null
) {
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
  val summaryRepo = remember { SQLiteRepo(ctx) }
  var pendingDeleteDate by remember { mutableStateOf<String?>(null) }

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
      vm.syncServerSummaries()
    }
  }

  LaunchedEffect(openTasksSignal, initialTaskId, tasksVm.state.currentUser?.user_id) {
    if (openTasksSignal > 0 && tasksVm.state.currentUser != null) {
      showTasks.value = true
      tasksVm.selectTab("my")
      tasksVm.loadUsers()
      tasksVm.loadMyTasks()
    }
  }


  pendingDeleteDate?.let { dateToDelete ->
    AlertDialog(
      onDismissRequest = { pendingDeleteDate = null },
      title = { Text("Удалить сводку") },
      text = { Text("Удалить сводку за $dateToDelete?") },
      confirmButton = {
        Button(
          onClick = {
            scope.launch {
              val session = PrefsSessionStorage(ctx)
              val api = ApiModule.createApi(
                baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
                sessionStorage = session
              )
              val syncRepo = DailySummarySyncRepository(api, ctx)

              when (val res = syncRepo.deleteDailySummary(dateToDelete)) {
                is AppResult.Success -> {
                  summaryRepo.deleteDailySummaryByDate(dateToDelete)
                  vm.refreshTimeline()
                  pendingDeleteDate = null
                }
                is AppResult.Error -> {
                  pendingDeleteDate = null
                  Toast.makeText(ctx, "Ошибка удаления: ${res.message}", Toast.LENGTH_LONG).show()
                }
              }
            }
          }
        ) {
          Text("Удалить")
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { pendingDeleteDate = null }) {
          Text("Отмена")
        }
      }
    )
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
    onRefresh = { vm.syncServerSummaries() }
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
              onClick = { showTasks.value = false }
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
              onClick = { showTasks.value = true }
            ) {
              Text("Задачи")
            }
          }

          Spacer(Modifier.weight(1f))

          tasksVm.state.currentUser?.let { accountUser ->
              if (accountUser.role == "admin") {
                Button(
                  onClick = {
                      adminTab = "users"
                      showAdminScreen = true
                      tasksVm.loadUsers()
                    },
                  shape = RoundedCornerShape(20.dp)
                ) {
                  
Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = "admin",
        modifier = Modifier.size(18.dp)
    )
    Spacer(Modifier.width(6.dp))
    Text("admin")
}

                }
                Spacer(Modifier.width(8.dp))
              }

              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                  Button(
                    onClick = { accountMenuExpanded = true },
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                  ) {
                    if (!accountUser.photo_url.isNullOrBlank()) {
                      AsyncImage(
                        model = accountUser.photo_url,
                        contentDescription = "avatar",
                        modifier = Modifier
                          .fillMaxSize()
                          .clip(CircleShape)
                      )
                    } else {
                      Text(
                        text = accountUser.display_name.take(1).ifBlank { "?" }.uppercase(),
                        style = MaterialTheme.typography.titleMedium
                      )
                    }
                  }

                  DropdownMenu(
                    expanded = accountMenuExpanded,
                    onDismissRequest = { accountMenuExpanded = false }
                  ) {
                    Text(
                      text = accountUser.email,
                      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                      color = TextBlack
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                      text = { Text("Изменить имя") },
                      onClick = {
                        accountMenuExpanded = false
                        draftDisplayName = accountUser.display_name.ifBlank {
                          accountUser.email.substringBefore("@").ifBlank { "Пользователь" }
                        }
                        showEditNameDialog = true
                      }
                    )
                    DropdownMenuItem(
                      text = { Text("Выйти") },
                      onClick = {
                        accountMenuExpanded = false
                        tasksVm.logout()
                      }
                    )
                  }
                }

                Text(
                  text = accountUser.display_name.ifBlank { "Без имени" },
                  color = TextBlack,
                  style = MaterialTheme.typography.labelSmall,
                  modifier = Modifier.padding(top = 4.dp)
                )
              }

              Spacer(Modifier.width(8.dp))
            }

          if (state.mode is ScreenMode.Details) {
            TextButton(onClick = { vm.backToTimeline() }) { Text("Назад", color = TextBlack) }

          } else {
            

          }
        }
      }

      if (showAdminScreen) {
          AdminScreen(
            adminTab = adminTab,
            onTabChange = {
                adminTab = it
                when (it) {
                  "users" -> tasksVm.loadUsers()
                  "tasks" -> tasksVm.loadAllTasks()
                  "history" -> tasksVm.loadHistory()
                }
              },
            users = tasksVm.state.users,
              onBack = { showAdminScreen = false },
            tasks = tasksVm.state.allTasks,
            history = tasksVm.state.history,
            error = tasksVm.state.error ?: tasksVm.state.info,
            onChangeRole = { userId, role -> tasksVm.adminChangeUserRole(userId, role) },
            onDeleteUser = { userId -> tasksVm.adminDeleteUser(userId) },
            onSendPush = { userId, title, body -> tasksVm.sendPush(userId, title, body) }
          )
        } else if (showAdminScreen) {
          AdminScreen(
              adminTab = adminTab,
              onTabChange = { adminTab = it },
                onBack = { showAdminScreen = false },
              users = tasksVm.state.users,
              tasks = tasksVm.state.allTasks,
              history = tasksVm.state.history,
              error = tasksVm.state.error ?: tasksVm.state.info,
              onChangeRole = { userId, role -> tasksVm.adminChangeUserRole(userId, role) },
              onDeleteUser = { userId -> tasksVm.adminDeleteUser(userId) },
              onSendPush = { userId, title, body -> tasksVm.sendPush(userId, title, body) }
            )
        } else if (showTasks.value) {
          TasksScreen(
              onBack = { showTasks.value = false },
              vm = tasksVm,
              initialOpenTaskId = initialTaskId,
              openSignal = openTasksSignal
          )
        } else if (!state.hasPack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.loading) {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
              Spacer(Modifier.height(12.dp))
            }
            Text(if (state.status.isNotBlank()) state.status else "Скачиваем базу…", color = TextBlack)

          }
        }
        } else {
          if (state.mode !is ScreenMode.ArticleEditor && state.mode !is ScreenMode.Stocks && state.mode !is ScreenMode.AddDailySummary) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Button(
                onClick = { openDatePicker(state.selectedDate) { vm.setDateFromPicker(it) } },
                colors = ButtonDefaults.buttonColors(containerColor = SoftGray, contentColor = TextBlack),
                modifier = Modifier.weight(1f)
              ) {
                Text("Дата: ${state.selectedDate}")
              }


            }

            if (state.status.isNotBlank()) {
              Spacer(Modifier.height(8.dp))
              Text(
                text = state.status,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
              )
            }
          }

          when (state.mode) {
            is ScreenMode.Timeline -> TimelineList(
              items = state.timeline,
              cardTypes = state.cardTypes,
              onOpen = { vm.openDetails(LocalDate.parse(it.date)) },
              onEditDay = { vm.openAddDailySummary(LocalDate.parse(it.date)) },
              canDelete = tasksVm.state.currentUser?.role == "admin",
              onDeleteDay = { pendingDeleteDate = it.date }
            )

            is ScreenMode.Details -> DetailsList(
              rows = state.rows,
              cardTypes = state.cardTypes
            )

            is ScreenMode.ArticleEditor -> AddEditArticleScreen(
              bagId = (state.mode as ScreenMode.ArticleEditor).bagId,
              onDone = { vm.backFromArticleEditor() }
            )

            is ScreenMode.Stocks -> StockScreen(
              refreshKey = state.status,
              onBack = { vm.backFromStocks() }
            )

            is ScreenMode.AddDailySummary -> AddDailySummaryScreen(
              initialDate = state.selectedDate,
              onBack = { vm.backFromAddDailySummary() }
            )
          }

          if (state.status.isNotBlank()) {
          Text(text = state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
        }
      }
    }

    if (showAdminScreen) {
      } else if (showTasks.value) {
        TasksBottomBar(
        onMyTasksClick = { tasksVm.selectTab("my") },
        onAddTaskClick = { tasksVm.selectTab("create") },
        onAllTasksClick = { tasksVm.selectTab("all") },
        allTasksEnabled = tasksVm.state.currentUser?.role == "plus" || tasksVm.state.currentUser?.role == "admin",
        modifier = Modifier.align(Alignment.BottomCenter)
      )
    } else if (state.mode !is ScreenMode.ArticleEditor) {
      ArticleBottomBar(
        onArticleClick = { vm.openArticleEditor() },
        onAddSummaryClick = { vm.openAddDailySummary() },
        onStocksClick = { vm.openStocks() },
        modifier = Modifier.align(Alignment.BottomCenter)
      )
    }

    if (showExitAppDialog) {
      AlertDialog(
        onDismissRequest = { showExitAppDialog = false },
        title = { Text("Выйти из приложения?") },
        text = { Text("Вы действительно хотите выйти?") },
        confirmButton = {
          Button(
            onClick = {
              showExitAppDialog = false
              activity?.finish()
            }
          ) {
            Text("Выйти")
          }
        },
        dismissButton = {
          OutlinedButton(
            onClick = { showExitAppDialog = false }
          ) {
            Text("Отмена")
          }
        }
      )
    }
      if (showEditNameDialog) {
        val mustSetName = tasksVm.state.currentUser?.display_name?.isBlank() == true

        AlertDialog(
          onDismissRequest = {
            if (!mustSetName) {
              showEditNameDialog = false
            }
          },
          title = { Text(if (mustSetName) "Укажите имя" else "Изменить имя") },
          text = {
            OutlinedTextField(
              value = draftDisplayName,
              onValueChange = { draftDisplayName = it },
              singleLine = true,
              label = { Text("Имя") }
            )
          },
          confirmButton = {
            Button(
              onClick = {
                val value = draftDisplayName.trim()
                if (value.isNotBlank()) {
                  tasksVm.updateOwnDisplayName(value)
                  showEditNameDialog = false
                }
              }
            ) {
              Text("Сохранить")
            }
          },
          dismissButton = {
            if (!mustSetName) {
              OutlinedButton(onClick = { showEditNameDialog = false }) {
                Text("Отмена")
              }
            }
          }
        )
      }


    PullRefreshIndicator(
      refreshing = state.loading,
      state = pullState,
      modifier = Modifier.align(Alignment.TopCenter)
    )
  }
}


@Composable
private fun TasksBottomBar(
  onMyTasksClick: () -> Unit,
  onAddTaskClick: () -> Unit,
  onAllTasksClick: () -> Unit,
  allTasksEnabled: Boolean,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color(0xFFF1ECF8))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Button(
      onClick = onMyTasksClick,
      modifier = Modifier.weight(1f)
    ) {
      Text("Мои задачи")
    }

    Button(
      onClick = onAddTaskClick,
      modifier = Modifier.size(72.dp),
      shape = RoundedCornerShape(36.dp),
      contentPadding = PaddingValues(0.dp)
    ) {
      Text("+")
    }

    Button(
      onClick = onAllTasksClick,
      enabled = allTasksEnabled,
      modifier = Modifier.weight(1f)
    ) {
      Text("Все задачи")
    }
  }
}

@Composable
private fun TimelineList(
  items: List<DaySummary>,
  cardTypes: Map<String, CardType>,
  onOpen: (DaySummary) -> Unit,
  onEditDay: (DaySummary) -> Unit,
  canDelete: Boolean,
  onDeleteDay: (DaySummary) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(items) { day ->
      val daySpend = day.byBags.sumOf { it.spend }
      val dayNet = day.byBags.sumOf { b ->
        val price = b.price ?: 0.0
        ProfitCalc.netProfit(b.orders.toDouble(), price, b.spend, b.cogs, null)
      }

      Card(
        colors = CardDefaults.cardColors(containerColor = SoftGray),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onOpen(day) }
      ) {
        Column(Modifier.padding(14.dp)) {

          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              day.date,
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
              color = TextBlack
            )

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

          Spacer(Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
          ) {
            Icon(
              imageVector = Icons.Default.Edit,
              contentDescription = "edit",
              modifier = Modifier
                .padding(end = 12.dp)
                .size(20.dp)
                .clickable {
                  onEditDay(day)
                }
            )

            if (canDelete) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "delete",
                tint = Color.Red,
                modifier = Modifier
                  .size(20.dp)
                  .clickable {
                    onDeleteDay(day)
                  }
              )
            }
          }

          Spacer(Modifier.height(10.dp))

          day.byBags.take(10).forEach { b ->
            val price = b.price ?: 0.0
            val net = ProfitCalc.netProfit(b.orders.toDouble(), price, b.spend, b.cogs, null)

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
      val price = r.price ?: 0.0
      val net = ProfitCalc.netProfit(
        orders = r.totalOrders,
        price = price,
        spend = r.totalSpend,
        cogs = r.cogs,
        deliveryFee = r.deliveryFee
      )

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
  onDeleteUser: (String) -> Unit,
  onSendPush: (String?, String, String) -> Unit
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
