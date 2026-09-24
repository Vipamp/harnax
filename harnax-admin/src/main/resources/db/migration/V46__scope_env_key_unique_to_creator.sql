-- V46: env_variable keys are unique per creator, not per tenant
--
-- The console has always listed one user's variables only
-- (`selectEnvVariableList` filters `creator = currentUsername`), while the storage rule was tenant
-- wide. The two disagreed: user B could not type a key user A happened to hold, and could not even
-- see that A held it, so the refusal read as a failure against an invisible row.
--
-- Nothing to backfill. A set already unique on (tenant_id, key) stays unique once `creator` joins
-- the key, so this only relaxes what the old key forbade.
--
-- `creator` is nullable in V1 but the write path defaults it to `''`, so live rows carry a value and
-- are covered. A hand-inserted NULL would escape any unique key in MySQL, which is why
-- `EnvVariableMapper.selectByKey` still asks for the key by name before the insert.

ALTER TABLE env_variable
    DROP INDEX uk_env_tenant_active_key,
    ADD UNIQUE KEY uk_env_tenant_creator_active_key (tenant_id, creator, active_env_key);
