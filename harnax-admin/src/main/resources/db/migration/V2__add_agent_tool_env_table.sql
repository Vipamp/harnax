-- ============================================
-- Tool Environment Variable Table
-- ============================================
CREATE TABLE IF NOT EXISTS `agent_tool_env` (
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT 'Env entry ID',
    `tool_id`         bigint       NOT NULL COMMENT 'FK to agent_tool.id',
    `env_name`        varchar(200) NOT NULL COMMENT 'Environment variable name, e.g. API_KEY',
    `required`        tinyint(1)   NOT NULL DEFAULT 0 COMMENT 'Is required (0: No, 1: Yes)',
    `secret`          tinyint(1)   NOT NULL DEFAULT 0 COMMENT 'Is sensitive (0: No, 1: Yes, encrypted storage)',
    `default_value`   text                  DEFAULT NULL COMMENT 'Default value (required when required=1)',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    KEY `idx_tool_id` (`tool_id`),
    UNIQUE KEY `uk_tool_env_name` (`tool_id`, `env_name`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent Tool environment variable definitions';
