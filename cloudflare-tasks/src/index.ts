export interface Env {
  DB: D1Database
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

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") return json({ ok: true })

    const url = new URL(request.url)
    const path = url.pathname

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
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)
        return json({ ok: true, user })
      }

      if (path === "/users_list" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const rows = await env.DB.prepare(`
          SELECT user_id, email, display_name, role, photo_url, created_at, last_login_at
          FROM users
          ORDER BY display_name ASC
        `).all()

        return json({ ok: true, users: rows.results || [] })
      }

      if (path === "/create_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

        const body = await request.json<{ title?: string; description?: string; assignee_user_id?: string }>().catch(() => null)
        const title = String(body?.title || "").trim()
        const description = String(body?.description || "").trim()
        const assigneeUserId = String(body?.assignee_user_id || "").trim()

        if (!title || !assigneeUserId) return json({ ok: false, error: "title and assignee_user_id required" }, 400)

        const taskId = randomId("t")

        await env.DB.prepare(`
          INSERT INTO tasks (
            task_id, title, description, status, created_by_user_id, assignee_user_id, created_at, updated_at
          ) VALUES (?, ?, ?, 'open', ?, ?, ?, ?)
        `).bind(taskId, title, description, user.user_id, assigneeUserId, nowIso(), nowIso()).run()

        await logAction(env, "task", taskId, "task_created", user.user_id, {
          title,
          assignee_user_id: assigneeUserId
        })

        return json({ ok: true, task_id: taskId })
      }

      if (path === "/my_tasks" && request.method === "GET") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

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
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)
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

      if (path === "/complete_task" && request.method === "POST") {
        const user = await getCurrentUser(request, env)
        if (!user) return json({ ok: false, error: "unauthorized" }, 401)

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
