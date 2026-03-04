package com.ml.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ml.app.domain.*
import java.io.File
import java.time.LocalDate

private val MercadoYellow = Color(0xFFFFE600)
private val MercadoBlue = Color(0xFF2D3277)
private val TextBlack = Color(0xFF111111)
private val SoftGray = Color(0xFFF7F7F7)
private val ChipGray = Color(0xFFEAEAEA)

private fun fmtMoney(v: Double): String = String.format("%.2f", v)
private fun fmtPct(v01: Double): String = String.format("%.2f%%", v01 * 100.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init() }

    BackHandler(enabled = state.mode !is ScreenMode.Timeline) {
        when (state.mode) {
            is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
            is ScreenMode.ArticlePicker -> vm.backFromArticlePicker()
            else -> vm.backToTimeline()
        }
    }

    Scaffold(
        bottomBar = {
            if (state.mode is ScreenMode.Timeline || state.mode is ScreenMode.Details) {
                Surface(tonalElevation = 8.dp) {
                    Button(
                        onClick = { vm.openArticlePicker() },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Добавить/редактировать артикул") }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Header
            Box(modifier = Modifier.fillMaxWidth().background(MercadoYellow).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ml", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
                    Spacer(Modifier.weight(1f))
                    if (state.mode !is ScreenMode.Timeline) {
                        TextButton(onClick = { vm.backToTimeline() }) {
                            Text("Назад", color = TextBlack, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            when (val mode = state.mode) {
                is ScreenMode.Timeline -> TimelineView(state, vm)
                is ScreenMode.Details -> DetailsView(state, vm)
                is ScreenMode.ArticlePicker -> ArticleSelectionScreen(state.bagsList, { vm.openArticleEditor(null) }, { vm.openArticleEditor(it) })
                is ScreenMode.ArticleEditor -> AddEditArticleScreen(bagId = mode.bagId, onDone = { vm.backFromArticleEditor() })
            }
        }
    }
}

@Composable
fun DetailsView(state: SummaryState, vm: SummaryViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                Text("Дата: ${state.selectedDate}", Modifier.padding(vertical = 12.dp).fillMaxWidth(), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { vm.syncIfChanged() }, colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue), shape = RoundedCornerShape(24.dp)) {
                Text("Обновить")
            }
        }
        LazyColumn {
            items(state.rows) { row ->
                val type = state.cardTypes[row.bagId] ?: CardType.CLASSIC
                val net = ProfitCalc.netProfit(type, row.totalOrders, row.price ?: 0.0, row.totalSpend, row.cogs)
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BagThumb(row.imagePath)
                        Spacer(Modifier.width(12.dp))
                        Text(row.bagName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StaticTypePill("Классика", selected = type == CardType.CLASSIC)
                        StaticTypePill("Премиум", selected = type == CardType.PREMIUM)
                    }
                    MetricRow("Заказы: ${row.totalOrders.toInt()}", "Расход: ${fmtMoney(row.totalSpend)}")
                    MetricRow("Цена за заказ: ${fmtMoney(row.cpo)}", "CTR: ${fmtPct(row.totalAds.ctr)} • CPC: ${fmtMoney(row.totalAds.cpc)}")
                    MetricRow("Себест.: ${fmtMoney(row.cogs)}", "Чистая прибыль: ${fmtMoney(net)}", isBold = true)
                    Text("органика + инста • Цена: ${fmtMoney(row.price ?: 0.0)}", color = Color.Gray, fontSize = 14.sp)
                    
                    Spacer(Modifier.height(12.dp))
                    ColorSection("Заказы по цветам", row.ordersByColors)
                    Spacer(Modifier.height(12.dp))
                    ColorSection("Остаток по цветам", row.stockByColors)
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = ChipGray)
                }
            }
        }
    }
}

@Composable
fun MetricRow(left: String, right: String, isBold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(left, Modifier.weight(1f))
        Text(right, fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun ColorSection(title: String, items: List<ColorValue>) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    items.forEach { cv ->
        Row(Modifier.fillMaxWidth()) {
            Text(cv.color, Modifier.weight(1f))
            Text(cv.value.toInt().toString(), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StaticTypePill(text: String, selected: Boolean) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) MercadoYellow else ChipGray).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, color = TextBlack, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BagThumb(path: String?) {
    val size = 56.dp
    if (!path.isNullOrBlank() && File(path).exists()) {
        AsyncImage(model = File(path), contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(ChipGray))
    }
}

@Composable
fun TimelineView(state: SummaryState, vm: SummaryViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.timeline) { day ->
            ListItem(
                headlineContent = { Text(day.date, fontWeight = FontWeight.Bold) },
                trailingContent = { Text("Заказы: ${day.totalOrders}", color = MercadoBlue, fontWeight = FontWeight.Black) },
                modifier = Modifier.clickable { vm.openDetails(LocalDate.parse(day.date)) }
            )
        }
    }
}

@Composable
fun ArticleSelectionScreen(bags: List<Pair<String, String>>, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    var showList by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!showList) {
            Button(onClick = onAdd, Modifier.fillMaxWidth().height(56.dp)) { Text("➕ Добавить новый артикул") }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { showList = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("📝 Редактировать существующий") }
        } else {
            LazyColumn { items(bags) { (id, name) -> ListItem(headlineContent = { Text(name) }, modifier = Modifier.clickable { onEdit(id) }) } }
        }
    }
}
