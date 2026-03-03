package com.ml.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)

@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
  val state by vm.uiState.collectAsState()

  val ctx = LocalContext.current

  fun openDatePicker() {
    val d = try { LocalDate.parse(state.selectedDate) } catch (_: Exception) { LocalDate.now() }
    DatePickerDialog(
      ctx,
      { _, y, m, day ->
        val newDate = LocalDate.of(y, m + 1, day).toString()
        vm.setDate(newDate)
      },
      d.year, d.monthValue - 1, d.dayOfMonth
    ).show()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
      .padding(16.dp)
  ) {

    Text(
      text = "ml",
      style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
      color = TextBlack
    )

    Spacer(Modifier.height(12.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(
        onClick = { openDatePicker() },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF2F2F2), contentColor = TextBlack),
        modifier = Modifier.weight(1f)
      ) {
        Text(text = "Дата: ${state.selectedDate}", maxLines = 1, overflow = TextOverflow.Ellipsis)
      }

      Button(
        onClick = { vm.refresh() },
        colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White),
        modifier = Modifier.weight(1f)
      ) { Text("Обновить") }

      Button(
        onClick = { vm.save() },
        enabled = state.canSave,
        colors = ButtonDefaults.buttonColors(containerColor = MercadoYellow, contentColor = TextBlack),
        modifier = Modifier.weight(1f)
      ) { Text("Сохранить") }
    }

    Spacer(Modifier.height(12.dp))

    if (state.statusText.isNotBlank()) {
      Text(text = state.statusText, color = TextBlack)
      Spacer(Modifier.height(8.dp))
    }

    // Главный контент
    val scroll = rememberScrollState()
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scroll),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Если у тебя пока один текстовый блок — покажем красиво в карточке
      if (state.prettyText.isNotBlank()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(Modifier.padding(14.dp)) {
            Text(state.prettyText, color = TextBlack)
          }
        }
      }

      // Если ViewModel уже умеет отдавать список сумок (рекомендовано) — покажем карточками
      state.items.forEach { item ->
        Card(
          colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.bag, fontWeight = FontWeight.Bold, color = TextBlack)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Цена: ${item.price}", color = TextBlack)
              Text("Заказы: ${item.ordersTotal}", color = TextBlack)
            }

            Text("Гипотеза: ${item.hypothesis}", color = TextBlack)
            Text("Остаток: ${item.stockTotal}", color = TextBlack)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("РК: ${item.rkSpend}", color = TextBlack)
              Text("IG: ${item.igSpend}", color = TextBlack)
            }

            if (item.colors.isNotEmpty()) {
              Text("По цветам:", fontWeight = FontWeight.SemiBold, color = TextBlack)
              item.colors.forEach { c ->
                Text("• ${c.name}: ${c.value}", color = TextBlack)
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Ниже — контракт UI-state.
 * В твоем проекте он уже есть. Эти интерфейсы должны совпасть по полям.
 * Если названия отличаются — просто скажи, я подгоню ровно под твой SummaryViewModel.
 */
data class SummaryItemUi(
  val bag: String,
  val price: String,
  val hypothesis: String,
  val ordersTotal: String,
  val stockTotal: String,
  val rkSpend: String,
  val igSpend: String,
  val colors: List<ColorUi>
)

data class ColorUi(val name: String, val value: String)
