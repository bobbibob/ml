package com.ml.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ml.app.data.PackPaths
import com.ml.app.domain.AdsMetrics
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.ColorValue
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)
private val SoftGray = Color(0xFFF7F7F7)

private fun fmt2(v: Double): String = String.format("%.2f", v)
private fun fmt0(v: Double): String = v.roundToInt().toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val state by vm.state.collectAsState()

  LaunchedEffect(Unit) { vm.init() }

  var openDatePicker by remember { mutableStateOf(false) }
  val pickerState = rememberDatePickerState(
    initialSelectedDateMillis = state.selectedDate
      .atStartOfDay(ZoneId.systemDefault())
      .toInstant().toEpochMilli()
  )

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("ml", fontWeight = FontWeight.Bold, color = TextBlack) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.White)
      )
    },
    containerColor = Color.White
  ) { pad ->
    Column(
      modifier = Modifier
        .padding(pad)
        .fillMaxSize()
        .background(Color.White)
        .padding(14.dp)
    ) {

      // Верхняя панель: дата + кнопки
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Button(
          onClick = { openDatePicker = true },
          colors = ButtonDefaults.buttonColors(containerColor = SoftGray, contentColor = TextBlack),
          modifier = Modifier.weight(1.2f)
        ) {
          Text(
            "Дата: ${state.selectedDate}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Button(
          onClick = { vm.syncDownload() },
          enabled = !state.loading,
          colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White),
          modifier = Modifier.weight(1f)
        ) { Text("Обновить") }

        Button(
          onClick = { vm.syncUploadWithConflictCheck() },
          enabled = state.hasPack && !state.loading,
          colors = ButtonDefaults.buttonColors(containerColor = MercadoYellow, contentColor = TextBlack),
          modifier = Modifier.weight(1f)
        ) { Text("Сохранить") }
      }

      Spacer(Modifier.height(10.dp))

      if (state.status.isNotBlank()) {
        Text(state.status, color = TextBlack)
        Spacer(Modifier.height(6.dp))
      }

      if (state.loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
      }

      if (!state.hasPack) {
        Card(colors = CardDefaults.cardColors(containerColor = SoftGray)) {
          Column(Modifier.padding(12.dp)) {
            Text("Нет данных. Нажми «Обновить», чтобы скачать пакет.", color = TextBlack)
          }
        }
        return@Column
      }

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(state.rows) { row ->
          BagCardMercado(row)
        }
      }
    }
  }

  if (openDatePicker) {
    DatePickerDialog(
      onDismissRequest = { openDatePicker = false },
      confirmButton = {
        TextButton(onClick = {
          pickerState.selectedDateMillis?.let { ms ->
            val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
            vm.setDate(d)
          }
          openDatePicker = false
        }) { Text("OK", color = TextBlack) }
      },
      dismissButton = {
        TextButton(onClick = { openDatePicker = false }) { Text("Отмена", color = TextBlack) }
      }
    ) { DatePicker(state = pickerState) }
  }
}

@Composable
private fun BagCardMercado(row: BagDayRow) {
  val context = LocalContext.current
  val imgFile: File? = row.imagePath?.let { rel -> File(PackPaths.packDir(context), rel) }

  Card(
    colors = CardDefaults.cardColors(containerColor = SoftGray),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

      // Header: image + title + key stats
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
          model = imgFile?.takeIf { it.exists() },
          contentDescription = row.bag,
          modifier = Modifier.size(86.dp)
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(row.bag, fontWeight = FontWeight.Bold, color = TextBlack, maxLines = 2, overflow = TextOverflow.Ellipsis)

          // price + orders
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Заказы: ${fmt0(row.totalOrders)}", color = TextBlack)
            Text("Цена: ${row.price?.let { fmt2(it) } ?: "—"}", color = TextBlack)
          }

          // spend + cpo
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Расход: ${fmt2(row.totalSpend)}", color = TextBlack)
            Text("CPO: ${fmt2(row.cpo)}", color = TextBlack)
          }
        }
      }

      row.hypothesis?.takeIf { it.isNotBlank() }?.let {
        Text("Гипотеза: $it", color = TextBlack)
      }

      // Ads blocks
      AdsLine(title = "Внутр. РК", m = row.rk)
      AdsLine(title = "Instagram", m = row.ig)
      AdsLine(title = "Итого Ads", m = row.totalAds, emphasize = true)

      // Colors: orders
      if (row.ordersByColors.isNotEmpty()) {
        Text("Заказы по цветам:", fontWeight = FontWeight.SemiBold, color = TextBlack)
        ColorList(row.ordersByColors)
      }

      // Colors: stock
      if (row.stockByColors.isNotEmpty()) {
        Text("Остаток по цветам:", fontWeight = FontWeight.SemiBold, color = TextBlack)
        ColorList(row.stockByColors)
      }
    }
  }
}

@Composable
private fun AdsLine(title: String, m: AdsMetrics, emphasize: Boolean = false) {
  val labelColor = if (emphasize) MercadoBlue else TextBlack
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(title, fontWeight = FontWeight.SemiBold, color = labelColor)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Spend: ${fmt2(m.spend)}", color = TextBlack)
      Text("Clicks: ${m.clicks}", color = TextBlack)
      Text("CTR: ${fmt2(m.ctr)}", color = TextBlack)
      Text("CPC: ${fmt2(m.cpc)}", color = TextBlack)
    }
  }
}

@Composable
private fun ColorList(list: List<ColorValue>) {
  // Показываем максимум 12, чтобы карточка не становилась гигантской
  val top = list.take(12)
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    top.forEach { c ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(c.color, color = TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(fmt2(c.value), color = TextBlack)
      }
    }
  }
}
