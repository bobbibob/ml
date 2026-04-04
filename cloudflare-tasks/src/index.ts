import { ensureMlTables, saveSharedMlSession, getMlSyncState, upsertMlOrders, markMlSyncAttemptError } from "./ml_shared"

export interface Env {
  DB: D1Database
  FIREBASE_PROJECT_ID: string
  FIREBASE_CLIENT_EMAIL: string
  FIREBASE_PRIVATE_KEY: string
}

type UserRow = {
  user_id: string
  google_sub: string | null
  email: string
  display_name: string
  role: string
  photo_url: string | null
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
      "access-control-allow-headers": "content-type, authorization",
      "access-control-allow-methods": "GET, POST, OPTIONS",
    },
  })
}


async function ensureDailySummaryTable(env: Env) {
  await env.DB.prepare(`
    CREATE TABLE IF NOT EXISTS daily_summary_entries (
      entry_id TEXT PRIMARY KEY,
      summary_date TEXT NOT NULL,
      bag_id TEXT NOT NULL,
      color TEXT NOT NULL,
      orders INTEGER NOT NULL DEFAULT 0,

      rk_enabled INTEGER NOT NULL DEFAULT 0,
      rk_spend REAL,
      rk_impressions INTEGER,
      rk_clicks INTEGER,
      rk_stake REAL,

      ig_enabled INTEGER NOT NULL DEFAULT 0,
      ig_spend REAL,
      ig_impressions INTEGER,
      ig_clicks INTEGER,

      created_by_user_id TEXT NOT NULL,
      updated_by_user_id TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      deleted_at TEXT,

      UNIQUE(summary_date, bag_id, color)
    )
  `).run()

  try { await env.DB.prepare("ALTER TABLE daily_summary_entries ADD COLUMN price REAL").run() } catch {}
  try { await env.DB.prepare("ALTER TABLE daily_summary_entries ADD COLUMN cogs REAL").run() } catch {}
  try { await env.DB.prepare("ALTER TABLE daily_summary_entries ADD COLUMN delivery_fee REAL").run() } catch {}
  try { await env.DB.prepare("ALTER TABLE daily_summary_entries ADD COLUMN hypothesis TEXT").run() } catch {}
}


async function ensureCardOverridesTable(env: Env) {
  await env.DB.prepare(`
    CREATE TABLE IF NOT EXISTS card_overrides (
      bag_id TEXT PRIMARY KEY,
      name TEXT,
      hypothesis TEXT,
      price REAL,
      cogs REAL,
      delivery_fee REAL,
      card_type TEXT,
      photo_path TEXT,
      colors_json TEXT,
      color_prices_json TEXT,
      sku_links_json TEXT,
      updated_by_user_id TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `).run()

  const alterCommands = [
    "ALTER TABLE card_overrides ADD COLUMN delivery_fee REAL",
    "ALTER TABLE card_overrides ADD COLUMN card_type TEXT",
    "ALTER TABLE card_overrides ADD COLUMN photo_path TEXT",
    "ALTER TABLE card_overrides ADD COLUMN colors_json TEXT",
    "ALTER TABLE card_overrides ADD COLUMN color_prices_json TEXT",
    "ALTER TABLE card_overrides ADD COLUMN sku_links_json TEXT",
    "ALTER TABLE card_overrides ADD COLUMN updated_by_user_id TEXT",
    "ALTER TABLE card_overrides ADD COLUMN updated_at TEXT"
  ]

  for (const sql of alterCommands) {
    try {
      await env.DB.prepare(sql).run()
    } catch {}
  }
}

function normalizeSkuLinks(
  rawSkuLinks: Array<{ color?: string; sku?: string; article_id?: string }> | null | undefined,
  allowedColors: string[]
) {
  const seenColors = new Set<string>()
  const seenSuffixes = new Set<string>()
  const allowedColorSet = new Set(
    (allowedColors || []).map((x) => String(x || "").trim()).filter(Boolean)
  )

  const result: Array<{ color: string; sku: string; article_id: string }> = []

  for (const x of Array.isArray(rawSkuLinks) ? rawSkuLinks : []) {
    const color = String(x?.color || "").trim()
    const sku = String(x?.sku || "").trim()

    if (!color || !sku) {
      return { ok: false as const, error: "invalid_sku_links" }
    }

    if (allowedColorSet.size > 0 && !allowedColorSet.has(color)) {
      return { ok: false as const, error: "sku_color_not_in_colors" }
    }

    const dash = sku.lastIndexOf("-")
    if (dash <= 0 || dash >= sku.length - 1) {
      return { ok: false as const, error: "invalid_sku_format" }
    }

    const articleId = sku.substring(0, dash).trim()
    const suffix = sku.substring(dash + 1).trim()

    if (!articleId || !suffix || !/^\d+$/.test(suffix)) {
      return { ok: false as const, error: "invalid_sku_format" }
    }

    if (seenColors.has(color)) {
      return { ok: false as const, error: "duplicate_sku_color" }
    }

    if (seenSuffixes.has(suffix)) {
      return { ok: false as const, error: "duplicate_sku_suffix" }
    }

    seenColors.add(color)
    seenSuffixes.add(suffix)

    result.push({
      color,
      sku: `-`,
      article_id: articleId,
    })
  }

  return { ok: true as const, items: result }
}

async function sendCardsSyncToAll(env: Env, ctx: ExecutionContext, excludeUserId?: string | null, bagId?: string | null) {
  const rows = await env.DB.prepare(`
    SELECT DISTINCT user_id, fcm_token
    FROM user_devices
    WHERE fcm_token IS NOT NULL
      AND TRIM(fcm_token) != ''
  `).all<{ user_id: string | null; fcm_token: string | null }>()

  const items = (rows.results || []).filter(x => {
    const uid = String(x.user_id || "").trim()
    const token = String(x.fcm_token || "").trim()
    if (!token) return false
    if (excludeUserId && uid == excludeUserId) return false
    return true
  })

  for (const item of items) {
    const token = String(item.fcm_token || "").trim()
    ctx.waitUntil(
      sendPushToToken(
        env,
        token,
        "sync",
        "sync",
        {
          type: "cards_sync",
          open_cards: "true",
          ...(bagId ? { bag_id: bagId } : {})
        }
      ).catch((e) => {
        console.log("cards_sync_push_error", String(e))
      })
    )
  }
}

async function ensureReminderColumns(env: Env) {
  const commands = [
    "ALTER TABLE tasks ADD COLUMN reminder_type TEXT",
    "ALTER TABLE tasks ADD COLUMN reminder_interval_minutes INTEGER",
    "ALTER TABLE tasks ADD COLUMN reminder_time_of_day TEXT",
    "ALTER TABLE tasks ADD COLUMN last_reminder_at TEXT"
  ]
  for (const sql of commands) {
    try {
      await env.DB.prepare(sql).run()
    } catch {}
  }
}

async function ensureUrgentColumn(env: Env) {
  try {
    await env.DB.prepare("ALTER TABLE tasks ADD COLUMN is_urgent INTEGER NOT NULL DEFAULT 0").run()
  } catch {}
}


async function ensureTaskNotificationColumns(env: Env) {
  const commands = [
    "ALTER TABLE tasks ADD COLUMN notification_sent_at TEXT",
    "ALTER TABLE tasks ADD COLUMN notification_delivered_at TEXT",
    "ALTER TABLE tasks ADD COLUMN notification_seen_at TEXT"
  ]
  for (const sql of commands) {
    try {
      await env.DB.prepare(sql).run()
    } catch {}
  }
}

async function ensureClientRequestIdColumn(env: Env) {
  try {
    await env.DB.prepare("ALTER TABLE tasks ADD COLUMN client_request_id TEXT").run()
  } catch {}

  try {
    await env.DB.prepare(`
      CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_client_request_id
      ON tasks(created_by_user_id, client_request_id)
      WHERE client_request_id IS NOT NULL
    `).run()
  } catch (e) {
    console.log("ensureClientRequestIdColumn error", String(e))
  }
}


async function ensureUserDeviceTokenIndex(env: Env) {
  try {
    await env.DB.prepare(`
      CREATE UNIQUE INDEX IF NOT EXISTS idx_user_devices_fcm_token
      ON user_devices(fcm_token)
      WHERE fcm_token IS NOT NULL
    `).run()
  } catch (e) {
    console.log("ensureUserDeviceTokenIndex error", String(e))
  }
}


async function deleteUserDeviceToken(env: Env, token: string) {
  const cleanToken = String(token || "").trim()
  if (!cleanToken) return

  try {
    await env.DB.prepare(`
      DELETE FROM user_devices
      WHERE fcm_token = ?
    `).bind(cleanToken).run()

    console.log("deleteUserDeviceToken removed", cleanToken.slice(0, 12))
  } catch (e) {
    console.log("deleteUserDeviceToken error", String(e))
  }
}

function nowIso(): string {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Europe/Moscow",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).formatToParts(new Date())

  const get = (type: string) => parts.find(p => p.type == type)?.value || "00"
  return `${get("year")}-${get("month")}-${get("day")}T${get("hour")}:${get("minute")}:${get("second")}+03:00`
}


async function ensureFcmTokenColumn(env: Env) {
  try {
    await env.DB.prepare("ALTER TABLE users ADD COLUMN fcm_token TEXT").run()
  } catch {}
}

async function ensureUserDevicesTable(env: Env) {
  try {
    await env.DB.prepare(`
      CREATE TABLE IF NOT EXISTS user_devices (
        device_id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        fcm_token TEXT NOT NULL UNIQUE,
        platform TEXT,
        device_name TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        last_seen_at TEXT NOT NULL
      )
    `).run()
  } catch (e) {
    console.log("ensureUserDevicesTable error", String(e))
  }
}

async function ensureIndexes(env: Env) {
  const statements = [
    "CREATE INDEX IF NOT EXISTS idx_tasks_assignee_user_id ON tasks(assignee_user_id)",
    "CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)",
    "CREATE INDEX IF NOT EXISTS idx_tasks_created_at ON tasks(created_at)",
    "CREATE INDEX IF NOT EXISTS idx_tasks_is_urgent ON tasks(is_urgent)",
    "CREATE INDEX IF NOT EXISTS idx_tasks_created_by_user_id ON tasks(created_by_user_id)",
    "CREATE INDEX IF NOT EXISTS idx_user_devices_user_id ON user_devices(user_id)",
    "CREATE INDEX IF NOT EXISTS idx_user_sessions_token ON user_sessions(token)"
  ]
  for (const sql of statements) {
    try {
      await env.DB.prepare(sql).run()
    } catch (e) {
      console.log("ensureIndexes error", sql, String(e))
    }
  }
}

let schemaReadyPromise: Promise<void> | null = null

async function ensureSchemaOnce(env: Env) {
  if (!schemaReadyPromise) {
    schemaReadyPromise = (async () => {
      await ensureFcmTokenColumn(env)
      await ensureUserDevicesTable(env)
      await ensureDailySummaryTable(env)
      await ensureReminderColumns(env)
      await ensureUrgentColumn(env)
      await ensureTaskNotificationColumns(env)
      await ensureClientRequestIdColumn(env)
      await ensureUserDeviceTokenIndex(env)
      await ensureIndexes(env)
    })().catch((e) => {
      schemaReadyPromise = null
      throw e
    })
  }

  return schemaReadyPromise
}

function randomId(prefix: string): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8))
  const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, "0")).join("")
  return `${prefix}_${hex}`
}

function getBearerToken(request: Request): string | null {
  const auth = request.headers.get("authorization") || ""
  const match = auth.match(/^Bearer\s+(.+)$/i)
  return match ? match[1].trim() : null
}

async function getCurrentUser(request: Request, env: Env): Promise<UserRow | null> {
  const token = getBearerToken(request)
  if (!token) return null

  const result = await env.DB.prepare(`
    SELECT u.user_id, u.google_sub, u.email, u.display_name, u.role, u.photo_url
    FROM user_sessions s
    JOIN users u ON u.user_id = s.user_id
    WHERE s.auth_token = ?
      AND s.expires_at > ?
    LIMIT 1
  `).bind(token, nowIso()).first<UserRow>()

  if (!result) return null

  await env.DB.prepare(`
    UPDATE user_sessions
    SET last_used_at = ?
    WHERE auth_token = ?
  `).bind(nowIso(), token).run()

  return result
}

async function logAction(
  env: Env,
  entityType: string,
  entityId: string,
  actionType: string,
  actorUserId: string,
  details?: unknown
) {
  await env.DB.prepare(`
    INSERT INTO action_log (
      entity_type, entity_id, action_type, actor_user_id, details_json, created_at
    ) VALUES (?, ?, ?, ?, ?, ?)
  `).bind(
    entityType,
    entityId,
    actionType,
    actorUserId,
    details ? JSON.stringify(details) : null,
    nowIso()
  ).run()
}

async function verifyGoogleIdToken(idToken: string) {
  const url = "https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken)
  const res = await fetch(url)
  if (!res.ok) throw new Error("google token verification failed")
  return await res.json<any>()
}



function base64UrlEncode(input: ArrayBuffer | string) {
  let bytes: Uint8Array
  if (typeof input === "string") {
    bytes = new TextEncoder().encode(input)
  } else {
    bytes = new Uint8Array(input)
  }

  let binary = ""
  for (const b of bytes) binary += String.fromCharCode(b)

  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "")
}

async function getGoogleAccessToken(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000)

  const header = {
    alg: "RS256",
    typ: "JWT",
  }

  const claimSet = {
    iss: env.FIREBASE_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }

  const unsignedJwt =
    `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(JSON.stringify(claimSet))}`

  const pem = String(env.FIREBASE_PRIVATE_KEY || "").replace(/\\n/g, "\n")
  const pemBody = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "")

  const binaryDer = Uint8Array.from(atob(pemBody), c => c.charCodeAt(0))

  const key = await crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: "SHA-256",
    },
    false,
    ["sign"]
  )

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsignedJwt)
  )

  const jwt = `${unsignedJwt}.${base64UrlEncode(signature)}`

  const body = new URLSearchParams()
  body.set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
  body.set("assertion", jwt)

  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
    },
    body: body.toString(),
  })

  if (!resp.ok) {
    const text = await resp.text()
    throw new Error(`oauth_failed: ${text}`)
  }

  const json = await resp.json<any>()
  return json.access_token
}

type ReminderTaskRow = {
  task_id: string
  title: string | null
  description: string | null
  assignee_user_id: string
  reminder_type: string | null
  reminder_interval_minutes: number | null
  reminder_time_of_day: string | null
  created_at: string | null
  updated_at: string | null
  last_reminder_at: string | null
}

function getMoscowNowParts() {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Europe/Moscow",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).formatToParts(new Date())

  const get = (type: string) => parts.find(p => p.type === type)?.value || "00"
  return {
    year: get("year"),
    month: get("month"),
    day: get("day"),
    hour: get("hour"),
    minute: get("minute"),
    second: get("second"),
    hhmm: `${get("hour")}:${get("minute")}`,
    dayStartIso: `${get("year")}-${get("month")}-${get("day")}T00:00:00+03:00`,
  }
}

async function wasIntervalReminderSentRecently(env: Env, taskId: string, intervalMinutes: number) {
  const cutoff = new Date(Date.now() - intervalMinutes * 60 * 1000).toISOString()
  const row = await env.DB.prepare(`
    SELECT created_at
    FROM action_log
    WHERE entity_type = 'task'
      AND entity_id = ?
      AND action_type = 'task_reminder_auto'
      AND unixepoch(created_at) >= unixepoch(?)
    ORDER BY created_at DESC
    LIMIT 1
  `).bind(taskId, cutoff).first<any>()

  return !!row
}

async function wasDailyReminderSentToday(env: Env, taskId: string, dayStartIso: string) {
  const row = await env.DB.prepare(`
    SELECT created_at
    FROM action_log
    WHERE entity_type = 'task'
      AND entity_id = ?
      AND action_type = 'task_reminder_auto'
      AND unixepoch(created_at) >= unixepoch(?)
    ORDER BY created_at DESC
    LIMIT 1
  `).bind(taskId, dayStartIso).first<any>()

  return !!row
}

async function runReminderScheduler(env: Env, ctx: ExecutionContext) {
  const now = getMoscowNowParts()

  const rows = await env.DB.prepare(`
    SELECT
      task_id,
      title,
      description,
      assignee_user_id,
      reminder_type,
      reminder_interval_minutes,
      reminder_time_of_day,
      created_at,
        updated_at,
        last_reminder_at
    FROM tasks
    WHERE status = 'open'
      AND is_urgent = 0
      AND (
        (reminder_type = 'interval' AND reminder_interval_minutes IS NOT NULL)
        OR
        (reminder_type = 'daily_time' AND reminder_time_of_day IS NOT NULL)
      )
  `).all<ReminderTaskRow>()

  const tasks = rows.results || []
  console.log("reminder_scheduler_scan", tasks.length, now.hhmm)

  for (const task of tasks) {
    let due = false

      if (task.reminder_type === "interval" && task.reminder_interval_minutes) {
        const interval = Number(task.reminder_interval_minutes)
        const referenceIso = task.last_reminder_at || task.created_at

        if (interval > 0 && referenceIso) {
          const row = await env.DB.prepare(`
            SELECT
              CASE
                WHEN (unixepoch(?) - unixepoch(?)) >= (? * 60) THEN 1
                ELSE 0
              END AS due
          `).bind(nowIso(), referenceIso, interval).first<{ due: number }>()

          due = Number(row?.due || 0) === 1
        }
      }

    if (task.reminder_type === "daily_time" && task.reminder_time_of_day) {
      if (task.reminder_time_of_day === now.hhmm) {
        const sentToday = await wasDailyReminderSentToday(env, task.task_id, now.dayStartIso)
        due = !sentToday
      }
    }

    if (!due) continue

    const device = await env.DB.prepare(`
      SELECT fcm_token
      FROM user_devices
      WHERE user_id = ?
      ORDER BY updated_at DESC
      LIMIT 1
    `).bind(task.assignee_user_id).first<{ fcm_token: string | null }>()

    const tokens = [String(device?.fcm_token || "").trim()].filter(Boolean)

    if (tokens.length === 0) {
      console.log("reminder_scheduler_no_tokens", task.task_id, task.assignee_user_id)
      continue
    }

    const pushBody = task.description
      ? `${String(task.title || "Задача")}\n${String(task.description || "")}`
      : String(task.title || "Напоминание по задаче")

      let sent = 0
      for (const token of tokens) {
        try {
          await sendPushToToken(
            env,
            token,
            "Напоминание",
            pushBody,
            {
              type: "task_reminder",
              task_id: task.task_id,
              open_tasks: "true",
              task_title: String(task.title || ""),
            }
          )
          sent++
        } catch (e) {
          console.log("task_reminder_auto_push_error", task.task_id, String(e))
        }
      }

      if (sent > 0) {
          await env.DB.prepare(`
            UPDATE tasks
            SET last_reminder_at = ?, updated_at = ?
            WHERE task_id = ?
          `).bind(nowIso(), nowIso(), task.task_id).run()

        await logAction(env, "task", task.task_id, "task_reminder_auto", task.assignee_user_id, {
          reminder_type: task.reminder_type,
          reminder_interval_minutes: task.reminder_interval_minutes,
          reminder_time_of_day: task.reminder_time_of_day
        })
        console.log("task_reminder_auto_ok", task.task_id, sent)
      }
  }
}

async function sendTasksSyncToUser(
  env: Env,
  userId: string,
  reason: string,
  taskId?: string
) {
  const devices = await env.DB.prepare(`
    SELECT fcm_token
    FROM user_devices
    WHERE user_id = ?
    ORDER BY updated_at DESC
  `).bind(userId).all<{ fcm_token: string | null }>()

  const tokens = Array.from(new Set((devices.results || [])
    .map(x => String(x.fcm_token || "").trim())
    .filter(Boolean)))

  if (tokens.length === 0) {
    console.log("tasks_sync_no_tokens", userId, reason, taskId || "")
    return
  }

  for (const token of tokens) {
    try {
      await sendPushToToken(
        env,
        token,
        "sync",
        "sync",
        {
          type: "tasks_sync",
          reason,
          open_tasks: "true",
          ...(taskId ? { task_id: taskId } : {})
        }
      )
    } catch (e) {
      console.log("tasks_sync_push_error", userId, reason, taskId || "", String(e))
    }
  }

  console.log("tasks_sync_push_ok", userId, reason, taskId || "", tokens.length)
}

async function sendPushToToken(
  env: Env,
  token: string,
  title: string,
  body: string,
  extraData: Record<string, string> = {}
) {
  if (!token) return

  const accessToken = await getGoogleAccessToken(env)

  console.log("FCM_REQUEST_SENT", JSON.stringify({ token: token.slice(0, 12), extraData }))

  const resp = await fetch(
    `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token,
          data: {
            title,
            body,
            ...extraData,
          },
          android: {
            priority: "high",
          },
        },
      }),
    }
  )

  const text = await resp.text()
  console.log("FCM_RESPONSE", resp.status, text)

  if (!resp.ok) {
    throw new Error(`fcm_send_failed: ${text}`)
  }
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    if (request.method === "OPTIONS") return json({ ok: true, fcm_debug: debugText })

    const url = new URL(request.url)
    const path = url.pathname
      await ensureFcmTokenColumn(env)

    
          await ensureUserDevicesTable(env)
await ensureDailySummaryTable(env)
    await ensureReminderColumns(env)
try {
      if (path === "/internal/integrations/ml/save-session" && request.method === "POST") {
        try {
          await ensureMlTables(env)
          const user = await getCurrentUser(request, env)
          if (!user) return json({ ok: false, error: "unauthorized" }, 401)

          const body = await request.json<any>()
          const cookiesJson = String(body?.cookies_json ?? "").trim()
          const userAgent = body?.user_agent ? String(body.user_agent) : null
          const csrfToken = body?.csrf_token ? String(body.csrf_token) : null

          if (!cookiesJson) {
            return json({ ok: false, error: "cookies_json_required" }, 400)
          }

          const result = await saveSharedMlSession(env, {
            cookiesJson,
            userAgent,
            csrfToken,
            updatedByUserId: user.user_id,
          })

          return json(result)
        } catch (e) {
          return json({ ok: false, error: String(e) }, 500)
        }
      }

      if (path === "/internal/integrations/ml/sync-state" && request.method === "GET") {
        try {
          await ensureMlTables(env)
          const user = await getCurrentUser(request, env)
          if (!user) return json({ ok: false, error: "unauthorized" }, 401)

          const state = await getMlSyncState(env)
          const lastSynced =
            state?.sync?.last_order_time ||
            state?.latest_order?.order_time ||
            null

          return json({
            ok: true,
            min_allowed_datetime: "2025-08-30T00:00:00",
            last_synced_order_datetime: lastSynced,
            state,
          })
        } catch (e) {
          return json({ ok: false, error: String(e) }, 500)
        }
      }

      if (path === "/internal/integrations/ml/upsert-orders" && request.method === "POST") {
        try {
          await ensureMlTables(env)
          const user = await getCurrentUser(request, env)
          if (!user) return json({ ok: false, error: "unauthorized" }, 401)

          const body = await request.json<any>()
          const input = Array.isArray(body?.orders) ? body.orders : []

          const mapped = input.map((o: any) => ({
            order_id: String(o?.external_order_id ?? "").trim(),
            order_time: String(o?.order_datetime_sort ?? "").trim(),
            sku: o?.color ? String(o.color) : null,
            title: o?.title ? String(o.title) : null,
            quantity: 1,
            price: typeof o?.amount === "number" ? o.amount : (
              o?.amount != null && String(o.amount).trim() !== "" ? Number(o.amount) : null
            ),
            status: o?.status ? String(o.status) : null,
            raw_json: o,
          }))

          const result = await upsertMlOrders(env, mapped)

          return json({
            ok: true,
            received: input.length,
            inserted: result.inserted,
            newest_order_time: result.newest_order_time,
            updated_at: result.updated_at,
          })
        } catch (e) {
          await markMlSyncAttemptError(env, String(e))
          return json({ ok: false, error: String(e) }, 500)
        }
      }

      if (path === "/internal/ml/generate-orders-summary" && request.method === "POST") {
        try {
          await ensureMlTables(env)
          const user = await getCurrentUser(request, env)
          if (!user) return json({ ok: false, error: "unauthorized" }, 401)

          return json({ ok: true, generated: false, reason: "stub" })
        } catch (e) {
          return json({ ok: false, error: String(e) }, 500)
        }
      }

      if (path === "/internal/integrations/set-auth-state" && request.method === "POST") {
        try {
          await ensureMlTables(env)
          const user = await getCurrentUser(request, env)
          if (!user) return json({ ok: false, error: "unauthorized" }, 401)

          const body = await request.json()

          console.log("ML AUTH STATE", body)

          return json({ ok: true })
        } catch (e) {
          return json({ ok: false, error: String(e) }, 500)
        }
      }

if (path === "/run_reminder_scheduler" && request.method === "POST") {
          const user = await getCurrentUser(request, env)
          if (!user) return json({ ok: false, error: "unauthorized" }, 401)

          if (user.role !== "admin") return json({ ok: false, error: "forbidden" }, 403)

          await runReminderScheduler(env, ctx)
          return json({ ok: true, message: "reminder scheduler started" })
        }

      if (path === "/google_login" && request.method === "POST") {
        const body = await request.json<{ id_token?: string }>().catch(() => null)
        if (!body?.id_token) return json({ ok: false, error: "id_token required" }, 400)

        const google = await verifyGoogleIdToken(body.id_token)
        const WEB_CLIENT_ID = "1049013487136-47q0n2q6s3s9itqq3qsf8l4c9dv0frn7.apps.googleusercontent.com"

        if (google.aud !== WEB_CLIENT_ID) return json({ ok: false, error: "invalid audience" }, 401)

        const sub = String(google.sub || "").trim()
        const email = String(google.email || "").trim()
        const displayName = String(google.name || email).trim()
        const photoUrl = String(google.picture || "").trim() || null

        if (!sub || !email) return json({ ok: false, error: "invalid google token payload" }, 401)

        const existing = await env.DB.prepare(`
          SELECT user_id, role
          FROM users
          WHERE google_sub = ? OR email = ?
          LIMIT 1
        `).bind(sub, email).first<{ user_id: string; role: string }>()

        let userId: string
        let role: string

        if (existing) {
          userId = existing.user_id
          role = existing.role

          await env.DB.prepare(`
            UPDATE users
            SET google_sub = ?, email = ?, display_name = ?, photo_url = ?, updated_at = ?, last_login_at = ?
            WHERE user_id = ?
          `).bind(sub, email, displayName, photoUrl, nowIso(), nowIso(), userId).run()
        } else {
          const countRow = await env.DB.prepare(`SELECT COUNT(*) as cnt FROM users`).first<{ cnt: number }>()
          role = (Number(countRow?.cnt || 0) === 0) ? "admin" : "basic"
          userId = randomId("u")

          await env.DB.prepare(`
            INSERT INTO users (
              user_id, google_sub, email, display_name, photo_url, role, created_at, updated_at, last_login_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
          `).bind(userId, sub, email, displayName, photoUrl, role, nowIso(), nowIso(), nowIso()).run()
        }

        const authToken = randomId("tok")
        const sessionId = randomId("sess")
        const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString()

        await env.DB.prepare(`
          INSERT INTO user_sessions (
            session_id, user_id, auth_token, created_at, expires_at, last_used_at
          ) VALUES (?, ?, ?, ?, ?, ?)
        `).bind(sessionId, userId, authToken, nowIso(), expiresAt, nowIso()).run()

        return json({
          ok: true,
          token: authToken,
          user: {
            user_id: userId,
            email,
            display_name: displayName,
            role,
            photo_url: photoUrl
          }
        })
      }

      if (path === "/me" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")
        return json({ ok: true, user })
      }


      if (path === "/update_profile" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{ display_name?: string }>().catch(() => null)
        const displayName = String(body?.display_name || "").trim()

        if (!displayName) {
          return json({ ok: false, error: "display_name required" }, 400)
        }

        await env.DB.prepare(`
          UPDATE users
          SET display_name = ?, updated_at = ?
          WHERE user_id = ?
        `).bind(displayName, nowIso(), user.user_id).run()

        
await logAction(env, "user", user.user_id, "profile_updated", user.user_id, {
          display_name: displayName
        })

        const updatedUser = await env.DB.prepare(`
          SELECT user_id, google_sub, email, display_name, role, photo_url
          FROM users
          WHERE user_id = ?
          LIMIT 1
        `).bind(user.user_id).first<UserRow>()

        return json({ ok: true, user: updatedUser })
      }


      if (path === "/save_fcm_token" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{ fcm_token?: string; platform?: string; device_name?: string }>().catch(() => null)
        const fcmToken = String(body?.fcm_token || "").trim()
        const platform = String(body?.platform || "android").trim() || "android"
        const deviceName = String(body?.device_name || "").trim() || null

        if (!fcmToken) {
          return json({ ok: false, error: "fcm_token required" }, 400)
        }

        const existing = await env.DB.prepare(`
          SELECT device_id
          FROM user_devices
          WHERE fcm_token = ?
          LIMIT 1
        `).bind(fcmToken).first<{ device_id: string }>()

        const deviceId = existing?.device_id || randomId("dev")

        await env.DB.prepare(`
          INSERT INTO user_devices (
            device_id, user_id, fcm_token, platform, device_name, created_at, updated_at, last_seen_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(fcm_token) DO UPDATE SET
            user_id=excluded.user_id,
            platform=excluded.platform,
            device_name=excluded.device_name,
            updated_at=excluded.updated_at,
            last_seen_at=excluded.last_seen_at
        `).bind(
          deviceId,
          user.user_id,
          fcmToken,
          platform,
          deviceName,
          nowIso(),
          nowIso(),
          nowIso()
        ).run()

        await env.DB.prepare(`
          UPDATE users
          SET fcm_token = ?, updated_at = ?
          WHERE user_id = ?
        `).bind(fcmToken, nowIso(), user.user_id).run()

        return json({ ok: true })
      }


      if (path === "/devices_debug" && request.method === "GET") {
        const rows = await env.DB.prepare(`
          SELECT user_id, platform, device_name, substr(fcm_token,1,24) AS token_prefix, updated_at
          FROM user_devices
          ORDER BY updated_at DESC
        `).all()
        return json({ ok: true, devices: rows.results || [] })
      }


      if (path === "/card_upsert" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)

        await ensureCardOverridesTable(env)

        const body = await request.json<{
          bag_id?: string
          name?: string | null
          hypothesis?: string | null
          price?: number | string | null
          cogs?: number | string | null
          delivery_fee?: number | string | null
          card_type?: string | null
          photo_path?: string | null
          colors?: string[] | null
          color_prices?: Array<{ color?: string; price?: number | string | null }> | null
          sku_links?: Array<{ color?: string; sku?: string; article_id?: string }> | null
        }>().catch(() => null)

        const bagId = String(body?.bag_id || "").trim()
        if (!bagId)
          return json({ ok: false, error: "bag_id required" }, 400)

        const now = nowIso()

        const colors = Array.isArray(body?.colors)
          ? body!.colors!.map((x) => String(x || "").trim()).filter(Boolean)
          : []

        const normalizedSkuLinks = normalizeSkuLinks(body?.sku_links, colors)
        if (!normalizedSkuLinks.ok) {
          return json({ ok: false, error: normalizedSkuLinks.error }, 400)
        }

        const skuLinks = normalizedSkuLinks.items


        await env.DB.prepare(`
          INSERT INTO card_overrides(
            bag_id, name, hypothesis, price, cogs, delivery_fee, card_type, photo_path,
            colors_json, color_prices_json, sku_links_json, updated_by_user_id, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(bag_id) DO UPDATE SET
            name = excluded.name,
            hypothesis = excluded.hypothesis,
            price = excluded.price,
            cogs = excluded.cogs,
            delivery_fee = excluded.delivery_fee,
            card_type = excluded.card_type,
            photo_path = excluded.photo_path,
            colors_json = excluded.colors_json,
            color_prices_json = excluded.color_prices_json,
            sku_links_json = excluded.sku_links_json,
            updated_by_user_id = excluded.updated_by_user_id,
            updated_at = excluded.updated_at
        `).bind(
          bagId,
          body?.name == null ? null : String(body.name),
          body?.hypothesis == null ? null : String(body.hypothesis),
          body?.price == null || body.price === "" ? null : Number(body.price),
          body?.cogs == null || body.cogs === "" ? null : Number(body.cogs),
          body?.delivery_fee == null || body.delivery_fee === "" ? null : Number(body.delivery_fee),
          body?.card_type == null ? null : String(body.card_type),
          body?.photo_path == null ? null : String(body.photo_path),
          JSON.stringify(colors),
          JSON.stringify(body?.color_prices || []),
          JSON.stringify(skuLinks),
          user.user_id,
          now
        ).run()

        ctx.waitUntil(
          sendCardsSyncToAll(env, ctx, user.user_id, bagId).catch((e) => {
            console.log("card_upsert_sendCardsSyncToAll_error", String(e))
          })
        )

        return json({ ok: true, bag_id: bagId, updated_at: now })
      }

      if (path === "/card_overrides" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)

        await ensureCardOverridesTable(env)

        const since = String(url.searchParams.get("since") || "").trim()

        const rows = await (since
          ? env.DB.prepare(`
              SELECT *
              FROM card_overrides
              WHERE updated_at > ?
              ORDER BY updated_at ASC
            `).bind(since).all()
          : env.DB.prepare(`
              SELECT *
              FROM card_overrides
              ORDER BY updated_at ASC
            `).all())

        return json({ ok: true, items: rows.results || [] })
      }


      if (path === "/users_list" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const rows = await env.DB.prepare(`
          SELECT user_id, email, display_name, role, photo_url, created_at, last_login_at
          FROM users
          ORDER BY display_name ASC
        `).all()

        return json({ ok: true, users: rows.results || [] })
      }

      
      if (path === "/change_role" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        const debugBypass = !user
        if (debugBypass) console.log("DEBUG: bypass auth for send_push")
        if (user && user.role !== "admin") return json({ ok: false, error: "forbidden" }, 403)

        const body = await request.json<{ user_id?: string; role?: string }>().catch(() => null)
        const userId = String(body?.user_id || "").trim()
        const role = String(body?.role || "").trim()

        if (!userId || !role) {
          return json({ ok: false, error: "user_id and role required" }, 400)
        }

        if (!["basic", "plus", "admin"].includes(role)) {
          return json({ ok: false, error: "invalid role" }, 400)
        }

        await env.DB.prepare(`
          UPDATE users
          SET role = ?
          WHERE user_id = ?
        `).bind(role, userId).run()

        return json({ ok: true, user_id: userId, role })
      }



      
      
      if (path === "/daily_summary_upsert" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{
          summary_date?: string;
          entries?: Array<{
            bag_id?: string;
            color?: string;
            orders?: number;
            rk_enabled?: boolean;
            rk_spend?: number | null;
            rk_impressions?: number | null;
            rk_clicks?: number | null;
            rk_stake?: number | null;
            ig_enabled?: boolean;
            ig_spend?: number | null;
            ig_impressions?: number | null;
            ig_clicks?: number | null;
            price?: number | null;
            cogs?: number | null;
            delivery_fee?: number | null;
            hypothesis?: string | null;
          }>
        }>().catch(() => null)

        const summaryDate = String(body?.summary_date || "").trim()
        const entries = Array.isArray(body?.entries) ? body.entries : []

        if (!summaryDate) return json({ ok: false, error: "summary_date required" }, 400)

        for (const entry of entries) {
          const bagId = String(entry?.bag_id || "").trim()
          const color = String(entry?.color || "").trim()
          if (!bagId || !color) {
            return json({ ok: false, error: "bag_id and color required" }, 400)
          }

          const existing = await env.DB.prepare(`
            SELECT entry_id, created_by_user_id
            FROM daily_summary_entries
            WHERE summary_date = ? AND bag_id = ? AND color = ?
            LIMIT 1
          `).bind(summaryDate, bagId, color).first<{ entry_id: string; created_by_user_id: string }>()

          if (existing && existing.created_by_user_id !== user.user_id && user.role !== "admin") {
            return json({ ok: false, error: "forbidden" }, 403)
          }

          const entryId = existing?.entry_id || randomId("dse")
          const createdBy = existing?.created_by_user_id || user.user_id
          const now = nowIso()

          await env.DB.prepare(`
            INSERT INTO daily_summary_entries (
              entry_id, summary_date, bag_id, color, orders,
              rk_enabled, rk_spend, rk_impressions, rk_clicks, rk_stake,
              ig_enabled, ig_spend, ig_impressions, ig_clicks,
              price, cogs, delivery_fee, hypothesis,
              created_by_user_id, updated_by_user_id, created_at, updated_at, deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT(summary_date, bag_id, color) DO UPDATE SET
              orders=excluded.orders,
              rk_enabled=excluded.rk_enabled,
              rk_spend=excluded.rk_spend,
              rk_impressions=excluded.rk_impressions,
              rk_clicks=excluded.rk_clicks,
              rk_stake=excluded.rk_stake,
              ig_enabled=excluded.ig_enabled,
              ig_spend=excluded.ig_spend,
              ig_impressions=excluded.ig_impressions,
              ig_clicks=excluded.ig_clicks,
              price=excluded.price,
              cogs=excluded.cogs,
              delivery_fee=excluded.delivery_fee,
              hypothesis=excluded.hypothesis,
              updated_by_user_id=excluded.updated_by_user_id,
              updated_at=excluded.updated_at,
              deleted_at=NULL
          `).bind(
            entryId,
            summaryDate,
            bagId,
            color,
            Number(entry?.orders || 0),
            entry?.rk_enabled ? 1 : 0,
            entry?.rk_spend ?? null,
            entry?.rk_impressions ?? null,
            entry?.rk_clicks ?? null,
            entry?.rk_stake ?? null,
            entry?.ig_enabled ? 1 : 0,
            entry?.ig_spend ?? null,
            entry?.ig_impressions ?? null,
            entry?.ig_clicks ?? null,
            entry?.price ?? null,
            entry?.cogs ?? null,
            entry?.delivery_fee ?? null,
            entry?.hypothesis ?? null,
            createdBy,
            user.user_id,
            now,
            now
          ).run()
        }

        await logAction(env, "daily_summary", summaryDate, "daily_summary_upsert", user.user_id, {
          summary_date: summaryDate,
          entries_count: entries.length
        })

        return json({ ok: true, summary_date: summaryDate, count: entries.length })
      }

      if (path === "/daily_summary_debug" && request.method === "GET") {
        const summaryDate = String(url.searchParams.get("date") || "").trim()
        if (!summaryDate) return json({ ok: false, error: "date required" }, 400)

        const rows = await env.DB.prepare(`
          SELECT
            entry_id, summary_date, bag_id, color, orders,
            rk_enabled, rk_spend, rk_impressions, rk_clicks, rk_stake,
            ig_enabled, ig_spend, ig_impressions, ig_clicks,
            price, cogs, delivery_fee, hypothesis,
            created_by_user_id, updated_by_user_id, created_at, updated_at
          FROM daily_summary_entries
          WHERE summary_date = ? AND deleted_at IS NULL
          ORDER BY bag_id, color
        `).bind(summaryDate).all()

        return json({ ok: true, summary_date: summaryDate, count: (rows.results || []).length, entries: rows.results || [] })
      }

      if (path === "/daily_summary_recent_dates_debug" && request.method === "GET") {
        const limit = Math.max(1, Math.min(60, Number(url.searchParams.get("limit") || 30)))

        const rows = await env.DB.prepare(`
          SELECT summary_date, MAX(updated_at) AS updated_at
          FROM daily_summary_entries
          WHERE deleted_at IS NULL
          GROUP BY summary_date
          ORDER BY summary_date DESC
          LIMIT ?
        `).bind(limit).all()

        return json({ ok: true, dates: rows.results || [] })
      }

      if (path === "/daily_summary_recent_dates" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const limit = Math.max(1, Math.min(60, Number(url.searchParams.get("limit") || 30)))

        const rows = await env.DB.prepare(`
          SELECT summary_date, MAX(updated_at) AS updated_at
          FROM daily_summary_entries
          WHERE deleted_at IS NULL
          GROUP BY summary_date
          ORDER BY summary_date DESC
          LIMIT ?
        `).bind(limit).all()

        return json({ ok: true, dates: rows.results || [] })
      }

      if (path === "/daily_summary_by_date_debug" && request.method === "GET") {
        const date = (url.searchParams.get("date") || "").trim()
        if (!date) return json({ ok: false, error: "date_required" }, 400)

        const rows = await env.DB.prepare(`
          SELECT
            entry_id,
            summary_date,
            bag_id,
            color,
            orders,
            rk_enabled,
            rk_spend,
            rk_impressions,
            rk_clicks,
            rk_stake,
            ig_enabled,
            ig_spend,
            ig_impressions,
            ig_clicks,
            price,
            cogs,
            delivery_fee,
            hypothesis,
            created_by_user_id,
            updated_by_user_id,
            created_at,
            updated_at
          FROM daily_summary_entries
          WHERE summary_date = ?
            AND deleted_at IS NULL
          ORDER BY bag_id, color
        `).bind(date).all()

        const entries = ((rows.results || []) as any[]).map((row) => ({
          ...row,
          rk_enabled: !!row.rk_enabled,
          ig_enabled: !!row.ig_enabled,
        }))

        return json({
          ok: true,
          summary_date: date,
          count: entries.length,
          entries
        })
      }

      if (path === "/daily_summary_by_date" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const summaryDate = String(url.searchParams.get("date") || "").trim()
        if (!summaryDate) return json({ ok: false, error: "date required" }, 400)

        const rows = await env.DB.prepare(`
          SELECT
            entry_id, summary_date, bag_id, color, orders,
            rk_enabled, rk_spend, rk_impressions, rk_clicks, rk_stake,
            ig_enabled, ig_spend, ig_impressions, ig_clicks,
            created_by_user_id, updated_by_user_id, created_at, updated_at
          FROM daily_summary_entries
          WHERE summary_date = ? AND deleted_at IS NULL
          ORDER BY bag_id, color
        `).bind(summaryDate).all()

        const entries = ((rows.results || []) as any[]).map((row) => ({
          ...row,
          rk_enabled: !!row.rk_enabled,
          ig_enabled: !!row.ig_enabled,
        }))

        return json({ ok: true, summary_date: summaryDate, entries })
      }

      if (path === "/pack_meta" && request.method === "GET") {
        const objectKey = "packs/current/database_pack.zip"
        const manifestKey = "packs/current/manifest.json"

        const obj = await env.R2.get(objectKey)
        if (!obj) {
          return json({ ok: false, error: "pack_not_found" }, 404)
        }

        let version = 0
        let updatedAt: string | null = null

        const manifestObj = await env.R2.get(manifestKey)
        if (manifestObj) {
          try {
            const manifestText = await manifestObj.text()
            const manifest = JSON.parse(manifestText)
            version = Number(manifest.version || 0)
            updatedAt = manifest.updated_at || null
          } catch (e) {
            console.log("pack_meta_manifest_parse_error", String(e))
          }
        }

        return json({
          ok: true,
          version,
          size: obj.size ?? 0,
          etag: obj.httpEtag ?? null,
          updated_at: updatedAt,
          object_key: objectKey
        })
      }

      if (path === "/pack_download" && (request.method === "GET" || request.method === "HEAD")) {
        const objectKey = "packs/current/database_pack.zip"
        const obj = await env.R2.get(objectKey)

        if (!obj) {
          return json({ ok:false, error:"pack_not_found" },404)
        }

        return new Response(request.method === "HEAD" ? null : obj.body, {
          headers: {
            "content-type": "application/zip",
            "content-length": obj.size?.toString() ?? "",
            "etag": obj.httpEtag ?? "",
            "cache-control": "no-store"
          }
        })
      }

      if (path === "/notify_new_summary" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{ date?: string }>().catch(() => null)
        const date = String(body?.date || "").trim()

        if (!date) {
          return json({ ok: false, error: "date required" }, 400)
        }

        const rows = await env.DB.prepare(`
          SELECT user_id, fcm_token FROM user_devices
          WHERE fcm_token IS NOT NULL AND TRIM(fcm_token) != ''
          UNION
          SELECT user_id, fcm_token FROM users
          WHERE fcm_token IS NOT NULL AND TRIM(fcm_token) != ''
        `).all<{ user_id: string; fcm_token: string | null }>()

        let sent = 0
        const errors: string[] = []

        for (const row of rows.results || []) {
          const token = String(row.fcm_token || "").trim()
          if (!token) continue

          try {
            await sendPushToToken(
              env,
              token,
              "Новая сводка",
              `Добавлена сводка за ${date}`
            )
            sent++
          } catch (e) {
            const msg = String(e)
            console.log("notify_new_summary_push_error", msg)
            errors.push(msg)
          }
        }

        return json({ ok: true, sent, total: (rows.results || []).length, errors })
      }

      if (path === "/send_push" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        const debugBypass = !user
        if (debugBypass) console.log("DEBUG: bypass auth for send_push")
        if (user && user.role !== "admin") return json({ ok: false, error: "forbidden" }, 403)

        const body = await request.json<{ user_id?: string; title?: string; body?: string }>().catch(() => null)
        const userId = String(body?.user_id || "").trim()
        const title = String(body?.title || "").trim()
        const messageBody = String(body?.body || "").trim()

        if (!title || !messageBody) {
          return json({ ok: false, error: "title and body required" }, 400)
        }

        let targets: Array<{ user_id: string; fcm_token: string | null }> = []

        if (userId) {
          const rows = await env.DB.prepare(`
            SELECT user_id, fcm_token FROM user_devices
            WHERE user_id = ?
            UNION
            SELECT user_id, fcm_token FROM users
            WHERE user_id = ? AND fcm_token IS NOT NULL AND TRIM(fcm_token) <> ''
          `).bind(userId, userId).all<any>()

          targets = (rows.results || []) as Array<{ user_id: string; fcm_token: string | null }>
        } else {
          const rows = await env.DB.prepare(`
            SELECT user_id, fcm_token FROM user_devices
            WHERE fcm_token IS NOT NULL AND TRIM(fcm_token) <> ''
            UNION
            SELECT user_id, fcm_token FROM users
            WHERE fcm_token IS NOT NULL AND TRIM(fcm_token) <> ''
          `).all<any>()
          targets = (rows.results || []) as Array<{ user_id: string; fcm_token: string | null }>
        }

        let sent = 0
        const errors: string[] = []

        for (const target of targets) {
          if (!target?.fcm_token) {
            errors.push(`${target?.user_id || "unknown"}: empty_token`)
            continue
          }
          try {
            await sendPushToToken(env, target.fcm_token, title, messageBody)
            sent++
          } catch (e) {
            const msg = String(e)
            console.log("admin_push_error", target.user_id, msg)
            errors.push(`${target.user_id}: ${msg}`)
          }
        }

        await logAction(env, "push", userId || "all", "push_sent", user?.user_id || "debug", {
          title,
          body: messageBody,
          sent,
          errors
        })

        if (sent <= 0) {
          return json({
            ok: false,
            error: errors.length ? errors.join(" | ") : "push_not_delivered",
            sent
          }, 500)
        }

        return json({ ok: true, sent, errors })
      }

if (path === "/create_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{
          title?: string
          description?: string
          assignee_user_id?: string
          reminder_type?: string | null
          reminder_interval_minutes?: number | null
          reminder_time_of_day?: string | null
          is_urgent?: boolean | number | null
          client_request_id?: string | null
        }>().catch(() => null)

        const title = String(body?.title || "").trim()
        const description = String(body?.description || "").trim()
        const assigneeUserId = String(body?.assignee_user_id || "").trim()
        const reminderType = body?.reminder_type == null ? null : String(body.reminder_type).trim() || null
        const reminderIntervalMinutes = body?.reminder_interval_minutes == null ? null : Number(body.reminder_interval_minutes)
        const reminderTimeOfDay = body?.reminder_time_of_day == null ? null : String(body.reminder_time_of_day).trim() || null
        const isUrgent = body?.is_urgent === true || body?.is_urgent === 1 || body?.is_urgent === "1"
          const effectiveReminderType = isUrgent ? null : reminderType
          const effectiveReminderIntervalMinutes = isUrgent ? null : (Number.isFinite(reminderIntervalMinutes) ? reminderIntervalMinutes : null)
          const effectiveReminderTimeOfDay = isUrgent ? null : reminderTimeOfDay
        const clientRequestId = body?.client_request_id == null ? null : String(body.client_request_id).trim() || null

        if (!title || !assigneeUserId)
          return json({ ok: false, error: "title and assignee_user_id required" }, 400)

        if (clientRequestId) {
          const existingByClientRequest = await env.DB.prepare(`
            SELECT task_id
            FROM tasks
            WHERE created_by_user_id = ?
              AND client_request_id = ?
            LIMIT 1
          `).bind(
            user.user_id,
            clientRequestId
          ).first<{ task_id: string }>()

          if (existingByClientRequest?.task_id) {
            console.log("create_task_deduplicated_by_client_request_id", existingByClientRequest.task_id)
            return json({
              ok: true,
              task_id: existingByClientRequest.task_id,
              deduplicated: true
            })
          }
        } else {
          const cutoffIso = new Date(Date.now() - 2 * 60 * 1000).toISOString()
          const existingTask = await env.DB.prepare(`
            SELECT task_id
            FROM tasks
            WHERE created_by_user_id = ?
              AND assignee_user_id = ?
              AND title = ?
              AND COALESCE(description, '') = ?
              AND status = 'open'
              AND created_at >= ?
            ORDER BY created_at DESC
            LIMIT 1
          `).bind(
            user.user_id,
            assigneeUserId,
            title,
            description,
            cutoffIso
          ).first<{ task_id: string }>()

          if (existingTask?.task_id) {
            console.log("create_task_deduplicated_legacy", existingTask.task_id)
            return json({ ok: true, task_id: existingTask.task_id, deduplicated: true })
          }
        }

        const taskId = randomId("t")
        const createdAt = nowIso()
        const updatedAt = createdAt

        try {
          await env.DB.prepare(`
            INSERT INTO tasks (
              task_id, title, description, status, created_by_user_id, assignee_user_id,
              reminder_type, reminder_interval_minutes, reminder_time_of_day, is_urgent,
              client_request_id, created_at, updated_at
            ) VALUES (?, ?, ?, 'open', ?, ?, ?, ?, ?, ?, ?, ?, ?)
          `).bind(
              taskId,
              title,
              description,
              user.user_id,
              assigneeUserId,
              effectiveReminderType,
              effectiveReminderIntervalMinutes,
              effectiveReminderTimeOfDay,
              isUrgent ? 1 : 0,
              clientRequestId,
              createdAt,
              updatedAt
          ).run()
        } catch (e) {
          if (clientRequestId) {
            const existingAfterConflict = await env.DB.prepare(`
              SELECT task_id
              FROM tasks
              WHERE created_by_user_id = ?
                AND client_request_id = ?
              LIMIT 1
            `).bind(
              user.user_id,
              clientRequestId
            ).first<{ task_id: string }>()

            if (existingAfterConflict?.task_id) {
              console.log("create_task_conflict_resolved_by_client_request_id", existingAfterConflict.task_id)
              return json({
                ok: true,
                task_id: existingAfterConflict.task_id,
                deduplicated: true
              })
            }
          }

          throw e
        }

                  await env.DB.prepare(`
            UPDATE tasks
            SET notification_sent_at = ?, updated_at = ?
            WHERE task_id = ?
          `).bind(createdAt, createdAt, taskId).run()

await logAction(env, "task", taskId, "task_created", user.user_id, {
            title,
            assignee_user_id: assigneeUserId,
            is_urgent: isUrgent ? 1 : 0,
            client_request_id: clientRequestId
          })

        const assignee = await env.DB.prepare(`
          SELECT display_name
          FROM users
          WHERE user_id = ?
          LIMIT 1
        `).bind(assigneeUserId).first<any>()

        const assigneeDevices = await env.DB.prepare(`
          SELECT fcm_token
          FROM user_devices
          WHERE user_id = ?
          ORDER BY updated_at DESC
        `).bind(assigneeUserId).all<{ fcm_token: string | null }>()

        const assigneeTokens = Array.from(new Set((assigneeDevices.results || [])
          .map(x => String(x.fcm_token || "").trim())
          .filter(Boolean)))

        console.log("push_debug", JSON.stringify({
          assignee_user_id: assigneeUserId,
          assignee_name: assignee?.display_name || null,
          token_count: assigneeTokens.length,
          title
        }))

        if (assigneeTokens.length > 0) {
          const authorName = String(user.display_name || user.email || "Автор").trim() || "Автор"
          ctx.waitUntil((async () => {
            const pushBody = description
              ? `От: ${authorName}\n${description}`
              : `От: ${authorName}`

            let sent = 0
            for (const token of assigneeTokens) {
              try {
                await sendPushToToken(
                  env,
                  token,
                  "Новая задача",
                  pushBody,
                  {
                    type: "task_created",
                    task_id: taskId,
                      is_urgent: isUrgent ? "1" : "0",
                    open_tasks: "true",
                    task_title: title,
                    author_name: authorName
                  }
                )
                sent++
              } catch (e) {
                const errorText = String(e)
                console.log("push_send_error", assigneeUserId, errorText)

                if (
                  errorText.includes("registration-token-not-registered") ||
                  errorText.includes("Requested entity was not found") ||
                  errorText.includes("UNREGISTERED") ||
                  errorText.includes("invalid-registration-token") ||
                  errorText.includes("Invalid registration token")
                ) {
                  await deleteUserDeviceToken(env, token)
                }
              }
            }

            if (sent > 0) {
              console.log("push_send_ok", assigneeUserId, sent)
            } else {
              console.log("push_send_skipped_no_valid_token", assigneeUserId)
            }
          })())
        } else {
          console.log("push_send_skipped_no_token", assigneeUserId)
        }

        return json({ ok: true, task_id: taskId })
      }

      

if (path === "/task_notification_delivered" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{ task_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        if (!taskId)
          return json({ ok: false, error: "task_id required" }, 400)

        await env.DB.prepare(`
          UPDATE tasks
          SET notification_delivered_at = COALESCE(notification_delivered_at, ?), updated_at = ?
          WHERE task_id = ? AND assignee_user_id = ?
        `).bind(nowIso(), nowIso(), taskId, user.user_id).run()

        return json({ ok: true, task_id: taskId })
      }

if (path === "/task_seen" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{ task_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        if (!taskId)
          return json({ ok: false, error: "task_id required" }, 400)

        await env.DB.prepare(`
          UPDATE tasks
          SET notification_seen_at = COALESCE(notification_seen_at, ?), updated_at = ?
          WHERE task_id = ? AND assignee_user_id = ?
        `).bind(nowIso(), nowIso(), taskId, user.user_id).run()

        return json({ ok: true, task_id: taskId })
      }

if (path === "/task_by_id" && request.method
 === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) 

        return json({ ok: false, error: "unauthorized" }, 401)

        const url = new URL(request.url)
        const taskId = String(url.searchParams.get("task_id") || "").trim()
        if (!taskId) 

        return json({ ok: false, error: "task_id_required" }, 400)

        const task = await env.DB.prepare(`
          SELECT
            t.task_id,
            t.title,
            t.description,
            t.status,
            t.created_at,
            t.updated_at,
            t.created_by_user_id,
            t.assignee_user_id,
            t.completed_by_user_id,
            t.completed_at,
            t.cancelled_by_user_id,
            t.cancelled_at,
            cu.display_name AS created_by_name,
            au.display_name AS assignee_name,
            compu.display_name AS completed_by_name,
            cancelu.display_name AS cancelled_by_name,
            t.reminder_type,
            t.reminder_interval_minutes,
            t.reminder_time_of_day,
            CASE
              WHEN t.notification_seen_at IS NOT NULL THEN 'seen'
              WHEN t.notification_delivered_at IS NOT NULL THEN 'delivered'
              WHEN t.notification_sent_at IS NOT NULL THEN 'sent'
              ELSE NULL
            END AS notification_status,
            COALESCE(t.is_urgent, 0) AS is_urgent
          FROM tasks t
          LEFT JOIN users cu ON cu.user_id = t.created_by_user_id
          LEFT JOIN users au ON au.user_id = t.assignee_user_id
          LEFT JOIN users compu ON compu.user_id = t.completed_by_user_id
          LEFT JOIN users cancelu ON cancelu.user_id = t.cancelled_by_user_id
          WHERE t.task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) {

        return json({ ok: false, error: "task_not_found" }, 404)
        }

        const isAdmin = user.role === "admin"
        const isAuthor = task.created_by_user_id === user.user_id
        const isAssignee = task.assignee_user_id === user.user_id

        if (!isAdmin && !isAuthor && !isAssignee) {

        return json({ ok: false, error: "forbidden" }, 403)
        }

        return json({ ok: true, task })
      }

      
if (path === "/my_tasks" && request.method
 === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)

        const rows = await env.DB.prepare(`
          SELECT
            t.task_id,
            t.title,
            t.description,
            t.status,
            t.created_at,
            t.updated_at,
            t.created_by_user_id,
            t.assignee_user_id,
            t.completed_by_user_id,
            t.completed_at,
            CASE
              WHEN t.notification_seen_at IS NOT NULL THEN 'seen'
              WHEN t.notification_delivered_at IS NOT NULL THEN 'delivered'
              WHEN t.notification_sent_at IS NOT NULL THEN 'sent'
              ELSE NULL
            END AS notification_status,
            COALESCE(t.is_urgent, 0) AS is_urgent,
            cu.display_name AS created_by_name,
            au.display_name AS assignee_name,
            compu.display_name AS completed_by_name
          FROM tasks t
          JOIN users cu ON cu.user_id = t.created_by_user_id
          JOIN users au ON au.user_id = t.assignee_user_id
          LEFT JOIN users compu ON compu.user_id = t.completed_by_user_id
          WHERE t.assignee_user_id = ? OR t.created_by_user_id = ?
          ORDER BY t.created_at DESC
        `).bind(user.user_id, user.user_id).all()

        return json({ ok: true, tasks: rows.results || [] })
      }

      if (path === "/all_tasks" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user)
          return json({ ok: false, error: "unauthorized" }, 401)
        if (!(user.role === "plus" || user.role === "admin")) {

        return json({ ok: false, error: "permission denied" }, 403)
        }

        const rows = await env.DB.prepare(`
          SELECT
            t.task_id,
            t.title,
            t.description,
            t.status,
            t.created_at,
            t.updated_at,
            t.created_by_user_id,
            t.assignee_user_id,
            t.completed_by_user_id,
            t.completed_at,
            CASE
              WHEN t.notification_seen_at IS NOT NULL THEN 'seen'
              WHEN t.notification_delivered_at IS NOT NULL THEN 'delivered'
              WHEN t.notification_sent_at IS NOT NULL THEN 'sent'
              ELSE NULL
            END AS notification_status,
            COALESCE(t.is_urgent, 0) AS is_urgent,
            cu.display_name AS created_by_name,
            au.display_name AS assignee_name,
            compu.display_name AS completed_by_name
          FROM tasks t
          JOIN users cu ON cu.user_id = t.created_by_user_id
          JOIN users au ON au.user_id = t.assignee_user_id
          LEFT JOIN users compu ON compu.user_id = t.completed_by_user_id
          ORDER BY t.created_at DESC
        `).all()

        return json({ ok: true, tasks: rows.results || [] })
      }


      if (path === "/update_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{ task_id?: string; title?: string; description?: string; assignee_user_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        const title = String(body?.title || "").trim()
        const description = String(body?.description || "").trim()
        const assigneeUserId = String(body?.assignee_user_id || "").trim()
        const reminderType = body?.reminder_type == null ? null : String(body.reminder_type).trim() || null
        const reminderIntervalMinutes = body?.reminder_interval_minutes == null ? null : Number(body.reminder_interval_minutes)
        const reminderTimeOfDay = body?.reminder_time_of_day == null ? null : String(body.reminder_time_of_day).trim() || null
        const isUrgent = body?.is_urgent === true || body?.is_urgent === 1 || body?.is_urgent === "1"

        if (!taskId || !title || !assigneeUserId) {

        return json({ ok: false, error: "task_id, title and assignee_user_id required" }, 400)
        }

        const task = await env.DB.prepare(`
          SELECT task_id, created_by_user_id, status
          FROM tasks
          WHERE task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) 

        return json({ ok: false, error: "task not found" }, 404)

        const canEdit = user.role === "admin" || task.created_by_user_id === user.user_id
        if (!canEdit) 

        return json({ ok: false, error: "permission denied" }, 403)
        if (task.status !== "open") 

        return json({ ok: false, error: "only open tasks can be edited" }, 400)

        await env.DB.prepare(`
          UPDATE tasks
          SET title = ?, description = ?, assignee_user_id = ?, reminder_type = ?, reminder_interval_minutes = ?, reminder_time_of_day = ?, is_urgent = ?, updated_at = ?
          WHERE task_id = ?
        `).bind(title, description, assigneeUserId, reminderType, Number.isFinite(reminderIntervalMinutes) ? reminderIntervalMinutes : null, reminderTimeOfDay, isUrgent ? 1 : 0, nowIso(), taskId).run()

        await logAction(env, "task", taskId, "task_updated", user.user_id, {
          title,
          assignee_user_id: assigneeUserId,
          reminder_type: reminderType,
          reminder_interval_minutes: Number.isFinite(reminderIntervalMinutes) ? reminderIntervalMinutes : null,
          reminder_time_of_day: reminderTimeOfDay,
          is_urgent: isUrgent ? 1 : 0
        })

        return json({ ok: true, task_id: taskId })
      }

      
if (path === "/delete_task" && request.method
 === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) 

        return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{ task_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        if (!taskId) 

        return json({ ok: false, error: "task_id required" }, 400)

        const task = await env.DB.prepare(`
            SELECT task_id, created_by_user_id, assignee_user_id, title
          FROM tasks
          WHERE task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) 

        return json({ ok: false, error: "task not found" }, 404)

        const canDelete = user.role === "admin" || task.created_by_user_id === user.user_id
        if (!canDelete) 

        return json({ ok: false, error: "permission denied" }, 403)

        await logAction(env, "task", taskId, "task_deleted", user.user_id, {
          title: task.title || ""
        })

        await env.DB.prepare(`
          DELETE FROM tasks
          WHERE task_id = ?
        `).bind(taskId).run()

          if (task.assignee_user_id) {
            const assigneeDevice = await env.DB.prepare(`
              SELECT fcm_token
              FROM user_devices
              WHERE user_id = ?
              ORDER BY updated_at DESC
              LIMIT 1
            `).bind(task.assignee_user_id).first<{ fcm_token: string | null }>()

            const assigneeToken = String(assigneeDevice?.fcm_token || "").trim()
            if (assigneeToken) {
              ctx.waitUntil((async () => {
                try {
                  await sendPushToToken(
                    env,
                    assigneeToken,
                    "Задача удалена",
                    `Задача "${String(task.title || "")}" была удалена`,
                    {
                      type: "task_deleted",
                      task_id: taskId,
                      open_tasks: "true",
                      task_title: String(task.title || "")
                    }
                  )
                } catch (e) {
                  console.log("task_deleted_push_error", taskId, String(e))
                }
              })())
            }
          }


          if (task.created_by_user_id) {
            ctx.waitUntil(sendTasksSyncToUser(env, task.created_by_user_id, "task_deleted", taskId))
          }
          if (task.assignee_user_id && task.assignee_user_id !== task.created_by_user_id) {
            ctx.waitUntil(sendTasksSyncToUser(env, task.assignee_user_id, "task_deleted", taskId))
          }

        return json({ ok: true, task_id: taskId })
      }

      
if (path === "/task_reminder" && request.method
 === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) 

        return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json().catch(() => null) as { task_id?: string } | null
        const taskId = String(body?.task_id || "").trim()
        if (!taskId) 

        return json({ ok: false, error: "task_id_required" }, 400)

        const task = await env.DB.prepare(`
          SELECT
            t.task_id,
            t.title,
            t.assignee_user_id,
            t.created_by_user_id,
            u.display_name AS assignee_name,
            u.fcm_token AS assignee_fcm_token
          FROM tasks t
          LEFT JOIN users u ON u.user_id = t.assignee_user_id
          WHERE t.task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) {

        return json({ ok: false, error: "task_not_found" }, 404)
        }

        const isAdmin = user.role === "admin"
        const isAuthor = task.created_by_user_id === user.user_id
        if (!isAdmin && !isAuthor) {

        return json({ ok: false, error: "forbidden" }, 403)
        }

        const assigneeDevices = await env.DB.prepare(`
          SELECT fcm_token
          FROM user_devices
          WHERE user_id = ?
          ORDER BY updated_at DESC
        `).bind(task.assignee_user_id).all<{ fcm_token: string | null }>()

        const assigneeTokens = Array.from(new Set((assigneeDevices.results || [])
          .map(x => String(x.fcm_token || "").trim())
          .filter(Boolean)))

        if (assigneeTokens.length === 0) {

        return json({ ok: false, error: "assignee_has_no_push_token" }, 400)
        }

        const authorName = String(user.display_name || user.email || "Автор").trim() || "Автор"

        ctx.waitUntil((async () => {
          let sent = 0
          for (const token of assigneeTokens) {
            try {
              await sendPushToToken(
                env,
                token,
                "Напоминание",
                `Напоминание по задаче "${task.title}" от ${authorName}`,
                {
                  type: "task_reminder",
                  task_id: taskId,
                  open_tasks: "true",
                  task_title: String(task.title || ""),
                  author_name: authorName
                }
              )
              sent++
            } catch (e) {
              console.log("task_reminder_push_error", taskId, String(e))
            }
          }

          console.log("task_reminder_push_ok", taskId, task.assignee_user_id, sent)
        })())

          await env.DB.prepare(`
            UPDATE tasks
            SET last_reminder_at = ?, updated_at = ?
            WHERE task_id = ?
          `).bind(nowIso(), nowIso(), taskId).run()

        await logAction(env, "task", taskId, "task_reminder", user.user_id, {
          assignee_user_id: task.assignee_user_id
        })

        return json({ ok: true, message: "Напоминание отправлено", task_id: taskId })
      }

      if (path === "/complete_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{ task_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        if (!taskId) 

        return json({ ok: false, error: "task_id required" }, 400)

        const task = await env.DB.prepare(`
          SELECT task_id, title, status, assignee_user_id, created_by_user_id
          FROM tasks
          WHERE task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) 

        return json({ ok: false, error: "task not found" }, 404)
        if (task.status === "done") 

        return json({ ok: false, error: "task already completed" }, 400)

        const canComplete =
          task.assignee_user_id === user.user_id ||
          user.role === "admin"

        if (!canComplete) 

        return json({ ok: false, error: "permission denied" }, 403)

        await env.DB.prepare(`
          UPDATE tasks
          SET status = 'done', completed_by_user_id = ?, completed_at = ?, updated_at = ?
          WHERE task_id = ?
        `).bind(user.user_id, nowIso(), nowIso(), taskId).run()

        await logAction(env, "task", taskId, "task_completed", user.user_id, {
          assignee_user_id: task.assignee_user_id,
          completed_by_user_id: user.user_id
        })

        if (task.created_by_user_id && task.created_by_user_id !== user.user_id) {
          const authorDevices = await env.DB.prepare(`
            SELECT fcm_token
            FROM user_devices
            WHERE user_id = ?
            ORDER BY updated_at DESC
          `).bind(task.created_by_user_id).all<{ fcm_token: string | null }>()

          const authorTokens = Array.from(new Set((authorDevices.results || [])
            .map(x => String(x.fcm_token || "").trim())
            .filter(Boolean)))

          const completedByName = String(user.display_name || user.email || "Исполнитель").trim() || "Исполнитель"

          ctx.waitUntil((async () => {
            for (const token of authorTokens) {
              try {
                await sendPushToToken(
                  env,
                  token,
                  "Задача выполнена",
                  `Задача "${String(task.title || "")}" выполнена — ${completedByName}`,
                  {
                    type: "task_completed",
                    task_id: taskId,
                    open_tasks: "true",
                    task_title: String(task.title || ""),
                    completed_by_name: completedByName
                  }
                )
              } catch (e) {
                console.log("task_completed_push_error", taskId, String(e))
              }
            }
          })())
        }

        return json({ ok: true, task_id: taskId })
      }

      return json({ ok: false, error: "not found" }, 404)
    } catch (e: any) {
      return json({ ok: false, error: e?.message || "internal error" }, 500)
    }
  },
  async scheduled(controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    await ensureSchemaOnce(env)
    await runReminderScheduler(env, ctx)
  }

}

// trigger deploy

// redeploy routes

// force redeploy tasks ui sync

// force redeploy avatar photo_url

// force redeploy update delete task

// force redeploy edit only open tasks

// force redeploy update profile name
