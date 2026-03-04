package com.ml.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
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
            // Если приложение падает, мы увидим текст ошибки на экране
            Log.e("ML_ERROR", "Crash on startup", e)
            setContent {
                Text(
                    text = "Критическая ошибка при запуске:\n\n${e.toString()}",
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}
