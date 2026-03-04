package com.ml.app.ui
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
@Composable
fun AddEditArticleScreen(bagId: String?, onDone: () -> Unit) {
    Text("Экран редактирования артикула: $bagId")
}
