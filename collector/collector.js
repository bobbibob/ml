const { chromium } = require("playwright");
const {
  getSession,
  upsertOrders,
  generateSummary,
  setAuthState,
  API_BASE
} = require("./backend");

const { parseOrdersFromPage, normalizeParsedOrders } = require("./parser");

const ORDERS_URL = process.env.ML_ORDERS_URL || "";
const HEADLESS = String(process.env.HEADLESS || "true") !== "false";

function requireEnv(name, value) {
  if (!value) {
    throw new Error(`Missing required env: ${name}`);
  }
}

function looksLikeLogin(url, html) {
  const s = `${url}\n${html}`.toLowerCase();
  return (
    s.includes("login") ||
    s.includes("código") ||
    s.includes("codigo") ||
    s.includes("verifica") ||
    s.includes("security code")
  );
}

async function main() {
  requireEnv("ML_ORDERS_URL", ORDERS_URL);

  console.log("collector_start");
  console.log("api_base", API_BASE);

  const browser = await chromium.launch({
    headless: HEADLESS
  });

  const context = await browser.newContext({
    locale: "pt-BR",
    timezoneId: "America/Sao_Paulo",
    viewport: { width: 412, height: 915 },
    isMobile: true,
    hasTouch: true,
    userAgent: "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
  });

  const page = await context.newPage();

  try {
    // 🔥 1. получаем session с backend
    const session = await getSession();
    const cookies = session?.session_payload?.cookies || [];

    console.log("session_cookies", cookies.length);

    if (!cookies.length) {
      throw new Error("No session cookies")
    }

    // 🔥 2. применяем cookies
    await context.addCookies(cookies);

    // 🔥 3. сначала открываем главную, потом страницу заказов
    await page.goto("https://www.mercadolivre.com.br/", {
      waitUntil: "domcontentloaded",
      timeout: 90000
    });
    await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});

    await page.goto(ORDERS_URL, {
      waitUntil: "domcontentloaded",
      timeout: 90000
    });

    await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});
    const html = await page.content();
    const url = page.url();

    if (looksLikeLogin(url, html)) {
      await setAuthState("auth_required", "Session expired");
      throw new Error("Session expired");
    }

    // 🔥 4. парсим
    const parsedOrders = await parseOrdersFromPage(page);
    const orders = normalizeParsedOrders(parsedOrders);

    console.log("final_url", page.url());
    console.log("page_title", await page.title());

    const bodyText = await page.locator("body").innerText().catch(() => "");
    console.log("body_preview", bodyText.slice(0, 3000));

    if (!orders.length) {
      const fs = require("fs");
      await page.screenshot({ path: "collector_debug.png", fullPage: true }).catch(() => {});
      const html = await page.content().catch(() => "");
      fs.writeFileSync("collector_debug.html", html || "", "utf8");
    }

    console.log("parsed_orders", orders.length);

    if (!orders.length) {
      throw new Error("No orders parsed");
    }

    // 🔥 5. отправляем
    await upsertOrders(orders);
    await generateSummary();

    await setAuthState("active", null);
    console.log("collector_success");

  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error("collector_error", message);

    if (message.toLowerCase().includes("session")) {
      await setAuthState("auth_required", message).catch(() => {});
    } else {
      await setAuthState("error", message).catch(() => {});
    }

    process.exitCode = 1;
  } finally {
    await context.close().catch(() => {});
    await browser.close().catch(() => {});
  }
}

main();
// trigger
// retrigger collector after session saved
