package com.ml.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddEditArticleScreen(
  bagId: String?,
  onDone: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(12.dp)
  ) {
    Column(Modifier.padding(14.dp)) {
      Text(
        text = if (bagId == null) "Add article" else "Edit article",
        style = MaterialTheme.typography.titleLarge
      )
      Spacer(Modifier.height(12.dp))
      Text(
        text = "TODO: form fields (name, hypothesis, price, card type, photo, colors, cogs)",
        style = MaterialTheme.typography.bodyMedium
      )
      Spacer(Modifier.height(16.dp))
      Button(onClick = onDone) { Text("Close") }
    }
  }
}
