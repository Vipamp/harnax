--
-- schema-it.sql: what the Testcontainers MySQL of BaseSchedulerIT starts with, before Flyway runs.
--
-- Deliberately only the three business tables plus one IT-private accounting table. The QRTZ_* schema is
-- NOT duplicated here on purpose: V1__quartz_tables.sql is on the IT classpath through this module's own
-- resources and Flyway applies it against the container, so a second copy would either collide with it or
-- quietly replace the thing the tests exist to prove (that Flyway, not Boot and not a hand script, owns the
-- cluster schema).
--
-- These three still live in harnax_admin at runtime (release 1 moves only Quartz), so release 1's tests
-- have to create them by hand from the same DDL harnax-entity tests use -- copied from
-- harnax-entity/src/test/resources/schema-test.sql, DDL only: no seed rows. A seeded agent_task would make
-- "what one reconcile round did" count somebody else's task, and the ITs assert exact numbers.
--

-- ============================================
-- 19. Agent Task - Scheduled agent execution
-- ============================================
CREATE TABLE IF NOT EXISTS `agent_task` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`       BIGINT DEFAULT 1,
    `name`            VARCHAR(128) NOT NULL COMMENT '任务名称',
    `agent_id`        BIGINT NOT NULL COMMENT '关联 Agent ID',
    `agent_name`      VARCHAR(128) COMMENT 'Agent 名称快照',
    `prompt`          TEXT NOT NULL COMMENT '定时执行的 prompt 内容',
    `cron_expression` VARCHAR(128) NOT NULL COMMENT 'Cron 表达式',
    `task_status`     TINYINT NOT NULL DEFAULT 0 COMMENT '0=暂停, 1=运行中',
    `concurrent`      TINYINT NOT NULL DEFAULT 0 COMMENT '0=不允许并发, 1=允许',
    `timeout_seconds` INT DEFAULT 300 COMMENT '超时秒数',
    `description`     VARCHAR(512) DEFAULT '' COMMENT '任务描述',
    `is_public`       TINYINT DEFAULT 0,
    `creator`         VARCHAR(64) DEFAULT '',
    `active`          TINYINT DEFAULT 1,
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_agent_id` (`agent_id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体定时任务';

-- ============================================
-- 20. Agent Task Log - Execution logs
-- ============================================
CREATE TABLE IF NOT EXISTS `agent_task_log` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         BIGINT NOT NULL COMMENT '关联 agent_task.id',
    `task_name`       VARCHAR(128) COMMENT '任务名称',
    `prompt`          TEXT COMMENT '本次执行的 prompt',
    `response`        TEXT COMMENT 'Agent 回复内容',
    `session_id`      VARCHAR(64) COMMENT '临时 session ID',
    `status`          TINYINT DEFAULT 1 COMMENT '0=失败, 1=成功, 2=超时',
    `error_info`      TEXT COMMENT '异常信息',
    `token_usage`     VARCHAR(512) COMMENT 'Token 使用 JSON',
    `start_time`      DATETIME COMMENT '开始时间',
    `end_time`        DATETIME COMMENT '结束时间',
    `duration_ms`     BIGINT COMMENT '执行耗时(毫秒)',
    `creator`         VARCHAR(64) DEFAULT '',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体定时任务执行日志';

-- ============================================
-- 20b. Agent Task Execution - cluster lock rows
-- Same shape as the production DDL (V27's sweep indexes included): HousekeepingGuardIT's two sweeps are
-- exactly the two DELETEs that walk `status` + `create_time`, so the table under test has to have them.
-- ============================================
CREATE TABLE IF NOT EXISTS `agent_task_execution` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         BIGINT NOT NULL COMMENT 'Associated agent_task.id',
    `trigger_time`    DATETIME NOT NULL COMMENT 'Trigger time (for deduplication)',
    `instance_id`     VARCHAR(128) DEFAULT '' COMMENT 'Execution instance identifier',
    `start_time`      DATETIME COMMENT 'Actual start time',
    `end_time`        DATETIME COMMENT 'Execution end time',
    `status`          TINYINT DEFAULT 0 COMMENT '0=running, 1=success, 2=failed',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_task_trigger` (`task_id`, `trigger_time`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_trigger_time` (`trigger_time`),
    INDEX `idx_status_create_time` (`status`, `create_time`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent task execution lock (multi-instance dedup)';

-- ============================================
-- IT-private: ClusterSingleFireIT's evidence table
-- ============================================
-- Written by ClusterSingleFireIT's job: the aggregate row count is the evidence that a clustered
-- scheduler fires each trigger once for the whole cluster rather than once per node.
CREATE TABLE IF NOT EXISTS `it_cluster_fire` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `instance_name` VARCHAR(190) NOT NULL,
    `fire_time`     BIGINT NOT NULL
) COMMENT='Cluster single-fire evidence';
