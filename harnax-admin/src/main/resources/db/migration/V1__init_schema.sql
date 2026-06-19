-- Harnax Database Initialization Script
-- All tables for the Harnax platform (merged from V1/V2/V3)

-- ============================================
-- System Tables
-- ============================================

-- User table
CREATE TABLE `sys_user`
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
CREATE TABLE `sys_token_blacklist`
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
CREATE TABLE `tenant`
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
CREATE TABLE `user_tenant`
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
CREATE TABLE `mcp_server`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'MCP Server ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`        varchar(100) NOT NULL COMMENT 'MCP Server name',
    `description` text COMMENT 'MCP Server description',
    `type`        varchar(20)  NOT NULL COMMENT 'MCP type (stdio/sse/streamablehttp)',
    `command`     varchar(500)          DEFAULT NULL COMMENT 'Command (for stdio type)',
    `url`         varchar(500)          DEFAULT NULL COMMENT 'URL (for sse/streamablehttp type)',
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
CREATE TABLE `skill_repository`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT 'Repository ID',
    `tenant_id`   bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`        varchar(100) NOT NULL,
    `url`         varchar(500)          DEFAULT NULL,
    `description` text,
    `status`      tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`   tinyint               DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`     varchar(100)          DEFAULT NULL,
    `active`      tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time` datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `branch`      varchar(100)          DEFAULT 'main',
    PRIMARY KEY (`id`),
    KEY           `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skill Repository table';

-- Skill table
CREATE TABLE `skill`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT 'Skill ID',
    `tenant_id`     bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`          varchar(100) NOT NULL,
    `repository_id` bigint       NOT NULL COMMENT 'Repository ID',
    `description`   text,
    `skillmd`       text COMMENT 'skill.md content',
    `resources`     text,
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
CREATE TABLE `model_provider`
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
CREATE TABLE `model`
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
CREATE TABLE `agent`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT 'Agent ID',
    `tenant_id`     bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`          varchar(100) NOT NULL,
    `description`   text,
    `system_prompt` text COMMENT 'System prompt (Markdown format)',
    `model_id`      bigint                DEFAULT NULL COMMENT 'Model ID',
    `mcp_list`      text COMMENT 'MCP list (JSON format)',
    `skill_list`    text COMMENT 'Skill list (JSON format)',
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
CREATE TABLE `session`
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
CREATE TABLE `plan_note`
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
CREATE TABLE `process_log`
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
CREATE TABLE `token_stats`
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
CREATE TABLE `tool_call_log`
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
-- Scheduled Job Tables
-- ============================================

CREATE TABLE `sys_job`
(
    `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT 'Job ID',
    `job_name`        varchar(128) NOT NULL COMMENT 'Job name',
    `job_group`       varchar(64)  NOT NULL DEFAULT 'DEFAULT' COMMENT 'Job group',
    `job_class`       varchar(256) NOT NULL COMMENT 'Fully qualified class name',
    `cron_expression` varchar(128) NOT NULL COMMENT 'Cron expression',
    `job_status`      tinyint      NOT NULL DEFAULT 1 COMMENT 'Status (0-paused, 1-running)',
    `concurrent`      tinyint      NOT NULL DEFAULT 1 COMMENT 'Concurrent execution allowed (0-no, 1-yes)',
    `description`     varchar(512) NOT NULL DEFAULT '' COMMENT 'Job description',
    `is_public`       tinyint      NOT NULL DEFAULT 1 COMMENT 'Public status (0:no, 1:yes)',
    `creator`         varchar(64)  NOT NULL DEFAULT 'system' COMMENT 'Creator',
    `active`          tinyint      NOT NULL DEFAULT 1 COMMENT 'Active status (0-deleted, 1-active)',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_name_group` (`job_name`, `job_group`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Scheduled Job table';

CREATE TABLE `sys_job_log`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT 'Log ID',
    `job_id`         bigint       NOT NULL COMMENT 'Job ID',
    `job_name`       varchar(128) NOT NULL COMMENT 'Job name',
    `job_group`      varchar(64)  NOT NULL DEFAULT 'DEFAULT' COMMENT 'Job group',
    `invoke_target`  varchar(512) NOT NULL DEFAULT '' COMMENT 'Invocation target',
    `job_message`    varchar(512) NOT NULL DEFAULT '' COMMENT 'Execution message',
    `status`         tinyint      NOT NULL DEFAULT 1 COMMENT 'Execution status (0-failure, 1-success)',
    `exception_info` text         DEFAULT NULL COMMENT 'Exception information',
    `start_time`     datetime     DEFAULT NULL COMMENT 'Start time',
    `end_time`       datetime     DEFAULT NULL COMMENT 'End time',
    `creator`        varchar(64)  NOT NULL DEFAULT 'system' COMMENT 'Creator',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    PRIMARY KEY (`id`),
    KEY `idx_job_id` (`job_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Scheduled Job Log table';

-- ============================================
-- Channel Table
-- ============================================

CREATE TABLE `channel` (
    `id`                 BIGINT(20)   NOT NULL AUTO_INCREMENT                       COMMENT 'ID',
    `tenant_id`          BIGINT(20)   NOT NULL DEFAULT 1                             COMMENT '租户ID',
    `name`               VARCHAR(100) NOT NULL                                       COMMENT '通道名称',
    `type`               VARCHAR(20)  NOT NULL                                       COMMENT '渠道类型 wecom/wechat/feishu/dingtalk/http',
    `agent_id`           BIGINT(20)   NOT NULL                                       COMMENT '关联的智能体 ID',
    `callback_key`       VARCHAR(100) NOT NULL                                       COMMENT '回调标识(用于生成回调URL)',
    `session_id`         VARCHAR(64)  NOT NULL                                       COMMENT '不可变的会话ID(UUID), 创建时生成',
    `communication_mode` VARCHAR(20)  NOT NULL DEFAULT 'webhook'                     COMMENT '通信模式 webhook/websocket/long_polling',
    `enabled`            TINYINT(1)   NOT NULL DEFAULT 1                             COMMENT '是否随服务启动自动监听 (0:否,1:是)',
    `config_json`        TEXT         DEFAULT NULL                                   COMMENT '渠道差异化配置 JSON',
    `description`        TEXT         DEFAULT NULL                                   COMMENT '描述',
    `creator`            VARCHAR(100) NOT NULL DEFAULT 'system'                      COMMENT '创建人',
    `status`             TINYINT(1)   NOT NULL DEFAULT 1                             COMMENT '是否启用 (0:禁用,1:启用)',
    `active`             TINYINT(1)   NOT NULL DEFAULT 1                             COMMENT '逻辑删除 (0:已删除,1:正常)',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP             COMMENT '创建时间',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_key` (`callback_key`),
    KEY `idx_agent_id` (`agent_id`),
    KEY `idx_type_enabled_status_active` (`type`, `enabled`, `status`, `active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Channel 通道配置表';

-- ============================================
-- API Key Table
-- ============================================

CREATE TABLE `api_key` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`            VARCHAR(128) NOT NULL UNIQUE COMMENT 'API Key name',
    `key_hash`        VARCHAR(64)  NOT NULL UNIQUE COMMENT 'SHA-256 hash of the raw key',
    `key_prefix`      VARCHAR(32)  NOT NULL COMMENT 'Key prefix for display (e.g. hnx_sk_live_xxxx)',
    `scopes`          VARCHAR(512) NOT NULL COMMENT 'Comma-separated scopes (e.g. api:chat,api:session)',
    `tenant_id`       BIGINT       NULL COMMENT 'Tenant ID',
    `rate_limit`      INT          NULL DEFAULT 60 COMMENT 'Rate limit per minute',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Whether enabled (0:disabled, 1:enabled)',
    `expires_at`      DATETIME     NULL COMMENT 'Expiration time',
    `creator`         VARCHAR(64)  NULL COMMENT 'Creator',
    `active`          INT          NOT NULL DEFAULT 1 COMMENT 'Active status (0:deleted, 1:active)',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_key_hash (`key_hash`),
    INDEX idx_enabled (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='External API Key table';

-- ============================================
-- Initial Data
-- ============================================

-- Default admin user (password: admin123, BCrypt encrypted)
INSERT INTO `sys_user` (`id`, `tenant_id`, `username`, `password`, `nickname`, `email`, `phone`, `gender`, `avatar`,
                        `status`, `is_admin`, `active`, `last_login_time`, `create_time`, `update_time`)
VALUES (1, NULL, 'admin', '$2a$10$esqm4yYiXlpoCQsUOcjGIubYyUU0irYEcLJpCQBpkAtP/Pmm6XphS', 'System Admin',
        'admin@harnax.com', '', 1, '', 1, 1, 1, '2026-05-13 22:48:44', '2026-04-22 16:35:55', '2026-05-13 22:48:44');

-- Default tenant
INSERT INTO `tenant` (`id`, `name`, `status`, `creator`, `active`, `create_time`, `update_time`)
VALUES (1, 'Default Organization', 1, 'system', 1, '2026-05-01 11:10:40', '2026-05-07 15:32:08');

-- Admin user-tenant relationship
INSERT INTO `user_tenant` (`id`, `user_id`, `tenant_id`, `role`, `status`, `joined_at`)
VALUES (1, 1, 1, 'admin', 1, '2026-05-01 11:10:42');

-- Default feishu channel
INSERT INTO `channel`
(`tenant_id`, `name`, `type`, `agent_id`, `callback_key`, `session_id`, `communication_mode`, `enabled`, `config_json`, `description`, `creator`, `status`, `active`)
VALUES
    (1, '飞书助手', 'feishu', 1, 'feishu-ws-20260605', 'ch-feishu-ws-001', 'websocket', 1,
     '{"appId": "cli_a978496aac395cbd", "appSecret": "4HwsyncAKtIttiEIvcRIqhMlGvNtpTFg"}',
     '飞书 WebSocket 长连接渠道', 'system', 1, 1);
