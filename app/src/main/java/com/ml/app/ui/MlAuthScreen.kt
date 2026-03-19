package com.ml.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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

private const val ML_START_URL = "https://www.mercadolivre.com.br/"
private const val ML_LOGIN_FALLBACK_URL = "https://www.mercadolivre.com.br/jms/mlb/lgz/login"

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
            buildMlWebView(
                context = context,
                cookieManager = cookieManager,
                token = token,
                onSuccess = onSuccess
            )
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildMlWebView(
    context: android.content.Context,
    cookieManager: CookieManager,
    token: String,
    onSuccess: () -> Unit
): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return handleSpecialUrl(url, view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleSpecialUrl(url.orEmpty(), view)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                val currentUrl = url.orEmpty()
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

        loadUrl(ML_LOGIN_FALLBACK_URL)
    }
}

private fun handleSpecialUrl(url: String, view: WebView?): Boolean {
    if (url.isBlank()) return false

    val lower = url.lowercase()

    if (lower.startsWith("meli://")) {
        view?.post {
            view.loadUrl(ML_LOGIN_FALLBACK_URL)
        }
        return true
    }

    if (
        lower.startsWith("intent://") ||
        lower.startsWith("market://") ||
        lower.startsWith("mercadolibre://")
    ) {
        view?.post {
            view.loadUrl(ML_LOGIN_FALLBACK_URL)
        }
        return true
    }

    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return false
    }

    return true
}
