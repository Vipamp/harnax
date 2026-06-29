-- V6: Add multi-source skill support (GIT, NPM, ZIP)
-- skill_repository: add source_type, source_config, version, storage_path
ALTER TABLE skill_repository
    ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'GIT' COMMENT 'GIT | NPM | ZIP' AFTER branch,
    ADD COLUMN source_config TEXT COMMENT 'Source configuration JSON' AFTER source_type,
    ADD COLUMN version VARCHAR(100) COMMENT 'Version identifier' AFTER source_config,
    ADD COLUMN storage_path VARCHAR(500) COMMENT 'Content storage path' AFTER version;

-- skill: add storage_path, version; keep skillmd/resources for backward compatibility
ALTER TABLE skill
    ADD COLUMN storage_path VARCHAR(500) COMMENT 'Content storage path' AFTER resources,
    ADD COLUMN version VARCHAR(100) COMMENT 'Skill version' AFTER storage_path;
