package com.ml.app.ui

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ml.app.BuildConfig
import com.ml.app.data.session.PrefsSessionStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun MlAuthScreen(
    onClose: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val cookieManager = CookieManager.getInstance()
    val session = PrefsSessionStorage(context)
    val token = session.getToken().orEmpty()

    BackHandler {
        onClose()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        val currentUrl = url ?: return
                        if (!currentUrl.contains("mercadolivre")) return

                        val cookiesRaw = cookieManager.getCookie(currentUrl).orEmpty()
                        if (cookiesRaw.isBlank()) return

                        val cookies = cookiesRaw.split(";").mapNotNull { part ->
                            val pieces = part.trim().split("=", limit = 2)
                            if (pieces.size != 2) return@mapNotNull null

                            JSONObject().apply {
                                put("name", pieces[0])
                                put("value", pieces[1])
                                put("domain", ".mercadolivre.com.br")
                                put("path", "/")
                            }
                        }

                        if (cookies.isEmpty()) return

                        Thread {
                            try {
                                val body = JSONObject().apply {
                                    put("source", "mercadolivre")
                                    put("session_payload", JSONObject().apply {
                                        put("cookies", JSONArray(cookies))
                                        put("saved_at", System.currentTimeMillis())
                                        put("source", "android_admin_webview")
                                    })
                                }

                                val request = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/ml/save-session")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post(
                                        body.toString().toRequestBody("application/json".toMediaType())
                                    )
                                    .build()

                                OkHttpClient().newCall(request).execute().use { resp ->
                                    if (resp.isSuccessful) {
                                        onSuccess()
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }.start()
                    }
                }

                loadUrl("https://www.mercadolivre.com.br/")
            }
        }
    )
}
