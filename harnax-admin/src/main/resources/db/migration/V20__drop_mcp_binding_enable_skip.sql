-- V20: Drop the MCP-binding "skip if missing" switch
--
-- `agent_mcp_binding.enable_skip` only covered one case: no `mcp_server` row found for the binding.
-- It never protected against an unreachable server — `McpHelper.createMcpClient` and
-- `registerMcpClient(...).block()` throw regardless of the flag, so the agent build failed either
-- way. That made the switch a misleading promise in the config UI rather than a tolerance setting.
--
-- A missing MCP config now behaves like a missing tool config: log a warning, continue building.
-- `McpSpec.skipIfMissing` and the `AGENT_MCP_NOT_FOUND` error code are gone with it. V17 kept this
-- column on the assumption above; that assumption no longer holds.

ALTER TABLE agent_mcp_binding DROP COLUMN enable_skip;
