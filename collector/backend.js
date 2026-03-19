const API_BASE = process.env.COLLECTOR_API_BASE || "https://ml-tasks-api.bboobb666.workers.dev";
const API_TOKEN = process.env.COLLECTOR_API_TOKEN || "";

async function request(path, options = {}) {
  const headers = {
    "content-type": "application/json",
    ...(options.headers || {})
  };

  if (API_TOKEN) {
    headers["x-internal-token"] = API_TOKEN;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  });

  const text = await res.text();
  let json = null;

  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }

  if (!res.ok) {
    throw new Error(`Request ${path} failed: ${res.status} ${text}`);
  }

  return json;
}

async function getSession() {
  return await request("/internal/integrations/ml/session", {
    method: "GET"
  });
}

async function upsertOrders(orders) {
  return await request("/internal/orders/upsert-bulk", {
    method: "POST",
    body: JSON.stringify({ orders })
  });
}

async function generateSummary() {
  return await request("/internal/ml/generate-orders-summary", {
    method: "POST",
    body: JSON.stringify({})
  });
}

async function setAuthState(auth_state, last_error = null) {
  return await request("/internal/integrations/set-auth-state", {
    method: "POST",
    body: JSON.stringify({
      source: "mercadolivre",
      auth_state,
      last_error
    })
  });
}

module.exports = {
  getSession,
  upsertOrders,
  generateSummary,
  setAuthState,
  API_BASE
};
