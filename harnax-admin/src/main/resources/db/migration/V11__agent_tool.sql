-- Agent Tool table
CREATE TABLE `agent_tool`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT 'Tool ID',
    `tenant_id`       bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`            varchar(100) NOT NULL COMMENT 'Tool identifier name (snake_case, unique per tenant)',
    `display_name`    varchar(200)          DEFAULT NULL COMMENT 'Display name',
    `description`     text COMMENT 'Tool description (sent to LLM)',
    `type`            varchar(20)  NOT NULL COMMENT 'Tool type: BUILTIN / CUSTOM / HTTP',
    `bean_name`       varchar(200)          DEFAULT NULL COMMENT 'Spring Bean name (for BUILTIN/CUSTOM type)',
    `http_url`        varchar(500)          DEFAULT NULL COMMENT 'HTTP request URL (for HTTP type)',
    `http_method`     varchar(10)           DEFAULT 'POST' COMMENT 'HTTP method (for HTTP type)',
    `http_headers`    text COMMENT 'HTTP headers JSON (for HTTP type)',
    `input_schema`    text COMMENT 'Input parameter JSON Schema (for HTTP type)',
    `output_schema`   text COMMENT 'Output result JSON Schema (for HTTP type)',
    `read_only`       tinyint(1)            DEFAULT '0' COMMENT 'Is read-only tool (0: No, 1: Yes)',
    `need_confirm`    tinyint(1)            DEFAULT '0' COMMENT 'Requires human confirmation (0: No, 1: Yes)',
    `timeout_seconds` int                   DEFAULT '30' COMMENT 'Timeout in seconds',
    `status`          tinyint(1)            DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`       tinyint(1)            DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`         varchar(100)          DEFAULT NULL COMMENT 'Creator',
    `active`          tinyint(1)            DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`     datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`     datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`, `active`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent Tool definition table';

-- Add tool_list column to agent table
ALTER TABLE `agent` ADD COLUMN `tool_list` text COMMENT 'Tool list JSON' AFTER `skill_list`;
