-- V15: Skill source integrity hardening
--
-- 1. The builtin repository was seeded as a ZIP source with a NULL config, describing a source it
--    never had. It is platform-managed, so it gets its own source type.
-- 2. Skill visibility only followed the repository in code written after V1; rows created before
--    that stayed private inside public repositories and never showed up in the skill list.
-- 3. Name uniqueness was enforced by an application-level lookup, so two concurrent creates could
--    both pass it. Unique indexes are the only real guard. Existing duplicates are renamed first
--    (suffix `#dup-<id>`), so no row is ever deleted by this migration.

-- 1. The builtin repository describes itself correctly.
--    url/source_config are written as empty strings, not NULL: the Kotlin entity declares both as
--    non-null, so a NULL only survives because MyBatis leaves unset columns at their default.
UPDATE skill_repository
SET source_type   = 'BUILTIN',
    source_config = '',
    url           = '',
    update_time   = NOW()
WHERE name = 'builtin-cli-skills'
  AND source_type <> 'BUILTIN';

-- 2. Skills inherit the visibility of their repository
UPDATE skill s
    JOIN skill_repository r ON s.repository_id = r.id
SET s.is_public   = r.is_public,
    s.update_time = NOW()
WHERE s.active = 1
  AND s.is_public <> r.is_public;

-- 3a. Rename duplicate active repository names, keeping the oldest row of each tenant
UPDATE skill_repository r
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY tenant_id, name ORDER BY id) AS rn
          FROM skill_repository
          WHERE active = 1) d ON r.id = d.id
SET r.name        = LEFT(CONCAT(SUBSTRING(r.name, 1, 80), '#dup-', r.id), 100),
    r.update_time = NOW()
WHERE d.rn > 1;

-- 3b. Rename duplicate active skill names, keeping the oldest row of each repository
UPDATE skill s
    JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY repository_id, name ORDER BY id) AS rn
          FROM skill
          WHERE active = 1) d ON s.id = d.id
SET s.name        = LEFT(CONCAT(SUBSTRING(s.name, 1, 80), '#dup-', s.id), 100),
    s.update_time = NOW()
WHERE d.rn > 1;

-- 3c. Uniqueness guards. Soft-deleted rows keep their name (the generated column turns NULL, and
--     MySQL unique indexes ignore NULL), so re-creating a previously deleted name still works.
ALTER TABLE skill_repository
    ADD COLUMN active_name VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL,
    ADD COLUMN builtin_guard TINYINT GENERATED ALWAYS AS (IF(active = 1 AND name = 'builtin-cli-skills', 1, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_skill_repository_tenant_active_name (tenant_id, active_name),
    ADD UNIQUE KEY uk_skill_repository_builtin_guard (builtin_guard);

ALTER TABLE skill
    ADD COLUMN active_name VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_skill_repo_active_name (repository_id, active_name);
