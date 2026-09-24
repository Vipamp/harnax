-- V44: model / model_provider — put the rows in the tenant that created them
--
-- Both tables have had `tenant_id` since V1, but neither insert statement ever listed the column, so
-- every provider and model created through the app landed in the DDL default (tenant 1). The inserts now
-- write the caller's tenant and `selectModelList`/`selectModelProviderList` filter on it, so without this
-- backfill a tenant 2 operator's own rows would keep claiming tenant 1: they would stay visible through
-- `is_public` but their private (`is_public = 0`) rows would become unreachable for their own owner, and
-- the name uniqueness check would start comparing against the wrong tenant's set.
--
-- The creator's primary tenant (`sys_user.tenant_id`) is the value the app would have stored, same as V22.

UPDATE model_provider p
    JOIN sys_user u ON u.username = p.creator
SET p.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND p.tenant_id <> u.tenant_id;

UPDATE model m
    JOIN sys_user u ON u.username = m.creator
SET m.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND m.tenant_id <> u.tenant_id;

-- A model has to sit on a provider its own tenant can use, so a model that was attributed to a different
-- tenant than its provider above follows that provider instead of being orphaned behind the filter.
UPDATE model m
    JOIN model_provider p ON p.id = m.provider_id
SET m.tenant_id = p.tenant_id
WHERE m.tenant_id <> p.tenant_id
  AND m.is_public = 0;
