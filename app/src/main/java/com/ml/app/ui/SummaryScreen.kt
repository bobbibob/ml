package com.ml.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  LaunchedEffect(Unit) { vm.init() }

  var openDatePicker by remember { mutableStateOf(false) }

  Scaffold(topBar = { TopAppBar(title = { Text("ml") }) }) { pad ->
    Column(Modifier.padding(pad).padding(12.dp)) {

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { openDatePicker = true }) { Text("Дата: ${state.selectedDate}") }
        OutlinedButton(onClick = { vm.syncDownload() }, enabled = !state.loading) { Text("Обновить") }
        OutlinedButton(onClick = { vm.syncUploadWithConflictCheck() }, enabled = state.hasPack && !state.loading) { Text("Сохранить") }
      }

      Spacer(Modifier.height(8.dp))
      Text(state.status)

      if (state.loading) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }

      Spacer(Modifier.height(10.dp))

      if (!state.hasPack) {
        Text("Нет данных. Нажми «Обновить» чтобы скачать пакет.")
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          items(state.rows) { row -> BagCard(row) }
        }
      }
    }
  }

  if (openDatePicker) {
    val pickerState = rememberDatePickerState(
      initialSelectedDateMillis = state.selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    DatePickerDialog(
      onDismissRequest = { openDatePicker = false },
      confirmButton = {
        TextButton(onClick = {
          pickerState.selectedDateMillis?.let { ms ->
            val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
            vm.setDate(d)
          }
          openDatePicker = false
        }) { Text("OK") }
      },
      dismissButton = { TextButton(onClick = { openDatePicker = false }) { Text("Отмена") } }
    ) { DatePicker(state = pickerState) }
  }
}

@Composable
private fun BagCard(row: BagDayRow) {
  val context = LocalContext.current
  val imgFile: File? = row.imagePath?.let { rel -> File(PackPaths.packDir(context), rel) }

  Card {
    Column(Modifier.padding(12.dp)) {

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {

        AsyncImage(
          model = imgFile?.takeIf { it.exists() },
          contentDescription = row.bag,
          modifier = Modifier.size(96.dp)
        )

        Column(Modifier.weight(1f)) {
          // HEADER: bag + orders/spend/cpo
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.bag, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
              Text("Заказы: ${fmt0(row.totalOrders)}")
              Text("Расход: ${fmt2(row.totalSpend)}")
              Text("CPO: ${fmt2(row.cpo)}")
            }
          }

          Spacer(Modifier.height(8.dp))

          // Orders by colors
          Text("Заказы по цветам:", style = MaterialTheme.typography.labelLarge)
          if (row.ordersByColors.isEmpty()) {
            Text("—")
          } else {
            row.ordersByColors.forEach { Text("• ${it.color}: ${fmt0(it.value)}") }
          }
        }

        // RIGHT: price + hypothesis + stock by colors
        Column(Modifier.widthIn(min = 150.dp)) {
          row.price?.let { Text("Цена: ${fmt2(it)}") } ?: Text("Цена: —")
          Text("Гипотеза: ${row.hypothesis?.takeIf { it.isNotBlank() } ?: "—"}")
          Spacer(Modifier.height(8.dp))
          Text("Остаток по цветам:", style = MaterialTheme.typography.labelLarge)
          if (row.stockByColors.isEmpty()) {
            Text("—")
          } else {
            row.stockByColors.forEach { Text("• ${it.color}: ${fmt0(it.value)}") }
          }
        }
      }

      Spacer(Modifier.height(12.dp))
      Divider()
      Spacer(Modifier.height(12.dp))

      // ADS: RK / IG / TOTAL
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AdsBox(title = "РК", m = row.rk, modifier = Modifier.weight(1f))
        AdsBox(title = "Instagram", m = row.ig, modifier = Modifier.weight(1f))
        AdsBox(title = "Всего", m = row.totalAds, modifier = Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun AdsBox(title: String, m: AdsMetrics, modifier: Modifier = Modifier) {
  Card(modifier) {
    Column(Modifier.padding(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(6.dp))
      Text("Расход: ${fmt2(m.spend)}")
      Text("Показы: ${m.impressions}")
      Text("Клики: ${m.clicks}")
      Text("CTR: ${fmtPct(m.ctr)}")
      Text("CPC: ${fmt2(m.cpc)}")
    }
  }
}

private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
private fun fmt0(v: Double): String = String.format(java.util.Locale.US, "%.0f", v)
private fun fmtPct(v: Double): String = String.format(java.util.Locale.US, "%.2f%%", v * 100.0)
