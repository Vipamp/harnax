-- V38: retire the `cli` rows the package model can never own
--
-- V35 assumed the table held no rows, and a fresh install is right — but an installation that used the
-- old CLI page has hand-entered rows behind it. They are dead by construction now: the `install_script`
-- column that made them work is gone, an empty `payload_digest` means no sandbox image can be named for
-- them, and since design D2 nothing writes this table except the registrar.
--
-- The registrar will not clean them up either, deliberately: `pruneMissingPackages` only ever deletes a
-- row whose `package_digest` it wrote itself, so a leftover is not mistaken for a package that retired
-- and hard-deleted together with its bindings. That leaves retirement to this migration.
--
-- `active = 0` is what the reader side already honours — the read-only page filters on it, and so do
-- `selectByIds`/`updateStatus`, which takes the row out of delivery. The name gains a `#retired-<id>`
-- suffix, the V15 answer for a unique key: `uk_cli_name` covers soft-deleted rows too, so without it a
-- package that happens to use the same name would overwrite this corpse instead of starting its own row.
-- Bindings go with the row; they can never be satisfied again and would only log "CLI not found" on
-- every spec resolution. Skills are left alone — a hand-entered CLI could bind skills from a repository
-- an operator owns, and those remain manageable from the skill page.

DELETE FROM agent_cli_binding
WHERE cli_id IN (SELECT id FROM cli WHERE package_digest = '' AND active = 1);

UPDATE cli
SET active      = 0,
    name        = LEFT(CONCAT(SUBSTRING(name, 1, 110), '#retired-', id), 128),
    update_time = NOW()
WHERE package_digest = ''
  AND active = 1;
