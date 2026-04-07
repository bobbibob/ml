package com.ml.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.zIndex
import com.ml.app.BuildConfig
import com.ml.app.data.session.PrefsSessionStorage
import com.ml.app.core.network.ApiModule
import com.ml.app.data.repository.DailySummarySyncRepository
import com.ml.app.data.SQLiteRepo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val ML_START_URL = "https://www.mercadolivre.com.br/vendas/omni/lista?filters=TAB_TODAY"
private const val ML_STOCK_URL = "https://www.mercadolivre.com.br/anuncios/lista/space_management?filters=on-sale%2Cin-transfer"

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
                .zIndex(2f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ML", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "close")
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f)
                .height(220.dp)
                .verticalScroll(rememberScrollState())
        ) {
        
            Button(
                modifier = Modifier.fillMaxWidth(),
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

                    statusText = "Сохраняем сессию..."

                    val userAgent: String = webViewRef?.settings?.userAgentString?.toString() ?: ""

                    Thread {
                        try {
                            val cookiesJson = JSONArray(cookies).toString()
                            val csrfToken = cookies.firstOrNull {
                                kotlin.runCatching {
                                    it.getString("name") == "_csrf"
                                }.getOrDefault(false)
                            }?.let {
                                kotlin.runCatching { it.getString("value") }.getOrNull()
                            }

                            val body = JSONObject().apply {
                                put("cookies_json", cookiesJson)
                                put("user_agent", userAgent as Any)
                                put("csrf_token", csrfToken)
                            }

                            val request = Request.Builder()
                                .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/ml/save-session")
                                .addHeader("Authorization", "Bearer $token")
                                .post(
                                    body.toString().toRequestBody("application/json".toMediaType())
                                )
                                .build()

                            OkHttpClient().newCall(request).execute().use { resp ->
                                val respText = resp.body?.string().orEmpty()
                                if (resp.isSuccessful) {
                                    statusText = "Сессия сохранена."
                                } else {
                                    statusText = "Ошибка сохранения сессии: ${resp.code} ${respText.take(300)}"
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
                    if (!currentUrl.contains("/anuncios/lista/space_management")) {
                        statusText = "Сначала открой страницу Остатки."
                        return@Button
                    }
                    statusText = "Снимаем карточки товаров..."

                    val js = """
                        (function() {
                          function txt(el) {
                            return ((el && (el.innerText || el.textContent)) || "").trim();
                          }

                          function norm(s) {
                            return (s || "").replace(/\s+/g, " ").trim();
                          }

                          function parseIntLoose(s) {
                            if (!s) return null;
                            const only = String(s).replace(/[^\d]/g, "");
                            if (!only) return null;
                            const n = Number(only);
                            return Number.isFinite(n) ? n : null;
                          }

                          function linesOf(el) {
                            return txt(el).split("\n").map(x => norm(x)).filter(Boolean);
                          }

                          function extractMlCode(raw) {
                            const m = raw.match(/Код\s+ML\s*:?\s*([A-Z0-9]+)/i);
                            return m ? m[1] : null;
                          }

                          function extractWeeks(raw) {
                            const m = raw.match(/До\s+(\d+)\s+нед/i);
                            return m ? Number(m[1]) : null;
                          }

                          function findMetric(lines, headerRegex) {
                            const idx = lines.findIndex(x => headerRegex.test(x));
                            if (idx < 0) return null;
                            for (let j = idx + 1; j < Math.min(idx + 4, lines.length); j++) {
                              const n = parseIntLoose(lines[j]);
                              if (n !== null) return n;
                            }
                            return null;
                          }

                          function extractTitle(lines) {
                            const idx = lines.findIndex(x => /^Код\s+ML/i.test(x));
                            if (idx < 0) return null;
                            const candidates = lines.slice(idx + 1).filter(x =>
                              x.length > 8 &&
                              !/^\+\s*\d+/.test(x) &&
                              !/единиц/i.test(x) &&
                              !/недел/i.test(x) &&
                              !/Варианты просмотра/i.test(x) &&
                              !/^Код\s+ML/i.test(x)
                            );
                            return candidates[0] || null;
                          }

                          const rowSelectors = [
                            "[role='row']",
                            "tr",
                            ".andes-table__row",
                            "[class*='table-row']",
                            "[class*='row']"
                          ];

                          let rows = [];
                          for (const sel of rowSelectors) {
                            const found = Array.from(document.querySelectorAll(sel));
                            if (found.length > rows.length) rows = found;
                          }

                          if (rows.length === 0) {
                            rows = Array.from(document.querySelectorAll("div"));
                          }

                          const items = [];
                          const seen = new Set();

                          for (const row of rows) {
                            const raw = norm(txt(row));
                            if (!raw) continue;
                            if (!/Код\s+ML/i.test(raw)) continue;

                            const mlCode = extractMlCode(raw);
                            if (!mlCode || seen.has(mlCode)) continue;

                            const lines = linesOf(row);
                            const img = row.querySelector("img");

                            items.push({
                              ml_code: mlCode,
                              title: extractTitle(lines),
                              stock_in_transfer: findMetric(lines, /^В\s*пути/i),
                              stock_not_fit_for_sale: findMetric(lines, /^Не\s*подходит/i),
                              stock_fit_for_sale: findMetric(lines, /^Подходит\s*для\s*продажи/i),
                              sales_30d: findMetric(lines, /^Продажи/i),
                              weeks_to_stockout: extractWeeks(raw),
                              stock_with_incoming: findMetric(lines, /^С\s*учетом\s*времени/i),
                              photo_url: img ? (img.getAttribute("src") || img.getAttribute("data-src") || null) : null,
                              raw_text: raw.slice(0, 2000)
                            });

                            seen.add(mlCode);
                          }

                          return JSON.stringify({
                            url: location.href,
                            title: document.title,
                            rowsFound: rows.length,
                            count: items.length,
                            items: items.slice(0, 10)
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
                            statusText = json.toString(2).take(7000)
                        } catch (t: Throwable) {
                            statusText = "Cards debug error: ${t.message}"
                        }
                    }
                }
            ) {
                Text("Карточки")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val webView = webViewRef ?: return@Button
                    statusText = "Читаем заказы со страницы..."

                    val js = """
                        (function() {
                          function txt(el) {
                            return ((el && (el.innerText || el.textContent)) || "").trim();
                          }

                          function norm(s) {
                            return (s || "").replace(/\s+/g, " ").trim();
                          }

                          function amountFrom(raw) {
                            const m = raw.match(/R\$\s*([\d\.,]+)/i);
                            if (!m) return null;
                            const v = Number(m[1].replace(/\./g, "").replace(",", "."));
                            return Number.isFinite(v) ? v : null;
                          }

                          function colorFrom(raw) {
                            const m = raw.match(/cor\s*:?\s*([^|\n]+)/i);
                            return m ? norm(m[1]) : null;
                          }

                          function dateTimeFrom(raw) {
                            const m = raw.match(/(\d{1,2})\s+([a-zç]{3})\s+(\d{1,2}:\d{2})\s*hs/i);
                            if (!m) {
                              return {
                                date_text: null,
                                time_text: null,
                                order_datetime_sort: null
                              };
                            }

                            const day = String(parseInt(m[1], 10)).padStart(2, "0");
                            const monStr = m[2].toLowerCase();
                            const time = m[3];

                            const months = {
                              jan:"01", fev:"02", mar:"03", abr:"04", mai:"05", jun:"06",
                              jul:"07", ago:"08", set:"09", out:"10", nov:"11", dez:"12"
                            };

                            const month = months[monStr];
                            if (!month) {
                              return {
                                date_text: norm(m[1] + " " + m[2]),
                                time_text: time,
                                order_datetime_sort: null
                              };
                            }

                            const now = new Date();
                            const currentYear = now.getFullYear();
                            const currentMonth = now.getMonth() + 1;
                            const parsedMonth = parseInt(month, 10);

                            const year = parsedMonth > currentMonth ? currentYear - 1 : currentYear;
                            const orderDateTimeSort = year + "-" + month + "-" + day + "T" + time + ":00";

                            return {
                              date_text: norm(m[1] + " " + m[2]),
                              time_text: time,
                              order_datetime_sort: orderDateTimeSort
                            };
                          }

                          function titleFrom(parts) {
                            const candidates = parts.filter(x =>
                              x.length > 12 &&
                              !/^#?\d{6,}/.test(x) &&
                              !/R\$/.test(x) &&
                              !/\d{1,2}\s+[a-zç]{3}/i.test(x) &&
                              !/não afeta|reputa|cancel|pago|entreg|envio|pendente|pronto|prepar|aguard|tr.nsito|a caminho|enviado/i.test(x)
                            );
                            return candidates[0] || null;
                          }

                          function buyerFrom(parts) {
                            const candidates = parts.filter(x =>
                              x.length > 3 &&
                              !/^#?\d{6,}/.test(x) &&
                              !/R\$/.test(x) &&
                              !/\d{1,2}\s+[a-zç]{3}/i.test(x) &&
                              !/não afeta|reputa|cancel|pago|entreg|envio|pendente|pronto|prepar|aguard|tr.nsito|a caminho|enviado/i.test(x)
                            );
                            return candidates[1] || null;
                          }

                          function statusFrom(raw) {
                            const parts = raw.split("\n").map(x => norm(x)).filter(Boolean);
                            return parts.find(x =>
                              /não afeta|reputa|cancel|pago|entreg|envio|pendente|pronto|prepar|aguard|tr.nsito|a caminho|enviado/i.test(x)
                            ) || null;
                          }

                          function firstImg(root) {
                            const img = root.querySelector("img");
                            if (!img) return null;
                            return img.getAttribute("src") || img.getAttribute("data-src") || null;
                          }

                          function skuFrom(raw, parts) {
                            const joined = [raw].concat(parts || []).join("\n");

                            const m =
                              joined.match(/\bSKU\s*:\s*([A-Z0-9._\/-]+)\b/i) ||
                              joined.match(/\bSeller\s*SKU\s*:\s*([A-Z0-9._\/-]+)\b/i);

                            if (!m || !m[1]) return null;
                            return norm(m[1]).replace(/\s+/g, "");
                          }

                          function articleColorFromSku(sku) {
                            const s = norm(sku || "").replace(/\s+/g, "");
                            if (!s) return { article: null, color_no: null };

                            const m = s.match(/^(.+?)[\/-](\d{1,3})$/i);
                            if (!m) return { article: null, color_no: null };

                            return {
                              article: String(m[1]).trim(),
                              color_no: String(m[2]).trim()
                            };
                          }

                          function nextPageFromRoot(root) {
                            const nextLink =
                              root.querySelector('a[rel="next"]') ||
                              root.querySelector('li.andes-pagination__button--next a') ||
                              root.querySelector('a.andes-pagination__link[title*="Seguinte"]') ||
                              root.querySelector('a.andes-pagination__link[aria-label*="Seguinte"]');

                            return nextLink ? (nextLink.href || nextLink.getAttribute("href") || "") : "";
                          }

                          function expandPackageRows(root) {
                            const selectors = [
                              'button[aria-expanded="false"]',
                              '[role="button"][aria-expanded="false"]',
                              'button[data-testid*="expand"]',
                              'button[data-testid*="accordion"]',
                              '[role="button"][data-testid*="expand"]',
                              '[role="button"][data-testid*="accordion"]',
                              'button',
                              '[role="button"]'
                            ];

                            const seen = new Set();
                            let clicked = 0;

                            for (const selector of selectors) {
                              const nodes = Array.from(root.querySelectorAll(selector));
                              for (const el of nodes) {
                                if (!el || seen.has(el)) continue;
                                seen.add(el);

                                const raw = norm(txt(el));
                                const aria = String(el.getAttribute("aria-label") || "").trim();
                                const testId = String(el.getAttribute("data-testid") || "").trim();
                                const cls = String(el.className || "").trim();

                                const looksExpandable =
                                  el.getAttribute("aria-expanded") === "false" ||
                                  /pacote de \d+ produtos/i.test(raw) ||
                                  /ver produtos/i.test(raw) ||
                                  /mostrar produtos/i.test(raw) ||
                                  /expand/i.test(raw) ||
                                  /accordion|expand|chevron|arrow/i.test(testId + " " + cls + " " + aria);

                                if (!looksExpandable) continue;

                                try {
                                  el.click();
                                  clicked += 1;
                                } catch (e) {}
                              }
                            }

                            return clicked;
                          }

                          function collectOrdersFromRoot(root, seenIds) {
                            const candidates = Array.from(root.querySelectorAll(
                              '[data-testid*="order"], [data-testid*="sale"], article, .andes-card, li'
                            ));

                            const cards = candidates.filter(card => {
                              const raw = txt(card);
                              if (!raw) return false;

                              const ids = raw.match(/#?\d{10,}/g) || [];
                              if (ids.length !== 1) return false;

                              if (raw.length > 2500) return false;

                              return true;
                            });

                            const pageOrders = [];

                            for (const card of cards) {
                              const raw = txt(card);
                              if (!raw) continue;

                              const idMatch =
                                raw.match(/#(\d{10,})/) ||
                                raw.match(/\b(\d{10,})\b/);

                              if (!idMatch) continue;

                              const externalId = idMatch[1];
                              if (seenIds.has(externalId)) continue;

                              const parts = raw.split("\n").map(x => norm(x)).filter(Boolean);
                              const dt = dateTimeFrom(raw);
                              const sku = skuFrom(raw, parts);
                              const skuParts = articleColorFromSku(sku);

                              const title = titleFrom(parts);
                              const rawNorm = norm(raw);

                              let article = skuParts.article || "";
                              let colorNo = skuParts.color_no || "";

                              if (!article) {
                                const articleFromTitle =
                                  (title ? title.match(/\b(\d{1,3}[a-zа-яё]?)[.\-\s]+/i) : null) ||
                                  rawNorm.match(/\b(\d{1,3}[a-zа-яё]?)\b/i);
                                if (articleFromTitle) {
                                  article = norm(articleFromTitle[1]);
                                }
                              }

                              if (!colorNo) {
                                const colorFromSku =
                                  (sku ? sku.match(/[\/-](\d{1,3})\b/) : null) ||
                                  rawNorm.match(/\b(?:cor|color|цвет)\D{0,6}(\d{1,3})\b/i) ||
                                  rawNorm.match(/\b(\d{1,3})\b/g);
                                if (colorFromSku) {
                                  colorNo = Array.isArray(colorFromSku) ? (colorFromSku[1] || "") : "";
                                }
                              }

                              if (!article) continue;

                              pageOrders.push({
                                external_order_id: externalId,
                                sku: sku || article,
                                article: article,
                                color_no: colorNo,
                                title: title,
                                buyer_name: buyerFrom(parts),
                                status: statusFrom(raw),
                                substatus: null,
                                amount: amountFrom(raw),
                                currency: "BRL",
                                date_text: dt.date_text,
                                time_text: dt.time_text,
                                order_datetime_sort: dt.order_datetime_sort,
                                color: colorNo,
                                photo_url: firstImg(card),
                                raw_text: rawNorm.slice(0, 4000)
                              });

                              seenIds.add(externalId);
                            }

                            return pageOrders;
                          }

                          const expandedClicked = expandPackageRows(document);
                          if (expandedClicked > 0) {
                            return JSON.stringify({
                              url: location.href,
                              title: document.title,
                              count: 0,
                              next_page_url: nextPageFromRoot(document) || "",
                              expanded_clicked: expandedClicked,
                              needs_retry: true,
                              orders: []
                            });
                          }

                          const seen = new Set();
                          const allOrders = collectOrdersFromRoot(document, seen);
                          const lastNextPageUrl = nextPageFromRoot(document) || "";

                          return JSON.stringify({
                            url: location.href,
                            title: document.title,
                            count: allOrders.length,
                            next_page_url: lastNextPageUrl,
                            expanded_clicked: 0,
                            needs_retry: false,
                            orders: allOrders
                          });
                        })();
                    """.trimIndent()

                    var parserRetryCount = 0

                    fun runParser() {
                        webView.evaluateJavascript(js) { result ->
                            Thread {
                                try {
                                val raw = result ?: ""

                                if (raw.isBlank() || raw.trim() == "null") {
                                    statusText = "JS вернул null/пусто"
                                    return@Thread
                                }

                                val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").getString("v")
                                } else {
                                    raw
                                }

                                val json = JSONObject(cleaned)
                                val orders = json.optJSONArray("orders") ?: JSONArray()

                                val safeOrders = JSONArray()
                                for (i in 0 until orders.length()) {
                                    val item = orders.opt(i)
                                    if (item is JSONObject) {
                                        safeOrders.put(item)
                                    }
                                }

                                val pageUrl = json.optString("url")
                                val pageTitle = json.optString("title")
                                val needsRetry = json.optBoolean("needs_retry", false)
                                val expandedClicked = json.optInt("expanded_clicked", 0)
                                val parserCount = json.optInt("count", safeOrders.length())
                                val sampleOrder = if (safeOrders.length() > 0) {
                                    val first = safeOrders.optJSONObject(0)
                                    if (first != null) {
                                        "id=" + first.optString("external_order_id") +
                                        " sku=" + first.optString("sku") +
                                        " article=" + first.optString("article") +
                                        " color_no=" + first.optString("color_no")
                                    } else {
                                        ""
                                    }
                                } else {
                                    ""
                                }

                                if (needsRetry) {
                                    parserRetryCount += 1
                                    statusText = "Разворачиваем комплектные заказы: $expandedClicked retry=$parserRetryCount"

                                    if (parserRetryCount >= 3) {
                                        statusText = "Комплектные заказы раскрыты, запускаем финальный парсинг..."
                                        webView.postDelayed({
                                            parserRetryCount = 0
                                            val jsNoExpand = js.replace("const expandedClicked = expandPackageRows(document);", "const expandedClicked = 0;")
                                                .replace("if (expandedClicked > 0) {", "if (false) {")
                                            webView.evaluateJavascript(jsNoExpand) { retryResult ->
                                                Thread {
                                                    try {
                                                        val raw2 = retryResult ?: ""
                                                        if (raw2.isBlank() || raw2.trim() == "null") {
                                                            statusText = "JS финальный парсер вернул null/пусто"
                                                            return@Thread
                                                        }

                                                        val cleaned2 = if (raw2.startsWith("\"") && raw2.endsWith("\"")) {
                                                            JSONObject("{\"v\":$raw2}").getString("v")
                                                        } else {
                                                            raw2
                                                        }

                                                        val json2 = JSONObject(cleaned2)
                                                        val orders2 = json2.optJSONArray("orders") ?: JSONArray()
                                                        val safeOrders2 = JSONArray()
                                                        for (j in 0 until orders2.length()) {
                                                            val item2 = orders2.opt(j)
                                                            if (item2 is JSONObject) safeOrders2.put(item2)
                                                        }

                                                        if (safeOrders2.length() == 0) {
                                                            statusText = "После раскрытия комплектов заказы не найдены"
                                                            return@Thread
                                                        }

                                                        val client2 = OkHttpClient()
                                                        val syncStateReq2 = Request.Builder()
                                                            .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/ml/sync-state")
                                                            .addHeader("Authorization", "Bearer $token")
                                                            .get()
                                                            .build()

                                                        val syncStateJson2 = client2.newCall(syncStateReq2).execute().use { resp ->
                                                            val bodyText2 = resp.body?.string().orEmpty()
                                                            if (!resp.isSuccessful) {
                                                                statusText = "Ошибка sync-state: ${resp.code} ${bodyText2.take(500)}"
                                                                return@Thread
                                                            }
                                                            if (bodyText2.isBlank() || bodyText2.trim() == "null") {
                                                                statusText = "sync-state вернул пусто/null"
                                                                return@Thread
                                                            }
                                                            JSONObject(bodyText2)
                                                        }

                                                        val minAllowed2 = syncStateJson2.optString("min_allowed_datetime").ifBlank { "2025-08-30T00:00:00" }
                                                        val lastSynced2 = syncStateJson2.optString("last_synced_order_datetime").ifBlank { null }

                                                        val filteredOrders2 = JSONArray()
                                                        for (j in 0 until safeOrders2.length()) {
                                                            val obj2 = safeOrders2.optJSONObject(j) ?: continue
                                                            filteredOrders2.put(obj2)
                                                        }

                                                        if (filteredOrders2.length() == 0) {
                                                            statusText = "Новых заказов нет после раскрытия. orders=${safeOrders2.length()} minAllowed=$minAllowed2 lastSynced=${lastSynced2 ?: "null"}"
                                                            return@Thread
                                                        }

                                                        statusText = "Отправляем ${filteredOrders2.length()} заказов после раскрытия комплектов..."

                                                        val upsertBody2 = JSONObject().apply {
                                                            put("orders", filteredOrders2)
                                                        }

                                                        val upsertReq2 = Request.Builder()
                                                            .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/ml/upsert-orders")
                                                            .addHeader("Authorization", "Bearer $token")
                                                            .post(upsertBody2.toString().toRequestBody("application/json".toMediaType()))
                                                            .build()

                                                        client2.newCall(upsertReq2).execute().use { resp ->
                                                            val bodyText2 = resp.body?.string().orEmpty()
                                                            if (!resp.isSuccessful) {
                                                                statusText = "Ошибка отправки заказов: ${resp.code} ${bodyText2.take(500)}"
                                                                return@Thread
                                                            }
                                                        }

                                                        statusText = "Комплектные заказы отправлены, начинаем summary..."
                                                        val summaryReq2 = Request.Builder()
                                                            .url(BuildConfig.TASKS_API_BASE_URL + "internal/ml/generate-orders-summary")
                                                            .addHeader("Authorization", "Bearer $token")
                                                            .post("{}".toRequestBody("application/json".toMediaType()))
                                                            .build()

                                                        var summaryBodyText2 = ""
                                                        client2.newCall(summaryReq2).execute().use { resp ->
                                                            val bodyText2 = resp.body?.string().orEmpty()
                                                            summaryBodyText2 = bodyText2
                                                            if (!resp.isSuccessful) {
                                                                statusText = "Заказы отправлены, но summary не создан: ${resp.code} ${bodyText2.take(500)}"
                                                                return@Thread
                                                            }
                                                        }

                                                        kotlin.runCatching {
                                                            kotlinx.coroutines.runBlocking {
                                                                val summaryJson2 = JSONObject(summaryBodyText2)
                                                                val targetDates2 = mutableListOf<String>()
                                                                val targetDatesJson2 = summaryJson2.optJSONArray("target_dates")
                                                                if (targetDatesJson2 != null) {
                                                                    for (k in 0 until targetDatesJson2.length()) {
                                                                        val d2 = targetDatesJson2.optString(k).trim()
                                                                        if (d2.isNotBlank()) targetDates2.add(d2)
                                                                    }
                                                                }

                                                                if (targetDates2.isEmpty()) {
                                                                    val fallbackDate2 = summaryJson2.optString("summary_date").ifBlank {
                                                                        java.time.LocalDate.now().toString()
                                                                    }
                                                                    if (fallbackDate2.isNotBlank()) targetDates2.add(fallbackDate2)
                                                                }

                                                                val localSession2 = PrefsSessionStorage(context)
                                                                val api2 = ApiModule.createApi(
                                                                    baseUrl = BuildConfig.TASKS_API_BASE_URL,
                                                                    sessionStorage = localSession2
                                                                )
                                                                val syncRepo2 = DailySummarySyncRepository(api2, context)
                                                                val localRepo2 = SQLiteRepo(context)

                                                                var syncedDates2 = 0
                                                                var totalEntries2 = 0

                                                                for (summaryDate2 in targetDates2.distinct()) {
                                                                    when (val byDate2 = syncRepo2.getDailySummaryByDate(summaryDate2)) {
                                                                        is com.ml.app.core.result.AppResult.Success -> {
                                                                            localRepo2.applyRemoteDailySummary(summaryDate2, byDate2.data)
                                                                            syncedDates2 += 1
                                                                            totalEntries2 += byDate2.data.size
                                                                        }
                                                                        else -> {}
                                                                    }
                                                                }

                                                                statusText = "Синхронизация ML завершена после раскрытия комплектов: ${filteredOrders2.length()} заказов, entries=$totalEntries2 syncedDates=$syncedDates2"
                                                            }
                                                        }.onFailure {
                                                            statusText = "Комплектные заказы отправлены, но local apply error: ${it.message}"
                                                        }
                                                    } catch (t: Throwable) {
                                                        statusText = "Ошибка финального парсинга комплектов: ${t.message}"
                                                    }
                                                }.start()
                                            }
                                        }, 3000)
                                    } else {
                                        webView.postDelayed({
                                            runParser()
                                        }, 2200)
                                    }
                                    return@Thread
                                }

                                if (safeOrders.length() == 0) {
                                    statusText = "Заказы не найдены. url=${pageUrl.take(120)} title=${pageTitle.take(80)} count=$parserCount next=${json.optString("next_page_url").take(120)}"
                                    return@Thread
                                }

                                statusText = "JS parsed orders=${safeOrders.length()} parserCount=$parserCount next=${json.optString("next_page_url").take(120)} sample=${sampleOrder.take(120)}"

                                val debugItems = StringBuilder()
                                val limit = minOf(15, safeOrders.length())
                                for (i in 0 until limit) {
                                    val o = safeOrders.optJSONObject(i) ?: continue
                                    debugItems.append("#")
                                    debugItems.append(i + 1)
                                    debugItems.append(" id=")
                                    debugItems.append(o.optString("external_order_id"))
                                    debugItems.append(" sku=")
                                    debugItems.append(o.optString("sku"))
                                    debugItems.append(" article=")
                                    debugItems.append(o.optString("article"))
                                    debugItems.append(" color_no=")
                                    debugItems.append(o.optString("color_no"))
                                    debugItems.append("\n")
                                }


                                val client = OkHttpClient()

                                val syncStateReq = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/ml/sync-state")
                                    .addHeader("Authorization", "Bearer $token")
                                    .get()
                                    .build()

                                val syncStateJson = client.newCall(syncStateReq).execute().use { resp ->
                                    val bodyText = resp.body?.string().orEmpty()
                                    if (!resp.isSuccessful) {
                                        statusText = "Ошибка sync-state: ${resp.code} ${bodyText.take(500)}"
                                        return@Thread
                                    }
                                    if (bodyText.isBlank() || bodyText.trim() == "null") {
                                        statusText = "sync-state вернул пусто/null"
                                        return@Thread
                                    }
                                    JSONObject(bodyText)
                                }

                                val minAllowed = syncStateJson.optString("min_allowed_datetime").ifBlank { "2025-08-30T00:00:00" }
                                val lastSynced = syncStateJson.optString("last_synced_order_datetime").ifBlank { null }

                                val filteredOrders = JSONArray()
                                var skippedBySummaryDate = 0
                                var skippedByTime = 0

                                val debugIds = mutableListOf<String>()
                                for (i in 0 until safeOrders.length()) {
                                    val dbg = safeOrders.optJSONObject(i)
                                    val dbgId = dbg?.optString("external_order_id").orEmpty()
                                    if (dbgId.isNotBlank()) {
                                        debugIds.add(dbgId)
                                    }
                                }
                                statusText = "JS parsed orders=${safeOrders.length()} ids=${debugIds.take(10).joinToString(",")}"

                                for (i in 0 until safeOrders.length()) {
                                    val obj = safeOrders.optJSONObject(i) ?: continue

                                    val orderDateTime =
                                        obj.optString("order_datetime_sort").trim().takeIf { it.isNotBlank() }
                                            ?: obj.optString("date_created").trim().takeIf { it.isNotBlank() }

                                    filteredOrders.put(obj)
                                }

                                if (filteredOrders.length() == 0) {
                                    statusText = "Новых заказов нет. orders=${safeOrders.length()} skippedDate=$skippedBySummaryDate skippedTime=$skippedByTime minAllowed=$minAllowed lastSynced=${lastSynced ?: "null"}"
                                    return@Thread
                                }

                                statusText = "Отправляем ${filteredOrders.length()} заказов. skippedDate=$skippedBySummaryDate skippedTime=$skippedByTime minAllowed=$minAllowed lastSynced=${lastSynced ?: "null"}"

                                val upsertBody = JSONObject().apply {
                                    put("orders", filteredOrders)
                                }

                                val upsertReq = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/ml/upsert-orders")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post(upsertBody.toString().toRequestBody("application/json".toMediaType()))
                                    .build()

                                client.newCall(upsertReq).execute().use { resp ->
                                    val bodyText = resp.body?.string().orEmpty()
                                    if (!resp.isSuccessful) {
                                        statusText = "Ошибка отправки заказов: ${resp.code} ${bodyText.take(500)}"
                                        return@Thread
                                    }
                                }

                                statusText = "orders отправлены, начинаем summary..."
                                val summaryReq = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/ml/generate-orders-summary")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post("{}".toRequestBody("application/json".toMediaType()))
                                    .build()

                                var summaryBodyText = ""
                                client.newCall(summaryReq).execute().use { resp ->
                                    val bodyText = resp.body?.string().orEmpty()
                                    summaryBodyText = bodyText
                                    if (!resp.isSuccessful) {
                                        statusText = "Заказы отправлены, но summary не создан: ${resp.code} ${bodyText.take(500)}"
                                        return@Thread
                                    }
                                    statusText = "summary raw: ${bodyText.take(300)}"
                                }

                                kotlin.runCatching {
                                    kotlinx.coroutines.runBlocking {
                                        statusText = "SUMMARY BODY RAW: " + summaryBodyText.take(200)
                                        val summaryJson = JSONObject(summaryBodyText)

                                        val targetDates = mutableListOf<String>()
                                        val targetDatesJson = summaryJson.optJSONArray("target_dates")
                                        if (targetDatesJson != null) {
                                            for (i in 0 until targetDatesJson.length()) {
                                                val d = targetDatesJson.optString(i).trim()
                                                if (d.isNotBlank()) {
                                                    targetDates.add(d)
                                                }
                                            }
                                        }

                                        if (targetDates.isEmpty()) {
                                            val fallbackDate = summaryJson.optString("summary_date").ifBlank {
                                                java.time.LocalDate.now().toString()
                                            }
                                            if (fallbackDate.isNotBlank()) {
                                                targetDates.add(fallbackDate)
                                            }
                                        }

                                        val localSession = PrefsSessionStorage(context)
                                        val api = ApiModule.createApi(
                                            baseUrl = BuildConfig.TASKS_API_BASE_URL,
                                            sessionStorage = localSession
                                        )
                                        val syncRepo = DailySummarySyncRepository(api, context)
                                        val localRepo = SQLiteRepo(context)

                                        var syncedDates = 0
                                        var totalEntries = 0
                                        var lastError: String? = null

                                        for (summaryDate in targetDates.distinct()) {
                                            when (val byDate = syncRepo.getDailySummaryByDate(summaryDate)) {
                                                is com.ml.app.core.result.AppResult.Success -> {
                                                    localRepo.applyRemoteDailySummary(summaryDate, byDate.data)
                                                    syncedDates += 1
                                                    totalEntries += byDate.data.size
                                                }
                                                is com.ml.app.core.result.AppResult.Error -> {
                                                    lastError = "date=$summaryDate: ${byDate.message}"
                                                }
                                                else -> {
                                                    lastError = "date=$summaryDate: unknown result"
                                                }
                                            }
                                        }

                                        val summariesJson = summaryJson.optJSONArray("summaries")
                                        var generatedEntries = 0
                                        var unmatchedCount = 0
                                        var unmatchedReason = ""
                                        var unmatchedArticle = ""
                                        var unmatchedTitle = ""

                                        if (summariesJson != null && summariesJson.length() > 0) {
                                            val firstSummary = summariesJson.optJSONObject(0)
                                            if (firstSummary != null) {
                                                generatedEntries = firstSummary.optInt("generated_entries", 0)
                                                unmatchedCount = firstSummary.optInt("unmatched_count", 0)

                                                val unmatched = firstSummary.optJSONArray("unmatched")
                                                val firstUnmatched = unmatched?.optJSONObject(0)
                                                if (firstUnmatched != null) {
                                                    unmatchedReason = firstUnmatched.optString("reason").trim()
                                                    unmatchedArticle = firstUnmatched.optString("article").trim()
                                                    unmatchedTitle = firstUnmatched.optString("title").trim()
                                                }
                                            }
                                        }

                                        statusText =
                                            if (syncedDates > 0) {
                                                "ML ok orders=${filteredOrders.length()} entries=$totalEntries backend_entries=$generatedEntries unmatched=$unmatchedCount reason=$unmatchedReason article=${unmatchedArticle.take(20)} title=${unmatchedTitle.take(30)}"
                                            } else {
                                                "Синхронизация ML завершена, но local summary sync error: ${lastError ?: "no dates synced"}"
                                            }
                                    }
                                }.onFailure {
                                    statusText = "Синхронизация ML завершена, но local apply error: ${it.message}"
                                }


                                val authStateBody = JSONObject().apply {
                                    put("source", "mercadolivre")
                                    put("auth_state", "active")
                                    put("last_error", JSONObject.NULL)
                                }

                                val authStateReq = Request.Builder()
                                    .url(BuildConfig.TASKS_API_BASE_URL + "internal/integrations/set-auth-state")
                                    .addHeader("Authorization", "Bearer $token")
                                    .post(authStateBody.toString().toRequestBody("application/json".toMediaType()))
                                    .build()

                                client.newCall(authStateReq).execute().use { }
                            } catch (t: Throwable) {
                                statusText = "Ошибка синхронизации: ${t.message}"
                            }
                        }.start()
                        }
                    }

                    webView.post {
                        val current = webView.url.orEmpty()

                        fun startPreparedParser() {
                            statusText = "Подготавливаем страницу заказов..."
                            webView.scrollTo(0, 0)
                            webView.postDelayed({
                                webView.pageDown(false)
                                webView.postDelayed({
                                    webView.pageDown(false)
                                    webView.postDelayed({
                                        webView.pageDown(false)
                                        webView.postDelayed({
                                            webView.pageDown(false)
                                            webView.postDelayed({
                                                webView.pageDown(false)
                                                webView.postDelayed({
                                                    webView.pageDown(false)
                                                    webView.postDelayed({
                                                        webView.pageDown(false)
                                                        webView.postDelayed({
                                                            webView.pageDown(false)
                                                            webView.postDelayed({
                                                                webView.scrollTo(0, 0)
                                                                webView.postDelayed({
                                                                    statusText = "Страница проскроллена, запускаем парсер..."
                                                                    runParser()
                                                                }, 1800)
                                                            }, 700)
                                                        }, 700)
                                                    }, 700)
                                                }, 700)
                                            }, 700)
                                        }, 700)
                                    }, 700)
                                }, 700)
                            }, 1200)
                        }

                        if (!current.contains("mercadolivre")) {
                            statusText = "Открываем страницу заказов..."
                            webView.loadUrl(ML_STOCK_URL)
                            webView.postDelayed({
                                startPreparedParser()
                            }, 5000)
                        } else {
                            startPreparedParser()
                        }
                    }
                }
            ) {
                Text("Синхро")
            }
        }

        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(2f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        AndroidView(
            modifier = Modifier
                .zIndex(0f)
                .fillMaxWidth()
                .padding(top = 320.dp)
                .height(620.dp),
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
