-- 会话表
DROP TABLE IF EXISTS `session`;
CREATE TABLE `session`
(
    `id`            BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    `title`         VARCHAR(100) NOT NULL COMMENT '会话名称',
    `session_id`    VARCHAR(100) NOT NULL COMMENT '会话ID',
    `agent_id`      BIGINT(20)   DEFAULT NULL COMMENT '关联的智能体ID',
    `name`          VARCHAR(100) DEFAULT NULL COMMENT '智能体名称',
    `description`   TEXT         DEFAULT NULL COMMENT '智能体描述',
    `system_prompt` TEXT         DEFAULT NULL COMMENT '系统提示词（支持 Markdown）',
    `model_id`      BIGINT(20)   DEFAULT NULL COMMENT '对话模型 ID',
    `mcp_list`      TEXT         DEFAULT NULL COMMENT 'MCP 服务列表（JSON 格式）',
    `skill_list`    TEXT         DEFAULT NULL COMMENT '技能列表（JSON 格式）',
    `owner`         VARCHAR(100) DEFAULT NULL COMMENT '所有者',
    `status`        TINYINT(1)   DEFAULT 1 COMMENT '是否启用（0:禁用，1:启用）',
    `is_public`     TINYINT(1)   DEFAULT 0 COMMENT '是否公开（0:否，1:是）',
    `creator`       VARCHAR(100) DEFAULT NULL COMMENT '创建人',
    `active`        TINYINT(1)   DEFAULT 1 COMMENT '是否可用（0:被删除，1:可用）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_creator` (`creator`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';
