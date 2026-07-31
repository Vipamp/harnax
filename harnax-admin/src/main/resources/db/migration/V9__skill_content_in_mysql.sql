-- V9: Skill content stays in MySQL
-- Skill packages are downloaded to a temp dir, persisted into these columns, then the
-- temp files are discarded. MySQL is the single source of truth for skill content, so
-- the columns are widened from TEXT (64KB) to MEDIUMTEXT (16MB) to fit larger packages
-- whose resources JSON embeds every bundled file.

ALTER TABLE skill
    MODIFY COLUMN skillmd MEDIUMTEXT COMMENT 'skill.md content',
    MODIFY COLUMN resources MEDIUMTEXT COMMENT 'Bundled resource files as JSON (path -> content)';
