-- V23: mcp_server name uniqueness enforced per tenant
--
-- Uniqueness was only ever an application-level lookup (`selectByName` before insert / rename), so
-- two concurrent creates could both pass it — the same defect V15 closed on the skill side. The real
-- database had no unique key here at all (V1 left only PRIMARY KEY and idx_tenant_id), while the
-- hand-maintained mapper test schema carried `uk_name (name)`: the tests were asserting a constraint
-- production never had.
--
-- The key is (tenant_id, active_name), not (name): the list query has filtered by tenant since V22,
-- so a name only identifies a server inside one tenant, and `selectByName` gained its tenantId
-- parameter to match. Duplicate live names inside a tenant are renamed first (suffix `#dup-<id>`),
-- keeping the oldest row of each tenant, so no row is deleted by this migration.

UPDATE mcp_server s
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY tenant_id, name ORDER BY id) AS rn
          FROM mcp_server
          WHERE active = 1) d ON s.id = d.id
SET s.name        = LEFT(CONCAT(SUBSTRING(s.name, 1, 80), '#dup-', s.id), 100),
    s.update_time = NOW()
WHERE d.rn > 1;

-- Soft-deleted rows keep their name: the generated column turns NULL and a MySQL unique index
-- ignores NULLs, so deleting a server and re-creating it under the same name still works.
-- idx_tenant_id becomes redundant: tenant_id is the leftmost prefix of the new key.
ALTER TABLE mcp_server
    DROP INDEX idx_tenant_id,
    ADD COLUMN active_name VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_mcp_server_tenant_active_name (tenant_id, active_name);
