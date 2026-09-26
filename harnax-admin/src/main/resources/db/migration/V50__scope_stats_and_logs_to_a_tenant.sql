-- V50: token_stats / tool_call_log / process_log — carry the tenant a run belonged to
--
-- These three tables have had a `session_id` and no tenant since V1, so every read of them has been
-- workspace-wide. `TokenStatsMapper`'s 20 aggregation statements filter by time only, which means one
-- tenant's console sees another tenant's token spend, fee total, model mix and per-session activity —
-- the same leak the dimension tables have been closed one by one on (V44 model and provider, V47
-- channel, V43 agent name scoping). Consumption is the last unscoped surface, not the first.
--
-- `tool_call_log` and `process_log` get the column in the same round even though both are write-only
-- today (`ToolCallLogMapper` reads nothing back, `ProcessLogMapper`'s non-insert statements are unused)
-- and so leak nothing yet. The reason is timing, not symmetry: a tenant is only knowable at insert
-- time, from the `AgentSpec` the runtime was handed, and no later round could recover it for a row
-- written in between. Adding it now costs one more column on the write path that already carries
-- `agentId`; adding it later means back-filling against a `session` table that keeps being edited.
--
-- NULL is a real state here, not a placeholder for tenant 1: a run whose tenant was never resolved
-- writes NULL, and every tenant-scoped read then skips the row. That is the intended outcome — an
-- unattributed row is invisible to nobody's totals rather than silently charged to whichever tenant
-- happens to own the default. `getOverallStats` counts no such row at all.

ALTER TABLE token_stats
    ADD COLUMN tenant_id BIGINT DEFAULT NULL COMMENT 'Tenant ID',
    ADD KEY idx_tenant_ts (tenant_id, ts);

ALTER TABLE tool_call_log
    ADD COLUMN tenant_id BIGINT DEFAULT NULL COMMENT 'Tenant ID',
    ADD KEY idx_tenant_ts (tenant_id, ts);

ALTER TABLE process_log
    ADD COLUMN tenant_id BIGINT DEFAULT NULL COMMENT 'Tenant ID',
    ADD KEY idx_tenant_ts (tenant_id, ts);

-- (tenant_id, ts) rather than two single-column keys: all 20 statements are
-- `WHERE tenant_id = ? AND ts >= ? AND ts <= ?`, so tenant_id has to be the leading column for the
-- equality to narrow the range scan. idx_ts stays — it is the only key left for a scan with no tenant,
-- which is what an operator's ad-hoc query over the whole workspace still is.

-- ============================================================
-- Backfill: the session's tenant, pinned to one row per session_id
-- ============================================================

-- The runtime writes no tenant of its own today, so the only evidence a historical row leaves is its
-- `session_id`, and `session.tenant_id` is NOT NULL — a matched session always answers with a value.
--
-- The join cannot go straight to `session`. `session.session_id` is only `NOT NULL`: V1 gave the table
-- PRIMARY KEY (id), idx_creator and idx_tenant_id, and no migration has added a key since, so one
-- session_id can name several rows and a plain `JOIN session ON session_id = ?` would let MySQL pick
-- any of them for a stats row. That is exactly the V44 mistake V49 had to come back and document
-- (`UPDATE model ... JOIN sys_user u ON u.username = p.creator`, unfiltered, "among several same-named
-- rows MySQL picked one arbitrarily"). Pinning to `MIN(id)` here makes the choice deterministic and
-- names the first session ever created under that id, which is the one that ran.
--
-- Retired sessions are deliberately kept in the join: admin deletes logically (`active = 0`), the row
-- still says which tenant it belonged to, and a session being retired afterwards does not change who
-- consumed the tokens.
--
-- A stats row whose session_id matches nothing — cleared before it was recorded, or written by a
-- runtime with no session row behind it — keeps NULL and disappears from the tenant-scoped reads, per
-- the note above. Nothing is guessed for it, and `update_time` is not a column on these tables, so the
-- backfill touches no audit trail beyond the tenant itself.

UPDATE token_stats t
    JOIN (
        SELECT s.session_id, s.tenant_id
        FROM session s
        JOIN (SELECT session_id, MIN(id) AS first_id FROM session GROUP BY session_id) f
          ON f.first_id = s.id
    ) x ON x.session_id = t.session_id
SET t.tenant_id = x.tenant_id
WHERE t.tenant_id IS NULL;

UPDATE tool_call_log t
    JOIN (
        SELECT s.session_id, s.tenant_id
        FROM session s
        JOIN (SELECT session_id, MIN(id) AS first_id FROM session GROUP BY session_id) f
          ON f.first_id = s.id
    ) x ON x.session_id = t.session_id
SET t.tenant_id = x.tenant_id
WHERE t.tenant_id IS NULL;

UPDATE process_log t
    JOIN (
        SELECT s.session_id, s.tenant_id
        FROM session s
        JOIN (SELECT session_id, MIN(id) AS first_id FROM session GROUP BY session_id) f
          ON f.first_id = s.id
    ) x ON x.session_id = t.session_id
SET t.tenant_id = x.tenant_id
WHERE t.tenant_id IS NULL;
