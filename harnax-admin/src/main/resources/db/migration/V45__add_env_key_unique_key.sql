-- V45: env_variable keeps more than one soft-deleted row per key
--
-- `uk_tenant_key_active` covered the raw `active` column, so (tenant, key, 0) could hold only ONE
-- deleted row. Deleting a variable, re-creating it under the same key and deleting it again hit the
-- key on the second delete: `EnvVariableMapper.xml` deletes with
-- `UPDATE env_variable SET active = 0`, which is an ordinary INSERT of a duplicate key from the
-- index's point of view. The API answered code 500 with the borrowed "The submitted name is already
-- in use" text, because no delete path is supposed to collide with a name rule.
--
-- Same fix as V15 (skill), V23 (mcp_server), V43 (agent): the key covers a generated column that
-- turns NULL once the row is gone, and MySQL ignores NULLs in a unique index. No backfill is needed
-- - the old key already kept live rows unique on (tenant_id, env_key), and the new one only relaxes
-- what happens after a row is deleted.
--
-- idx_tenant_id becomes redundant: tenant_id is the leftmost prefix of the new key.

ALTER TABLE env_variable
    DROP INDEX idx_tenant_id,
    DROP INDEX uk_tenant_key_active,
    ADD COLUMN active_env_key VARCHAR(200) GENERATED ALWAYS AS (IF(active = 1, env_key, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_env_tenant_active_key (tenant_id, active_env_key);
