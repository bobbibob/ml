package com.ml.app.ui

@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.ml.app.ui

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

    val pullState = rememberPullRefreshState(refreshing = state.loading, onRefresh = { vm.syncIfChanged() })

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
        Box(modifier = Modifier.fillMaxSize().padding(padding).pullRefresh(pullState)) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                    is ScreenMode.ArticlePicker -> ArticleSelectionScreen(
                        bags = state.bagsList,
                        onAdd = { vm.openArticleEditor(null) },
                        onEdit = { vm.openArticleEditor(it) }
                    )
                    is ScreenMode.ArticleEditor -> AddEditArticleScreen(
                        bagId = mode.bagId,
                        onDone = { vm.backFromArticleEditor() }
                    )
                }
            }
            PullRefreshIndicator(state.loading, pullState, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
fun DetailsView(state: SummaryState, vm: SummaryViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = SoftGray)) {
                Text("Дата: ${state.selectedDate}", Modifier.padding(vertical = 12.dp).fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            }
            Button(onClick = { vm.syncIfChanged() }, colors = ButtonDefaults.buttonColors(containerColor = MercadoBlue), shape = RoundedCornerShape(24.dp)) {
                Text("Обновить")
            }
        }
        LazyColumn {
            items(state.rows) { row ->
                val type = state.cardTypes[row.bagId] ?: CardType.CLASSIC
                val net = ProfitCalc.netProfit(type, row.totalOrders, row.price ?: 0.0, row.totalSpend, row.cogs)
                Column(Modifier.padding(16.dp)) {
                    Text(row.bagName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        StaticTypePill("Классика", selected = type == CardType.CLASSIC)
                        StaticTypePill("Премиум", selected = type == CardType.PREMIUM)
                    }
                    MetricRow("Заказы: ${row.totalOrders.toInt()}", "Расход: ${fmtMoney(row.totalSpend)}")
                    MetricRow("Чистая прибыль: ", fmtMoney(net), isNet = true)
                    // ... (остальные метрики и списки цветов)
                }
            }
        }
    }
}

@Composable
fun MetricRow(left: String, right: String, isNet: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(left, Modifier.weight(1f))
        Text(right, fontWeight = if (isNet) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun StaticTypePill(text: String, selected: Boolean) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) MercadoYellow else ChipGray).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = TextBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("➕ Добавить новый артикул") }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { showList = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("📝 Редактировать существующий") }
        } else {
            LazyColumn {
                items(bags) { (id, name) ->
                    ListItem(headlineContent = { Text(name) }, modifier = Modifier.clickable { onEdit(id) })
                }
            }
        }
    }
}
