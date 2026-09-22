-- V37: retire the V12-seeded `harnax-cli` skill row
--
-- V12 seeded this skill by hand because the built-in CLI had no other way to reach an agent. Since V35
-- a CLI's skill is whatever `skill/SKILL.md` sits inside its package: the `harnax` package registers its
-- own row under the package name, and `cli.skill_id` points at *that* one. The seeded row is left with
-- no reader — delivery only resolves skills reachable through a binding or a `cli.skill_id`, the old
-- `GET /internal/builtin-skills` endpoint is gone, and `SkillBindingResolver` refuses to let an agent or
-- a team lead bind anything from the builtin repository. It cannot even be cleaned up from the UI:
-- management writes to that repository are blocked by `requireNotBuiltinRepo`.
--
-- Soft delete, the same shape `SkillMapper.deleteById` uses, so `active_name` releases the name and a
-- future package may use it. Guarded on `cli.skill_id` rather than on the name alone: an installation
-- where a package already owns the name keeps its row.

UPDATE skill
SET active = 0
WHERE name = 'harnax-cli'
  AND active = 1
  AND repository_id = (SELECT id FROM skill_repository WHERE name = 'builtin-cli-skills' LIMIT 1)
  AND id NOT IN (SELECT skill_id FROM cli WHERE skill_id IS NOT NULL);
