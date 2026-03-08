package com.ml.app.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ml.app.data.SQLiteRepo
import java.time.LocalDate

private data class DailySummaryBagUi(
    val bagId: String,
    val bagName: String,
    val photoPath: String?,
    val colors: List<String>
)

@Composable
fun AddDailySummaryScreen(
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }

    var selectedDate by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    val items = remember { mutableStateListOf<DailySummaryBagUi>() }
    val orders = remember { mutableStateMapOf<String, Int>() }

    var rkEnabled by remember { mutableStateOf(false) }
    var rkSpend by remember { mutableStateOf("") }
    var rkImpressions by remember { mutableStateOf("") }
    var rkClicks by remember { mutableStateOf("") }
    var rkStake by remember { mutableStateOf("") }

    var igEnabled by remember { mutableStateOf(false) }
    var igSpend by remember { mutableStateOf("") }
    var igImpressions by remember { mutableStateOf("") }
    var igClicks by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val meta = repo.listSummaryBagColorMeta()
        items.clear()
        items.addAll(
            meta.map {
                DailySummaryBagUi(
                    bagId = it.bagId,
                    bagName = it.bagName,
                    photoPath = it.photoPath,
                    colors = it.colors.sortedBy { c -> c.lowercase() }
                )
            }
        )
        for (bag in items) {
            for (color in bag.colors) {
                orders.putIfAbsent("${bag.bagId}::$color", 0)
            }
        }
    }

    BackHandler { onBack() }

    fun openDatePicker() {
        DatePickerDialog(
            ctx,
            { _, y, m, d ->
                selectedDate = LocalDate.of(y, m + 1, d)
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "Добавить сводку",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { openDatePicker() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Дата: $selectedDate")
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.bagId }) { bag ->
                Card(
                    colors = CardDefaults.cardColors(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (!bag.photoPath.isNullOrBlank()) {
                                AsyncImage(
                                    model = bag.photoPath,
                                    contentDescription = bag.bagName,
                                    modifier = Modifier
                                        .width(92.dp)
                                        .height(92.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bag.bagName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                for (color in bag.colors) {
                                    val key = "${bag.bagId}::$color"
                                    val value = orders[key] ?: 0

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = color,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedButton(
                                            onClick = { orders[key] = maxOf(0, value - 1) }
                                        ) {
                                            Text("-")
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = value.toString(),
                                            modifier = Modifier.width(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))

                                        OutlinedButton(
                                            onClick = { orders[key] = value + 1 }
                                        ) {
                                            Text("+")
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Расходы",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "РК",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rkEnabled,
                                onCheckedChange = { rkEnabled = it }
                            )
                            Text("Включить РК")
                        }

                        OutlinedTextField(
                            value = rkSpend,
                            onValueChange = { rkSpend = it },
                            enabled = rkEnabled,
                            label = { Text("Расход") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = rkImpressions,
                            onValueChange = { rkImpressions = it.filter { ch -> ch.isDigit() } },
                            enabled = rkEnabled,
                            label = { Text("Показы") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = rkClicks,
                            onValueChange = { rkClicks = it.filter { ch -> ch.isDigit() } },
                            enabled = rkEnabled,
                            label = { Text("Клики") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = rkStake,
                            onValueChange = { rkStake = it },
                            enabled = rkEnabled,
                            label = { Text("Ставка") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Instagram",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = igEnabled,
                                onCheckedChange = { igEnabled = it }
                            )
                            Text("Включить Instagram")
                        }

                        OutlinedTextField(
                            value = igSpend,
                            onValueChange = { igSpend = it },
                            enabled = igEnabled,
                            label = { Text("Расход") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = igImpressions,
                            onValueChange = { igImpressions = it.filter { ch -> ch.isDigit() } },
                            enabled = igEnabled,
                            label = { Text("Показы") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = igClicks,
                            onValueChange = { igClicks = it.filter { ch -> ch.isDigit() } },
                            enabled = igEnabled,
                            label = { Text("Клики") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { onBack() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Дальше будет сохранение")
                        }
                    }
                }
            }
        }
    }
}
