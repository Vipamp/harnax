-- SQLite 初始化脚本
-- 用于本地模式，替代 MySQL 的 Flyway 迁移

CREATE TABLE IF NOT EXISTS api_call_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    caller_id       TEXT NOT NULL,
    caller_type     TEXT NOT NULL,
    tenant_id       INTEGER,
    session_id      TEXT,
    agent_id        INTEGER,
    agent_name      TEXT,
    model_id        INTEGER,
    model_name      TEXT,
    endpoint        TEXT NOT NULL,
    method          TEXT NOT NULL,
    request_type    TEXT,
    status_code     INTEGER NOT NULL,
    success         INTEGER NOT NULL,
    error_message   TEXT,
    start_time      TEXT NOT NULL,
    end_time        TEXT NOT NULL,
    duration_ms     INTEGER NOT NULL,
    instance_id     TEXT,
    request_id      TEXT,
    create_time     TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_caller_id ON api_call_log(caller_id);
CREATE INDEX IF NOT EXISTS idx_session_id ON api_call_log(session_id);
CREATE INDEX IF NOT EXISTS idx_start_time ON api_call_log(start_time);
CREATE INDEX IF NOT EXISTS idx_tenant_id ON api_call_log(tenant_id);
CREATE INDEX IF NOT EXISTS idx_instance_id ON api_call_log(instance_id);
