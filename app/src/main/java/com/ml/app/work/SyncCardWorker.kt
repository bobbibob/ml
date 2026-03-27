package com.ml.app.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import com.ml.app.BuildConfig

class SyncCardWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val json = inputData.getString("payload") ?: return Result.failure()

        return try {
            val client = OkHttpClient()

            val body = RequestBody.create(
                "application/json".toMediaType(),
                json
            )

            val request = Request.Builder()
                .url(BuildConfig.TASKS_API_BASE_URL + "/card/upsert")
                .post(body)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) Result.success()
            else Result.retry()

        } catch (e: Exception) {
            Result.retry()
        }
    }
}
