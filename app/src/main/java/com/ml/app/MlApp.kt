package com.ml.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureMlNotificationChannel()
    }

    private fun ensureMlNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "ml_tasks_channel",
                "Задачи ML",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления по задачам ML"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
