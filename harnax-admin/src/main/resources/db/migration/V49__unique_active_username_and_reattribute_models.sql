-- V49: sys_user.username — one live account per name, and the V44 attribution re-read on top of it
--
-- The auth path has assumed this rule from the start: `SysUserMapper.selectByUsername` is
-- `WHERE username = ? AND active = 1 LIMIT 1`, and that one statement backs login, `SecurityUtils`,
-- the tenant switch, MCP session ownership and the mini-program login. Nothing enforced it — V1 left
-- `sys_user` with PRIMARY KEY and `idx_tenant_id` only — so two live accounts could carry one name and
-- the `LIMIT 1` decided, with no error to chase, which of them the request was served as.
--
-- Same shape as V15 (skill), V23 (mcp_server), V43 (agent), V45 (env_variable): the key covers a
-- generated column that turns NULL once the row is deleted, and MySQL ignores NULLs in a unique index,
-- so retiring an account still frees its name for a new one. A NULL `active` turns NULL too, which is
-- right — `active = 1` rejects such a row in the auth query as well.
--
-- The new column inherits the table's `utf8mb4_0900_ai_ci`, so the key is case-insensitive exactly like
-- `WHERE username = ?` is: `Admin` and `admin` are one account to the auth path either way.
--
-- Unlike those four, nothing is repaired before the key goes on: a `#dup-<id>` suffix would rewrite a
-- login name out from under its owner, and choosing between two colliding accounts is an operator's
-- call, so an ALTER that fails loudly is the intended outcome. The live data has one row (`admin`).
--
-- idx_tenant_id stays: `username` is not its prefix, so the new key serves no tenant-filtered scan.

ALTER TABLE sys_user
    ADD COLUMN active_username VARCHAR(50) GENERATED ALWAYS AS (IF(active = 1, username, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_active_username (active_username);

-- V44's three statements again, each creator join now carrying `AND u.active = 1`, placed after the
-- ALTER so the join is 1:1 by the time it decides anything. V44 joined `sys_user` unfiltered: a row
-- could be attributed to the primary tenant of a deleted account that merely shared the creator's
-- name, and among several same-named rows MySQL picked one arbitrarily.
--
-- A row whose creator has no live account matches no join and keeps whatever V44 gave it. That is
-- deliberate: the app cannot serve that creator's login either, so there is no better value to write,
-- and inventing one (tenant 1, or its provider's) would move data on a guess.
--
-- Idempotent the way V44 is — the `<>` guard makes an already-correct row a no-op — and `update_time`
-- stays untouched, because attribution corrects stored data rather than recording an owner's edit.

UPDATE model_provider p
    JOIN sys_user u ON u.username = p.creator AND u.active = 1
SET p.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND p.tenant_id <> u.tenant_id;

UPDATE model m
    JOIN sys_user u ON u.username = m.creator AND u.active = 1
SET m.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL
  AND m.tenant_id <> u.tenant_id;

-- A private model has to sit on a provider its own tenant can use, so it follows the provider
-- attributed above; last, for the same reason V44 ran it last.

UPDATE model m
    JOIN model_provider p ON p.id = m.provider_id
SET m.tenant_id = p.tenant_id
WHERE m.tenant_id <> p.tenant_id
  AND m.is_public = 0;
