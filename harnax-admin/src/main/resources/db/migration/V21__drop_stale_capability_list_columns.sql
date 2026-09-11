-- V21: Drop the pre-binding capability list columns from agent and session
--
-- V7 moved tool / MCP / skill bindings into the agent_*_binding tables. The old JSON columns stayed
-- behind with no writer: `createAgent` never assigns them, so every row inserted since then carries
-- the empty default, while readers still trusted them:
--
-- - `SessionServiceImpl` parsed `session.mcp_list` / `session.skill_list` to build the session
--   detail's MCP and skill lists, so both were permanently empty in the UI;
-- - `InternalApiController.getAgentTaskSpec` delivered `agent.mcp_list` / `agent.skill_list` as the
--   task agent spec, so a task-driven agent was told it had no MCP servers and no skills.
--
-- Both reads now go through the binding tables of the bound agent (`agentId`), which is what the
-- delivery path has been using all along. Sessions never had their own bindings, and a frozen
-- per-session snapshot is not a requirement anywhere — if one is ever needed it deserves its own
-- table rather than a JSON column that nothing writes.

ALTER TABLE agent DROP COLUMN mcp_list;
ALTER TABLE agent DROP COLUMN skill_list;
ALTER TABLE agent DROP COLUMN tool_list;
ALTER TABLE session DROP COLUMN mcp_list;
ALTER TABLE session DROP COLUMN skill_list;
