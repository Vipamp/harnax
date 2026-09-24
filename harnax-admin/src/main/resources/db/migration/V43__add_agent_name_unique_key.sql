-- V43: agent name uniqueness enforced per tenant
--
-- Nothing backed the rule: V1 left `agent` with PRIMARY KEY and idx_tenant_id only, and the service
-- never looked the name up before insert or rename. So two creates inside one tenant could both
-- land the same name, and the list page's `name LIKE` then returned several rows the caller could
-- not tell apart. Same defect V23 closed on the MCP side, following V15 on the skill side.
--
-- The key is (tenant_id, active_name): the list query filters by tenant, so a name only identifies
-- an agent inside one tenant. Duplicate live names inside a tenant are renamed first
-- (suffix `#dup-<id>`), keeping the oldest row of each tenant, so no row is deleted here.

UPDATE agent s
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY tenant_id, name ORDER BY id) AS rn
          FROM agent
          WHERE active = 1) d ON s.id = d.id
SET s.name        = LEFT(CONCAT(SUBSTRING(s.name, 1, 80), '#dup-', s.id), 100),
    s.update_time = NOW()
WHERE d.rn > 1;

-- Soft-deleted rows keep their name: the generated column turns NULL and a MySQL unique index
-- ignores NULLs, so deleting an agent and re-creating it under the same name still works.
-- idx_tenant_id becomes redundant: tenant_id is the leftmost prefix of the new key.
ALTER TABLE agent
    DROP INDEX idx_tenant_id,
    ADD COLUMN active_name VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_agent_tenant_active_name (tenant_id, active_name);
