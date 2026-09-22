-- V36: drop agent_skill_binding.env_bindings
--
-- The column was reserved for per-skill environment variables, and no consumer ever arrived:
-- `saveSkillBindings` writes agentId/skillId only, and neither the agent detail nor the delivery
-- path reads it back. The same column on agent_tool_binding / agent_mcp_binding / agent_cli_binding
-- *is* live — admin resolves those into plaintext when it delivers a spec — which is exactly why
-- keeping a dead copy here was harmful: it read as "skills can carry per-agent env" in a table where
-- nothing supports it. A future skill-level env channel should add the column together with its
-- first consumer.

ALTER TABLE agent_skill_binding DROP COLUMN env_bindings;
