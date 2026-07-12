-- Harnax Database Initialization Script
-- All tables for the Harnax platform (merged from V1~V16)

-- ============================================
-- System Tables
-- ============================================

-- User table
CREATE TABLE IF NOT EXISTS `sys_user`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT 'User ID',
    `tenant_id`       bigint       DEFAULT NULL COMMENT 'Tenant ID (primary tenant)',
    `username`        varchar(50)  NOT NULL,
    `password`        varchar(100) NOT NULL,
    `nickname`        varchar(50)  NOT NULL,
    `email`           varchar(100) NOT NULL,
    `phone`           varchar(20)  NOT NULL,
    `gender`          tinyint      DEFAULT '2' COMMENT 'Gender (0: Male, 1: Female, 2: Unknown)',
    `avatar`          varchar(255) DEFAULT '' COMMENT 'Avatar URL',
    `status`          tinyint      DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_admin`        tinyint      DEFAULT '0' COMMENT 'Is admin (0: No, 1: Yes)',
    `active`          tinyint      DEFAULT '1' COMMENT 'Active status (0: Inactive, 1: Active)',
    `last_login_time` datetime     DEFAULT NULL COMMENT 'Last login time',
    `create_time`     datetime     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY               `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='User table';

-- Token blacklist table
CREATE TABLE IF NOT EXISTS `sys_token_blacklist`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'Blacklist ID',
    `token`       varchar(512) NOT NULL COMMENT 'JWT Token',
    `token_hash`  varchar(64)  NOT NULL COMMENT 'Token hash (SHA256)',
    `username`    varchar(50)           DEFAULT NULL,
    `user_id`     bigint                DEFAULT NULL COMMENT 'User ID',
    `reason`      varchar(50)           DEFAULT 'logout' COMMENT 'Blacklist reason (logout/revoke/ban/expired)',
    `expire_time` datetime     NOT NULL COMMENT 'Token expiration time',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_ip`   varchar(50)           DEFAULT NULL COMMENT 'Client IP address',
    PRIMARY KEY (`id`),
    KEY           `idx_expire_time` (`expire_time`),
    KEY           `idx_user_id` (`user_id`),
    KEY           `idx_username` (`username`),
    KEY           `idx_token_lookup` (`token_hash`,`expire_time`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Token blacklist table';

-- Tenant table
CREATE TABLE IF NOT EXISTS `tenant`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'Tenant ID',
    `name`        varchar(100) NOT NULL COMMENT 'Tenant name',
    `status`      tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `creator`     varchar(100) NOT NULL COMMENT 'Creator',
    `active`      tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Tenant table';

-- User-Tenant relationship table
CREATE TABLE IF NOT EXISTS `user_tenant`
(
    `id`        BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'Relationship ID',
    `user_id`   BIGINT      NOT NULL COMMENT 'User ID',
    `tenant_id` BIGINT      NOT NULL COMMENT 'Tenant ID',
    `role`      VARCHAR(50) NOT NULL DEFAULT 'member' COMMENT 'Role (admin/member)',
    `status`    TINYINT(1)  NOT NULL DEFAULT 1 COMMENT 'Status (0: Disabled, 1: Enabled)',
    `joined_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Join time',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='User-Tenant relationship table';

-- ============================================
-- Business Tables
-- ============================================

-- MCP Server table
CREATE TABLE IF NOT EXISTS `mcp_server`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'MCP Server ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`        varchar(100) NOT NULL COMMENT 'MCP Server name',
    `description` text COMMENT 'MCP Server description',
    `type`        varchar(20)  NOT NULL COMMENT 'MCP type (stdio/sse/streamablehttp)',
    `command`     varchar(500)          DEFAULT NULL COMMENT 'Command (for stdio type)',
    `url`         varchar(500)          DEFAULT NULL COMMENT 'URL (for sse/streamablehttp type)',
    `headers`     text                  DEFAULT NULL COMMENT 'HTTP headers JSON: [{"key":"Authorization","value":"Bearer xxx","secret":true}]',
    `envs`        text                  DEFAULT NULL COMMENT 'Env vars JSON: [{"key":"API_KEY","value":"sk-xxx","secret":true}]',
    `status`      tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`   tinyint               DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`     varchar(100)          DEFAULT NULL,
    `active`      tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY           `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP Server table';

-- Skill Repository table
CREATE TABLE IF NOT EXISTS `skill_repository`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT 'Repository ID',
    `tenant_id`     bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`          varchar(100) NOT NULL,
    `url`           varchar(500)          DEFAULT NULL,
    `description`   text,
    `status`        tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`     tinyint               DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`       varchar(100)          DEFAULT NULL,
    `active`        tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`   datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `branch`        varchar(100)          DEFAULT 'main',
    `source_type`   VARCHAR(20)  NOT NULL DEFAULT 'GIT' COMMENT 'Source type: GIT / NPM / ZIP',
    `source_config` text                  COMMENT 'Source configuration JSON',
    `version`       varchar(100)          COMMENT 'Version identifier',
    `storage_path`  varchar(500)          COMMENT 'Content storage path',
    PRIMARY KEY (`id`),
    KEY             `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skill Repository table';

-- Skill table
CREATE TABLE IF NOT EXISTS `skill`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT 'Skill ID',
    `tenant_id`     bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`          varchar(100) NOT NULL,
    `repository_id` bigint       NOT NULL COMMENT 'Repository ID',
    `description`   text,
    `skillmd`       text COMMENT 'skill.md content',
    `resources`     text,
    `storage_path`  varchar(500)          COMMENT 'Content storage path',
    `version`       varchar(100)          COMMENT 'Skill version',
    `status`        tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`     tinyint               DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`       varchar(100)          DEFAULT NULL,
    `active`        tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`   datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY             `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skill table';

-- Model Provider table
CREATE TABLE IF NOT EXISTS `model_provider`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'Provider ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `type`        varchar(50)  NOT NULL COMMENT 'Provider type (dashscope/openai/ollama)',
    `name`        varchar(100) NOT NULL COMMENT 'Display name',
    `description` varchar(500)          DEFAULT NULL COMMENT 'Provider description',
    `api_key`     varchar(500)          DEFAULT NULL COMMENT 'API key',
    `base_url`    varchar(500)          DEFAULT NULL COMMENT 'API base URL',
    `status`      tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`   tinyint(1) DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`     varchar(100) NOT NULL COMMENT 'Creator',
    `active`      tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    KEY           `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Model Provider table';

-- Model table
CREATE TABLE IF NOT EXISTS `model`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT 'Model ID',
    `tenant_id`         bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`              varchar(100) NOT NULL COMMENT 'Model name',
    `model_name`        varchar(100) NOT NULL COMMENT 'Model identifier',
    `provider_id`       bigint       NOT NULL COMMENT 'Model Provider ID',
    `description`       text COMMENT 'Model description',
    `model_type`        varchar(20)  NOT NULL COMMENT 'Model type (chat/embedding)',
    `support_internet`  tinyint(1) DEFAULT '0' COMMENT 'Support internet search (0: No, 1: Yes)',
    `support_reasoning` tinyint(1) DEFAULT '0' COMMENT 'Support reasoning (0: No, 1: Yes)',
    `support_tool`      tinyint(1) DEFAULT '0' COMMENT 'Support tools (0: No, 1: Yes)',
    `support_mcp`       tinyint(1) DEFAULT '0' COMMENT 'Support MCP (0: No, 1: Yes)',
    `support_vision`    tinyint(1) DEFAULT '0' COMMENT 'Support vision (0: No, 1: Yes)',
    `price`             decimal(10, 4)        DEFAULT '0.0000' COMMENT 'Price (CNY per million tokens)',
    `status`            tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`         tinyint(1) DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`           varchar(100)          DEFAULT NULL COMMENT 'Creator',
    `active`            tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`       datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`       datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    KEY                 `idx_provider_id` (`provider_id`),
    KEY                 `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Model table';

-- Agent table
CREATE TABLE IF NOT EXISTS `agent`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT 'Agent ID',
    `tenant_id`     bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`          varchar(100) NOT NULL,
    `description`   text,
    `system_prompt` text COMMENT 'System prompt (Markdown format)',
    `model_id`      bigint                DEFAULT NULL COMMENT 'Model ID',
    `mcp_list`      text COMMENT 'MCP list (JSON format)',
    `skill_list`    text COMMENT 'Skill list (JSON format)',
    `tool_list`     text COMMENT 'Tool list (JSON format)',
    `owner`         varchar(100)          DEFAULT NULL,
    `status`        tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`     tinyint               DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`       varchar(100)          DEFAULT NULL,
    `active`        tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`   datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY             `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent table';

-- Session table
CREATE TABLE IF NOT EXISTS `session`
(
    `id`                  bigint       NOT NULL AUTO_INCREMENT COMMENT 'Session ID',
    `tenant_id`           bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `title`               varchar(100) NOT NULL,
    `session_description` text,
    `session_id`          varchar(100) NOT NULL COMMENT 'Unique session identifier',
    `agent_id`            bigint                DEFAULT NULL COMMENT 'Agent ID',
    `name`                varchar(100)          DEFAULT NULL,
    `description`         text,
    `system_prompt`       text COMMENT 'System prompt (Markdown format)',
    `model_id`            bigint                DEFAULT NULL COMMENT 'Model ID',
    `enable_think`        tinyint(1) DEFAULT '0' COMMENT 'Enable deep thinking (0: No, 1: Yes)',
    `enable_search`       tinyint(1) DEFAULT '0' COMMENT 'Enable internet search (0: No, 1: Yes)',
    `enable_plan`         tinyint(1) DEFAULT '0' COMMENT 'Enable planning (0: No, 1: Yes)',
    `permission_mode`     VARCHAR(20)  NOT NULL DEFAULT 'DEFAULT' COMMENT 'Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)',
    `mcp_list`            text COMMENT 'MCP list (JSON format)',
    `skill_list`          text COMMENT 'Skill list (JSON format)',
    `owner`               varchar(100)          DEFAULT NULL,
    `status`              tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`           tinyint(1) DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`             varchar(100)          DEFAULT NULL,
    `active`              tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`         datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time`         datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY                   `idx_creator` (`creator`),
    KEY                   `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Session table';

-- Plan Note table
CREATE TABLE IF NOT EXISTS `plan_note`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT 'Plan ID',
    `session_id`       varchar(128) NOT NULL COMMENT 'Session ID',
    `plan_id`          varchar(128) NOT NULL COMMENT 'Plan identifier',
    `name`             varchar(256) NOT NULL COMMENT 'Plan name',
    `description`      text COMMENT 'Plan description',
    `expected_outcome` text COMMENT 'Expected outcome',
    `subtasks`         text COMMENT 'Subtasks list (JSON format)',
    `created_at`       varchar(64) DEFAULT NULL COMMENT 'Creation timestamp',
    `finished_at`      varchar(64) DEFAULT NULL COMMENT 'Completion timestamp',
    `cost_timeseconds` bigint      DEFAULT '0' COMMENT 'Execution time (seconds)',
    `status`           varchar(32) DEFAULT 'TODO' COMMENT 'Status (TODO, IN_PROGRESS, DONE, ABANDONED)',
    PRIMARY KEY (`id`),
    KEY                `idx_session_id` (`session_id`),
    KEY                `idx_plan_id` (`plan_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Plan Note table';

-- Process Log table
CREATE TABLE IF NOT EXISTS `process_log`
(
    `id`          bigint NOT NULL AUTO_INCREMENT COMMENT 'Log ID',
    `agent_id`    bigint       DEFAULT NULL COMMENT 'Agent ID',
    `agent_name`  varchar(255) DEFAULT NULL COMMENT 'Agent name',
    `session_id`  varchar(255) DEFAULT NULL COMMENT 'Session ID',
    `message`     text COMMENT 'Log message',
    `log_type`    varchar(20)  DEFAULT 'INFO' COMMENT 'Log type (INFO/WARN/ERROR)',
    `stack_trace` text COMMENT 'Exception stack trace',
    `ts`          datetime     DEFAULT NULL COMMENT 'Timestamp',
    PRIMARY KEY (`id`),
    KEY           `idx_agent_id` (`agent_id`),
    KEY           `idx_session_id` (`session_id`),
    KEY           `idx_log_type` (`log_type`),
    KEY           `idx_ts` (`ts`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Process Log table';

-- Token Statistics table
CREATE TABLE IF NOT EXISTS `token_stats`
(
    `id`            bigint NOT NULL AUTO_INCREMENT COMMENT 'Stats ID',
    `agent_id`      bigint         DEFAULT NULL COMMENT 'Agent ID',
    `session_id`    varchar(255)   DEFAULT NULL COMMENT 'Session ID',
    `chat_model_id` bigint         DEFAULT NULL COMMENT 'Chat Model ID',
    `input_token`   bigint         DEFAULT '0' COMMENT 'Input token count',
    `output_token`  bigint         DEFAULT '0' COMMENT 'Output token count',
    `total_token`   bigint         DEFAULT '0' COMMENT 'Total token count',
    `ts`            datetime       DEFAULT NULL,
    `fee`           decimal(10, 0) DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY             `idx_agent_id` (`agent_id`),
    KEY             `idx_session_id` (`session_id`),
    KEY             `idx_chat_model_id` (`chat_model_id`),
    KEY             `idx_ts` (`ts`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Token Statistics table';

-- Tool Call Log table
CREATE TABLE IF NOT EXISTS `tool_call_log`
(
    `id`         bigint NOT NULL AUTO_INCREMENT COMMENT 'Log ID',
    `agent_id`   bigint       DEFAULT NULL COMMENT 'Agent ID',
    `session_id` varchar(255) DEFAULT NULL COMMENT 'Session ID',
    `tool_name`  varchar(255) DEFAULT NULL,
    `args`       text COMMENT 'Tool arguments (JSON format)',
    `result`     text,
    `success`    tinyint(1) DEFAULT '1' COMMENT 'Execution result (1: Success, 0: Failed)',
    `start_time` datetime     DEFAULT NULL,
    `end_time`   datetime     DEFAULT NULL,
    `duration`   bigint       DEFAULT '0',
    `ts`         datetime     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY          `idx_agent_id` (`agent_id`),
    KEY          `idx_session_id` (`session_id`),
    KEY          `idx_tool_name` (`tool_name`),
    KEY          `idx_ts` (`ts`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Tool Call Log table';

-- ============================================
-- Channel Table
-- ============================================

CREATE TABLE IF NOT EXISTS `channel` (
    `id`                 BIGINT(20)   NOT NULL AUTO_INCREMENT                       COMMENT 'ID',
    `tenant_id`          BIGINT(20)   NOT NULL DEFAULT 1                             COMMENT 'Tenant ID',
    `name`               VARCHAR(100) NOT NULL                                       COMMENT 'Channel name',
    `type`               VARCHAR(20)  NOT NULL                                       COMMENT 'Channel type: wecom/wechat/feishu/dingtalk/http',
    `agent_id`           BIGINT(20)   NOT NULL                                       COMMENT 'Associated Agent ID',
    `callback_key`       VARCHAR(100) NOT NULL                                       COMMENT 'Callback key (for generating callback URL)',
    `session_id`         VARCHAR(64)  NOT NULL                                       COMMENT 'Immutable session ID (UUID), generated on creation',
    `communication_mode` VARCHAR(20)  NOT NULL DEFAULT 'webhook'                     COMMENT 'Communication mode: webhook/websocket/long_polling',
    `permission_mode`    VARCHAR(20)  NOT NULL DEFAULT 'DEFAULT'                     COMMENT 'Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)',
    `enabled`            TINYINT(1)   NOT NULL DEFAULT 1                             COMMENT 'Auto-start with service (0: No, 1: Yes)',
    `config_json`        TEXT         DEFAULT NULL                                   COMMENT 'Channel-specific configuration JSON',
    `description`        TEXT         DEFAULT NULL                                   COMMENT 'Description',
    `creator`            VARCHAR(100) NOT NULL DEFAULT 'system'                      COMMENT 'Creator',
    `status`             TINYINT(1)   NOT NULL DEFAULT 1                             COMMENT 'Enabled (0: Disabled, 1: Enabled)',
    `active`             TINYINT(1)   NOT NULL DEFAULT 1                             COMMENT 'Logical delete (0: Deleted, 1: Active)',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP             COMMENT 'Creation time',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_key` (`callback_key`),
    KEY `idx_agent_id` (`agent_id`),
    KEY `idx_type_enabled_status_active` (`type`, `enabled`, `status`, `active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Channel configuration table';

-- ============================================
-- API Key Table
-- ============================================

CREATE TABLE IF NOT EXISTS `api_key` (
    `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`              VARCHAR(128) NOT NULL COMMENT 'API Key name',
    `key_type`          VARCHAR(16)  NOT NULL DEFAULT 'TEMPORARY' COMMENT 'Key type: PERMANENT, TEMPORARY or SYSTEM',
    `user_id`           BIGINT       NULL COMMENT 'Associated user ID (for PERMANENT keys)',
    `raw_key_encrypted` VARCHAR(256) NULL COMMENT 'AES-encrypted raw key (for PERMANENT/SYSTEM keys only)',
    `service_name`      VARCHAR(64)  NULL COMMENT 'Service name (for SYSTEM keys, e.g. channel-service)',
    `key_hash`          VARCHAR(64)  NOT NULL UNIQUE COMMENT 'SHA-256 hash of the raw key',
    `key_prefix`        VARCHAR(32)  NOT NULL COMMENT 'Key prefix for display (e.g. hnx_sk_live_xxxx)',
    `scopes`            VARCHAR(512) NOT NULL COMMENT 'Comma-separated scopes (e.g. api:chat,api:session)',
    `tenant_id`         BIGINT       NULL COMMENT 'Tenant ID',
    `rate_limit`        INT          NULL DEFAULT 60 COMMENT 'Rate limit per minute',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Whether enabled (0:disabled, 1:enabled)',
    `expires_at`        DATETIME     NULL COMMENT 'Expiration time',
    `creator`           VARCHAR(64)  NULL COMMENT 'Creator',
    `active`            INT          NOT NULL DEFAULT 1 COMMENT 'Active status (0:deleted, 1:active)',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_name` (`name`),
    INDEX `idx_key_hash` (`key_hash`),
    INDEX `idx_enabled` (`enabled`),
    INDEX `idx_key_type` (`key_type`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_service_name` (`service_name`),
    UNIQUE INDEX `uk_user_permanent` (`user_id`, `key_type`),
    UNIQUE INDEX `uk_service_system` (`service_name`, `key_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Key table (supports PERMANENT / TEMPORARY / SYSTEM types)';

-- ============================================
-- Agent Tool Table
-- ============================================

CREATE TABLE IF NOT EXISTS `agent_tool` (
    `id`                  bigint       NOT NULL AUTO_INCREMENT COMMENT 'Tool ID',
    `tenant_id`           bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`                varchar(100) NOT NULL COMMENT 'Tool identifier name (snake_case, unique per tenant)',
    `display_name`        varchar(200)          DEFAULT NULL COMMENT 'Display name',
    `description`         text COMMENT 'Tool description (sent to LLM)',
    `type`                varchar(20)  NOT NULL COMMENT 'Tool type: BUILTIN / CUSTOM / HTTP',
    `bean_name`           varchar(200)          DEFAULT NULL COMMENT 'Spring Bean name (for BUILTIN/CUSTOM type)',
    `http_url`            varchar(500)          DEFAULT NULL COMMENT 'HTTP request URL (for HTTP type)',
    `http_method`         varchar(10)           DEFAULT 'POST' COMMENT 'HTTP method (for HTTP type)',
    `http_headers`        text COMMENT 'HTTP headers JSON (for HTTP type)',
    `envs`                text COMMENT 'Environment variables JSON: [{"key":"API_KEY","value":"sk-xxx","secret":true}]',
    `required_env_keys`   varchar(1000)         DEFAULT NULL COMMENT 'Required env variable key list, JSON array: ["API_KEY","SECRET"]',
    `input_schema`        text COMMENT 'Input parameter JSON Schema (for HTTP type)',
    `output_schema`       text COMMENT 'Output result JSON Schema (for HTTP type)',
    `read_only`           tinyint(1)            DEFAULT '0' COMMENT 'Is read-only tool (0: No, 1: Yes)',
    `need_confirm`        tinyint(1)            DEFAULT '0' COMMENT 'Requires human confirmation (0: No, 1: Yes)',
    `timeout_seconds`     int                   DEFAULT '30' COMMENT 'Timeout in seconds',
    `status`              tinyint(1)            DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`           tinyint(1)            DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`             varchar(100)          DEFAULT NULL COMMENT 'Creator',
    `active`              tinyint(1)            DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`         datetime              DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`         datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_name` (`tenant_id`, `name`, `active`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent Tool definition table';

-- ============================================
-- Environment Variable Table
-- ============================================

CREATE TABLE IF NOT EXISTS `env_variable` (
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `env_key`     varchar(200) NOT NULL COMMENT 'Environment variable key',
    `env_value`   text         NOT NULL COMMENT 'Environment variable value',
    `description` varchar(500) DEFAULT NULL COMMENT 'Description',
    `sensitive`   tinyint(1)   DEFAULT '0' COMMENT 'Sensitive flag (0: No, 1: Yes)',
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Enabled status (0: Disabled, 1: Enabled)',
    `creator`     varchar(100) DEFAULT NULL COMMENT 'Creator',
    `active`      tinyint(1)   DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time` datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_creator` (`creator`),
    KEY `idx_enabled` (`enabled`),
    UNIQUE KEY `uk_tenant_key_active` (`tenant_id`, `env_key`, `active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Environment Variable';

-- ============================================
-- Mobile Platform Tables
-- ============================================

-- Mobile session table
CREATE TABLE IF NOT EXISTS `mp_session` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT 'ID',
    `user_id`           BIGINT          NOT NULL                 COMMENT 'User ID (FK to sys_user)',
    `session_name`      VARCHAR(255)    NOT NULL DEFAULT ''      COMMENT 'Session name',
    `router_session_id` VARCHAR(128)    NOT NULL DEFAULT ''      COMMENT 'Corresponding router session ID',
    `agent_id`          BIGINT          NOT NULL DEFAULT 0       COMMENT 'Associated Agent ID',
    `status`            TINYINT         NOT NULL DEFAULT 1       COMMENT 'Status (0:archived, 1:active)',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    INDEX `idx_mp_session_user_id` (`user_id`),
    INDEX `idx_mp_session_status` (`status`),
    INDEX `idx_mp_session_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mobile chat sessions';

-- Mobile chat message table
CREATE TABLE IF NOT EXISTS `mp_chat_message` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT 'ID',
    `session_id`       BIGINT       NOT NULL                 COMMENT 'Session ID (FK to mp_session)',
    `role`             VARCHAR(32)  NOT NULL DEFAULT 'user'  COMMENT 'Message role (user/assistant/system)',
    `content`          MEDIUMTEXT                            COMMENT 'Plain text content',
    `segments_json`    MEDIUMTEXT                            COMMENT 'Message segments (JSON array)',
    `token_usage_json` TEXT                                  COMMENT 'Token usage info (JSON object)',
    `image_urls_json`  TEXT                                  COMMENT 'Image URLs (JSON array)',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    PRIMARY KEY (`id`),
    INDEX `idx_mp_chat_message_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mobile chat messages';

-- ============================================
-- Agent Task Tables
-- ============================================

-- Agent Task - Scheduled agent execution
CREATE TABLE IF NOT EXISTS `agent_task` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`       BIGINT DEFAULT 1,
    `name`            VARCHAR(128) NOT NULL COMMENT 'Task name',
    `agent_id`        BIGINT NOT NULL COMMENT 'Associated Agent ID',
    `agent_name`      VARCHAR(128) COMMENT 'Agent name snapshot',
    `prompt`          TEXT NOT NULL COMMENT 'Prompt content for scheduled execution',
    `cron_expression` VARCHAR(128) NOT NULL COMMENT 'Cron expression',
    `task_status`     TINYINT NOT NULL DEFAULT 0 COMMENT '0=paused, 1=running',
    `concurrent`      TINYINT NOT NULL DEFAULT 0 COMMENT '0=no concurrent, 1=allow concurrent',
    `timeout_seconds` INT DEFAULT 300 COMMENT 'Timeout in seconds, default 5 minutes',
    `description`     VARCHAR(512) DEFAULT '' COMMENT 'Task description',
    `is_public`       TINYINT DEFAULT 0,
    `creator`         VARCHAR(64) DEFAULT '',
    `active`          TINYINT DEFAULT 1,
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_agent_id` (`agent_id`),
    UNIQUE KEY `uk_name` (`name`)
) COMMENT='Agent scheduled tasks';

-- Agent Task execution log
CREATE TABLE IF NOT EXISTS `agent_task_log` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         BIGINT NOT NULL COMMENT 'Associated agent_task.id',
    `task_name`       VARCHAR(128) COMMENT 'Task name',
    `prompt`          TEXT COMMENT 'Prompt for this execution',
    `response`        TEXT COMMENT 'Agent response content',
    `session_id`      VARCHAR(64) COMMENT 'Temporary session ID',
    `status`          TINYINT DEFAULT 1 COMMENT '0=failed, 1=success, 2=timeout',
    `error_info`      TEXT COMMENT 'Exception information',
    `token_usage`     VARCHAR(512) COMMENT 'Token usage JSON',
    `start_time`      DATETIME COMMENT 'Start time',
    `end_time`        DATETIME COMMENT 'End time',
    `duration_ms`     BIGINT COMMENT 'Execution duration (milliseconds)',
    `creator`         VARCHAR(64) DEFAULT '',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_create_time` (`create_time`)
) COMMENT='Agent task execution log';

-- Agent task execution guard for multi-instance deployment
CREATE TABLE IF NOT EXISTS `agent_task_execution` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         BIGINT NOT NULL COMMENT 'Associated agent_task.id',
    `trigger_time`    DATETIME NOT NULL COMMENT 'Trigger time (for deduplication)',
    `instance_id`     VARCHAR(128) DEFAULT '' COMMENT 'Execution instance identifier',
    `start_time`      DATETIME COMMENT 'Actual start time',
    `end_time`        DATETIME COMMENT 'Execution end time',
    `status`          TINYINT DEFAULT 0 COMMENT '0=running, 1=success, 2=failed',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_task_trigger` (`task_id`, `trigger_time`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_trigger_time` (`trigger_time`)
) COMMENT='Agent task execution lock (multi-instance dedup)';

-- ============================================
-- Initial Data
-- ============================================

-- Default admin user (password: admin123, BCrypt encrypted)
INSERT IGNORE INTO `sys_user` (`id`, `tenant_id`, `username`, `password`, `nickname`, `email`, `phone`, `gender`, `avatar`,
                        `status`, `is_admin`, `active`, `last_login_time`, `create_time`, `update_time`)
VALUES (1, NULL, 'admin', '$2a$10$esqm4yYiXlpoCQsUOcjGIubYyUU0irYEcLJpCQBpkAtP/Pmm6XphS', 'System Admin',
        'admin@harnax.com', '', 1, '', 1, 1, 1, '2026-05-13 22:48:44', '2026-04-22 16:35:55', '2026-05-13 22:48:44');

-- Default tenant
INSERT IGNORE INTO `tenant` (`id`, `name`, `status`, `creator`, `active`, `create_time`, `update_time`)
VALUES (1, 'Default Organization', 1, 'system', 1, '2026-05-01 11:10:40', '2026-05-07 15:32:08');

-- Admin user-tenant relationship
INSERT IGNORE INTO `user_tenant` (`id`, `user_id`, `tenant_id`, `role`, `status`, `joined_at`)
VALUES (1, 1, 1, 'admin', 1, '2026-05-01 11:10:42');
