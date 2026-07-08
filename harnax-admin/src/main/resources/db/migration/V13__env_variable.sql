-- Environment Variable Management Table
CREATE TABLE IF NOT EXISTS `env_variable` (
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `env_key`     varchar(200) NOT NULL COMMENT 'Environment variable key',
    `env_value`   text         NOT NULL COMMENT 'Environment variable value',
    `description` varchar(500) DEFAULT NULL COMMENT 'Description',
    `sensitive`   tinyint(1)   DEFAULT '0' COMMENT 'Sensitive flag (0: No, 1: Yes)',
    `creator`     varchar(100) DEFAULT NULL COMMENT 'Creator',
    `active`      tinyint(1)   DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_creator` (`creator`),
    UNIQUE KEY `uk_tenant_key_active` (`tenant_id`, `env_key`, `active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Environment Variable';
