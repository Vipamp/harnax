-- Token 消耗统计表
CREATE TABLE IF NOT EXISTS `token_stats` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `agent_id` BIGINT DEFAULT NULL COMMENT '智能体 ID',
  `session_id` VARCHAR(255) DEFAULT NULL COMMENT '会话 ID',
  `chat_model_id` BIGINT DEFAULT NULL COMMENT '对话模型 ID',
  `input_token` BIGINT DEFAULT 0 COMMENT '输入 token 数量',
  `output_token` BIGINT DEFAULT 0 COMMENT '输出 token 数量',
  `total_token` BIGINT DEFAULT 0 COMMENT '总 token 数量',
  `fee` DECIMAL(10,2) DEFAULT 0.00 COMMENT '模型费用（单位：元）',
  `ts` DATETIME DEFAULT NULL COMMENT '时间戳',
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_chat_model_id` (`chat_model_id`),
  KEY `idx_ts` (`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token 消耗统计表';
