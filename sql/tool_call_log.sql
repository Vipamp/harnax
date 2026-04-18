-- 工具调用日志表
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
