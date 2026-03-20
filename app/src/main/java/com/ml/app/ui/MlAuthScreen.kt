package com.ml.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ml.app.BuildConfig
import com.ml.app.data.session.PrefsSessionStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val ML_START_URL = "https://www.mercadolivre.com.br/vendas/omni/lista?filters=TAB_TODAY"

@Composable
fun MlAuthScreen(
    onClose: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val cookieManager = CookieManager.getInstance()
    val session = PrefsSessionStorage(context)
    val token = session.getToken().orEmpty()

    var currentUrl by remember { mutableStateOf(ML_START_URL) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var statusText by remember { mutableStateOf("Войдите в Mercado Livre, затем нажмите «Сохранить сессию».") }

    BackHandler { onClose() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onClose) {
                Text("Назад")
            }

            Button(
                onClick = {
                    val url = currentUrl
                    val cookiesRaw = cookieManager.getCookie(url).orEmpty()
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

                    if (cookies.isEmpty()) {
                        statusText = "Cookies ещё не появились. Сначала войдите в аккаунт."
                        return@Button
                    }

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
            ) {
                Text("Сохранить сессию")
            }

            Button(
                onClick = {
                    val webView = webViewRef ?: return@Button
                    statusText = "Снимаем DOM..."

                    val js = """
                        (function() {
                          function txt(el) {
                            return ((el && (el.innerText || el.textContent)) || "").trim();
                          }

                          function sample(list) {
                            return list
                              .map(x => txt(x))
                              .filter(Boolean)
                              .slice(0, 10);
                          }

                          const trNodes = Array.from(document.querySelectorAll("tr"));
                          const rowNodes = Array.from(document.querySelectorAll("[role='row']"));
                          const articleNodes = Array.from(document.querySelectorAll("article"));
                          const liNodes = Array.from(document.querySelectorAll("li"));
                          const cardNodes = Array.from(document.querySelectorAll(".andes-card"));

                          return JSON.stringify({
                            url: location.href,
                            title: document.title,
                            counts: {
                              tr: trNodes.length,
                              row: rowNodes.length,
                              article: articleNodes.length,
                              li: liNodes.length,
                              andesCard: cardNodes.length
                            },
                            samples: {
                              tr: sample(trNodes),
                              row: sample(rowNodes),
                              article: sample(articleNodes),
                              li: sample(liNodes),
                              andesCard: sample(cardNodes)
                            },
                            bodyPreview: txt(document.body).slice(0, 4000)
                          });
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { result ->
                        try {
                            val raw = result ?: ""
                            val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                JSONObject("{\"v\":$raw}").getString("v")
                            } else {
                                raw
                            }

                            val json = JSONObject(cleaned)
                            statusText = json.toString(2).take(6000)
                        } catch (t: Throwable) {
                            statusText = "DOM debug error: ${t.message}"
                        }
                    }
                }
            ) {
                Text("Показать DOM")
            }

            Button(
                onClick = {
                    val webView = webViewRef ?: return@Button
                    statusText = "Читаем заказы со страницы..."

                    val js = """
                        (function() {
                          function txt(el) {
                            return ((el && (el.innerText || el.textContent)) || "").trim();
                          }

                          const nodes = Array.from(document.querySelectorAll("tr, [role='row'], article, li, .andes-card"));
                          const orders = [];

                          for (const node of nodes) {
                            const raw = txt(node);
                            if (!raw) continue;

                            const idMatch = raw.match(/\b\d{6,}\b/);
                            if (!idMatch) continue;

                            const amountMatch = raw.match(/R\$\s*([\d\.,]+)/i);
                            let amount = null;
                            if (amountMatch) {
                              amount = Number(amountMatch[1].replace(/\./g, "").replace(",", "."));
                              if (!Number.isFinite(amount)) amount = null;
                            }

                            const parts = raw.split("\n").map(s => s.trim()).filter(Boolean);

                            orders.push({
                              external_order_id: idMatch[0],
                              title: parts[0] || null,
                              buyer_name: null,
                              status: parts.find(x => /cancel|pago|entreg|envio|pendente|pronto/i.test(x)) || null,
                              substatus: null,
                              amount: amount,
                              currency: "BRL"
                            });
                          }

                          return JSON.stringify({
                            url: location.href,
                            title: document.title,
                            count: orders.length,
                            orders: orders.slice(0, 100)
                          });
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { result ->
                        Thread {
                            try {
                                val raw = result ?: ""
                                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").getString("v")
                                } else {
                                    raw
                                }

                                val json = JSONObject(cleaned)
                                val orders = json.optJSONArray("orders") ?: JSONArray()

                                if (orders.length() == 0) {
                                    statusText = "Заказы не найдены. Проверь, что открыт список продаж."
                                    return@Thread
                                }

                                val client = OkHttpClient()

                                val upsertBody = JSONObject().apply {
                                    put("orders", orders)
                                }

                                val upsertReq = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/orders/upsert-bulk")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post(upsertBody.toString().toRequestBody("application/json".toMediaType()))
                                    .build()

                                client.newCall(upsertReq).execute().use { resp ->
                                    if (!resp.isSuccessful) {
                                        statusText = "Ошибка отправки заказов: ${resp.code}"
                                        return@Thread
                                    }
                                }

                                val summaryReq = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/ml/generate-orders-summary")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post("{}".toRequestBody("application/json".toMediaType()))
                                    .build()

                                client.newCall(summaryReq).execute().use { resp ->
                                    if (!resp.isSuccessful) {
                                        statusText = "Заказы отправлены, но summary не создан: ${resp.code}"
                                        return@Thread
                                    }
                                }

                                statusText = "Синхронизация ML завершена: ${orders.length()} заказов."
                            } catch (t: Throwable) {
                                statusText = "Ошибка синхронизации: ${t.message}"
                            }
                        }.start()
                    }
                }
            ) {
                Text("Синхронизировать ML")
            }
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                buildMlWebView(
                    context = context,
                    cookieManager = cookieManager,
                    onUrlChanged = { url ->
                        currentUrl = url
                    },
                    onStatusChanged = { text ->
                        statusText = text
                    }
                ).also { webViewRef = it }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildMlWebView(
    context: android.content.Context,
    cookieManager: CookieManager,
    onUrlChanged: (String) -> Unit,
    onStatusChanged: (String) -> Unit
): WebView {
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return handleSpecialUrl(url, view, onStatusChanged)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleSpecialUrl(url.orEmpty(), view, onStatusChanged)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onUrlChanged(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url.orEmpty()
                onUrlChanged(currentUrl)

                if (currentUrl.contains("mercadolivre")) {
                    val cookiesRaw = cookieManager.getCookie(currentUrl).orEmpty()
                    if (cookiesRaw.isNotBlank()) {
                        onStatusChanged("Вход выполнен или cookies доступны. Нажмите «Сохранить сессию».")
                    }
                }
            }
        }

        loadUrl(ML_START_URL)
    }
}

private fun handleSpecialUrl(
    url: String,
    view: WebView?,
    onStatusChanged: (String) -> Unit
): Boolean {
    if (url.isBlank()) return false

    val lower = url.lowercase()

    if (lower.startsWith("meli://")) {
        onStatusChanged("Приложение Mercado Livre заблокировано, остаёмся в веб-версии.")
        view?.post {
            view.loadUrl(ML_START_URL)
        }
        return true
    }

    if (
        lower.startsWith("intent://") ||
        lower.startsWith("market://") ||
        lower.startsWith("mercadolibre://")
    ) {
        onStatusChanged("Открытие приложения заблокировано, остаёмся в веб-версии.")
        view?.post {
            view.loadUrl(ML_START_URL)
        }
        return true
    }

    return !(lower.startsWith("http://") || lower.startsWith("https://"))
}
