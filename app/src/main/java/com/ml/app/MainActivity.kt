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

    private fun syncFcmTokenNow() {
        val session = com.ml.app.data.session.PrefsSessionStorage(applicationContext)
        if (session.getToken().isNullOrBlank()) return

        val api = com.ml.app.core.network.ApiModule.createApi(
            baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
            sessionStorage = session
        )
        val authRepo = com.ml.app.data.repository.AuthRepository(api, session)

        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                val token = task.result ?: return@addOnCompleteListener

                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    kotlin.runCatching { authRepo.saveFcmToken(token) }
                }
            }
    }


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
        syncFcmTokenNow()
        applyLaunchIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncFcmTokenNow()
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
