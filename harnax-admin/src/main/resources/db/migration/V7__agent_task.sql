-- V7: Agent Task - Scheduled agent execution
-- agent_task: scheduled tasks that invoke agents via prompts
CREATE TABLE agent_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT DEFAULT 1,
    name            VARCHAR(128) NOT NULL COMMENT '任务名称',
    agent_id        BIGINT NOT NULL COMMENT '关联 Agent ID',
    agent_name      VARCHAR(128) COMMENT 'Agent 名称快照',
    prompt          TEXT NOT NULL COMMENT '定时执行的 prompt 内容',
    cron_expression VARCHAR(128) NOT NULL COMMENT 'Cron 表达式',
    task_status     TINYINT NOT NULL DEFAULT 0 COMMENT '0=暂停, 1=运行中',
    concurrent      TINYINT NOT NULL DEFAULT 0 COMMENT '0=不允许并发, 1=允许',
    timeout_seconds INT DEFAULT 300 COMMENT '超时秒数，默认5分钟',
    description     VARCHAR(512) DEFAULT '' COMMENT '任务描述',
    is_public       TINYINT DEFAULT 0,
    creator         VARCHAR(64) DEFAULT '',
    active          TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_agent_id (agent_id),
    UNIQUE KEY uk_name (name)
) COMMENT '智能体定时任务';

-- agent_task_log: execution logs for agent tasks
CREATE TABLE agent_task_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT NOT NULL COMMENT '关联 agent_task.id',
    task_name       VARCHAR(128) COMMENT '任务名称',
    prompt          TEXT COMMENT '本次执行的 prompt',
    response        TEXT COMMENT 'Agent 回复内容',
    session_id      VARCHAR(64) COMMENT '临时 session ID',
    status          TINYINT DEFAULT 1 COMMENT '0=失败, 1=成功, 2=超时',
    error_info      TEXT COMMENT '异常信息',
    token_usage     VARCHAR(512) COMMENT 'Token 使用 JSON',
    start_time      DATETIME COMMENT '开始时间',
    end_time        DATETIME COMMENT '结束时间',
    duration_ms     BIGINT COMMENT '执行耗时(毫秒)',
    creator         VARCHAR(64) DEFAULT '',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) COMMENT '智能体定时任务执行日志';
