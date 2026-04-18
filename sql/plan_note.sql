-- PlanNote 表
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
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_plan_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PlanNote表';
