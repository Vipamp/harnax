-- V3: Add i18n support columns
-- agent_tool: display_name_zh for Chinese display name
ALTER TABLE `agent_tool`
    ADD COLUMN `display_name_zh` varchar(200) DEFAULT NULL COMMENT 'Display name (Chinese, for i18n zh-CN locale)'
    AFTER `display_name`;

-- agent_tool_env: description for env variable explanation shown in Admin UI
ALTER TABLE `agent_tool_env`
    ADD COLUMN `description` varchar(500) DEFAULT NULL COMMENT 'Human-readable description shown in Admin UI'
    AFTER `env_name`;
