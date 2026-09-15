-- V28: index what the session-ownership lookup reads
--
-- The router asks "which tenant owns this session" before it proxies any session-scoped call, and it
-- caches the answer for five minutes — so every cache miss lands on this endpoint. For a `chn-` id the
-- answer now comes from `channel.session_id`, because a channel session never lives in `session`: it is
-- minted at channel creation and stamped on the channel row. Until then this endpoint read only
-- `session` and so answered "no such session" for every channel session — and "no such session" is a
-- pass to the router's guard, which is how any logged-in user of any tenant could name another tenant's
-- `chn-{uuid}` and read its conversation, plans and sandbox files.
--
-- `ChannelMapper.selectBySessionId` is `WHERE session_id = ? AND active = 1 LIMIT 1`, and V1 keyed this
-- table on `uk_callback_key`, `idx_agent_id` and `(type, enabled, status, active)` — none of them leads
-- with session_id, so the lookup scanned the whole table. That is not only cost: `channel` is a
-- configuration table every inbound channel message reads, so a growing full scan would sit behind each
-- cache miss on the router's hot path. session_id is a UUID stamped once at creation, so the bare
-- equality index is enough; `active` earns no place in the key.
--
-- MySQL has no `ADD INDEX IF NOT EXISTS`, and Flyway's version history only covers the databases it
-- drives itself: an index an operator added by hand, or one a restored dump carried, would otherwise
-- fail this migration and stop the rollout. So the statement is prepared behind a dictionary check,
-- which also makes re-running the file harmless.

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel' AND INDEX_NAME = 'idx_session_id'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE `channel` ADD INDEX `idx_session_id` (`session_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
