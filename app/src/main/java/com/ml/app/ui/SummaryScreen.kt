@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.ml.app.ui

import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.material3.AlertDialog

import android.app.Activity

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    if (showTasks.value) {
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
                  onClick = { showAdminDialog = true },
                  shape = RoundedCornerShape(20.dp)
                ) {
                  Text("adm")
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

      if (showTasks.value) {
        TasksScreen(onBack = { showTasks.value = false }, vm = tasksVm)
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

              Button(
                onClick = { vm.syncPackNow() },
                enabled = !state.loading
              ) {
                Text("Синхр.")
              }
            }
          }

          when (state.mode) {
            is ScreenMode.Timeline -> TimelineList(
              items = state.timeline,
              cardTypes = state.cardTypes,
              onOpen = { vm.openDetails(LocalDate.parse(it.date)) }
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
              onBack = { vm.backFromAddDailySummary() }
            )
          }

          if (state.status.isNotBlank()) {
          Text(text = state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
        }
      }
    }

    if (showTasks.value) {
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

      if (showAdminDialog) {
        AlertDialog(
          onDismissRequest = { showAdminDialog = false },
          title = { Text("adm") },
          text = { Text("Админ-панель подключим следующим пакетом.") },
          confirmButton = {
            Button(onClick = { showAdminDialog = false }) {
              Text("OK")
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
  onOpen: (DaySummary) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(items) { day ->
      val daySpend = day.byBags.sumOf { it.spend }
      val dayNet = day.byBags.sumOf { b ->
        val t = cardTypes[b.bagId] ?: CardType.CLASSIC
        val price = b.price ?: 0.0
        ProfitCalc.netProfit(t, b.orders.toDouble(), price, b.spend, b.cogs)
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
private fun AdminScreen(
  adminTab: String,
  onTabChange: (String) -> Unit,
  users: List<com.ml.app.data.remote.dto.UserDto>,
  tasks: List<com.ml.app.data.remote.dto.TaskDto>,
  history: List<com.ml.app.data.remote.dto.HistoryItemDto>,
  error: String?
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
      ) {
        Text("Пользователи")
      }
      Button(
        onClick = { onTabChange("tasks") },
        modifier = Modifier.weight(1f)
      ) {
        Text("Задачи")
      }
      Button(
        onClick = { onTabChange("history") },
        modifier = Modifier.weight(1f)
      ) {
        Text("История")
      }
    }

    error?.let {
      Text("Ошибка: $it", color = Color.Red)
    }

    when (adminTab) {
      "tasks" -> AdminTasksTab(tasks = tasks)
      "history" -> AdminHistoryTab(history = history)
      else -> AdminUsersTab(users = users)
    }
  }
}

@Composable
private fun AdminUsersTab(
  users: List<com.ml.app.data.remote.dto.UserDto>
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
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(user.display_name, fontWeight = FontWeight.Bold)
          Text(user.email)
          Text("Роль: ${user.role}")
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
