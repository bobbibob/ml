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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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

    val rkEnabled = remember { mutableStateMapOf<String, Boolean>() }
    val rkSpend = remember { mutableStateMapOf<String, String>() }
    val rkImpressions = remember { mutableStateMapOf<String, String>() }
    val rkClicks = remember { mutableStateMapOf<String, String>() }
    val rkStake = remember { mutableStateMapOf<String, String>() }

    val igEnabled = remember { mutableStateMapOf<String, Boolean>() }
    val igSpend = remember { mutableStateMapOf<String, String>() }
    val igImpressions = remember { mutableStateMapOf<String, String>() }
    val igClicks = remember { mutableStateMapOf<String, String>() }

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

            rkEnabled.putIfAbsent(bag.bagId, false)
            rkSpend.putIfAbsent(bag.bagId, "")
            rkImpressions.putIfAbsent(bag.bagId, "")
            rkClicks.putIfAbsent(bag.bagId, "")
            rkStake.putIfAbsent(bag.bagId, "")

            igEnabled.putIfAbsent(bag.bagId, false)
            igSpend.putIfAbsent(bag.bagId, "")
            igImpressions.putIfAbsent(bag.bagId, "")
            igClicks.putIfAbsent(bag.bagId, "")
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

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "РК",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = rkEnabled[bag.bagId] == true,
                                        onCheckedChange = { rkEnabled[bag.bagId] = it }
                                    )
                                    Text("Включить РК")
                                }

                                OutlinedTextField(
                                    value = rkSpend[bag.bagId].orEmpty(),
                                    onValueChange = { rkSpend[bag.bagId] = it },
                                    enabled = rkEnabled[bag.bagId] == true,
                                    label = { Text("Расход") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = rkImpressions[bag.bagId].orEmpty(),
                                    onValueChange = { rkImpressions[bag.bagId] = it.filter { ch -> ch.isDigit() } },
                                    enabled = rkEnabled[bag.bagId] == true,
                                    label = { Text("Показы") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = rkClicks[bag.bagId].orEmpty(),
                                    onValueChange = { rkClicks[bag.bagId] = it.filter { ch -> ch.isDigit() } },
                                    enabled = rkEnabled[bag.bagId] == true,
                                    label = { Text("Клики") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = rkStake[bag.bagId].orEmpty(),
                                    onValueChange = { rkStake[bag.bagId] = it },
                                    enabled = rkEnabled[bag.bagId] == true,
                                    label = { Text("Ставка") },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Instagram",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = igEnabled[bag.bagId] == true,
                                        onCheckedChange = { igEnabled[bag.bagId] = it }
                                    )
                                    Text("Включить Instagram")
                                }

                                OutlinedTextField(
                                    value = igSpend[bag.bagId].orEmpty(),
                                    onValueChange = { igSpend[bag.bagId] = it },
                                    enabled = igEnabled[bag.bagId] == true,
                                    label = { Text("Расход") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = igImpressions[bag.bagId].orEmpty(),
                                    onValueChange = { igImpressions[bag.bagId] = it.filter { ch -> ch.isDigit() } },
                                    enabled = igEnabled[bag.bagId] == true,
                                    label = { Text("Показы") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = igClicks[bag.bagId].orEmpty(),
                                    onValueChange = { igClicks[bag.bagId] = it.filter { ch -> ch.isDigit() } },
                                    enabled = igEnabled[bag.bagId] == true,
                                    label = { Text("Клики") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            item {
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
