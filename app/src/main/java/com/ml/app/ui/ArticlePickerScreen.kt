package com.ml.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ml.app.data.SQLiteRepo
import kotlinx.coroutines.launch

@Composable
fun ArticlePickerScreen(
  repo: SQLiteRepo,
  onCreateNew: () -> Unit,
  onSelect: (bagId: String) -> Unit
) {
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var items by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
  var status by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    scope.launch {
      try {
        status = "Загружаю…"
        items = repo.listAllBags()
        status = ""
      } catch (t: Throwable) {
        status = "Ошибка: ${t.message}"
      }
    }
  }

  Column(Modifier.fillMaxSize().padding(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = onCreateNew, modifier = Modifier.weight(1f)) {
        Text("➕ Новый артикул")
      }
    }

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      label = { Text("Поиск") },
      modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(12.dp))

    if (status.isNotBlank()) {
      Text(status)
      Spacer(Modifier.height(8.dp))
    }

    val filtered = remember(items, query) {
      val q = query.trim().lowercase()
      if (q.isBlank()) items
      else items.filter { (id, name) ->
        id.lowercase().contains(q) || name.lowercase().contains(q)
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(filtered) { (id, name) ->
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(id) }
        ) {
          Column(Modifier.padding(14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(id, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}
