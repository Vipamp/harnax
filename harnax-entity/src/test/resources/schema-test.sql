-- 测试数据库初始化脚本
-- 用于 Mapper 层集成测试

-- ============================================
-- 1. 用户表
-- ============================================
CREATE TABLE IF NOT EXISTS `sys_user` (
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
    `last_login_time` DATETIME DEFAULT NULL COMMENT '最近一次登陆时间，初始为 NULL',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `phone`, `gender`, `avatar`, `status`, `is_admin`, `active`) VALUES
('testuser1', 'password123', '测试用户1', 'test1@example.com', '13800138001', 1, 'https://example.com/avatar1.jpg', 1, 0, 1),
('testuser2', 'password123', '测试用户2', 'test2@example.com', '13800138002', 2, 'https://example.com/avatar2.jpg', 1, 0, 1),
('testuser3', 'password123', '测试用户3', 'test3@example.com', '13800138003', 0, 'https://example.com/avatar3.jpg', 0, 0, 1),
('admin', 'admin123', '管理员', 'admin@example.com', '13800138000', 1, 'https://example.com/admin.jpg', 1, 1, 1),
('deleted_user', 'password123', '已删除用户', 'deleted@example.com', '13800138099', 2, NULL, 1, 0, 0);

-- ============================================
-- 2. 模型服务商表
-- ============================================
CREATE TABLE IF NOT EXISTS `model_provider` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `type` VARCHAR(50) NOT NULL COMMENT '服务商类型(dashscope/openai/ollama)',
    `name` VARCHAR(100) NOT NULL COMMENT '名称',
    `description` TEXT DEFAULT NULL COMMENT '描述',
    `api_key` VARCHAR(500) DEFAULT NULL COMMENT 'API 密钥',
    `base_url` VARCHAR(500) DEFAULT NULL COMMENT 'API 地址',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用(0:禁用,1:启用)',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开(0:否,1:是)',
    `creator` VARCHAR(100) NOT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用(0:被删除,1:可用)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型服务商表';

INSERT INTO `model_provider` (`type`, `name`, `api_key`, `base_url`, `status`, `is_public`, `creator`, `active`) VALUES
('dashscope', '阿里云百炼', 'sk-test-key-12345', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 1, 1, 'admin', 1),
('openai', 'OpenAI', 'sk-openai-key-67890', 'https://api.openai.com/v1', 1, 1, 'admin', 1),
('ollama', 'Ollama', '', 'http://localhost:11434', 1, 1, 'admin', 1),
('deleted_provider', '已删除服务商', 'sk-deleted', 'https://api.deleted.com', 1, 1, 'admin', 0);

-- ============================================
-- 3. 模型表
-- ============================================
CREATE TABLE IF NOT EXISTS `model` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '名称',
    `model_name` VARCHAR(100) NOT NULL COMMENT '模型名称',
    `provider_id` BIGINT(20) NOT NULL COMMENT '服务商 ID',
    `description` TEXT DEFAULT NULL COMMENT '模型描述',
    `model_type` VARCHAR(20) NOT NULL DEFAULT 'chat' COMMENT '模型类型（chat/embedding）',
    `support_internet` TINYINT(1) DEFAULT 0 COMMENT '是否支持联网（0:否，1:是）',
    `support_reasoning` TINYINT(1) DEFAULT 0 COMMENT '是否支持推理（0:否，1:是）',
    `support_tool` TINYINT(1) DEFAULT 0 COMMENT '是否支持工具（0:否，1:是）',
    `support_mcp` TINYINT(1) DEFAULT 0 COMMENT '是否支持MCP（0:否，1:是）',
    `support_vision` TINYINT(1) DEFAULT 0 COMMENT '是否支持视觉（0:否，1:是）',
    `price` DECIMAL(10, 4) DEFAULT 0.0000 COMMENT '价格',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开（0:否，1:是）',
    `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_provider_id` (`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型表';

INSERT INTO `model` (`name`, `model_name`, `provider_id`, `description`, `model_type`, `support_internet`, `support_reasoning`, `support_tool`, `support_mcp`, `support_vision`, `price`, `status`, `is_public`, `creator`, `active`) VALUES
('GPT-4', 'gpt-4', 1, 'GPT-4 模型', 'chat', 1, 1, 1, 1, 0, 0.0300, 1, 1, 'admin', 1),
('GPT-3.5 Turbo', 'gpt-3.5-turbo', 1, 'GPT-3.5 Turbo 模型', 'chat', 0, 0, 1, 0, 0, 0.0020, 1, 1, 'admin', 1),
('Claude 3', 'claude-3', 2, 'Claude 3 模型', 'chat', 0, 1, 1, 1, 0, 0.0250, 1, 1, 'admin', 1),
('Qwen-Turbo', 'qwen-turbo', 3, '通义千问 Turbo', 'chat', 0, 0, 1, 0, 0, 0.0010, 1, 1, 'admin', 1),
('Deleted Model', 'deleted-model', 1, '已删除模型', 'chat', 0, 0, 0, 0, 0, 0.0100, 1, 1, 'admin', 0);

-- ============================================
-- 4. MCP 服务表
-- ============================================
CREATE TABLE IF NOT EXISTS `mcp_server` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT 'MCP 服务名称',
    `description` TEXT DEFAULT NULL COMMENT 'MCP 服务描述',
    `type` VARCHAR(20) NOT NULL DEFAULT 'streamablehttp' COMMENT 'MCP 类型（stdio/sse/streamablehttp）',
    `command` VARCHAR(500) DEFAULT NULL COMMENT '执行命令（仅 stdio 类型）',
    `url` VARCHAR(500) DEFAULT NULL COMMENT '服务地址（sse/streamablehttp 类型）',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开（0:否，1:是）',
    `creator` VARCHAR(100) NOT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务表';

INSERT INTO `mcp_server` (`name`, `description`, `type`, `command`, `url`, `status`, `is_public`, `creator`, `active`) VALUES
('Weather MCP', '天气查询服务', 'streamablehttp', NULL, 'http://localhost:8081/weather', 1, 1, 'admin', 1),
('Calculator MCP', '计算器服务', 'stdio', 'python calculator.py', NULL, 1, 1, 'admin', 1),
('File MCP', '文件操作服务', 'sse', NULL, 'http://localhost:8083/file', 1, 1, 'admin', 1),
('Deleted MCP', '已删除服务', 'streamablehttp', NULL, 'http://localhost:8084/deleted', 1, 1, 'admin', 0);

-- ============================================
-- 5. 技能仓库表
-- ============================================
CREATE TABLE IF NOT EXISTS `skill_repository` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '仓库名称',
    `url` VARCHAR(500) NOT NULL COMMENT '仓库 URL',
    `branch` VARCHAR(100) NOT NULL DEFAULT 'main' COMMENT '分支名称',
    `description` TEXT DEFAULT NULL COMMENT '仓库描述',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开（0:否，1:是）',
    `creator` VARCHAR(100) NOT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能仓库表';

INSERT INTO `skill_repository` (`name`, `url`, `branch`, `description`, `status`, `is_public`, `creator`, `active`) VALUES
('Default Repository', 'https://github.com/agnetix/skills', 'main', '默认技能仓库', 1, 1, 'admin', 1),
('Advanced Skills', 'https://github.com/agnetix/advanced-skills', 'master', '高级技能仓库', 1, 1, 'admin', 1),
('Deleted Repository', 'https://github.com/agnetix/deleted', 'main', '已删除仓库', 1, 1, 'admin', 0);

-- ============================================
-- 6. 技能表
-- ============================================
CREATE TABLE IF NOT EXISTS `skill` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '技能名称',
    `repository_id` BIGINT(20) NOT NULL COMMENT '所属仓库 ID',
    `description` TEXT DEFAULT NULL COMMENT '技能描述',
    `skillmd` TEXT DEFAULT NULL COMMENT 'skill.md 内容',
    `resources` TEXT DEFAULT NULL COMMENT '资源信息',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开（0:否，1:是）',
    `creator` VARCHAR(100) NOT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_repository_id` (`name`, `repository_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能表';

INSERT INTO `skill` (`name`, `repository_id`, `description`, `skillmd`, `resources`, `status`, `is_public`, `creator`, `active`) VALUES
('web-search', 1, '网络搜索技能', '# Web Search\n搜索网络信息', '{}', 1, 1, 'admin', 1),
('code-review', 1, '代码审查技能', '# Code Review\n审查代码质量', '{}', 1, 1, 'admin', 1),
('data-analysis', 2, '数据分析技能', '# Data Analysis\n分析数据', '{}', 1, 1, 'admin', 1),
('deleted-skill', 1, '已删除技能', '# Deleted', '{}', 1, 1, 'admin', 0);

-- ============================================
-- 7. 智能体表
-- ============================================
CREATE TABLE IF NOT EXISTS `agent` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '智能体名称',
    `description` TEXT DEFAULT NULL COMMENT '智能体描述',
    `system_prompt` TEXT DEFAULT NULL COMMENT '系统提示词（支持 Markdown）',
    `model_id` BIGINT(20) DEFAULT NULL COMMENT '对话模型 ID',
    `mcp_list` TEXT DEFAULT NULL COMMENT 'MCP 服务列表（JSON 格式）',
    `skill_list` TEXT DEFAULT NULL COMMENT '技能列表（JSON 格式）',
    `owner` VARCHAR(100) DEFAULT NULL COMMENT '所有者',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开（0:否，1:是）',
    `creator` VARCHAR(100) DEFAULT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体表';

INSERT INTO `agent` (`name`, `description`, `system_prompt`, `model_id`, `mcp_list`, `skill_list`, `owner`, `status`, `is_public`, `creator`, `active`) VALUES
('Test Agent 1', '测试智能体1', '你是一个助手', 1, '[{"id":1,"enable_skip":"false"}]', '[1,2]', 'testuser1', 1, 1, 'testuser1', 1),
('Test Agent 2', '测试智能体2', '你是一个编程助手', 2, '[]', '[1]', 'testuser1', 1, 0, 'testuser1', 1),
('Test Agent 3', '测试智能体3', '你是一个翻译助手', 3, '[{"id":2,"enable_skip":"true"}]', '[]', 'testuser2', 0, 1, 'testuser2', 1),
('Deleted Agent', '已删除智能体', '已删除', 1, '[]', '[]', 'testuser1', 1, 1, 'testuser1', 0);

-- ============================================
-- 8. Channel 通道表
-- ============================================
CREATE TABLE IF NOT EXISTS `channel` (
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

INSERT INTO `channel` (`name`, `type`, `agent_id`, `callback_key`, `session_id`, `communication_mode`, `enabled`, `config_json`, `description`, `status`, `active`) VALUES
('Test WeCom Channel', 'wecom', 1, 'test-wecom', 'sess-wecom-001', 'webhook', 1, '{"webhookUrl":"http://localhost:8080/webhook/wecom","token":"test-token","encodingAesKey":"aes-key-123"}', '企业微信测试通道', 1, 1),
('Test HTTP Channel', 'http', 2, 'test-http', 'sess-http-001', 'webhook', 1, '{"webhookUrl":"http://localhost:8080/webhook/http","token":"http-token"}', 'HTTP测试通道', 1, 1),
('Test Feishu Channel', 'feishu', 1, 'test-feishu', 'sess-feishu-001', 'websocket', 1, '{"appId":"app-id-123","appSecret":"app-secret-123"}', '飞书测试通道', 1, 1),
('Deleted Channel', 'wecom', 1, 'test-deleted', 'sess-deleted-001', 'webhook', 1, '{"webhookUrl":"http://localhost:8080/webhook/deleted","token":"deleted-token"}', '已删除通道', 1, 0);

-- ============================================
-- 9. 会话表
-- ============================================
CREATE TABLE IF NOT EXISTS `session` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `session_id` VARCHAR(100) NOT NULL COMMENT '会话 ID',
    `agent_id` BIGINT(20) NOT NULL COMMENT '智能体 ID',
    `title` VARCHAR(200) DEFAULT NULL COMMENT '会话标题',
    `session_description` TEXT DEFAULT NULL COMMENT '会话描述',
    `user_id` BIGINT(20) DEFAULT NULL COMMENT '用户 ID',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态（0:已结束，1:进行中）',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

INSERT INTO `session` (`session_id`, `agent_id`, `title`, `session_description`, `user_id`, `status`, `active`) VALUES
('session-001', 1, '测试会话1', '第一个测试会话', 1, 1, 1),
('session-002', 1, '测试会话2', '第二个测试会话', 1, 1, 1),
('session-003', 2, '测试会话3', '第三个测试会话', 2, 0, 1),
('session-004', 3, '测试会话4', '第四个测试会话', 1, 1, 1),
('session-deleted', 1, '已删除会话', '已删除', 1, 1, 0);

-- ============================================
-- 10. 定时任务表
-- ============================================
CREATE TABLE IF NOT EXISTS `sys_job` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `job_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `job_group` VARCHAR(50) NOT NULL COMMENT '任务组名',
    `job_class` VARCHAR(255) NOT NULL COMMENT '执行类全路径',
    `cron_expression` VARCHAR(50) NOT NULL COMMENT 'Cron执行表达式',
    `job_status` TINYINT(1) DEFAULT 1 COMMENT '状态（0-暂停，1-运行）',
    `concurrent` TINYINT(1) DEFAULT 1 COMMENT '是否允许并发（0-禁止，1-允许）',
    `description` TEXT DEFAULT NULL COMMENT '任务描述',
    `is_public` TINYINT(1) DEFAULT 1 COMMENT '是否公开（0:否，1:是）',
    `creator` VARCHAR(100) NOT NULL COMMENT '创建人',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0-已删除，1-未删除）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_job_name_group` (`job_name`, `job_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务表';

INSERT INTO `sys_job` (`job_name`, `job_group`, `job_class`, `cron_expression`, `job_status`, `concurrent`, `description`, `is_public`, `creator`, `active`) VALUES
('Data Sync Job', 'DEFAULT', 'com.agnetix.harnax.admin.job.DataSyncJob', '0 0 * * * ?', 1, 1, '数据同步任务', 1, 'admin', 1),
('Report Job', 'DEFAULT', 'com.agnetix.harnax.admin.job.ReportJob', '0 0 8 * * ?', 1, 0, '报表生成任务', 1, 'admin', 1),
('Cleanup Job', 'SYSTEM', 'com.agnetix.harnax.admin.job.CleanupJob', '0 0 2 * * ?', 0, 1, '清理任务', 1, 'admin', 1),
('Deleted Job', 'DEFAULT', 'com.agnetix.harnax.admin.job.DeletedJob', '0 0 12 * * ?', 1, 1, '已删除任务', 1, 'admin', 0);

-- ============================================
-- 11. 定时任务日志表
-- ============================================
CREATE TABLE IF NOT EXISTS `sys_job_log` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `job_id` BIGINT(20) NOT NULL COMMENT '任务ID',
    `job_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `job_group` VARCHAR(50) NOT NULL COMMENT '任务组名',
    `job_class` VARCHAR(255) NOT NULL COMMENT '执行类全路径',
    `message` TEXT DEFAULT NULL COMMENT '日志信息',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态（0-失败，1-成功）',
    `start_time` DATETIME DEFAULT NULL COMMENT '开始时间',
    `end_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_id` (`job_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务日志表';

INSERT INTO `sys_job_log` (`job_id`, `job_name`, `job_group`, `job_class`, `message`, `status`, `start_time`, `end_time`) VALUES
(1, 'Data Sync Job', 'DEFAULT', 'com.agnetix.harnax.admin.job.DataSyncJob', '执行成功', 1, '2026-04-25 10:00:00', '2026-04-25 10:00:05'),
(1, 'Data Sync Job', 'DEFAULT', 'com.agnetix.harnax.admin.job.DataSyncJob', '执行成功', 1, '2026-04-25 11:00:00', '2026-04-25 11:00:03'),
(2, 'Report Job', 'DEFAULT', 'com.agnetix.harnax.admin.job.ReportJob', '执行失败：数据库连接超时', 0, '2026-04-25 08:00:00', '2026-04-25 08:05:00');

-- ============================================
-- 12. PlanNote 表
-- ============================================
CREATE TABLE IF NOT EXISTS `plan_note` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `session_id` VARCHAR(128) NOT NULL COMMENT '会话ID',
    `plan_id` VARCHAR(128) NOT NULL COMMENT '计划ID',
    `name` VARCHAR(256) NOT NULL COMMENT '计划名称',
    `description` TEXT COMMENT '计划描述',
    `expected_outcome` TEXT COMMENT '预期结果',
    `subtasks` TEXT COMMENT '子任务列表（JSON格式）',
    `created_at` VARCHAR(64) COMMENT '创建时间',
    `finished_at` VARCHAR(64) COMMENT '完成时间',
    `cost_timeseconds` BIGINT DEFAULT 0 COMMENT '耗时（秒）',
    `status` VARCHAR(32) DEFAULT 'TODO' COMMENT '状态（TODO, IN_PROGRESS, DONE, ABANDONED）',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_plan_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PlanNote表';

INSERT INTO `plan_note` (`session_id`, `plan_id`, `name`, `description`, `expected_outcome`, `subtasks`, `created_at`, `finished_at`, `cost_timeseconds`, `status`) VALUES
('session-001', 'plan-001', '数据分析计划', '分析用户数据', '生成分析报告', '[{"name":"数据收集","status":"DONE"},{"name":"数据分析","status":"IN_PROGRESS"}]', '2026-04-25 10:00:00', NULL, 300, 'IN_PROGRESS'),
('session-001', 'plan-002', '数据清洗计划', '清洗原始数据', '输出清洗后的数据', '[{"name":"数据导入","status":"DONE"},{"name":"数据清洗","status":"DONE"}]', '2026-04-25 09:00:00', '2026-04-25 09:30:00', 1800, 'DONE'),
('session-002', 'plan-003', '报表生成计划', '生成月度报表', 'PDF格式报表', '[]', '2026-04-25 11:00:00', NULL, 0, 'TODO');

-- ============================================
-- 13. 处理日志表
-- ============================================
CREATE TABLE IF NOT EXISTS `process_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `agent_id` BIGINT DEFAULT NULL COMMENT '智能体 ID',
  `agent_name` VARCHAR(255) DEFAULT NULL COMMENT '智能体名称',
  `session_id` VARCHAR(255) DEFAULT NULL COMMENT '会话 ID',
  `message` TEXT DEFAULT NULL COMMENT '日志消息',
  `log_type` VARCHAR(20) DEFAULT 'INFO' COMMENT '日志类型 (INFO/WARN/ERROR)',
  `stack_trace` TEXT DEFAULT NULL COMMENT '异常堆栈信息',
  `ts` DATETIME DEFAULT NULL COMMENT '时间戳',
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_log_type` (`log_type`),
  KEY `idx_ts` (`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='处理日志表';

INSERT INTO `process_log` (`agent_id`, `agent_name`, `session_id`, `message`, `log_type`, `stack_trace`, `ts`) VALUES
(1, 'Test Agent 1', 'session-001', '开始处理请求', 'INFO', NULL, '2026-04-25 10:00:00'),
(1, 'Test Agent 1', 'session-001', '处理完成', 'INFO', NULL, '2026-04-25 10:00:05'),
(2, 'Test Agent 2', 'session-002', '发生错误：超时', 'ERROR', 'java.util.concurrent.TimeoutException', '2026-04-25 11:00:00');

-- ============================================
-- 14. 工具调用日志表
-- ============================================
CREATE TABLE IF NOT EXISTS `tool_call_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `agent_id` BIGINT DEFAULT NULL COMMENT '智能体 ID',
  `session_id` VARCHAR(255) DEFAULT NULL COMMENT '会话 ID',
  `tool_name` VARCHAR(255) DEFAULT NULL COMMENT '工具名称',
  `args` TEXT DEFAULT NULL COMMENT '工具参数（JSON 格式）',
  `result` TEXT DEFAULT NULL COMMENT '工具执行结果',
  `success` TINYINT(1) DEFAULT 1 COMMENT '是否成功（1-成功，0-失败）',
  `start_time` DATETIME DEFAULT NULL COMMENT '开始时间戳',
  `end_time` DATETIME DEFAULT NULL COMMENT '结束时间戳',
  `duration` BIGINT DEFAULT 0 COMMENT '执行耗时（毫秒）',
  `ts` DATETIME DEFAULT NULL COMMENT '时间戳',
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_tool_name` (`tool_name`),
  KEY `idx_ts` (`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用日志表';

INSERT INTO `tool_call_log` (`agent_id`, `session_id`, `tool_name`, `args`, `result`, `success`, `start_time`, `end_time`, `duration`, `ts`) VALUES
(1, 'session-001', 'web-search', '{"query":"AI latest news"}', '{"results":[]}', 1, '2026-04-25 10:00:01', '2026-04-25 10:00:03', 2000, '2026-04-25 10:00:03'),
(1, 'session-001', 'code-review', '{"code":"print(1)"}', '{"issues":[]}', 1, '2026-04-25 10:00:04', '2026-04-25 10:00:05', 1000, '2026-04-25 10:00:05'),
(2, 'session-002', 'web-search', '{"query":"test"}', '{"error":"timeout"}', 0, '2026-04-25 11:00:00', '2026-04-25 11:00:30', 30000, '2026-04-25 11:00:30');

-- ============================================
-- 15. Token 统计表
-- ============================================
CREATE TABLE IF NOT EXISTS `token_stats` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `session_id` VARCHAR(100) DEFAULT NULL COMMENT '会话 ID',
    `agent_id` BIGINT(20) DEFAULT NULL COMMENT '智能体 ID',
    `model_id` BIGINT(20) DEFAULT NULL COMMENT '模型 ID',
    `user_id` BIGINT(20) DEFAULT NULL COMMENT '用户 ID',
    `prompt_tokens` INT DEFAULT 0 COMMENT '输入 token 数',
    `completion_tokens` INT DEFAULT 0 COMMENT '输出 token 数',
    `total_tokens` INT DEFAULT 0 COMMENT '总 token 数',
    `cost` DECIMAL(10, 4) DEFAULT 0.0000 COMMENT '费用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_agent_id` (`agent_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token 统计表';

INSERT INTO `token_stats` (`session_id`, `agent_id`, `model_id`, `user_id`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `cost`) VALUES
('session-001', 1, 1, 1, 100, 50, 150, 0.0045),
('session-001', 1, 1, 1, 200, 100, 300, 0.0090),
('session-002', 1, 1, 1, 150, 75, 225, 0.0068),
('session-003', 2, 2, 2, 300, 150, 450, 0.0060),
('session-004', 3, 3, 1, 250, 120, 370, 0.0037);

-- ============================================
-- 16. Token 黑名单表
-- ============================================
CREATE TABLE IF NOT EXISTS `sys_token_blacklist` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `token` TEXT NOT NULL COMMENT 'JWT Token',
    `token_hash` VARCHAR(64) NOT NULL COMMENT 'Token 的 SHA256 哈希值',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `user_id` BIGINT(20) NOT NULL COMMENT '用户 ID',
    `reason` VARCHAR(50) DEFAULT 'logout' COMMENT '加入黑名单原因',
    `expire_time` DATETIME NOT NULL COMMENT 'Token 过期时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_ip` VARCHAR(50) DEFAULT NULL COMMENT '操作 IP',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token 黑名单表';

INSERT INTO `sys_token_blacklist` (`token`, `token_hash`, `username`, `user_id`, `reason`, `expire_time`, `create_ip`) VALUES
('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test1', 'hash001', 'testuser1', 1, 'logout', '2026-04-26 10:00:00', '192.168.1.100'),
('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test2', 'hash002', 'testuser2', 2, 'logout', '2026-04-26 11:00:00', '192.168.1.101'),
('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.admin', 'hash003', 'admin', 4, 'force_logout', '2026-04-26 12:00:00', '192.168.1.1');

-- ============================================
-- 17. Tenant table
-- ============================================
CREATE TABLE IF NOT EXISTS `tenant` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'Tenant ID',
    `name` VARCHAR(100) NOT NULL COMMENT 'Tenant name',
    `status` TINYINT(2) DEFAULT 1 COMMENT 'Status (0:disabled, 1:enabled)',
    `creator` VARCHAR(100) NOT NULL COMMENT 'Creator',
    `active` TINYINT(2) DEFAULT 1 COMMENT 'Status (0:deleted, 1:active)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tenant table';

-- ============================================
-- 18. User-Tenant Association table
-- ============================================
CREATE TABLE IF NOT EXISTS `user_tenant` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `user_id` BIGINT(20) NOT NULL COMMENT 'User ID',
    `tenant_id` BIGINT(20) NOT NULL COMMENT 'Tenant ID',
    `role` VARCHAR(50) NOT NULL DEFAULT 'member' COMMENT 'Role (admin/member)',
    `status` TINYINT(2) DEFAULT 1 COMMENT 'Status (0:disabled, 1:enabled)',
    `joined_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Join time',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tenant` (`user_id`, `tenant_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-Tenant Association table';
