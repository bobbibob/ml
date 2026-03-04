package com.ml.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ml.app.ui.SummaryScreen
import com.ml.app.ui.theme.MlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                MlTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        SummaryScreen()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ML_CRASH", "Crash in MainActivity", e)
            val stackTrace = Log.getStackTraceString(e)
            setContent {
                Surface(color = Color.White) {
                    LazyColumn(modifier = Modifier.padding(16.dp)) {
                        item {
                            Text("Критическая ошибка!", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("\nПричина: ${e.message}\n", color = Color.Black, fontWeight = FontWeight.Medium)
                            Text("Технический лог:\n$stackTrace", color = Color.DarkGray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
