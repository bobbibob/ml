export interface Env {
  DB: D1Database
  FIREBASE_PROJECT_ID: string
  FIREBASE_CLIENT_EMAIL: string
  FIREBASE_PRIVATE_KEY: string
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

async function ensureReminderColumns(env: Env) {
  const commands = [
    "ALTER TABLE tasks ADD COLUMN reminder_type TEXT",
    "ALTER TABLE tasks ADD COLUMN reminder_interval_minutes INTEGER",
    "ALTER TABLE tasks ADD COLUMN reminder_time_of_day TEXT",
    "ALTER TABLE tasks ADD COLUMN last_reminder_sent_at TEXT",
    "ALTER TABLE tasks ADD COLUMN next_reminder_at TEXT"
  ]
  for (const sql of commands) {
    try {
      if (path === "/health_db" && request.method === "GET") {
        const rows = await env.DB.prepare(`
          SELECT task_id, title, status, created_at
          FROM tasks
          ORDER BY created_at DESC
          LIMIT 5
        `).all<any>()

        return json({
          ok: true,
          worker: "ml-reminders-debug",
          now: new Date().toISOString(),
          tasks: rows.results || []
        })
      }

      await env.DB.prepare(sql).run()
    } catch {}
  }
}

let schemaReadyPromise: Promise<void> | null = null

async function ensureSchemaOnce(env: Env) {
  if (!schemaReadyPromise) {
    schemaReadyPromise = (async () => {
      await ensureReminderColumns(env)
    })().catch((e) => {
      schemaReadyPromise = null
      throw e
    })
  }
  return schemaReadyPromise
}

function nowIso(): string {
  return new Date().toISOString()
}

function toDailyReminderIso(timeOfDay: string, baseIso: string): string {
  const base = new Date(baseIso)
  const [hhRaw, mmRaw] = String(timeOfDay || "").split(":")
  const hh = Number(hhRaw)
  const mm = Number(mmRaw)

  if (!Number.isFinite(hh) || !Number.isFinite(mm)) {
    return base.toISOString()
  }

  const dt = new Date(base)
  dt.setUTCHours(hh, mm, 0, 0)

  if (dt.getTime() <= base.getTime()) {
    dt.setUTCDate(dt.getUTCDate() + 1)
  }

  return dt.toISOString()
}

function computeNextReminderAt(
  reminderType: string | null,
  reminderIntervalMinutes: number | null,
  reminderTimeOfDay: string | null,
  baseIso: string
): string | null {
  if (!reminderType) return null

  const base = new Date(baseIso)

  if (
    reminderType === "interval" &&
    Number.isFinite(reminderIntervalMinutes || NaN) &&
    (reminderIntervalMinutes || 0) > 0
  ) {
    return new Date(base.getTime() + (reminderIntervalMinutes as number) * 60 * 1000).toISOString()
  }

  if (reminderType === "daily_time" && reminderTimeOfDay) {
    return toDailyReminderIso(reminderTimeOfDay, baseIso)
  }

  return null
}

async function getGoogleAccessToken(env: Env): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" }
  const now = Math.floor(Date.now() / 1000)
  const claim = {
    iss: env.FIREBASE_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  }

  const enc = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "")

  const unsigned = `${enc(header)}.${enc(claim)}`

  const pem = env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, "\n")
  const pemBody = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "")
  const binary = Uint8Array.from(atob(pemBody), c => c.charCodeAt(0))

  const key = await crypto.subtle.importKey(
    "pkcs8",
    binary.buffer,
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
    new TextEncoder().encode(unsigned)
  )

  const jwt = `${unsigned}.${btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "")}`

  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body:
      "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" +
      encodeURIComponent(jwt),
  })

  if (!resp.ok) {
    throw new Error("google_access_token_failed")
  }

  const json = await resp.json<any>()
  return json.access_token
}

async function sendPushToToken(
  env: Env,
  token: string,
  title: string,
  body: string,
  data: Record<string, string> = {}
) {
  const accessToken = await getGoogleAccessToken(env)
  const resp = await fetch(
    `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "authorization": `Bearer ${accessToken}`,
      },
      body: JSON.stringify({
        message: {
          token,
          notification: { title, body },
          data,
          android: { priority: "high" }
        }
      }),
    }
  )

  const text = await resp.text()
  if (!resp.ok) {
    throw new Error(`fcm_send_failed: ${text}`)
  }
}

const DEBUG_REMINDER_KEY = "123456"

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return json({ ok: true })
    }

    await ensureSchemaOnce(env)

    const url = new URL(request.url)
    const path = url.pathname

    try {
      if (path === "/health" && request.method === "GET") {
        return json({ ok: true, worker: "ml-reminders-debug", now: nowIso() })
      }

      if (path === "/debug_find_task" && request.method === "GET") {
        const key = url.searchParams.get("key")
        const title = (url.searchParams.get("title") || "").trim()
        if (key !== DEBUG_REMINDER_KEY) {
          return json({ ok: false, error: "forbidden" }, 403)
        }
        if (!title) {
          return json({ ok: false, error: "title required" }, 400)
        }

        const rows = await env.DB.prepare(`
          SELECT
            task_id,
            title,
            status,
            assignee_user_id,
            created_by_user_id,
            reminder_type,
            reminder_interval_minutes,
            reminder_time_of_day,
            last_reminder_sent_at,
            next_reminder_at,
            created_at,
            updated_at
          FROM tasks
          WHERE title = ?
          ORDER BY created_at DESC
          LIMIT 10
        `).bind(title).all<any>()

        return json({ ok: true, tasks: rows.results || [] })
      }

      if (path === "/debug_list_reminders" && request.method === "GET") {
        const key = url.searchParams.get("key")
        if (key !== DEBUG_REMINDER_KEY) {
          return json({ ok: false, error: "forbidden" }, 403)
        }

        const rows = await env.DB.prepare(`
          SELECT
            task_id,
            title,
            status,
            assignee_user_id,
            created_by_user_id,
            reminder_type,
            reminder_interval_minutes,
            reminder_time_of_day,
            last_reminder_sent_at,
            next_reminder_at,
            created_at,
            updated_at
          FROM tasks
          ORDER BY created_at DESC
          LIMIT 20
        `).all<any>()

        return json({ ok: true, tasks: rows.results || [] })
      }

      if (path === "/debug_backfill_reminders" && request.method === "POST") {
        const key = url.searchParams.get("key")
        if (key !== DEBUG_REMINDER_KEY) {
          return json({ ok: false, error: "forbidden" }, 403)
        }

        const rows = await env.DB.prepare(`
          SELECT
            task_id,
            reminder_type,
            reminder_interval_minutes,
            reminder_time_of_day,
            created_at
          FROM tasks
          WHERE status = 'open'
            AND reminder_type IS NOT NULL
            AND next_reminder_at IS NULL
          ORDER BY created_at DESC
          LIMIT 100
        `).all<any>()

        const tasks = rows.results || []
        let updated = 0

        for (const task of tasks) {
          const nextReminderAt = computeNextReminderAt(
            task.reminder_type == null ? null : String(task.reminder_type),
            task.reminder_interval_minutes == null ? null : Number(task.reminder_interval_minutes),
            task.reminder_time_of_day == null ? null : String(task.reminder_time_of_day),
            String(task.created_at || nowIso())
          )

          await env.DB.prepare(`
            UPDATE tasks
            SET next_reminder_at = ?, updated_at = ?
            WHERE task_id = ?
          `).bind(nextReminderAt, nowIso(), task.task_id).run()

          updated++
        }

        return json({
          ok: true,
          updated
        })
      }

      if (path === "/debug_run_reminders" && request.method === "POST") {
        const key = url.searchParams.get("key")
        if (key !== DEBUG_REMINDER_KEY) {
          return json({ ok: false, error: "forbidden" }, 403)
        }

        const now = nowIso()

        const due = await env.DB.prepare(`
          SELECT
            t.task_id,
            t.title,
            t.description,
            t.assignee_user_id,
            t.created_by_user_id,
            t.reminder_type,
            t.reminder_interval_minutes,
            t.reminder_time_of_day,
            t.next_reminder_at
          FROM tasks t
          WHERE t.status = 'open'
            AND t.reminder_type IS NOT NULL
            AND t.next_reminder_at IS NOT NULL
          ORDER BY t.next_reminder_at ASC
          LIMIT 50
        `).all<any>()

        const tasks = due.results || []
        let processed = 0
        let sentTotal = 0

        for (const task of tasks) {
          const tokensRes = await env.DB.prepare(`
            SELECT fcm_token
            FROM user_devices
            WHERE user_id = ?
              AND fcm_token IS NOT NULL
              AND TRIM(fcm_token) <> ''
            ORDER BY updated_at DESC
          `).bind(task.assignee_user_id).all<{ fcm_token: string | null }>()

          const tokens = Array.from(new Set((tokensRes.results || [])
            .map(x => String(x.fcm_token || "").trim())
            .filter(Boolean)))

          let sent = 0
          const body =
            String(task.description || "").trim() ||
            String(task.title || "").trim() ||
            "Напоминание по задаче"

          for (const token of tokens) {
            try {
              await sendPushToToken(
                env,
                token,
                "Напоминание по задаче",
                body,
                {
                  type: "task_reminder_auto",
                  task_id: String(task.task_id),
                  open_tasks: "true"
                }
              )
              sent++
              sentTotal++
            } catch (e) {
              console.log("debug_task_reminder_push_error", String(task.task_id), String(e))
            }
          }

          const nextReminderAt = computeNextReminderAt(
            task.reminder_type == null ? null : String(task.reminder_type),
            task.reminder_interval_minutes == null ? null : Number(task.reminder_interval_minutes),
            task.reminder_time_of_day == null ? null : String(task.reminder_time_of_day),
            now
          )

          await env.DB.prepare(`
            UPDATE tasks
            SET last_reminder_sent_at = ?,
                next_reminder_at = ?,
                updated_at = ?
            WHERE task_id = ?
          `).bind(now, nextReminderAt, now, task.task_id).run()

          console.log("task_reminder_debug_run", JSON.stringify({
            task_id: task.task_id,
            sent,
            previous_next_reminder_at: task.next_reminder_at,
            next_reminder_at: nextReminderAt
          }))

          processed++
        }

        return json({
          ok: true,
          processed,
          sent: sentTotal,
          now
        })
      }

      return json({ ok: false, error: "not found" }, 404)
    } catch (e: any) {
      return json({ ok: false, error: e?.message || "internal error" }, 500)
    }
  }
}
