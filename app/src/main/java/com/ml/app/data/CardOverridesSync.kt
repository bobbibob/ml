package com.ml.app.data

import android.content.Context
import com.ml.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object CardOverridesSync {
    private fun resolveApiBaseUrl(): String {
        val candidates = listOf("TASKS_API_BASE_URL", "API_BASE_URL", "BASE_URL")
        for (name in candidates) {
            val value = kotlin.runCatching {
                val field = BuildConfig::class.java.getField(name)
                field.get(null)?.toString().orEmpty()
            }.getOrDefault("")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val repo = SQLiteRepo(context)
        val since = repo.getLatestServerCardOverrideUpdatedAt().orEmpty()

        val url = if (since.isBlank()) {
            URL(resolveApiBaseUrl() + "/card_overrides")
        } else {
            URL(resolveApiBaseUrl() + "/card_overrides?since=" + java.net.URLEncoder.encode(since, "UTF-8"))
        }

        if (resolveApiBaseUrl().isBlank()) return@withContext

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) return@withContext

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val items = root.optJSONArray("items") ?: return@withContext

            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                repo.upsertServerCardOverride(
                    bagId = o.optString("bag_id"),
                    name = o.optString("name").takeIf { it.isNotBlank() },
                    hypothesis = o.optString("hypothesis").takeIf { it.isNotBlank() },
                    price = o.optDouble("price").takeIf { !it.isNaN() },
                    cogs = o.optDouble("cogs").takeIf { !it.isNaN() },
                    deliveryFee = o.optDouble("delivery_fee").takeIf { !it.isNaN() },
                    cardType = o.optString("card_type").takeIf { it.isNotBlank() },
                    photoPath = o.optString("photo_path").takeIf { it.isNotBlank() },
                    colorsJson = o.optString("colors_json").takeIf { it.isNotBlank() },
                    colorPricesJson = o.optString("color_prices_json").takeIf { it.isNotBlank() },
                    skuLinksJson = o.optString("sku_links_json").takeIf { it.isNotBlank() },
                    updatedAt = o.optString("updated_at")
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}
