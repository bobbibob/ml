package com.ml.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ml.app.data.PackPaths
import com.ml.app.domain.BagDayRow
import coil.compose.AsyncImage
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
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = imgFile?.takeIf { it.exists() }, contentDescription = row.bag, modifier = Modifier.size(96.dp))
        Column(Modifier.weight(1f)) {
          Text(row.bag, style = MaterialTheme.typography.titleMedium)
          Text("Заказы всего: ${row.totalOrders}")
          row.price?.let { Text("Цена: $it") }
          row.stock?.let { Text("Остаток: $it") }
        }
      }

      if (row.byColors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("По цветам:", style = MaterialTheme.typography.labelLarge)
        row.byColors.forEach { c -> Text("• ${c.color}: ${c.orders}") }
      }

      if (row.bySources.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("По источникам:", style = MaterialTheme.typography.labelLarge)
        row.bySources.forEach { s -> Text("• ${s.source}: ${s.orders}") }
      }

      row.hypothesis?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(8.dp))
        Text("Гипотеза: $it")
      }
    }
  }
}
