package com.ml.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.ml.app.MainActivity
import com.ml.app.R
import com.ml.app.data.remote.dto.TaskDto

object UrgentTaskNotifier {

    private const val CHANNEL_ID = "ml_urgent_tasks_channel"
    private const val CHANNEL_NAME = "Срочные задачи"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Постоянные уведомления по срочным задачам"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun canNotify(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PermissionChecker.PERMISSION_GRANTED
        } else true
    }

    private fun notifId(taskId: String): Int = ("urgent_" + taskId).hashCode()

    fun show(context: Context, task: TaskDto) {
        if (!canNotify(context)) return
        if (task.status != "open" || task.is_urgent != 1) return

        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("open_tasks", true)
            putExtra("task_id", task.task_id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId(task.task_id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = buildString {
            append("Срочная задача")
            if (!task.created_by_name.isNullOrBlank()) {
                append(" • от ")
                append(task.created_by_name)
            }
            if (!task.description.isNullOrBlank()) {
                append("\n")
                append(task.description)
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notifId(task.task_id), notification)
    }

    fun cancel(context: Context, taskId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notifId(taskId))
    }

    fun syncForTasks(context: Context, tasks: List<TaskDto>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeIds = tasks
            .filter { it.status == "open" && it.is_urgent == 1 }
            .map { notifId(it.task_id) }
            .toSet()

        tasks.filter { it.status == "open" && it.is_urgent == 1 }
            .forEach { show(context, it) }

        tasks.filter { it.status != "open" || it.is_urgent != 1 }
            .forEach { cancel(context, it.task_id) }
    }
}
