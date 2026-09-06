-- V16: Drop the never-written storage columns, and index the delete-path lookups
--
-- 1. `storage_path` on both skill tables is a leftover of the file-storage design that V9 replaced:
--    skill content has lived in MySQL since then, and no write path ever set the column — only test
--    seed data did. Keeping a column that reads like "where the content is" invites someone to trust
--    an always-empty value, so it goes.
-- 2. Deleting a skill removes its rows from both binding tables with `WHERE skill_id IN (...)`, and
--    neither table is indexed on skill_id: every delete scanned the whole binding table. The binding
--    tables only ever got an index for the forward lookup (agent_id / cli_id).
-- 3. `selectBuiltinRepository(name)` and `selectByName(name, tenantId)` filter on `name`, which has
--    no index: V15's `uk_skill_repository_tenant_active_name` leads with tenant_id, so a query
--    without a tenant cannot use it. The builtin repository is looked up on every agent-spec
--    delivery, so this one is not cold.

-- 1. Reserved columns, dropped.
ALTER TABLE skill_repository DROP COLUMN storage_path;
ALTER TABLE skill DROP COLUMN storage_path;

-- 2. Reverse lookups used by the delete paths.
ALTER TABLE agent_skill_binding ADD INDEX idx_agent_skill_binding_skill_id (skill_id);
ALTER TABLE cli_skill_binding ADD INDEX idx_cli_skill_binding_skill_id (skill_id);

-- 3. Repository name lookups.
ALTER TABLE skill_repository ADD INDEX idx_skill_repository_name (name);
