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
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val fcmSyncPrefsName = "ml_fcm_sync"
    private val lastSyncedFcmTokenKey = "last_synced_fcm_token"

    private fun lastSyncedFcmToken(): String {
        return applicationContext
            .getSharedPreferences(fcmSyncPrefsName, android.content.Context.MODE_PRIVATE)
            .getString(lastSyncedFcmTokenKey, null)
            .orEmpty()
    }

    private fun markFcmTokenSynced(token: String) {
        applicationContext
            .getSharedPreferences(fcmSyncPrefsName, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(lastSyncedFcmTokenKey, token)
            .apply()
    }


    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val openTasksSignalState = mutableStateOf(0)
    private val openTaskIdState = mutableStateOf<String?>(null)

    private fun extractPushTaskId(src: Intent?): String? {
        val direct = src?.getStringExtra("task_id")?.trim().orEmpty()
        if (direct.isNotBlank()) return direct

        val fromData = src?.data?.getQueryParameter("task_id")?.trim().orEmpty()
        if (fromData.isNotBlank()) return fromData

        val extrasTaskId = src?.extras?.get("task_id")?.toString()?.trim().orEmpty()
        if (extrasTaskId.isNotBlank()) return extrasTaskId

        return null
    }

    private fun shouldOpenTasksFromIntent(src: Intent?): Boolean {
        if (src?.getBooleanExtra("open_tasks", false) == true) return true
        if (!extractPushTaskId(src).isNullOrBlank()) return true
        return false
    }

    private fun syncFcmTokenNow() {
        val session = com.ml.app.data.session.PrefsSessionStorage(applicationContext)
        if (session.getToken().isNullOrBlank()) return

        val api = com.ml.app.core.network.ApiModule.createApi(
            baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
            sessionStorage = session
        )
        val authRepo = com.ml.app.data.repository.AuthRepository(api, session)

        CoroutineScope(Dispatchers.IO).launch {
            kotlin.runCatching {
                val fm = com.google.firebase.messaging.FirebaseMessaging.getInstance()
                val token = Tasks.await(fm.token)?.trim().orEmpty()
                if (token.isBlank()) return@launch
                authRepo.saveFcmToken(token)
                markFcmTokenSynced(token)
            }
        }
    }


    private fun applyLaunchIntent() {
        val src = intent
        val openTasks = shouldOpenTasksFromIntent(src)
        val taskId = extractPushTaskId(src)

        if (openTasks) {
            openTaskIdState.value = taskId
            openTasksSignalState.value = openTasksSignalState.value + 1
            src?.removeExtra("open_tasks")
            src?.removeExtra("task_id")
        } else {
            openTaskIdState.value = null
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
