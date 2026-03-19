CREATE TABLE IF NOT EXISTS orders (
    external_order_id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    title TEXT,
    buyer_name TEXT,
    status TEXT,
    substatus TEXT,
    amount REAL,
    currency TEXT DEFAULT 'BRL',
    created_at TEXT,
    updated_at TEXT,
    last_seen_at TEXT NOT NULL,
    raw_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_orders_last_seen_at
ON orders(last_seen_at);

CREATE INDEX IF NOT EXISTS idx_orders_status
ON orders(status);

CREATE TABLE IF NOT EXISTS integration_state (
    source TEXT PRIMARY KEY,
    auth_state TEXT NOT NULL,
    last_success_at TEXT,
    last_run_at TEXT,
    last_error TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS summary_reports (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    report_type TEXT NOT NULL,
    period_start TEXT NOT NULL,
    period_end TEXT NOT NULL,
    summary_text TEXT NOT NULL,
    payload_json TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_summary_reports_created_at
ON summary_reports(created_at DESC);

INSERT OR IGNORE INTO integration_state (
    source,
    auth_state,
    updated_at
) VALUES (
    'mercadolivre',
    'active',
    CURRENT_TIMESTAMP
);
