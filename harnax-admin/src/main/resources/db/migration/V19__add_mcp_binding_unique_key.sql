-- V19: One row per (agent, MCP server) in agent_mcp_binding
--
-- Same shape as V18 on the tool side: `saveMcpBindings` rewrites the whole set on every save, so a
-- duplicate could only come from a client posting the same mcpId twice in one request. Duplicates
-- are tolerated today (delivery maps each binding row separately, so a duplicate delivers the same
-- server twice), which makes "the binding row is the source of truth for this agent-MCP pair" false.
--
-- Collapse existing duplicates first — the newest row carries the latest env-binding snapshot.

DELETE t1 FROM agent_mcp_binding t1
    JOIN agent_mcp_binding t2
    ON t1.agent_id = t2.agent_id AND t1.mcp_id = t2.mcp_id AND t1.id < t2.id;

-- idx_agent_mcp_binding_agent_id becomes redundant: agent_id is the leftmost prefix of the new key.
ALTER TABLE agent_mcp_binding
    DROP INDEX idx_agent_mcp_binding_agent_id,
    ADD UNIQUE KEY uk_agent_mcp_binding_agent_id_mcp_id (agent_id, mcp_id);
