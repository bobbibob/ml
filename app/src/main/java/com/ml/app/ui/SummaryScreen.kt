@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
package com.ml.app.ui

import android.app.DatePickerDialog
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
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.CardType
import com.ml.app.domain.DaySummary
import com.ml.app.domain.ProfitCalc
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)
private val SoftGray = Color(0xFFF7F7F7)

private fun fmtInt(v: Double): String = v.roundToInt().toString()
private fun fmtMoney(v: Double): String = String.format("%.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  val ctx = LocalContext.current

  LaunchedEffect(Unit) { vm.init() }

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
    Column(modifier = Modifier.fillMaxSize()) {

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

        when (val mode = state.mode) {
          is ScreenMode.Timeline -> TimelineList(
            items = state.timeline,
            onOpen = { vm.openDetails(LocalDate.parse(it.date)) }
          )
          is ScreenMode.Details -> DetailsList(
            rows = state.rows,
            cardTypes = state.cardTypes,
            onSetType = { bagId, t -> vm.setCardType(bagId, t) }
          )
        }

        if (state.status.isNotBlank()) {
          Text(text = state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
        }
      }
    }

    PullRefreshIndicator(
      refreshing = state.loading,
      state = pullState,
      modifier = Modifier.align(Alignment.TopCenter)
    )
  }
}

@Composable
private fun TimelineList(items: List<DaySummary>, onOpen: (DaySummary) -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(items) { day ->
      Card(
        colors = CardDefaults.cardColors(containerColor = SoftGray),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onOpen(day) }
      ) {
        Column(Modifier.padding(14.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = day.date,
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
              color = TextBlack
            )
            Spacer(Modifier.weight(1f))
            Text(
              text = "Заказы: ${day.totalOrders}",
              style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
              color = MercadoBlue
            )
          }

          Spacer(Modifier.height(10.dp))

          day.byBags.take(12).forEach { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              BagThumb(b.imagePath)
              Spacer(Modifier.width(10.dp))
              Text(
                text = b.bagName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextBlack
              )
              Text(text = b.orders.toString(), color = TextBlack, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DetailsList(
  rows: List<BagDayRow>,
  cardTypes: Map<String, CardType>,
  onSetType: (String, CardType) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(rows) { r ->
      val type = cardTypes[r.bagId] ?: CardType.CLASSIC

      val price = r.price ?: 0.0
      val net = ProfitCalc.netProfit(
        type = type,
        orders = r.totalOrders,
        price = price,
        spend = r.totalSpend,
        cogs = r.cogs
      )

      Card(
        colors = CardDefaults.cardColors(containerColor = SoftGray),
        modifier = Modifier.fillMaxWidth()
      ) {
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

          Spacer(Modifier.height(8.dp))

          // Type switch
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
              onClick = { onSetType(r.bagId, CardType.CLASSIC) },
              label = { Text("Классика") },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = if (type == CardType.CLASSIC) MercadoYellow else Color(0xFFEAEAEA),
                labelColor = TextBlack
              )
            )
            AssistChip(
              onClick = { onSetType(r.bagId, CardType.PREMIUM) },
              label = { Text("Премиум") },
              colors = AssistChipDefaults.assistChipColors(
                containerColor = if (type == CardType.PREMIUM) MercadoYellow else Color(0xFFEAEAEA),
                labelColor = TextBlack
              )
            )
          }

          Spacer(Modifier.height(8.dp))

          Row(Modifier.fillMaxWidth()) {
            Text("Заказы: ${fmtInt(r.totalOrders)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("Расход: ${fmtMoney(r.totalSpend)}", color = TextBlack)
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

          if (rkEmpty) Text("Нет РК", color = Color.Gray)
          else Text("РК: расход ${fmtMoney(r.rk.spend)} • показы ${r.rk.impressions} • клики ${r.rk.clicks}", color = Color.Gray)

          if (igEmpty) Text("Нет Instagram", color = Color.Gray)
          else Text("Instagram: расход ${fmtMoney(r.ig.spend)} • показы ${r.ig.impressions} • клики ${r.ig.clicks}", color = Color.Gray)
        }
      }
    }
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
