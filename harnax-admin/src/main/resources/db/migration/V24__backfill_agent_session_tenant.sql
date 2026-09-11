-- V24: agent / session — the tenant their creator actually belongs to
--
-- Both services have set `tenantId` from the request's tenant for a long time, but neither value
-- reached the row: `AgentMapper.xml` listed no `tenant_id` in its insert and did not even map the
-- column, and `SessionServiceImpl.createSession` never assigned it (only the mini-program path did).
-- So every agent row and every web session row sits in the DDL default tenant 1.
--
-- The P1 identity work now filters both lists by tenant and refuses single-row access outside the
-- current tenant. Without a backfill that would hide a tenant-2 owner's own rows rather than
-- protect them, exactly the reason V22 backfilled `mcp_server`. Same rule here: the creator's
-- primary tenant (`sys_user.tenant_id`), which is what the insert would have stored.
--
-- `agent.creator` is always a username. `session.creator` is a username on the web path and a
-- numeric user id on the mini-program path, so both forms are joined; the digit guard keeps the two
-- statements disjoint.

UPDATE agent a
    JOIN sys_user u ON u.username = a.creator
SET a.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND a.tenant_id <> u.tenant_id;

UPDATE session s
    JOIN sys_user u ON u.username = s.creator
SET s.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND s.tenant_id <> u.tenant_id
  AND s.creator NOT REGEXP '^[0-9]+$';

UPDATE session s
    JOIN sys_user u ON CAST(u.id AS CHAR) = s.creator
SET s.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND s.tenant_id <> u.tenant_id
  AND s.creator REGEXP '^[0-9]+$';
