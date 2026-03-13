package com.ml.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import com.ml.app.ui.SummaryScreen

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val openTasksSignalState = mutableStateOf(0)
    private val openTaskIdState = mutableStateOf<String?>(null)

    private fun applyLaunchIntent() {
        val openTasks = intent?.getBooleanExtra("open_tasks", false) == true
        val taskId = intent?.getStringExtra("task_id")?.trim()

        if (openTasks) {
            openTaskIdState.value = taskId
            openTasksSignalState.value = openTasksSignalState.value + 1
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchIntent()
        applyLaunchIntent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme {
                Surface {
                    SummaryScreen(
                        openTasksSignal = openTasksSignalState.value,
                        initialTaskId = openTaskIdState.value
                    )
                }
            }
        }
    }
}
