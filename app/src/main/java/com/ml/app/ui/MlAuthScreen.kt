package com.ml.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
private const val ML_STOCK_URL = "https://www.mercadolivre.com.br/anuncios/lista/space_management?filters=on-sale%2Cin-transfer"
private const val ML_LISTINGS_URL = "https://www.mercadolivre.com.br/anuncios/lista?filters=OMNI_ACTIVE|OMNI_INACTIVE|CHANNEL_NO_PROXIMITY_AND_NO_MP_MERCHANTS&page=1&sort=DEFAULT"

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
    var statusText by remember { mutableStateOf("Войдите в Mercado Livre, затем нажмите «Сессия».") }

    BackHandler { onClose() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Text(
            text = "ML auth",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                                    .build()

                                OkHttpClient().newCall(request).execute().use { resp ->
                                    if (resp.isSuccessful) {
                                        onSuccess()
                                    } else {
                                        statusText = "Не удалось сохранить сессию: ${resp.code}"
                                    }
                                }
                            } catch (t: Throwable) {
                                statusText = "Ошибка сохранения сессии: ${t.message}"
                            }
                        }.start()
                    }
                ) {
                    Text("Сессия")
                }

                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        if (!currentUrl.contains("/anuncios/lista") || currentUrl.contains("space_management")) {
                            statusText = "Сначала открой страницу Карточки."
                            return@Button
                        }
                        statusText = "Парсим карточки товаров..."

                        webView.evaluateJavascript(listingsExtractorJs()) { result ->
                            try {
                                val raw = result ?: ""
                                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").getString("v")
                                } else {
                                    raw
                                }
                                val json = JSONObject(cleaned)
                                statusText = json.toString(2).take(12000)
                            } catch (t: Throwable) {
                                statusText = "Listings parse error: ${t.message}"
                            }
                        }
                    }
                ) {
                    Text("JSON карточки")
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
                    Text("DOM")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        statusText = "Открываем страницу остатков..."
                        webView.loadUrl(ML_STOCK_URL)
                    }
                ) {
                    Text("Склад")
                }

                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        statusText = "Открываем карточки товаров..."
                        webView.loadUrl(ML_LISTINGS_URL)
                    }
                ) {
                    Text("Карточки")
                }

                Button(
                    onClick = {
                        webViewRef?.reload()
                    }
                ) {
                    Text("Обновить")
                }

                Button(onClick = onClose) {
                    Text("Закрыть")
                }
            }

            SelectionContainer {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black
                )
            }
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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


private fun listingsExtractorJs(): String = """
(function() {
  function norm(s) {
    return (s || "").replace(/\r/g, "").replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim();
  }

  function parseIntLoose(s) {
    if (!s) return null;
    const only = String(s).replace(/[^\d]/g, "");
    if (!only) return null;
    const n = Number(only);
    return Number.isFinite(n) ? n : null;
  }

  function parseMoney(s) {
    if (!s) return null;
    const m = String(s).match(/R\$\s*([\d\.\,]+)/);
    if (!m) return null;
    const v = m[1].replace(/\./g, "").replace(",", ".");
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  const bodyText = norm(document.body ? (document.body.innerText || document.body.textContent || "") : "");
  const parts = bodyText
    .split(/Selecionar anúncio/gi)
    .map(x => norm(x))
    .filter(x => /#\d{6,}/.test(x));

  const items = parts.map(block => {
    const lines = block.split("\n").map(x => x.trim()).filter(Boolean);

    const listingIdMatch = block.match(/#(\d{6,})/);
    const listing_id = listingIdMatch ? listingIdMatch[1] : null;
    const listing_code = listing_id ? "#" + listing_id : null;

    let title = null;
    if (listing_code) {
      const idx = lines.findIndex(x => x === listing_code);
      if (idx >= 0) {
        for (let i = idx + 1; i < lines.length; i++) {
          const line = lines[i];
          if (/^\d+\s+unidades$/i.test(line)) break;
          if (/^SKU\s+/i.test(line)) continue;
          if (/^Cor:/i.test(line)) continue;
          if (/^I D /i.test(line)) continue;
          if (!/^#\d+$/.test(line)) {
            title = line;
            break;
          }
        }
      }
    }

    const stock_total = parseIntLoose((block.match(/(\d+)\s+unidades/i) || [])[1]);
    const visits = parseIntLoose((block.match(/([\d\.]+)\s+visitas/i) || [])[1]);
    const sold_total = parseIntLoose((block.match(/(\d+)\s+unidades vendidas/i) || [])[1]);
    const price = parseMoney((block.match(/R\$\s*[\d\.\,]+/) || [])[0]);

    let status = null;
    const statusHits = block.match(/\b(Ativo|Pausado|Inativo)\b/gi);
    if (statusHits && statusHits.length) status = statusHits[statusHits.length - 1];

    const qualityScoreMatch = block.match(/(\d+)\s+Qualidade do anúncio/i);
    const quality_score = qualityScoreMatch ? Number(qualityScoreMatch[1]) : null;

    const experienceScoreMatch = block.match(/(\d+)\s+Experiência de compra/i);
    const experience_score = experienceScoreMatch ? Number(experienceScoreMatch[1]) : null;

    const variants = [];
    const variantRegex = /Cor:\s*([^\n]+)\n+\s*SKU\s+([A-Z0-9-]+)/gi;
    let vm;
    while ((vm = variantRegex.exec(block)) !== null) {
      const color = (vm[1] || "").trim();
      const sku = (vm[2] || "").trim();
      variants.push({
        variant_id: listing_id && sku ? listing_id + ":" + sku : null,
        sku: sku || null,
        variant_title: null,
        color: color || null,
        size: null,
        material: null,
        attributes: color ? { "Cor": color } : {},
        image_main_url: null,
        images: []
      });
    }

    return {
      listing_id,
      listing_code,
      title,
      status,
      price,
      promo_price: null,
      currency: "BRL",
      stock_total,
      visits,
      sold_total,
      sale_fee_type: null,
      sale_fee_amount: null,
      shipping_mode: null,
      shipping_cost: null,
      shipping_paid_by: null,
      net_amount: null,
      quality_score,
      quality_level: null,
      experience_score,
      experience_level: null,
      bulk_price_enabled: /preço de atacado/i.test(block),
      bulk_price_min_qty: parseIntLoose((block.match(/(\d+)\s+unidades\s*\n\s*R\$\s*[\d\.\,]+\/u/i) || [])[1]),
      bulk_price_amount: parseMoney((block.match(/(\bR\$\s*[\d\.\,]+\/u\b)/i) || [])[1]),
      variants,
      raw_text: block
    };
  });

  return JSON.stringify({
    source: "listings",
    url: location.href,
    captured_at: Date.now(),
    total: items.length,
    items: items
  });
})();
""".trimIndent()

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
