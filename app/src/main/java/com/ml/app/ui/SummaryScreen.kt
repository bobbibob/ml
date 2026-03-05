@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.ml.app.ui

import android.app.DatePickerDialog
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)
private val SoftGray = Color(0xFFF7F7F7)
private val ChipGray = Color(0xFFEAEAEA)

private fun fmtInt(v: Double): String = v.roundToInt().toString()
private fun fmtMoney(v: Double): String = String.format("%.2f", v)
private fun fmtPct(v01: Double): String = String.format("%.2f%%", v01 * 100.0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  val ctx = LocalContext.current

  LaunchedEffect(Unit) { vm.init() }

  BackHandler(enabled = (state.mode is ScreenMode.Details) || (state.mode is ScreenMode.ArticleEditor)) {
    when (state.mode) {
      is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
      else -> vm.backToTimeline()
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
          Text(
            text = "ml",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = TextBlack
          )
          Spacer(Modifier.weight(1f))

          if (state.mode is ScreenMode.Details) {
            TextButton(onClick = { vm.backToTimeline() }) { Text("Назад", color = TextBlack) }


          } else {
            TextButton(onClick = { vm.syncIfChanged() }) { Text("Проверить", color = TextBlack) }


          }
        }
      }

      if (!state.hasPack) {
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
            Text("Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)


          }

          Button(
            onClick = { vm.syncIfChanged() },
            colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White)
          ) { Text("Обновить") }
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
}

        if (state.status.isNotBlank()) {
          Text(text = state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
        }
      }
    }

    ArticleBottomBar(
      onArticleClick = { vm.openArticleEditor() },
      modifier = Modifier.align(Alignment.BottomCenter)
    )

    PullRefreshIndicator(
      refreshing = state.loading,
      state = pullState,
      modifier = Modifier.align(Alignment.TopCenter)
    )
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
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    tonalElevation = 4.dp
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Button(
        onClick = onArticleClick,
        modifier = Modifier.weight(1f)
      ) {
        Text("Добавить/редактировать артикул")
      }
    }
  }
}
