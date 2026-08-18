-- V11: Seed the built-in harnax-cli plugin metadata.
-- Ensures the system CLI plugin exists at database initialization time, independent of the
-- runtime CliPluginAutoRegistrar. Uses ON DUPLICATE KEY UPDATE (cli_plugin.uk_cli_plugin_name)
-- to refresh metadata only; the status column is never overwritten so admins can disable it.

INSERT INTO cli_plugin (
    tenant_id, name, display_name, display_name_zh, description, version,
    type, binary_path, init_script, skill_doc_path, health_check,
    status, creator, active, create_time, update_time
) VALUES (
    1, 'harnax-cli', 'Harnax CLI', 'Harnax 命令行',
    'Platform management CLI for agent, session, model, tool, MCP, channel, env-variable, scheduler, API key, tenant and user operations',
    NULL,
    'SYSTEM', '/usr/local/bin/harnax', '/opt/plugins/harnax-cli/init.sh',
    '/opt/plugins/harnax-cli/SKILL.md', 'harnax --version',
    1, 'SYSTEM', 1, NOW(), NOW()
) ON DUPLICATE KEY UPDATE
    display_name    = VALUES(display_name),
    display_name_zh = VALUES(display_name_zh),
    description     = VALUES(description),
    version         = VALUES(version),
    binary_path     = VALUES(binary_path),
    init_script     = VALUES(init_script),
    skill_doc_path  = VALUES(skill_doc_path),
    health_check    = VALUES(health_check),
    update_time     = NOW();
