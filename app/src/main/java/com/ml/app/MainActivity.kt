package com.ml.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ml.app.ui.SummaryScreen
import com.ml.app.ui.theme.MlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                MlTheme {
                    SummaryScreen()
                }
            }
        } catch (e: Exception) {
            // Если всё сломается, мы увидим причину на экране телефона
            val errorLog = Log.getStackTraceString(e)
            setContent {
                LazyColumn(modifier = Modifier.padding(16.dp)) {
                    item {
                        Text("Критическая ошибка при запуске!", color = Color.Red, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                        Text("\nПричина:\n${e.localizedMessage ?: "Неизвестна"}\n", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text("Полный лог:\n$errorLog", fontSize = androidx.compose.ui.unit.sp(10))
                    }
                }
            }
        }
    }
}
