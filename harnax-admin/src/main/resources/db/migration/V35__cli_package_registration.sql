-- V35: CLI becomes a package-registered asset instead of a hand-entered record
--
-- Design: docs/superpowers/specs/2026-09-21-cli-package-plugin-design.md
--
-- Before this migration the platform carried two parallel CLI models: the hand-entered `cli` rows
-- (name + a free-text Dockerfile `install_script` run as root at image build time) and the
-- "built-in plugin" trio `cli_plugin` / `agent_cli_plugin_binding` / V11's seed row. The latter
-- never reached a runtime decision — the real switch is the `SANDBOX_CLI_PLUGINS_ENABLED` environment
-- variable — and `agent_cli_plugin_binding` had no reader at all. Both models are replaced by one:
-- a `.harnaxcli.zip` package dropped into admin's package directory, parsed at startup, its binary
-- stored in MinIO and its `skill/SKILL.md` registered as the CLI's own skill.
--
-- What this migration does:
-- 1. `cli` gains the package columns and a direct 1:1 pointer to its shipped skill, so "which skill
--    belongs to this CLI" is a column rather than a join through `cli_skill_binding`.
-- 2. `cli` loses the hand-entry columns: `install_script` (replaced by the package payload plus a
--    declared `deps.apt` list), and `tenant_id` / `creator` / `is_public` — packages are published
--    platform-wide, so there is nothing for a tenant or an author to express.
-- 3. `name` becomes the unique identity. A new version of a package overwrites the same row, which
--    is also what `agent_cli_binding` binds to (a CLI, not a version).
-- 4. The three tables of the retired models are dropped. `cli` holds no rows today and the plugin
--    tables hold only V11's single seed row, so nothing is lost either way.
--
-- `agent_cli_binding` is untouched: its `env_bindings` really is per-agent (an agent can point one
-- CLI at its own endpoint or credentials), unlike the shipped skill which is package-owned.

ALTER TABLE cli
    ADD COLUMN skill_id       BIGINT        DEFAULT NULL COMMENT 'FK to skill.id: the SKILL.md shipped inside the package' AFTER check_command,
    ADD COLUMN package_digest CHAR(64)      NOT NULL DEFAULT '' COMMENT 'sha256 of the whole package zip: package identity, MinIO object key, re-registration test',
    ADD COLUMN payload_digest CHAR(64)      NOT NULL DEFAULT '' COMMENT 'Canonical sha256 of payload/ plus deps: the sandbox image fingerprint. Deliberately not package_digest — editing only SKILL.md must not rebuild live containers',
    ADD COLUMN package_object VARCHAR(256)  NOT NULL DEFAULT '' COMMENT 'MinIO object key of the package',
    ADD COLUMN deps_apt       VARCHAR(512)  DEFAULT NULL COMMENT 'apt packages installed alongside the payload (JSON array)',
    ADD COLUMN runtime_env    VARCHAR(1024) DEFAULT NULL COMMENT 'Env slots the platform injects at container creation, JSON object of name to literal value or platform slot (e.g. HARNAX_URL -> platform.adminUrl)';

ALTER TABLE cli DROP INDEX idx_cli_tenant_id;

ALTER TABLE cli
    DROP COLUMN install_script,
    DROP COLUMN tenant_id,
    DROP COLUMN creator,
    DROP COLUMN is_public;

ALTER TABLE cli ADD UNIQUE KEY uk_cli_name (name);

DROP TABLE IF EXISTS cli_skill_binding;
DROP TABLE IF EXISTS agent_cli_plugin_binding;
DROP TABLE IF EXISTS cli_plugin;
