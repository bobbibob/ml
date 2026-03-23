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
import com.ml.app.data.SQLiteRepo
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
private const val ML_ORDERS_URL = "https://www.mercadolivre.com.br/vendas/omni/lista?filters=TAB_TODAY#menu-user"

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
val parsed = try {
    if (raw.startsWith("{") || raw.startsWith("[")) raw
    else JSONObject("{\"v\":" + raw + "}").getString("v")
} catch (e: Exception) {
    raw
}

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
                        if (!currentUrl.contains("/anuncios/lista") || currentUrl.contains("space_management")) {
                            statusText = "Сначала открой Карточки."
                            return@Button
                        }
                        statusText = "Шаг 1/5: раскрываем варианты..."
                        webView.evaluateJavascript(expandListingVariantsJs()) { _ ->
                            webView.postDelayed({
                                statusText = "Шаг 2/5: повторно раскрываем варианты..."
                                webView.evaluateJavascript(expandListingVariantsJs()) { _ ->
                                    webView.postDelayed({
                                        statusText = "Шаг 3/5: прокручиваем вниз для догрузки..."
                                        webView.evaluateJavascript(
                                            """
                                            (function() {
                                              window.scrollTo(0, document.body.scrollHeight || 0);
                                              return JSON.stringify({ ok: true, h: document.body.scrollHeight || 0 });
                                            })();
                                            """.trimIndent()
                                        ) { _ ->
                                            webView.postDelayed({
                                                statusText = "Шаг 4/5: возвращаемся вверх..."
                                                webView.evaluateJavascript(
                                                    """
                                                    (function() {
                                                      window.scrollTo(0, 0);
                                                      return JSON.stringify({ ok: true });
                                                    })();
                                                    """.trimIndent()
                                                ) { _ ->
                                                    webView.postDelayed({
                                                        statusText = "Шаг 5/5: финально раскрываем и собираем JSON..."
                                                        webView.evaluateJavascript(expandListingVariantsJs()) { _ ->
                                                            webView.postDelayed({
                                                                webView.evaluateJavascript(listingsExtractorJs()) { result ->
                                                                    try {
                                                                        val raw = result ?: ""
val parsed = try {
    if (raw.startsWith("{") || raw.startsWith("[")) raw
    else JSONObject("{\"v\":" + raw + "}").getString("v")
} catch (e: Exception) {
    raw
}

                                                                        val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                                                            JSONObject("{\"v\":$raw}").getString("v")
                                                                        } else raw

                                                                        Thread {
                                                                            try {
                                                                                val repo = SQLiteRepo(context)
                                                                                val saved = repo.importMlListingsJsonToArticles(cleaned)
                                                                                kotlin.runCatching { repo.normalizeImportedMlArticleNames() }
                                                                                val rawCount = try { JSONObject(cleaned).optJSONArray("items")?.length() ?: 0 } catch (_: Throwable) { 0 }\n                                                                                statusText = "Сохранено сырьё: $rawCount, собрано артикулов: $saved. Готово"
                                                                            } catch (t: Throwable) {
                                                                                statusText = "Ошибка сохранения в артикулы: ${t.message}"
                                                                            }
                                                                        }.start()
                                                                    } catch (t: Throwable) {
                                                                        statusText = "Listings save error: ${t.message}"
                                                                    }
                                                                }
                                                            }, 2200)
                                                        }
                                                    }, 1200)
                                                }
                                            }, 2200)
                                        }
                                    }, 1200)
                                }
                            }, 1500)
                        }
                    }
                ) {
                    Text("В артикулы")
                }

                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        if (!currentUrl.contains("/anuncios/lista") || currentUrl.contains("space_management")) {
                            statusText = "Сначала открой Карточки."
                            return@Button
                        }
                        statusText = "Парсим карточки..."
                        webView.evaluateJavascript(listingsExtractorJs()) { result ->
                            try {
                                val raw = result ?: ""
val parsed = try {
    if (raw.startsWith("{") || raw.startsWith("[")) raw
    else JSONObject("{\"v\":" + raw + "}").getString("v")
} catch (e: Exception) {
    raw
}

                                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").getString("v")
                                } else raw
                                statusText = JSONObject(cleaned).toString(2).take(12000)
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
                        if (!currentUrl.contains("space_management")) {
                            statusText = "Сначала открой Остатки."
                            return@Button
                        }
                        statusText = "Парсим остатки..."
                        webView.evaluateJavascript(stockExtractorJs()) { result ->
                            try {
                                val raw = result ?: ""
val parsed = try {
    if (raw.startsWith("{") || raw.startsWith("[")) raw
    else JSONObject("{\"v\":" + raw + "}").getString("v")
} catch (e: Exception) {
    raw
}

                                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").getString("v")
                                } else raw
                                statusText = JSONObject(cleaned).toString(2).take(12000)
                            } catch (t: Throwable) {
                                statusText = "Stock parse error: ${t.message}"
                            }
                        }
                    }
                ) {
                    Text("JSON остатки")
                }

                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        if (!currentUrl.contains("/vendas/omni/lista")) {
                            statusText = "Сначала открой Продажи."
                            return@Button
                        }
                        statusText = "Парсим продажи..."
                        webView.evaluateJavascript(ordersExtractorJs()) { result ->
                            try {
                                val raw = result ?: ""
val parsed = try {
    if (raw.startsWith("{") || raw.startsWith("[")) raw
    else JSONObject("{\"v\":" + raw + "}").getString("v")
} catch (e: Exception) {
    raw
}

                                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").getString("v")
                                } else raw
                                statusText = JSONObject(cleaned).toString(2).take(12000)
                            } catch (t: Throwable) {
                                statusText = "Orders parse error: ${t.message}"
                            }
                        }
                    }
                ) {
                    Text("JSON продажи")
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
val parsed = try {
    if (raw.startsWith("{") || raw.startsWith("[")) raw
    else JSONObject("{\"v\":" + raw + "}").getString("v")
} catch (e: Exception) {
    raw
}

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
                        statusText = "Открываем карточки..."
                        webView.loadUrl(ML_LISTINGS_URL)
                    }
                ) {
                    Text("Карточки")
                }

                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        statusText = "Открываем остатки..."
                        webView.loadUrl(ML_STOCK_URL)
                    }
                ) {
                    Text("Остатки")
                }

                Button(
                    onClick = {
                        val webView = webViewRef ?: return@Button
                        statusText = "Открываем продажи..."
                        webView.loadUrl(ML_ORDERS_URL)
                    }
                ) {
                    Text("Продажи")
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



private fun expandListingVariantsJs(): String = """
(function() {
  function txt(el) {
    return ((el && (el.innerText || el.textContent)) || "").trim();
  }

  let clicked = 0;
  const nodes = Array.from(document.querySelectorAll("button,a,span,div"));
  for (const el of nodes) {
    const t = txt(el);
    if (!t) continue;
    if (/Ver variações/i.test(t) || /É possível agrupar as variações/i.test(t)) {
      try {
        el.click();
        clicked++;
      } catch (_) {}
    }
  }
  return JSON.stringify({ clicked: clicked });
})();
""".trimIndent()

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

  function parseMoney(text) {
    if (!text) return null;
    const m = String(text).match(/R\$\s*([\d\.,]+)/);
    if (!m) return null;
    const raw = m[1].replace(/\./g, "").replace(",", ".");
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  }

  function titleFromLines(lines, listingCode) {
    if (!listingCode) return null;
    const idx = lines.findIndex(x => x === listingCode);
    if (idx < 0) return null;
    for (let i = idx + 1; i < lines.length; i++) {
      const line = lines[i];
      if (!line) continue;
      if (/^\d+\s+unidades$/i.test(line)) break;
      if (/^SKU\s+/i.test(line)) continue;
      if (/^Cor:/i.test(line)) continue;
      if (/^I D /i.test(line)) continue;
      if (/^#\d+$/.test(line)) continue;
      if (/^(Clássico|Premium|Ativo|Pausado|Inativo)$/i.test(line)) continue;
      if (/^R\$\s*/i.test(line)) continue;
      if (/visitas|vendidas|Qualidade do anúncio|Experiência de compra|Adicionar preços de atacado|Ir para Promoções|Alterar preço|Alterar/i.test(line)) continue;
      return line;
    }
    return null;
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

    const title = titleFromLines(lines, listing_code);
    const stock_total = parseIntLoose((block.match(/(\d+)\s+unidades/i) || [])[1]);
    const visits = parseIntLoose((block.match(/([\d\.]+)\s+visitas/i) || [])[1]);
    const sold_total = parseIntLoose((block.match(/(\d+)\s+unidades vendidas/i) || [])[1]);

    const promoPriceMatch =
      block.match(/Você vende por\s*R\$\s*([\d\.,]+)\s+na promoção/i) ||
      block.match(/Preço\*?\s*\n\s*R\$\s*([\d\.,]+)/i);
    const promo_price = promoPriceMatch ? parseMoney(promoPriceMatch[0]) : null;

    const priceMatch =
      block.match(/\nR\$\s*([\d\.,]+)\n\n(?:Você oferece|Você vende|Clássico|Premium)/i) ||
      block.match(/\bR\$\s*([\d\.,]+)\b/);
    const price = priceMatch ? parseMoney(priceMatch[0]) : null;

    let status = null;
    const statusHits = block.match(/\b(Ativo|Pausado|Inativo)\b/gi);
    if (statusHits && statusHits.length) status = statusHits[statusHits.length - 1];

    const variants = [];
    const seen = new Set();

    const rxColorSku = /Cor:\s*([^\n]+?)\s*(?:\n+[^\n]*)*?\n+SKU\s+([A-Za-z0-9\-]+)/gi;
    let m;
    while ((m = rxColorSku.exec(block)) !== null) {
      const color = norm(m[1]);
      const sku = norm(m[2]).toUpperCase();
      if (!sku || seen.has(sku)) continue;
      seen.add(sku);
      variants.push({
        sku: sku,
        color: color || null,
        size: null,
        material: null,
        price: promo_price ?? price,
        promo_price: promo_price ?? price,
        image_main_url: null,
        images: []
      });
    }

    if (variants.length === 0) {
      const fallbackColor = ((block.match(/Cor:\s*([^\n]+)/i) || [])[1] || "").trim() || null;
      const skuMatches = [...block.matchAll(/\bSKU\s+([A-Za-z0-9\-]+)/gi)];
      for (const mm of skuMatches) {
        const sku = norm(mm[1]).toUpperCase();
        if (!sku || seen.has(sku)) continue;
        seen.add(sku);
        variants.push({
          sku: sku,
          color: fallbackColor,
          size: null,
          material: null,
          price: promo_price ?? price,
          promo_price: promo_price ?? price,
          image_main_url: null,
          images: []
        });
      }
    }

    const firstVariant = variants[0] || null;

    return {
      listing_id: listing_id,
      listing_code: listing_code,
      title: title,
      status: status,
      price: price,
      promo_price: promo_price,
      currency: "BRL",
      stock_total: stock_total,
      visits: visits,
      sold_total: sold_total,
      sku: firstVariant ? firstVariant.sku : null,
      color: firstVariant ? firstVariant.color : null,
      image_main_url: null,
      variants: variants,
      raw_text: block
    };
  }).filter(item => item.variants && item.variants.length > 0);

  return JSON.stringify({
    url: location.href,
    title: document.title || "",
    total: items.length,
    captured_at: Date.now(),
    items: items
  });
})();
""".trimIndent()



private fun stockExtractorJs(): String = """
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

  function parseUnits(s) {
    const m = String(s || "").match(/^(\d+)\s*un\.$/i);
    return m ? Number(m[1]) : null;
  }

  function parseWeeks(s) {
    const m = String(s || "").match(/Até\s+(\d+)\s*sem\./i);
    return m ? Number(m[1]) : null;
  }

  const rows = Array.from(document.querySelectorAll("tr"))
    .map(el => norm(el.innerText || el.textContent || ""))
    .filter(x => /Código\s+ML:/i.test(x));

  const items = rows.map(block => {
    const lines = block.split("\n").map(x => x.trim()).filter(Boolean);

    const mlCodeMatch = block.match(/Código\s+ML:\s*([A-Z0-9]+)/i);
    const ml_code = mlCodeMatch ? mlCodeMatch[1] : null;

    const variantsCountMatch = block.match(/^\+(\d+)$/m);
    const variants_count = variantsCountMatch ? Number(variantsCountMatch[1]) : 0;

    let title = null;
    const idx = lines.findIndex(x => /Código\s+ML:/i.test(x));
    if (idx >= 0) {
      for (let i = idx + 1; i < lines.length; i++) {
        const line = lines[i];
        if (/^\+\d+$/.test(line)) continue;
        if (/^Ver variações$/i.test(line)) continue;
        if (/^(PEQUENO|MÉDIO|GRANDE|EXTRAGRANDE)$/i.test(line)) continue;
        if (/^\d+\s*un\.$/i.test(line)) continue;
        if (/^Até\s+\d+\s*sem\./i.test(line)) continue;
        if (/Agora, você pode conferir/i.test(line)) continue;
        if (/Use o novo filtro/i.test(line)) continue;
        if (/Entendi/i.test(line)) continue;
        title = line;
        break;
      }
    }

    let variant_label = null;
    if (title) {
      const tIdx = lines.indexOf(title);
      if (tIdx >= 0 && tIdx + 1 < lines.length) {
        const cand = lines[tIdx + 1];
        if (
          !/^(PEQUENO|MÉDIO|GRANDE|EXTRAGRANDE)$/i.test(cand) &&
          !/^\d+\s*un\.$/i.test(cand) &&
          !/^Até\s+\d+\s*sem\./i.test(cand) &&
          !/^Ver variações$/i.test(cand)
        ) {
          variant_label = cand;
        }
      }
    }

    const size_bucket = lines.find(x => /^(PEQUENO|MÉDIO|GRANDE|EXTRAGRANDE)$/i.test(x)) || null;

    const units = lines.map(parseUnits).filter(v => v !== null);
    const weeksLine = lines.find(x => /^Até\s+\d+\s*sem\./i.test(x)) || null;
    const weeks_to_stockout = parseWeeks(weeksLine);

    let suggested_action = null;
    for (const key of [
      "Retire as unidades não aptas",
      "Impulsione suas vendas",
      "Não há recomendações porque ele é de boa qualidade.",
      "Alterar produto",
      "Como retirar unidades"
    ]) {
      const hit = lines.find(x => x.includes(key));
      if (hit) {
        suggested_action = hit;
        break;
      }
    }

    let stock_health = null;
    if (/boa qualidade/i.test(block)) stock_health = "good";
    else if (/estoque excedente/i.test(block)) stock_health = "excess";
    else if (/devolvidas com problemas/i.test(block)) stock_health = "not_sellable";
    else if (/entrada pendente/i.test(block)) stock_health = "pending";

    return {
      stock_id: ml_code && variant_label ? (ml_code + ":" + variant_label) : ml_code,
      ml_code,
      listing_id: null,
      sku: null,
      title,
      variant_label,
      size_bucket,
      variants_count,
      inbound_units: units[0] ?? null,
      not_sellable_units: units[1] ?? null,
      sellable_units: units[2] ?? null,
      sales_last_30d: units[3] ?? null,
      weeks_to_stockout,
      aged_or_excess_units: units[4] ?? null,
      stock_health,
      suggested_action,
      raw_text: block
    };
  });

  return JSON.stringify({
    source: "stock",
    url: location.href,
    captured_at: Date.now(),
    total: items.length,
    items
  });
})();
""".trimIndent()

private fun ordersExtractorJs(): String = """
(function() {
  function norm(s) {
    return (s || "").replace(/\r/g, "").replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim();
  }

  function parseMoney(s) {
    if (!s) return null;
    const m = String(s).match(/R\$\s*([\d\.\,]+)/);
    if (!m) return null;
    const v = m[1].replace(/\./g, "").replace(",", ".");
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  function parseQty(s) {
    const m = String(s || "").match(/(\d+)\s+unidade/i);
    return m ? Number(m[1]) : null;
  }

  const bodyText = norm(document.body ? (document.body.innerText || document.body.textContent || "") : "");
  const chunks = bodyText
    .split(/\n(?=Nova venda|A enviar|Pronto para enviar|Entregar|Hoje|Ontem)/i)
    .map(x => norm(x))
    .filter(x => x.length > 40);

  const items = [];
  for (const block of chunks) {
    const orderIdMatch =
      block.match(/#?(\d{13,})/) ||
      block.match(/Pedido\s*#?\s*(\d{8,})/i);

    const moneyMatches = [...block.matchAll(/R\$\s*[\d\.\,]+/g)].map(m => m[0]);
    const unit_price = moneyMatches.length ? parseMoney(moneyMatches[0]) : null;

    const skuMatch = block.match(/SKU\s+([A-Z0-9-]+)/i);
    const qty = parseQty(block);

    let status = null;
    for (const key of [
      "A enviar",
      "Pronto para enviar",
      "Entregar",
      "Enviado",
      "Cancelado"
    ]) {
      if (new RegExp("\\b" + key + "\\b", "i").test(block)) {
        status = key;
        break;
      }
    }

    let sale_date_text = null;
    const dateMatch = block.match(/\b(Hoje|Ontem)[^\n]*/i);
    if (dateMatch) sale_date_text = dateMatch[0];

    const lines = block.split("\n").map(x => x.trim()).filter(Boolean);

    let product_title = null;
    for (const line of lines) {
      if (/^SKU\s+/i.test(line)) continue;
      if (/^R\$\s*/i.test(line)) continue;
      if (/^\d+\s+unidade/i.test(line)) continue;
      if (/^(Hoje|Ontem)\b/i.test(line)) continue;
      if (/^(A enviar|Pronto para enviar|Entregar|Enviado|Cancelado)$/i.test(line)) continue;
      if (line.length > 10) {
        product_title = line;
        break;
      }
    }

    if (!orderIdMatch && !product_title) continue;

    items.push({
      order_id: orderIdMatch ? orderIdMatch[1] : null,
      listing_id: null,
      sku: skuMatch ? skuMatch[1] : null,
      ml_code: null,
      sale_date_text,
      sale_timestamp: null,
      buyer_name: null,
      buyer_alias: null,
      product_title,
      quantity: qty,
      unit_price,
      total_price: qty && unit_price ? qty * unit_price : unit_price,
      currency: "BRL",
      status,
      shipping_status: status,
      delivery_mode: null,
      raw_text: block
    });
  }

  return JSON.stringify({
    source: "orders",
    url: location.href,
    captured_at: Date.now(),
    total: items.length,
    items
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
