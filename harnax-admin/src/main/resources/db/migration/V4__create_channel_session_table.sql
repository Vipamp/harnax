-- Create channel_session table
-- Schema structure mirrors the channel table, with session_id linking to the agent session.
-- When a channel is created, a channel_session record is created to manage the agent session.

CREATE TABLE `channel_session` (
    `id`                 BIGINT(20)   NOT NULL AUTO_INCREMENT                       COMMENT 'ID',
    `tenant_id`          BIGINT(20)   NOT NULL DEFAULT 1                             COMMENT '租户ID',
    `name`               VARCHAR(100) NOT NULL                                       COMMENT '通道名称',
    `type`               VARCHAR(20)  NOT NULL                                       COMMENT '渠道类型 wecom/wechat/feishu/dingtalk/http',
    `agent_id`           BIGINT(20)   NOT NULL                                       COMMENT '关联的智能体 ID',
    `callback_key`       VARCHAR(100) NOT NULL                                       COMMENT '回调标识(用于生成回调URL)',
    `session_id`         VARCHAR(64)  NOT NULL                                       COMMENT '关联的会话ID(UUID), 创建时生成',
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
    UNIQUE KEY `uk_cs_callback_key` (`callback_key`),
    KEY `idx_cs_session_id` (`session_id`),
    KEY `idx_cs_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Channel 会话关联表';
