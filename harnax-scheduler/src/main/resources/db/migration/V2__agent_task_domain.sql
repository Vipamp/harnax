-- Release 2 (docs/superpowers/plans/2026-09-14-scheduler-domain-migration.md): the scheduled-task domain
-- tables, now owned by harnax-scheduler.
--
-- Ownership of `agent_task`, `agent_task_log` and `agent_task_execution` moves out of harnax-admin with this
-- release, so their definitions move here too. The three definitions below are harnax-admin's
-- V1__init_schema.sql:480-536 copied column for column and in column order: the two files have to stay
-- diffable while both describe the same table. Four edits and nothing else:
--   * `agent_task_log.session_id` grows from 64 to 128 characters, because the id gained the agentId
--     segment (contract C1);
--   * the two indexes admin added afterwards in V27__add_agent_task_execution_sweep_indexes.sql are inlined
--     into `agent_task_execution` here instead of getting a separate V3. This schema is new, so there is no
--     existing table to alter and no reason to make every reader replay admin's history to know what the
--     five-minute sweeps scan;
--   * no statement that removes anything. The `harnax_admin` copies of these tables are operator work, not
--     this file's;
--   * this header.
--
-- Two consequences the rest of the cut has to live with:
--   * These three tables start EMPTY and stay empty until someone re-creates the tasks in the webui: no
--     history is copied (decision D8, confirmed with the user — there is no legacy data). So after the cut
--     the task list's last-run columns read null and the execution-log page has no rows, which is expected
--     rather than an incident. `agent_task_log` is still built, because `AgentTaskMapper.selectTaskList`
--     LEFT JOINs it for last_run_status / last_run_time — a missing table is a 500 on the list page, not an
--     empty column.
--   * The same-named tables in `harnax_admin` are untouched by this file, and nothing in this release moves
--     a row into these three — so from the cut onward they hold no data any service reads. Dropping them is
--     a step of the cut (docs/deploy-harnax-scheduler.md), not a follow-up with an observation period.
--
-- Where this lands: this module's Flyway applies it into the database `spring.datasource.url` names, which
-- release 2 sets to `harnax_scheduler` — this service's own schema, built here from nothing rather than
-- layered onto admin's.

-- Agent Task - Scheduled agent execution
CREATE TABLE IF NOT EXISTS `agent_task` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tenant_id`       BIGINT DEFAULT 1,
    `name`            VARCHAR(128) NOT NULL COMMENT 'Task name',
    `agent_id`        BIGINT NOT NULL COMMENT 'Associated Agent ID',
    `agent_name`      VARCHAR(128) COMMENT 'Agent name snapshot',
    `prompt`          TEXT NOT NULL COMMENT 'Prompt content for scheduled execution',
    `cron_expression` VARCHAR(128) NOT NULL COMMENT 'Cron expression',
    `task_status`     TINYINT NOT NULL DEFAULT 0 COMMENT '0=paused, 1=running',
    `concurrent`      TINYINT NOT NULL DEFAULT 0 COMMENT '0=no concurrent, 1=allow concurrent',
    `timeout_seconds` INT DEFAULT 300 COMMENT 'Timeout in seconds, default 5 minutes',
    `description`     VARCHAR(512) DEFAULT '' COMMENT 'Task description',
    `is_public`       TINYINT DEFAULT 0,
    `creator`         VARCHAR(64) DEFAULT '',
    `active`          TINYINT DEFAULT 1,
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_tenant_id` (`tenant_id`),
    INDEX `idx_agent_id` (`agent_id`),
    UNIQUE KEY `uk_name` (`name`)
) COMMENT='Agent scheduled tasks';

-- Agent Task execution log
CREATE TABLE IF NOT EXISTS `agent_task_log` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `task_id`         BIGINT NOT NULL COMMENT 'Associated agent_task.id',
    `task_name`       VARCHAR(128) COMMENT 'Task name',
    `prompt`          TEXT COMMENT 'Prompt for this execution',
    `response`        TEXT COMMENT 'Agent response content',
    `session_id`      VARCHAR(128) COMMENT 'task-{taskId}-{agentId}-{uuid} (C1); the legacy three-segment form only ever exists in the rows left behind in harnax_admin, none of which are copied here',
    `status`          TINYINT DEFAULT 1 COMMENT '0=failed, 1=success, 2=timeout',
    `error_info`      TEXT COMMENT 'Exception information',
    `token_usage`     VARCHAR(512) COMMENT 'Token usage JSON',
    `start_time`      DATETIME COMMENT 'Start time',
    `end_time`        DATETIME COMMENT 'End time',
    `duration_ms`     BIGINT COMMENT 'Execution duration (milliseconds)',
    `creator`         VARCHAR(64) DEFAULT '',
    `create_time`     DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_create_time` (`create_time`)
) COMMENT='Agent task execution log';

-- Agent task execution guard for multi-instance deployment
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
    KEY `idx_status_create_time` (`status`, `create_time`),
    KEY `idx_create_time` (`create_time`)
) COMMENT='Agent task execution lock (multi-instance dedup)';
