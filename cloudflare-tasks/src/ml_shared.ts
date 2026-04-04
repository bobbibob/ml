export type EnvLike = {
  DB: D1Database
}

export function nowIso(): string {
  return new Date().toISOString()
}

export async function ensureMlTables(env: EnvLike) {
  await env.DB.exec(`
    CREATE TABLE IF NOT EXISTS ml_shared_session (
      id TEXT PRIMARY KEY,
      cookies_json TEXT NOT NULL,
      user_agent TEXT,
      csrf_token TEXT,
      updated_by_user_id TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      is_active INTEGER NOT NULL DEFAULT 1,
      last_check_at TEXT,
      last_error TEXT
    );

    CREATE TABLE IF NOT EXISTS ml_sync_state (
      key TEXT PRIMARY KEY,
      last_success_sync_at TEXT,
      last_attempt_sync_at TEXT,
      last_order_time TEXT,
      last_error TEXT,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS ml_orders (
      order_id TEXT PRIMARY KEY,
      order_time TEXT NOT NULL,
      sku TEXT,
      title TEXT,
      quantity INTEGER,
      price REAL,
      status TEXT,
      raw_json TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_ml_orders_order_time
      ON ml_orders(order_time DESC);
  `)
}


export async function saveSharedMlSession(
  env: EnvLike,
  input: {
    cookiesJson: string
    userAgent?: string | null
    csrfToken?: string | null
    updatedByUserId: string
  }
) {
  const now = nowIso()

  await ensureMlTables(env)

  await env.DB.prepare(`
    INSERT INTO ml_shared_session(
      id, cookies_json, user_agent, csrf_token,
      updated_by_user_id, updated_at, is_active, last_error
    ) VALUES (?, ?, ?, ?, ?, ?, 1, NULL)
    ON CONFLICT(id) DO UPDATE SET
      cookies_json = excluded.cookies_json,
      user_agent = excluded.user_agent,
      csrf_token = excluded.csrf_token,
      updated_by_user_id = excluded.updated_by_user_id,
      updated_at = excluded.updated_at,
      is_active = 1,
      last_error = NULL
  `).bind(
    "shared",
    input.cookiesJson,
    input.userAgent ?? null,
    input.csrfToken ?? null,
    input.updatedByUserId,
    now
  ).run()

  await env.DB.prepare(`
    INSERT INTO ml_sync_state(key, updated_at)
    VALUES(?, ?)
    ON CONFLICT(key) DO UPDATE SET updated_at = excluded.updated_at
  `).bind("orders", now).run()

  return { ok: true, updated_at: now }
}

export async function getMlSyncState(env: EnvLike) {
  await ensureMlTables(env)

  const sessionRow = await env.DB.prepare(`
    SELECT id, updated_by_user_id, updated_at, is_active, last_check_at, last_error
    FROM ml_shared_session
    WHERE id='shared'
    LIMIT 1
  `).first<{
    id: string
    updated_by_user_id: string
    updated_at: string
    is_active: number
    last_check_at: string | null
    last_error: string | null
  }>()

  const syncRow = await env.DB.prepare(`
    SELECT key, last_success_sync_at, last_attempt_sync_at, last_order_time, last_error, updated_at
    FROM ml_sync_state
    WHERE key='orders'
    LIMIT 1
  `).first<{
    key: string
    last_success_sync_at: string | null
    last_attempt_sync_at: string | null
    last_order_time: string | null
    last_error: string | null
    updated_at: string
  }>()

  const lastOrderRow = await env.DB.prepare(`
    SELECT order_id, order_time, sku, title, quantity, price, status, updated_at
    FROM ml_orders
    ORDER BY order_time DESC, updated_at DESC
    LIMIT 1
  `).first()

  return {
    ok: true,
    session: sessionRow
      ? {
          exists: true,
          updated_by_user_id: sessionRow.updated_by_user_id,
          updated_at: sessionRow.updated_at,
          is_active: !!sessionRow.is_active,
          last_check_at: sessionRow.last_check_at,
          last_error: sessionRow.last_error,
        }
      : {
          exists: false,
        },
    sync: syncRow ?? null,
    latest_order: lastOrderRow ?? null,
  }
}

export async function upsertMlOrders(
  env: EnvLike,
  items: Array<{
    order_id?: string
    order_time?: string
    sku?: string | null
    title?: string | null
    quantity?: number | null
    price?: number | null
    status?: string | null
    raw_json?: unknown
  }>
) {
  await ensureMlTables(env)

  const now = nowIso()
  let inserted = 0
  let newestOrderTime: string | null = null

  for (const item of items) {
    const orderId = String(item.order_id || "").trim()
    const orderTime = String(item.order_time || "").trim()
    if (!orderId || !orderTime) continue

    await env.DB.prepare(`
      INSERT INTO ml_orders(
        order_id, order_time, sku, title, quantity, price, status, raw_json, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(order_id) DO UPDATE SET
        order_time = excluded.order_time,
        sku = excluded.sku,
        title = excluded.title,
        quantity = excluded.quantity,
        price = excluded.price,
        status = excluded.status,
        raw_json = excluded.raw_json,
        updated_at = excluded.updated_at
    `).bind(
      orderId,
      orderTime,
      item.sku ?? null,
      item.title ?? null,
      item.quantity ?? null,
      item.price ?? null,
      item.status ?? null,
      JSON.stringify(item.raw_json ?? item),
      now,
      now
    ).run()

    inserted += 1
    if (!newestOrderTime || orderTime > newestOrderTime) {
      newestOrderTime = orderTime
    }
  }

  await env.DB.prepare(`
    INSERT INTO ml_sync_state(
      key, last_success_sync_at, last_attempt_sync_at, last_order_time, last_error, updated_at
    ) VALUES (?, ?, ?, ?, NULL, ?)
    ON CONFLICT(key) DO UPDATE SET
      last_success_sync_at = excluded.last_success_sync_at,
      last_attempt_sync_at = excluded.last_attempt_sync_at,
      last_order_time = COALESCE(excluded.last_order_time, ml_sync_state.last_order_time),
      last_error = NULL,
      updated_at = excluded.updated_at
  `).bind(
    "orders",
    now,
    now,
    newestOrderTime,
    now
  ).run()

  return {
    ok: true,
    inserted,
    newest_order_time: newestOrderTime,
    updated_at: now,
  }
}

export async function markMlSyncAttemptError(env: EnvLike, errorText: string) {
  await ensureMlTables(env)
  const now = nowIso()

  await env.DB.prepare(`
    INSERT INTO ml_sync_state(
      key, last_attempt_sync_at, last_error, updated_at
    ) VALUES (?, ?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET
      last_attempt_sync_at = excluded.last_attempt_sync_at,
      last_error = excluded.last_error,
      updated_at = excluded.updated_at
  `).bind("orders", now, errorText, now).run()

  await env.DB.prepare(`
    UPDATE ml_shared_session
    SET last_error = ?, last_check_at = ?
    WHERE id='shared'
  `).bind(errorText, now).run()
}
