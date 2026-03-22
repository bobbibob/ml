package com.ml.app.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ml.app.data.SQLiteRepo
import com.ml.app.data.SQLiteRepo.BagPickerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SkuListScreen(
    isAdmin: Boolean = false,
    onDone: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val repo = remember { SQLiteRepo(ctx) }
    val scope = rememberCoroutineScope()

    var selectedBagId by remember { mutableStateOf<String?>(null) }
    var activeItems by remember { mutableStateOf<List<BagPickerRow>>(emptyList()) }
    var deletedItems by remember { mutableStateOf<List<BagPickerRow>>(emptyList()) }
    var showDeleted by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }

    suspend fun loadAll() {
        activeItems = kotlin.runCatching { repo.listBagPickerRowsV3() }.getOrDefault(emptyList())
        deletedItems = kotlin.runCatching { repo.listDeletedBagPickerRowsV3() }.getOrDefault(emptyList())
    }

    LaunchedEffect(showDeleted) {
        loadAll()
    }

    if (selectedBagId != null) {
        AddEditArticleScreen(
            bagId = selectedBagId,
            onDone = { selectedBagId = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Список SKU", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        if (isAdmin) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = !showDeleted,
                    onClick = { showDeleted = false },
                    label = { Text("Активные") }
                )
                FilterChip(
                    selected = showDeleted,
                    onClick = { showDeleted = true },
                    label = { Text("Удалённые") }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val itemsToShow = if (showDeleted) deletedItems else activeItems
            items(itemsToShow, key = { it.bagId }) { bag ->
                Card(
                    colors = CardDefaults.cardColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!bag.photoPath.isNullOrBlank()) {
                            AsyncImage(
                                model = bag.photoPath,
                                contentDescription = bag.bagId,
                                modifier = Modifier
                                    .width(72.dp)
                                    .height(72.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bag.bagId,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            bag.colorsText?.takeIf { it.isNotBlank() }?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!showDeleted) {
                                OutlinedButton(onClick = { selectedBagId = bag.bagId }) {
                                    Text("Открыть")
                                }
                            }

                            if (isAdmin && !showDeleted) {
                                OutlinedButton(onClick = { pendingDelete = bag.bagId }) {
                                    Text("Удалить")
                                }
                            }

                            if (isAdmin && showDeleted) {
                                OutlinedButton(onClick = { pendingRestore = bag.bagId }) {
                                    Text("Восстановить")
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    pendingDelete?.let { deleteId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить артикул?") },
            text = { Text(deleteId) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            kotlin.runCatching { repo.softDeleteArticleV3(deleteId) }
                            pendingDelete = null
                            loadAll()
                        }
                    }
                ) { Text("Удалить") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    pendingRestore?.let { restoreId ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Восстановить артикул?") },
            text = { Text(restoreId) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            kotlin.runCatching { repo.restoreDeletedArticleV3(restoreId) }
                            pendingRestore = null
                            showDeleted = false
                            loadAll()
                        }
                    }
                ) { Text("Восстановить") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingRestore = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}
