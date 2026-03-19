CREATE TABLE IF NOT EXISTS users (
  user_id TEXT PRIMARY KEY,
  google_sub TEXT UNIQUE,
  email TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  photo_url TEXT,
  role TEXT NOT NULL DEFAULT 'basic',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  last_login_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_sessions (
  session_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  auth_token TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  last_used_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_token ON user_sessions(auth_token);
CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id);

CREATE TABLE IF NOT EXISTS tasks (
  task_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT,
  status TEXT NOT NULL DEFAULT 'open',
  created_by_user_id TEXT NOT NULL,
  assignee_user_id TEXT NOT NULL,
  completed_by_user_id TEXT,
  completed_at TEXT,
  cancelled_by_user_id TEXT,
  cancelled_at TEXT,
  reminder_type TEXT,
  reminder_interval_minutes INTEGER,
  reminder_time_of_day TEXT,
  notification_sent_at TEXT,
  notification_delivered_at TEXT,
  notification_seen_at TEXT,
  is_urgent INTEGER NOT NULL DEFAULT 0,
  client_request_id TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(assignee_user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_creator ON tasks(created_by_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_client_request_id
  ON tasks(created_by_user_id, client_request_id)
  WHERE client_request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS action_log (
  log_id INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  action_type TEXT NOT NULL,
  actor_user_id TEXT NOT NULL,
  details_json TEXT,
  created_at TEXT NOT NULL
);

