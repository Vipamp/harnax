-- 测试数据库初始化脚本
-- 创建用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(100) NOT NULL COMMENT '密码',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `gender` TINYINT(2) DEFAULT 2 COMMENT '性别 (0:女 1:男 2:未知)',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像 URL',
    `status` TINYINT(2) DEFAULT 1 COMMENT '状态 (0:禁用 1:使用)',
    `is_admin` TINYINT(2) DEFAULT 0 COMMENT '是否是管理员（0:否，1:是）',
    `active` TINYINT(2) DEFAULT 1 COMMENT '状态 (0:已删除 1:未删除)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 插入测试数据
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `phone`, `gender`, `avatar`, `status`, `is_admin`, `active`) VALUES
('testuser1', 'password123', '测试用户1', 'test1@example.com', '13800138001', 1, 'https://example.com/avatar1.jpg', 1, 0, 1),
('testuser2', 'password123', '测试用户2', 'test2@example.com', '13800138002', 2, 'https://example.com/avatar2.jpg', 1, 0, 1),
('testuser3', 'password123', '测试用户3', 'test3@example.com', '13800138003', 0, 'https://example.com/avatar3.jpg', 0, 0, 1),
('admin', 'admin123', '管理员', 'admin@example.com', '13800138000', 1, 'https://example.com/admin.jpg', 1, 1, 1),
('deleted_user', 'password123', '已删除用户', 'deleted@example.com', '13800138099', 2, NULL, 1, 0, 0);

-- 模型服务商表
CREATE TABLE IF NOT EXISTS `model_provider` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '服务商名称',
    `api_base_url` VARCHAR(500) DEFAULT NULL COMMENT 'API 基础 URL',
    `api_key` VARCHAR(500) DEFAULT NULL COMMENT 'API 密钥',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型服务商表';

INSERT INTO `model_provider` (`name`, `api_base_url`, `api_key`, `status`, `active`) VALUES
('OpenAI', 'https://api.openai.com', 'sk-test-openai-key', 1, 1),
('Anthropic', 'https://api.anthropic.com', 'sk-test-anthropic-key', 1, 1),
('Deleted Provider', 'https://api.deleted.com', 'sk-deleted', 1, 0);

-- 模型表
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
('Deleted Model', 'deleted-model', 1, '已删除模型', 'chat', 0, 0, 0, 0, 0, 0.0100, 1, 1, 'admin', 0);

-- MCP 服务表
CREATE TABLE IF NOT EXISTS `mcp_server` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT 'MCP 服务名称',
    `description` TEXT DEFAULT NULL COMMENT 'MCP 服务描述',
    `url` VARCHAR(500) NOT NULL COMMENT 'MCP 服务 URL',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务表';

INSERT INTO `mcp_server` (`name`, `description`, `url`, `status`, `active`) VALUES
('Weather MCP', '天气查询服务', 'http://localhost:8081/weather', 1, 1),
('Calculator MCP', '计算器服务', 'http://localhost:8082/calc', 1, 1),
('Deleted MCP', '已删除服务', 'http://localhost:8083/deleted', 1, 0);

-- 技能仓库表
CREATE TABLE IF NOT EXISTS `skill_repository` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '仓库名称',
    `url` VARCHAR(500) NOT NULL COMMENT '仓库 URL',
    `description` TEXT DEFAULT NULL COMMENT '仓库描述',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能仓库表';

INSERT INTO `skill_repository` (`name`, `url`, `description`, `status`, `active`) VALUES
('Default Repository', 'https://github.com/vipamp/skills', '默认技能仓库', 1, 1),
('Deleted Repository', 'https://github.com/vipamp/deleted', '已删除仓库', 1, 0);

-- 技能表
CREATE TABLE IF NOT EXISTS `skill` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '技能名称',
    `repository_id` BIGINT(20) NOT NULL COMMENT '所属仓库 ID',
    `skillmd` TEXT DEFAULT NULL COMMENT '技能 Markdown 描述',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_repository_id` (`name`, `repository_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能表';

INSERT INTO `skill` (`name`, `repository_id`, `skillmd`, `status`, `active`) VALUES
('web-search', 1, '网络搜索技能', 1, 1),
('code-review', 1, '代码审查技能', 1, 1),
('deleted-skill', 1, '已删除技能', 1, 0);

-- 智能体表
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
('Test Agent 1', '测试智能体1', '你是一个助手', 1, '[{"id":1,"enable_skip":"false"}]', '1,2', 'testuser1', 1, 1, 'testuser1', 1),
('Test Agent 2', '测试智能体2', '你是一个编程助手', 2, '', '1', 'testuser1', 1, 0, 'testuser1', 1),
('Test Agent 3', '测试智能体3', '你是一个翻译助手', 3, '[{"id":2,"enable_skip":"true"}]', '', 'testuser2', 0, 1, 'testuser2', 1),
('Deleted Agent', '已删除智能体', '已删除', 1, '', '', 'testuser1', 1, 1, 'testuser1', 0);

-- Channel 通道表
CREATE TABLE IF NOT EXISTS `channel` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `name` VARCHAR(100) NOT NULL COMMENT '通道名称',
    `type` VARCHAR(20) NOT NULL COMMENT '类型(wecom/feishu/dingtalk/http)',
    `agent_id` BIGINT(20) NOT NULL COMMENT '关联的智能体ID',
    `webhook_url` VARCHAR(500) DEFAULT NULL COMMENT '推送地址',
    `token` VARCHAR(500) DEFAULT NULL COMMENT '验证Token',
    `encoding_aes_key` VARCHAR(500) DEFAULT NULL COMMENT '加密密钥(企业微信)',
    `app_id` VARCHAR(100) DEFAULT NULL COMMENT '应用ID(飞书/钉钉)',
    `app_secret` VARCHAR(500) DEFAULT NULL COMMENT '应用密钥',
    `callback_key` VARCHAR(100) NOT NULL COMMENT '回调标识(用于生成回调URL)',
    `description` TEXT DEFAULT NULL COMMENT '描述',
    `status` TINYINT(1) DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `active` TINYINT(1) DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_callback_key` (`callback_key`),
    KEY `idx_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Channel通道表';

INSERT INTO `channel` (`name`, `type`, `agent_id`, `webhook_url`, `token`, `callback_key`, `description`, `status`, `active`) VALUES
('Test WeCom Channel', 'wecom', 1, 'http://localhost:8080/webhook/wecom', 'test-token', 'test-wecom', '企业微信测试通道', 1, 1),
('Test HTTP Channel', 'http', 2, 'http://localhost:8080/webhook/http', 'http-token', 'test-http', 'HTTP测试通道', 1, 1),
('Deleted Channel', 'wecom', 1, 'http://localhost:8080/webhook/deleted', 'deleted-token', 'test-deleted', '已删除通道', 1, 0);

-- 会话表
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
('session-deleted', 1, '已删除会话', '已删除', 1, 1, 0);

-- Token 统计表
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
('session-003', 2, 2, 2, 300, 150, 450, 0.0060);
