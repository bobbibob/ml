package com.ml.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.ml.app.data.PackUploadManager
import com.ml.app.data.SQLiteRepo
import kotlinx.coroutines.launch

private data class StockBagUi(
    val bagId: String,
    val bagName: String,
    val photoPath: String?,
    val colors: List<Pair<String, Double>>
)

@Composable
fun StockScreen(
    date: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<StockBagUi>>(emptyList()) }
    var editingBagId by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateMapOf<String, String>() }

    suspend fun reload() {
        val meta = repo.listStockBagMeta()

        val stocks = repo.getResolvedStocksForDate(date)
            .groupBy { it.bagId }

        items = meta.mapNotNull { bag ->
            val rows = stocks[bag.bagId]
                ?.sortedBy { it.color.lowercase() }
                ?.map { it.color to it.stock }

            if (rows.isNullOrEmpty()) null
            else StockBagUi(
                bagId = bag.bagId,
                bagName = bag.bagName,
                photoPath = bag.photoPath,
                colors = rows
            )
        }
    }

    LaunchedEffect(date) {
        reload()
    }

    BackHandler {
        if (editingBagId != null) {
            editingBagId = null
            drafts.clear()
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "Остатки",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

                                for ((color, stock) in bag.colors) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = color,
                                            modifier = Modifier.weight(1f)
                                        )

                                        if (editingBagId == bag.bagId) {
                                            val key = "${bag.bagId}::$color"
                                            OutlinedTextField(
                                                value = drafts[key] ?: stock.toInt().toString(),
                                                onValueChange = { drafts[key] = it },
                                                modifier = Modifier.width(120.dp),
                                                singleLine = true
                                            )
                                        } else {
                                            Text(stock.toInt().toString())
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (editingBagId == bag.bagId) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        editingBagId = null
                                        drafts.clear()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Отменить")
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val rows = bag.colors.map { (color, stock) ->
                                                val key = "${bag.bagId}::$color"
                                                val value = (drafts[key] ?: stock.toInt().toString()).trim()
                                                color to (value.toIntOrNull()?.toDouble() ?: stock.toInt().toDouble())
                                            }
                                            repo.replaceBagStockOverrides(date, bag.bagId, rows)
                                            PackUploadManager.saveUserChangesAndUpload(ctx)
                                            editingBagId = null
                                            drafts.clear()
                                            reload()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Сохранить")
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    editingBagId = bag.bagId
                                    drafts.clear()
                                    for ((color, stock) in bag.colors) {
                                        drafts["${bag.bagId}::$color"] = stock.toInt().toString()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Редактировать")
                            }
                        }
                    }
                }
            }
        }
    }
}
