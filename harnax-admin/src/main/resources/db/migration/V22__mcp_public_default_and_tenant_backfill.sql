-- V22: mcp_server — public default, and the tenant the row really belongs to
--
-- 1. `is_public` defaulted to 0 in V1 while the entity, the create request and every capability
--    table around it (agent_tool: 1) default to 1. The app always writes the column explicitly, so
--    the gap only showed for a direct INSERT that omitted it — which then produced a private row.
--    Align the DDL with the rest.
--
-- 2. `McpServerServiceImpl` has set `tenantId` from the request's tenant since forever, but the
--    insert statement never listed the column, so every row landed in the DDL default (tenant 1).
--    The insert now writes it, and `selectMcpServerList` filters on it (same convention as `cli`).
--    Without a backfill, an MCP server created by another tenant would keep claiming tenant 1 and
--    stay invisible to its owner's tenant once that filter takes effect. The creator's primary
--    tenant (`sys_user.tenant_id`) is the value the app would have stored.

ALTER TABLE mcp_server ALTER COLUMN is_public SET DEFAULT 1;

UPDATE mcp_server m
    JOIN sys_user u ON u.username = m.creator
SET m.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND m.tenant_id <> u.tenant_id;
