const API_BASE = process.env.COLLECTOR_API_BASE || "https://ml-tasks-api.bboobb666.workers.dev";
const API_TOKEN = process.env.COLLECTOR_API_TOKEN || "";

async function postJson(path, body) {
  const headers = {
    "content-type": "application/json"
  };

  if (API_TOKEN) {
    headers["authorization"] = `Bearer ${API_TOKEN}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body)
  });

  const text = await res.text();
  let json = null;

  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }

  if (!res.ok) {
    throw new Error(`POST ${path} failed: ${res.status} ${text}`);
  }

  return json;
}

async function upsertOrders(orders) {
  return await postJson("/internal/orders/upsert-bulk", { orders });
}

async function generateSummary() {
  return await postJson("/internal/ml/generate-orders-summary", {});
}

async function setAuthState(auth_state, last_error = null) {
  return await postJson("/internal/integrations/set-auth-state", {
    source: "mercadolivre",
    auth_state,
    last_error
  });
}

module.exports = {
  upsertOrders,
  generateSummary,
  setAuthState,
  API_BASE
};
