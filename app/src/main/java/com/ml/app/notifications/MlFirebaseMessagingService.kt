package com.ml.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ml.app.core.network.ApiModule
import com.ml.app.data.repository.AuthRepository
import com.ml.app.data.session.PrefsSessionStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MlFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("ML_PUSH", "newToken=$token")

        val session = PrefsSessionStorage(applicationContext)
        val api = ApiModule.createApi(
            baseUrl = "https://ml-tasks-api.bboobb666.workers.dev/",
            sessionStorage = session
        )
        val authRepo = AuthRepository(api, session)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                authRepo.saveFcmToken(token)
                Log.d("ML_PUSH", "token synced")
            } catch (e: Exception) {
                Log.e("ML_PUSH", "token sync failed: ${e.message}", e)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Log.d("ML_PUSH", "onMessageReceived data=${message.data} notification=${message.notification}")

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "ML"

        val body = message.notification?.body
            ?: message.data["body"]
            ?: "У вас новое уведомление"

        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "ml_tasks_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Задачи ML",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
