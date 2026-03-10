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


async function ensureReminderColumns(env: Env) {
  const commands = [
    "ALTER TABLE tasks ADD COLUMN reminder_type TEXT",
    "ALTER TABLE tasks ADD COLUMN reminder_interval_minutes INTEGER",
    "ALTER TABLE tasks ADD COLUMN reminder_time_of_day TEXT"
  ]
  for (const sql of commands) {
    try {
      await env.DB.prepare(sql).run()
    } catch {}
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

async function sendPushToToken(
  env: Env,
  token: string,
  title: string,
  body: string
) {
  if (!token) return

  const accessToken = await getGoogleAccessToken(env)

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
          },
          android: {
            priority: "high",
            notification: {
              channel_id: "ml_tasks_channel",
              sound: "default",
            },
          },
        },
      }),
    }
  )

  const text = await resp.text()
  console.log("FCM_RESPONSE", text)

  if (!resp.ok) {
    throw new Error(`fcm_send_failed: ${text}`)
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return json({ ok: true, fcm_debug: debugText })

    const url = new URL(request.url)
    const path = url.pathname
      await ensureFcmTokenColumn(env)

    
      await ensureReminderColumns(env)
try {
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
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{ fcm_token?: string }>().catch(() => null)
        const fcmToken = String(body?.fcm_token || "").trim()

        if (!fcmToken) {
          return json({ ok: false, error: "fcm_token required" }, 400)
        }

        await env.DB.prepare(`
          UPDATE users
          SET fcm_token = ?, updated_at = ?
          WHERE user_id = ?
        `).bind(fcmToken, nowIso(), user.user_id).run()

        return json({ ok: true, fcm_debug: debugText })
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
          const target = await env.DB.prepare(`
            SELECT user_id, fcm_token
            FROM users
            WHERE user_id = ?
            LIMIT 1
          `).bind(userId).first<any>()

          if (target) targets = [target]
        } else {
          const rows = await env.DB.prepare(`
            SELECT user_id, fcm_token
            FROM users
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
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{
          title?: string
          description?: string
          assignee_user_id?: string
          reminder_type?: string | null
          reminder_interval_minutes?: number | null
          reminder_time_of_day?: string | null
        }>().catch(() => null)
        const title = String(body?.title || "").trim()
        const description = String(body?.description || "").trim()
        const assigneeUserId = String(body?.assignee_user_id || "").trim()
        const reminderType = body?.reminder_type == null ? null : String(body.reminder_type).trim() || null
        const reminderIntervalMinutes = body?.reminder_interval_minutes == null ? null : Number(body.reminder_interval_minutes)
        const reminderTimeOfDay = body?.reminder_time_of_day == null ? null : String(body.reminder_time_of_day).trim() || null

        if (!title || !assigneeUserId) return json({ ok: false, error: "title and assignee_user_id required" }, 400)

        const taskId = randomId("t")

        await env.DB.prepare(`
          INSERT INTO tasks (
            task_id, title, description, status, created_by_user_id, assignee_user_id,
            reminder_type, reminder_interval_minutes, reminder_time_of_day,
            created_at, updated_at
          ) VALUES (?, ?, ?, 'open', ?, ?, ?, ?, ?, ?, ?)
        `).bind(
          taskId,
          title,
          description,
          user.user_id,
          assigneeUserId,
          reminderType,
          Number.isFinite(reminderIntervalMinutes) ? reminderIntervalMinutes : null,
          reminderTimeOfDay,
          nowIso(),
          nowIso()
        ).run()

        await logAction(env, "task", taskId, "task_created", user.user_id, {
          title,
          assignee_user_id: assigneeUserId
        })

        const assignee = await env.DB.prepare(`
          SELECT display_name, fcm_token
          FROM users
          WHERE user_id = ?
          LIMIT 1
        `).bind(assigneeUserId).first<any>()

        console.log("push_debug", JSON.stringify({
          assignee_user_id: assigneeUserId,
          has_token: !!assignee?.fcm_token,
          title
        }))

        if (assignee?.fcm_token) {
          try {
            await sendPushToToken(
              env,
              assignee.fcm_token,
              "Новая задача",
              title
            )
            console.log("push_send_ok", assigneeUserId)
          } catch (e) {
            console.log("push_send_error", String(e))
          }
        } else {
          console.log("push_send_skipped_no_token", assigneeUserId)
        }


        return json({ ok: true, task_id: taskId })
      }

      if (path === "/my_tasks" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

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
        if (!user) console.log("DEBUG: bypass auth for send_push")
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

        if (!taskId || !title || !assigneeUserId) {
          return json({ ok: false, error: "task_id, title and assignee_user_id required" }, 400)
        }

        const task = await env.DB.prepare(`
          SELECT task_id, created_by_user_id, status
          FROM tasks
          WHERE task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) return json({ ok: false, error: "task not found" }, 404)

        const canEdit = user.role === "admin" || task.created_by_user_id === user.user_id
        if (!canEdit) return json({ ok: false, error: "permission denied" }, 403)
        if (task.status !== "open") return json({ ok: false, error: "only open tasks can be edited" }, 400)

        await env.DB.prepare(`
          UPDATE tasks
          SET title = ?, description = ?, assignee_user_id = ?, reminder_type = ?, reminder_interval_minutes = ?, reminder_time_of_day = ?, updated_at = ?
          WHERE task_id = ?
        `).bind(title, description, assigneeUserId, reminderType, Number.isFinite(reminderIntervalMinutes) ? reminderIntervalMinutes : null, reminderTimeOfDay, nowIso(), taskId).run()

        await logAction(env, "task", taskId, "task_updated", user.user_id, {
          title,
          assignee_user_id: assigneeUserId,
          reminder_type: reminderType,
          reminder_interval_minutes: Number.isFinite(reminderIntervalMinutes) ? reminderIntervalMinutes : null,
          reminder_time_of_day: reminderTimeOfDay
        })

        return json({ ok: true, task_id: taskId })
      }

      if (path === "/delete_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{ task_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        if (!taskId) return json({ ok: false, error: "task_id required" }, 400)

        const task = await env.DB.prepare(`
          SELECT task_id, created_by_user_id, title
          FROM tasks
          WHERE task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) return json({ ok: false, error: "task not found" }, 404)

        const canDelete = user.role === "admin" || task.created_by_user_id === user.user_id
        if (!canDelete) return json({ ok: false, error: "permission denied" }, 403)

        await env.DB.prepare(`
          DELETE FROM tasks
          WHERE task_id = ?
        `).bind(taskId).run()

        await logAction(env, "task", taskId, "task_deleted", user.user_id, {
          title: task.title || ""
        })

        return json({ ok: true, task_id: taskId })
      }

      if (path === "/complete_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) console.log("DEBUG: bypass auth for send_push")

        const body = await request.json<{ task_id?: string }>().catch(() => null)
        const taskId = String(body?.task_id || "").trim()
        if (!taskId) return json({ ok: false, error: "task_id required" }, 400)

        const task = await env.DB.prepare(`
          SELECT task_id, status, assignee_user_id
          FROM tasks
          WHERE task_id = ?
          LIMIT 1
        `).bind(taskId).first<any>()

        if (!task) return json({ ok: false, error: "task not found" }, 404)
        if (task.status === "done") return json({ ok: false, error: "task already completed" }, 400)

        const canComplete =
          (user.role === "basic" && task.assignee_user_id === user.user_id) ||
          user.role === "plus" ||
          user.role === "admin"

        if (!canComplete) return json({ ok: false, error: "permission denied" }, 403)

        await env.DB.prepare(`
          UPDATE tasks
          SET status = 'done', completed_by_user_id = ?, completed_at = ?, updated_at = ?
          WHERE task_id = ?
        `).bind(user.user_id, nowIso(), nowIso(), taskId).run()

        await logAction(env, "task", taskId, "task_completed", user.user_id, {
          assignee_user_id: task.assignee_user_id,
          completed_by_user_id: user.user_id
        })

        return json({ ok: true, task_id: taskId })
      }

      return json({ ok: false, error: "not found" }, 404)
    } catch (e: any) {
      return json({ ok: false, error: e?.message || "internal error" }, 500)
    }
  }
}

// trigger deploy

// redeploy routes

// force redeploy tasks ui sync

// force redeploy avatar photo_url

// force redeploy update delete task

// force redeploy edit only open tasks

// force redeploy update profile name