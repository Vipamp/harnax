-- V42: retire the soft-deleted `cli` rows V38's predicate left behind
--
-- V38 matched `package_digest = '' AND active = 1`, and the old page's delete was a soft delete
-- (`UPDATE cli SET active = 0 WHERE id = ? AND active = 1`, with only `cli_skill_binding` cleared — a
-- table V35 has since dropped). So any installation where an operator deleted a hand-entered CLI before
-- the package model arrived still holds that row, inactive and unnamed-over.
--
-- Two things survive on those rows, and the second is the reason this migration exists:
--
-- 1. `agent_cli_binding` rows. The old delete never touched them, and they name a CLI no reader will
--    resolve again — `selectByIds`/`selectCliList` filter `active = 1` — so they are noise today.
-- 2. The name itself, which `uk_cli_name` keeps held. `selectByName` carries no `active` condition,
--    because the registrar must see a row it wrote and disabled; so when a package takes a retired
--    hand-entered CLI's name, it reads that corpse as its own existing row and
--    `upsertCliPackage`'s `ON DUPLICATE KEY UPDATE active = 1` resurrects it. The new package then ships
--    under the old row's id — and those stale bindings, and whatever `status` the corpse carried, come
--    back with it. Agents end up with a CLI their operator never granted.
--
-- Renaming is the V15/V38 answer for a unique key, and it is what makes the claim in `CliMapper.xml`
-- ("looking a package name up never lands on a soft-deleted row") true rather than nearly true. V38's
-- own suffix is skipped so this stays re-runnable; nothing here touches a row the registrar wrote.
--
-- One hole is left on purpose. A hand-entered name that literally contains `#retired-` is skipped by the
-- filter, and such a row could in principle be the name another row's suffix computes to — which would
-- fail this migration and stop admin from booting. Guarding it means a derived-table subquery on `cli`
-- inside an `UPDATE cli`, for a name no operator has ever typed; the filter keeps the readable shape
-- V38 established instead.

DELETE FROM agent_cli_binding
WHERE cli_id IN (SELECT id FROM cli WHERE package_digest = '' AND active = 0);

UPDATE cli
SET name        = LEFT(CONCAT(SUBSTRING(name, 1, 110), '#retired-', id), 128),
    update_time = NOW()
WHERE package_digest = ''
  AND active = 0
  AND name NOT LIKE '%#retired-%';
