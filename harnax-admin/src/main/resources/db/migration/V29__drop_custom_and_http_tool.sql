-- V29: tools are builtin-only — drop the CUSTOM / HTTP tool types and the per-tool visibility flag.
--
-- The tool API is read-only now: every agent_tool row is written at admin startup by
-- BuiltinToolAutoRegistrar from @Tool/@ToolMeta, so nothing can create a CUSTOM or HTTP tool any
-- more. Visibility is no longer per tool either— every builtin tool is offered to every tenant.
--
-- Order matters. The rows that only existed because of those two types go first, together with the
-- bindings and env-parameter definitions hanging off them; otherwise an agent would keep carrying a
-- reference to a tool that no longer resolves. Losing the stored CUSTOM / HTTP configuration is
-- accepted on purpose: no runtime path can execute either kind after this change.
--
-- No key covers any dropped column— V4 replaced uk_tenant_name with uk_tenant_bean_method, and
-- idx_tenant_id is the only other index— so the ALTER below touches no index.

DELETE binding FROM agent_tool_binding binding
    JOIN agent_tool tool ON binding.tool_id = tool.id
WHERE tool.type <> 'BUILTIN';

DELETE env FROM agent_tool_env_param env
    JOIN agent_tool tool ON env.tool_id = tool.id
WHERE tool.type <> 'BUILTIN';

DELETE FROM agent_tool
WHERE type <> 'BUILTIN';

-- env_params held the values the old tool form wrote; the definitions live in agent_tool_env_param
-- and the builtin sync never filled this column, so it is empty by construction.
ALTER TABLE agent_tool
    DROP COLUMN `type`,
    DROP COLUMN `is_public`,
    DROP COLUMN `http_url`,
    DROP COLUMN `http_method`,
    DROP COLUMN `http_headers`,
    DROP COLUMN `input_schema`,
    DROP COLUMN `output_schema`,
    DROP COLUMN `env_params`;
