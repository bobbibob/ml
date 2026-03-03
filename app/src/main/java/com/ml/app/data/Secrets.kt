package com.ml.app.data

import android.content.Context
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

    fun load(context: Context): Secrets {
      cached?.let { return it }

      val jsonText = context.resources.openRawResource(R.raw.secrets)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

      val j = JSONObject(jsonText)
      val s = Secrets(
        endpoint = j.getString("R2_ENDPOINT"),
        bucket = j.getString("R2_BUCKET"),
        accessKey = j.getString("R2_ACCESS_KEY"),
        secretKey = j.getString("R2_SECRET_KEY"),
        objectKey = j.getString("R2_OBJECT_KEY"),
        region = j.optString("R2_REGION", "auto"),
        updatedBy = j.optString("UPDATED_BY", "ml-app")
      )
      cached = s
      return s
    }
  }
}
