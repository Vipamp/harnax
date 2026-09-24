-- V41: One row per (agent, cli) in agent_cli_binding
--
-- Same shape as V33 (which mirrors V18 and V19): `saveCliBindings` rewrites the whole set on every
-- save, so a duplicate could only come from a client posting the same CLI twice in one request. The
-- tool and MCP saves collapse their list with `distinctBy`; the CLI save did not, and `cliList` is
-- the one the agent wizard rebuilds from a multi-select, so repeats reach it.
--
-- Delivery maps one row per binding into `cliDetails`, so a duplicate shipped the same CLI twice and
-- the sandbox image hash and its `check_command` were computed per copy.
--
-- Collapse existing duplicates first, keeping the newest row: unlike the skill bindings, these carry
-- `env_bindings`, and the row written last is the one the last save intended.

DELETE t1 FROM agent_cli_binding t1
    JOIN agent_cli_binding t2
    ON t1.agent_id = t2.agent_id AND t1.cli_id = t2.cli_id AND t1.id < t2.id;

-- idx_agent_cli_binding_agent_id becomes redundant: agent_id is the leftmost prefix of the new key.
ALTER TABLE agent_cli_binding
    DROP INDEX idx_agent_cli_binding_agent_id,
    ADD UNIQUE KEY uk_agent_cli_binding_agent_id_cli_id (agent_id, cli_id);
