package com.ml.app.ui

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ml.app.data.PackPaths
import com.ml.app.domain.BagDayRow
import com.ml.app.domain.DaySummary
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

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
  ) {
    // Header
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

        when (state.mode) {
          is ScreenMode.Details -> {
            TextButton(onClick = { vm.backToTimeline() }) { Text("Назад", color = TextBlack) }
          }
          else -> {
            TextButton(
              onClick = { if (!state.hasPack) vm.syncDownload() else vm.refreshTimeline() }
            ) { Text("Обновить", color = TextBlack) }
          }
        }
      }
    }

    if (!state.hasPack) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
          Text(
            "Нет базы данных (pack).\nНажми «Скачать», чтобы загрузить database_pack.zip",
            color = TextBlack
          )
          Spacer(Modifier.height(12.dp))
          Button(
            onClick = { vm.syncDownload() },
            colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White)
          ) { Text("Скачать") }
        }
      }
      if (state.loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      if (state.status.isNotBlank()) {
        Text(state.status, modifier = Modifier.padding(12.dp), color = Color.Gray)
      }
      return@Column
    }

    // Top date picker
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

      when (state.mode) {
        is ScreenMode.Details -> Button(
          onClick = { vm.refreshDetails() },
          colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White)
        ) { Text("Обновить") }
        else -> Button(
          onClick = { vm.syncUploadWithConflictCheck() },
          colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue, contentColor = Color.White)
        ) { Text("Сохранить") }
      }
    }

    if (state.loading) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    when (val mode = state.mode) {
      is ScreenMode.Timeline -> TimelineList(
        items = state.timeline,
        onOpen = { vm.openDetails(LocalDate.parse(it.date)) }
      )
      is ScreenMode.Details -> DetailsList(rows = state.rows)
    }

    if (state.status.isNotBlank()) {
      Text(
        text = state.status,
        modifier = Modifier.padding(12.dp),
        color = Color.Gray
      )
    }
  }
}

@Composable
private fun BagThumb(imagePath: String?, size: Int = 44) {
  val ctx = LocalContext.current
  val bmp = remember(imagePath) {
    try {
      if (imagePath.isNullOrBlank()) return@remember null
      val f = File(PackPaths.packDir(ctx), imagePath)
      if (!f.exists()) return@remember null
      BitmapFactory.decodeFile(f.absolutePath)
    } catch (_: Throwable) {
      null
    }
  }

  Box(
    modifier = Modifier
      .size(size.dp)
      .background(Color.White, RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center
  ) {
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      // placeholder
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEAEAEA), RoundedCornerShape(12.dp)))
    }
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
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              BagThumb(b.imagePath, size = 40)
              Spacer(Modifier.width(10.dp))
              Text(
                text = b.bag,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TextBlack
              )
              Text(text = b.orders.toString(), color = TextBlack, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun DetailsList(rows: List<BagDayRow>) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    items(rows) { r ->
      Card(
        colors = CardDefaults.cardColors(containerColor = SoftGray),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(Modifier.padding(14.dp)) {

          Row(verticalAlignment = Alignment.CenterVertically) {
            BagThumb(r.imagePath, size = 48)
            Spacer(Modifier.width(10.dp))
            Text(
              text = r.bag,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = TextBlack,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f)
            )
          }

          Spacer(Modifier.height(8.dp))

          Row(Modifier.fillMaxWidth()) {
            Text("Заказы: ${fmtInt(r.totalOrders)}", modifier = Modifier.weight(1f), color = TextBlack)
            Text("Расход: ${fmtMoney(r.totalSpend)}", color = TextBlack)
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
          Text(
            text = "РК: ${fmtMoney(r.rk.spend)} • IG: ${fmtMoney(r.ig.spend)} • CPO: ${fmtMoney(r.cpo)}",
            color = Color.Gray
          )
        }
      }
    }
  }
}
