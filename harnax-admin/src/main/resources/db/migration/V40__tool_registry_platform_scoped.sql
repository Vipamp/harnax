-- V40: tools become platform-scoped, name-identified, and additive
--
-- The table is now driven by one rule: a `@Tool` method is the only fact source, and the startup sync
-- only ever adds or updates. Nothing is deleted, so the identity it looks a row up by has to be the
-- one thing a declaration owns — its name. `bean_name` / `method_name` stay (the runtime instantiates
-- through them) but they are attributes, not identity: moving a method into another ToolBox used to
-- change the old key and drop the row, taking the agent bindings with it.
--
-- Three changes:
-- 1. `tenant_id` and its index go. Every row was written under the DDL default tenant and offered to
--    all of them, so the column only suggested an isolation that never existed.
-- 2. `timeout_seconds` goes with `@ToolMeta(timeoutSeconds)`. The value was synced, delivered and
--    copied onto the entity, then read by nobody; the only timeout that ever applied is the whole-turn
--    one, which lives at assembly time (HarnessConfig.turnTimeoutSeconds).
-- 3. `uk_tenant_bean_method` is replaced by `uk_agent_tool_name`. Two methods cannot both be the same
--    tool, so the registrar now refuses such a classpath at startup — otherwise an arbitrary name
--    winner would decide which method the tool runs.
--
-- Order matters: the unique key cannot be added while duplicates exist, and duplicates can only come
-- from the collision the registrar now rejects. They are renamed with the `#dup-<id>` suffix V23 uses
-- (keeping the oldest row of each name), so no row and no binding is lost — the renamed row simply
-- stops matching a declaration and is therefore no longer delivered.
ALTER TABLE agent_tool
    DROP INDEX uk_tenant_bean_method,
    DROP INDEX idx_tenant_id,
    DROP COLUMN tenant_id,
    DROP COLUMN timeout_seconds;

-- Which row keeps the name matters: the sync resolves by name and revives what it finds, so keeping a
-- row left at active = 0 would revive the corpse and rename the live row — the one an agent is bound
-- to — into an orphan the delivery filter then holds back. An active row therefore wins, and only when
-- every row of a name is inactive does the oldest one keep it (the same "keep the oldest" V23 uses).
UPDATE agent_tool t
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY name ORDER BY (active = 1) DESC, id) AS rn
          FROM agent_tool) d ON t.id = d.id
SET t.name        = LEFT(CONCAT(SUBSTRING(t.name, 1, 80), '#dup-', t.id), 100),
    t.update_time = NOW()
WHERE d.rn > 1;

ALTER TABLE agent_tool ADD UNIQUE KEY uk_agent_tool_name (name);