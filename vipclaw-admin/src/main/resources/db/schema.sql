-- 创建数据库
CREATE DATABASE IF NOT EXISTS `vipclaw` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `vipclaw`;

-- 用户表
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `nickname` VARCHAR(50) NOT NULL COMMENT '昵称',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) NOT NULL COMMENT '手机号',
    `gender` TINYINT(2) DEFAULT 2 COMMENT '性别 (0:女 1:男 2:未知)',
    `avatar` VARCHAR(255) DEFAULT '' COMMENT '头像 URL',
    `status` TINYINT(2) DEFAULT 1 COMMENT '状态 (0:禁用 1:使用)',
    `is_admin` TINYINT(2) DEFAULT 0 COMMENT '是否是管理员（0:否，1:是）',
    `active` TINYINT(2) DEFAULT 1 COMMENT '状态 (0:已删除 1:未删除)',
    `last_login_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次登陆时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- MCP 服务表
DROP TABLE IF EXISTS `mcp_server`;
CREATE TABLE `mcp_server`
(
    `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'MCP ID',
    `name`        VARCHAR(100) NOT NULL COMMENT 'MCP 名称',
    `description` TEXT         DEFAULT NULL COMMENT 'MCP 描述',
    `type`        VARCHAR(20)  NOT NULL COMMENT 'MCP 类型（stdio/sse/streamablehttp）',
    `command`     VARCHAR(500) DEFAULT NULL COMMENT '执行命令（仅 stdio 类型生效）',
    `url`         VARCHAR(500) DEFAULT NULL COMMENT '服务地址（sse/streamablehttp 类型生效）',
    `status`      TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`      TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务表';

-- 模型服务商表
DROP TABLE IF EXISTS `model_provider`;
CREATE TABLE `model_provider`
(
    `id`           BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`         VARCHAR(50)  NOT NULL COMMENT '服务商名称（dashscope/openai/ollama）',
    `display_name` VARCHAR(100) NOT NULL COMMENT '显示名称',
    `api_key`      VARCHAR(500) DEFAULT NULL COMMENT 'API 密钥',
    `base_url`     VARCHAR(500) DEFAULT NULL COMMENT 'API 地址',
    `status`       TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`       TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型服务商表';

-- 模型表
DROP TABLE IF EXISTS `model`;
CREATE TABLE `model`
(
    `id`               BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`             VARCHAR(100) NOT NULL COMMENT '名称',
    `model_name`       VARCHAR(100) NOT NULL COMMENT '模型名称',
    `provider_id`      BIGINT(20)   NOT NULL COMMENT '模型供应商ID',
    `description`      TEXT         DEFAULT NULL COMMENT '描述',
    `model_type`       VARCHAR(20)  NOT NULL COMMENT '模型类型（chat/embedding）',
    `support_internet` TINYINT(1)   DEFAULT 0 COMMENT '是否支持联网（0:否，1:是）',
    `support_reasoning` TINYINT(1)  DEFAULT 0 COMMENT '是否支持推理（0:否，1:是）',
    `support_tool`     TINYINT(1)   DEFAULT 0 COMMENT '是否支持工具（0:否，1:是）',
    `support_mcp`      TINYINT(1)   DEFAULT 0 COMMENT '是否支持MCP（0:否，1:是）',
    `support_vision`   TINYINT(1)   DEFAULT 0 COMMENT '是否支持视觉（0:否，1:是）',
    `price`            DECIMAL(10, 4) DEFAULT 0.0000 COMMENT '价格（元/百万token）',
    `status`           TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`           TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_provider_id` (`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型表';

-- 定时任务表
DROP TABLE IF EXISTS `sys_job`;
CREATE TABLE `sys_job` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `job_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `job_group` VARCHAR(100) NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
    `job_class` VARCHAR(255) NOT NULL COMMENT '执行类全路径',
    `cron_expression` VARCHAR(100) NOT NULL COMMENT 'Cron执行表达式',
    `job_status` TINYINT(1) DEFAULT 0 COMMENT '状态（0-暂停，1-运行）',
    `concurrent` TINYINT(1) DEFAULT 1 COMMENT '是否允许并发（0-禁止，1-允许）',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '任务描述',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0-已删除，1-未删除）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_name_group` (`job_name`, `job_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务表';

-- 定时任务日志表
DROP TABLE IF EXISTS `sys_job_log`;
CREATE TABLE `sys_job_log` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `job_id` BIGINT(20) NOT NULL COMMENT '任务ID',
    `job_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `job_group` VARCHAR(100) NOT NULL COMMENT '任务组名',
    `invoke_target` VARCHAR(255) DEFAULT NULL COMMENT '调用目标',
    `job_message` VARCHAR(500) DEFAULT NULL COMMENT '执行信息',
    `status` TINYINT(1) DEFAULT 0 COMMENT '执行状态（0-失败，1-成功）',
    `exception_info` TEXT DEFAULT NULL COMMENT '异常信息',
    `start_time` DATETIME DEFAULT NULL COMMENT '开始时间',
    `end_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_id` (`job_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务日志表';

-- 技能仓库表
DROP TABLE IF EXISTS `skill_repository`;
CREATE TABLE `skill_repository`
(
    `id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '仓库名称',
    `url`         VARCHAR(500) DEFAULT NULL COMMENT '仓库地址',
    `branch`      VARCHAR(100) DEFAULT 'main' COMMENT '分支名称',
    `description` TEXT         DEFAULT NULL COMMENT '仓库描述',
    `status`      TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`      TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能仓库表';

-- 技能表
DROP TABLE IF EXISTS `skill`;
CREATE TABLE `skill`
(
    `id`            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`          VARCHAR(100) NOT NULL COMMENT '技能名称',
    `repository_id` BIGINT(20)   NOT NULL COMMENT '仓库ID',
    `description`   TEXT         DEFAULT NULL COMMENT '技能描述',
    `skillmd`       TEXT         DEFAULT NULL COMMENT 'skill.md 内容',
    `resources`     TEXT         DEFAULT NULL COMMENT '资源信息',
    `status`        TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`        TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_repository_id` (`name`, `repository_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能表';

-- 智能体表
DROP TABLE IF EXISTS `agent`;
CREATE TABLE `agent`
(
    `id`            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`          VARCHAR(100) NOT NULL COMMENT '智能体名称',
    `description`   TEXT         DEFAULT NULL COMMENT '智能体描述',
    `system_prompt` TEXT         DEFAULT NULL COMMENT '系统提示词（支持 Markdown）',
    `model_id`      BIGINT(20)   DEFAULT NULL COMMENT '对话模型 ID',
    `mcp_list`      TEXT         DEFAULT NULL COMMENT 'MCP 服务列表（JSON 格式）',
    `skill_list`    TEXT         DEFAULT NULL COMMENT '技能列表（JSON 格式）',
    `owner`         VARCHAR(100) DEFAULT NULL COMMENT '所有者',
    `status`        TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`        TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体表';

-- Channel 通道表
DROP TABLE IF EXISTS `channel`;
CREATE TABLE `channel`
(
    `id`               BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name`             VARCHAR(100) NOT NULL COMMENT '通道名称',
    `type`             VARCHAR(20)  NOT NULL COMMENT '类型(wecom/feishu/dingtalk/http)',
    `agent_id`         BIGINT(20)   NOT NULL COMMENT '关联的智能体ID',
    `webhook_url`      VARCHAR(500) DEFAULT NULL COMMENT '推送地址',
    `token`            VARCHAR(500) DEFAULT NULL COMMENT '验证Token',
    `encoding_aes_key` VARCHAR(500) DEFAULT NULL COMMENT '加密密钥(企业微信)',
    `app_id`           VARCHAR(100) DEFAULT NULL COMMENT '应用ID(飞书/钉钉)',
    `app_secret`       VARCHAR(500) DEFAULT NULL COMMENT '应用密钥',
    `callback_key`     VARCHAR(100) NOT NULL COMMENT '回调标识(用于生成回调URL)',
    `description`      TEXT         DEFAULT NULL COMMENT '描述',
    `status`           TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active`           TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_key` (`callback_key`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Channel通道表';

-- Channel 消息记录表
DROP TABLE IF EXISTS `channel_message`;
CREATE TABLE `channel_message`
(
    `id`          BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `channel_id`  BIGINT(20)    NOT NULL COMMENT '通道ID',
    `session_id`  VARCHAR(100)  NOT NULL COMMENT '会话标识(用户/群组)',
    `message_id`  VARCHAR(100)  DEFAULT NULL COMMENT '原始消息ID',
    `role`        VARCHAR(20)   NOT NULL COMMENT '角色(user/assistant)',
    `content`     TEXT          NOT NULL COMMENT '消息内容',
    `sender_id`   VARCHAR(100)  DEFAULT NULL COMMENT '发送者ID',
    `sender_name` VARCHAR(100)  DEFAULT NULL COMMENT '发送者名称',
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_channel_session` (`channel_id`, `session_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Channel消息记录表';
