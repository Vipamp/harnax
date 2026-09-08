-- V17: Drop the tool-binding "skip if missing" switch
--
-- `agent_tool_binding.enable_skip` was never a user-meaningful option: it only chose between two log
-- levels when a bound tool could not be resolved, and a missing tool silently disappeared from the
-- agent either way. The agent config UI exposed it as "缺失时跳过", which read like a runtime
-- tolerance setting it never was. The switch and the `ToolSpec.skipIfMissing` field it fed are gone;
-- the delivery path always logs a warning and continues.
--
-- `agent_mcp_binding.enable_skip` keeps its column and behaviour: an unreachable MCP server really
-- can block agent construction, so that one still distinguishes "fail" from "warn and skip".

ALTER TABLE agent_tool_binding DROP COLUMN enable_skip;
