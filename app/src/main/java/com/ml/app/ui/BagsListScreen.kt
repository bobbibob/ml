package com.ml.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BagsListScreen(
  items: List<BagInfo>,
  onEdit: (String) -> Unit,
  onCreateNew: () -> Unit,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Сумки",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
        modifier = Modifier.weight(1f)
      )
      TextButton(onClick = onBack) { Text("Назад") }
    }

    Spacer(Modifier.height(10.dp))

    LazyColumn(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(items, key = { it.bagId }) { b ->
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(b.bagId) }
        ) {
          Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
              text = b.bagName,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
              text = "ID: ${b.bagId}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onEdit(b.bagId) }, modifier = Modifier.align(Alignment.End)) {
              Text("Редактировать")
            }
          }
        }
      }
    }

    Spacer(Modifier.height(10.dp))

    Button(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
      Text("+ Создать новый")
    }
  }
}
