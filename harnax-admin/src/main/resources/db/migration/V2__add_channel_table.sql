-- ============================================
-- Channel 通道配置表
-- 公共业务字段 + 渠道差异化配置 JSON
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
    `config_json`        TEXT         DEFAULT NULL                                   COMMENT '渠道差异化配置 JSON: {appId,appSecret,encodingAesKey,webhookUrl,token,...}',
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




INSERT INTO `channel`
(`tenant_id`, `name`, `type`, `agent_id`, `callback_key`, `session_id`, `communication_mode`, `enabled`, `config_json`, `description`, `creator`, `status`, `active`)
VALUES
    (
        1,                                          -- tenant_id
        '飞书助手',                                   -- name
        'feishu',                                   -- type
        1,                                          -- agent_id (关联的智能体ID，按实际改)
        'feishu-ws-20260605',                       -- callback_key (唯一标识，用于生成回调URL)
        'ch-feishu-ws-001',                         -- session_id (不可变会话ID)
        'websocket',                                -- communication_mode (飞书长连接模式)
        1,                                          -- enabled (随服务启动自动监听)
        '{
            "appId": "cli_a978496aac395cbd",
            "appSecret": "4HwsyncAKtIttiEIvcRIqhMlGvNtpTFg"
        }',                                         -- config_json (飞书应用凭证)
        '飞书 WebSocket 长连接渠道',                   -- description
        'system',                                   -- creator
        1,                                          -- status (启用)
        1                                           -- active (正常)
    );
