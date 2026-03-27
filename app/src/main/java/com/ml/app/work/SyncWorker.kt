package com.ml.app.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import com.ml.app.BuildConfig

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val payload = inputData.getString("payload") ?: return Result.success()

        return try {
            val client = OkHttpClient()

            val body = RequestBody.create(
                "application/json".toMediaType(),
                payload
            )

            val request = Request.Builder()
                .url("https://ml-tasks-api.your-domain.workers.dev/card/upsert")
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
