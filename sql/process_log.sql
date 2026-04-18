-- 处理日志表
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
