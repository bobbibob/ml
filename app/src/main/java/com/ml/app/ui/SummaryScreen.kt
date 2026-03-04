@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.ml.app.ui

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
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

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
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.init() }

    BackHandler(enabled = state.mode !is ScreenMode.Timeline) {
        when (state.mode) {
            is ScreenMode.ArticleEditor -> vm.backFromArticleEditor()
            is ScreenMode.ArticlePicker -> vm.backFromArticlePicker()
            else -> vm.backToTimeline()
        }
    }

    val pullState = rememberPullRefreshState(
        refreshing = state.loading,
        onRefresh = { vm.syncIfChanged() }
    )

    Scaffold(
        bottomBar = {
            if (state.mode is ScreenMode.Timeline || state.mode is ScreenMode.Details) {
                ArticleBottomBar(onArticleClick = { vm.openArticlePicker() })
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White)
                .pullRefresh(pullState)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().background(MercadoYellow).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ml", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
                        Spacer(Modifier.weight(1f))
                        if (state.mode !is ScreenMode.Timeline) {
                            TextButton(onClick = { vm.backToTimeline() }) { Text("Назад", color = TextBlack) }
                        }
                    }
                }

                when (val mode = state.mode) {
                    is ScreenMode.Timeline -> TimelineList(state.timeline, state.cardTypes) { vm.openDetails(LocalDate.parse(it.date)) }
                    is ScreenMode.Details -> DetailsList(state.rows, state.cardTypes)
                    is ScreenMode.ArticlePicker -> {
                        Column(Modifier.padding(16.dp)) {
                            Button(onClick = { vm.openArticleEditor(null) }, Modifier.fillMaxWidth()) { Text("Создать новый артикул") }
                        }
                    }
                    is ScreenMode.ArticleEditor -> AddEditArticleScreen(bagId = mode.bagId, onDone = { vm.backFromArticleEditor() })
                }
            }
            PullRefreshIndicator(state.loading, pullState, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun TimelineList(items: List<DaySummary>, cardTypes: Map<String, CardType>, onOpen: (DaySummary) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { day ->
            Card(colors = CardDefaults.cardColors(containerColor = SoftGray), modifier = Modifier.fillMaxWidth().clickable { onOpen(day) }) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(day.date, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.weight(1f))
                        Text("Заказы: ${day.totalOrders}", color = MercadoBlue, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsList(rows: List<BagDayRow>, cardTypes: Map<String, CardType>) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows) { r ->
            Card(colors = CardDefaults.cardColors(containerColor = SoftGray), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.bagName, fontWeight = FontWeight.Bold)
                    Text("Заказы: ${r.totalOrders.toInt()} • Расход: ${fmtMoney(r.totalSpend)}")
                }
            }
        }
    }
}

@Composable
private fun ArticleBottomBar(onArticleClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Button(onClick = onArticleClick, modifier = Modifier.padding(12.dp)) { Text("Добавить/редактировать артикул") }
    }
}

@Composable
private fun BagThumb(absPath: String?) {
    val size = 56.dp
    if (!absPath.isNullOrBlank() && File(absPath).exists()) {
        AsyncImage(model = File(absPath), contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)))
    } else {
        Box(modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAEAEA)))
    }
}
