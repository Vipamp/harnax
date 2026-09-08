-- V18: One row per (agent, tool) in agent_tool_binding
--
-- `saveToolBindings` rewrites the whole set on every save, so a duplicate could only come from a
-- client posting the same tool twice in one request. Duplicates are harmless today (delivery
-- de-duplicates by id) but they make "the binding row is the source of truth for this agent-tool
-- pair" false, so the database should refuse them.
--
-- Collapse existing duplicates first — the newest row carries the latest env-binding snapshot.

DELETE t1 FROM agent_tool_binding t1
    JOIN agent_tool_binding t2
    ON t1.agent_id = t2.agent_id AND t1.tool_id = t2.tool_id AND t1.id < t2.id;

-- idx_agent_tool_binding_agent_id becomes redundant: agent_id is the leftmost prefix of the new key.
ALTER TABLE agent_tool_binding
    DROP INDEX idx_agent_tool_binding_agent_id,
    ADD UNIQUE KEY uk_agent_tool_binding_agent_id_tool_id (agent_id, tool_id);
