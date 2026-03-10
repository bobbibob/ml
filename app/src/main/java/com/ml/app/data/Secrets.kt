package com.ml.app.data

import android.content.Context
import com.ml.app.BuildConfig
import com.ml.app.R
import org.json.JSONObject

data class Secrets(
  val endpoint: String,
  val bucket: String,
  val accessKey: String,
  val secretKey: String,
  val objectKey: String,
  val region: String,
  val updatedBy: String
) {
  companion object {
    @Volatile private var cached: Secrets? = null

    private fun strOrNull(v: String?): String? {
      val x = v?.trim().orEmpty()
      if (x.isBlank()) return null
      if (x == "REPLACE_ME") return null
      return x
    }

    fun load(context: Context): Secrets {
      cached?.let { return it }

      val jsonText = context.resources.openRawResource(R.raw.secrets)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

      val j = JSONObject(jsonText)

      val s = Secrets(
        endpoint = strOrNull(BuildConfig.R2_ENDPOINT) ?: j.getString("R2_ENDPOINT"),
        bucket = strOrNull(BuildConfig.R2_BUCKET) ?: j.getString("R2_BUCKET"),
        accessKey = strOrNull(BuildConfig.R2_ACCESS_KEY) ?: j.getString("R2_ACCESS_KEY"),
        secretKey = strOrNull(BuildConfig.R2_SECRET_KEY) ?: j.getString("R2_SECRET_KEY"),
        objectKey = strOrNull(BuildConfig.R2_OBJECT_KEY) ?: j.getString("R2_OBJECT_KEY"),
        region = strOrNull(BuildConfig.R2_REGION) ?: j.optString("R2_REGION", "auto"),
        updatedBy = strOrNull(BuildConfig.UPDATED_BY) ?: j.optString("UPDATED_BY", "ml-app")
      )

      cached = s
      return s
    }
  }
}
