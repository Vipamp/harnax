-- V8: Agent task execution guard for multi-instance deployment
-- Prevents duplicate execution when multiple admin instances are running
CREATE TABLE agent_task_execution (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT NOT NULL COMMENT '关联 agent_task.id',
    trigger_time    DATETIME NOT NULL COMMENT '触发时间（精确到秒，用于去重）',
    instance_id     VARCHAR(128) DEFAULT '' COMMENT '执行实例标识',
    start_time      DATETIME COMMENT '实际开始执行时间',
    end_time        DATETIME COMMENT '执行结束时间',
    status          TINYINT DEFAULT 0 COMMENT '0=执行中, 1=成功, 2=失败',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_trigger (task_id, trigger_time),
    INDEX idx_task_id (task_id),
    INDEX idx_trigger_time (trigger_time)
) COMMENT '智能体定时任务执行锁（多实例防重）';
