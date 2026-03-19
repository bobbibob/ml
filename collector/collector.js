const { chromium } = require("playwright");
const { upsertOrders, generateSummary, setAuthState, API_BASE } = require("./backend");
const { parseOrdersFromPage, normalizeParsedOrders } = require("./parser");

const ORDERS_URL = process.env.ML_ORDERS_URL || "";
const ML_EMAIL = process.env.ML_EMAIL || "";
const ML_PASSWORD = process.env.ML_PASSWORD || "";
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
    s.includes("iniciar sesión") ||
    s.includes("iniciar sess") ||
    s.includes("entrar") ||
    s.includes("ingresar") ||
    s.includes("mercado libre") && s.includes("continuar")
  );
}

async function tryLogin(page) {
  if (!ML_EMAIL || !ML_PASSWORD) {
    throw new Error("Missing ML_EMAIL or ML_PASSWORD");
  }

  const emailInput = page.locator('input[type="email"], input[name="user_id"], input[name="login"], input[id*="user"]').first();
  await emailInput.waitFor({ timeout: 15000 });
  await emailInput.fill(ML_EMAIL);

  const continueBtn = page.locator('button:has-text("Continuar"), button:has-text("Continue"), input[type="submit"]').first();
  if (await continueBtn.count()) {
    await continueBtn.click();
  }

  const passwordInput = page.locator('input[type="password"]').first();
  await passwordInput.waitFor({ timeout: 15000 });
  await passwordInput.fill(ML_PASSWORD);

  const loginBtn = page.locator('button:has-text("Entrar"), button:has-text("Iniciar"), button:has-text("Login"), input[type="submit"]').first();
  if (await loginBtn.count()) {
    await loginBtn.click();
  }

  await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});
}

async function main() {
  requireEnv("ML_ORDERS_URL", ORDERS_URL);

  console.log("collector_start");
  console.log("api_base", API_BASE);
  console.log("orders_url", ORDERS_URL);

  const browser = await chromium.launch({
    headless: HEADLESS
  });

  const context = await browser.newContext();
  const page = await context.newPage();

  try {
    await page.goto(ORDERS_URL, {
      waitUntil: "domcontentloaded",
      timeout: 90000
    });

    await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});
    let html = await page.content();
    let url = page.url();

    if (looksLikeLogin(url, html)) {
      console.log("login_detected_trying_auth");
      await setAuthState("auth_in_progress", null);
      await tryLogin(page);

      await page.goto(ORDERS_URL, {
        waitUntil: "domcontentloaded",
        timeout: 90000
      });

      await page.waitForLoadState("networkidle", { timeout: 30000 }).catch(() => {});
      html = await page.content();
      url = page.url();
    }

    if (looksLikeLogin(url, html)) {
      await setAuthState("auth_required", "Login required or login failed");
      throw new Error("Login required");
    }

    const parsedOrders = await parseOrdersFromPage(page);
    const orders = normalizeParsedOrders(parsedOrders);

    console.log("parsed_orders", orders.length);

    if (!orders.length) {
      throw new Error("No orders parsed");
    }

    const upsertResult = await upsertOrders(orders);
    console.log("upsert_result", JSON.stringify(upsertResult));

    const summaryResult = await generateSummary();
    console.log("summary_result", JSON.stringify(summaryResult));

    await setAuthState("active", null);
    console.log("collector_success");
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error("collector_error", message);

    if (
      message.toLowerCase().includes("login") ||
      message.toLowerCase().includes("auth")
    ) {
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
