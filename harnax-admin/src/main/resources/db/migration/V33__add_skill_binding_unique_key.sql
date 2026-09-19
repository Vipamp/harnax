-- V33: One row per (agent, skill) in agent_skill_binding
--
-- Same shape as V18 and V19: `saveSkillBindings` rewrites the whole set on every save, so a
-- duplicate could only come from a client posting the same skill twice in one request. Delivery
-- resolves every binding row on its own, so a duplicate loaded the same skill twice.
--
-- Collapse existing duplicates first — the newest row carries the latest update_time, and the two
-- columns that could differ (`env_bindings` is never written for skills) make the rows interchangeable.

DELETE t1 FROM agent_skill_binding t1
    JOIN agent_skill_binding t2
    ON t1.agent_id = t2.agent_id AND t1.skill_id = t2.skill_id AND t1.id < t2.id;

-- idx_agent_skill_binding_agent_id becomes redundant: agent_id is the leftmost prefix of the new key.
ALTER TABLE agent_skill_binding
    DROP INDEX idx_agent_skill_binding_agent_id,
    ADD UNIQUE KEY uk_agent_skill_binding_agent_id_skill_id (agent_id, skill_id);
