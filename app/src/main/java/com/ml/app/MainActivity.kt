package com.ml.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ml.app.ui.SummaryScreen
import com.ml.app.ui.theme.MlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ML_STARTUP", "MainActivity started")
        try {
            setContent {
                MlTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        SummaryScreen()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ML_STARTUP", "CRASH: ", e)
            setContent {
                Text(
                    text = "Ошибка при запуске:\n\n${e.message}\n\n${e.stackTrace.take(5).joinToString("\n")}",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
