-- Harnax scheduler schema baseline
--
-- This one file is the whole schema of the module: the QRTZ_* cluster store plus the three agent task
-- tables, applied into whatever database spring.datasource.url names (harnax_scheduler) and recorded in
-- this service's own history table flyway_schema_history_scheduler rather than admin's. Schema changes fold
-- into it and the environment is rebuilt from it; a follow-up ALTER script is written only when a live
-- database must not be rebuilt.
--
-- Quartz JDBC cluster store: this migration owns the QRTZ_* schema.
--
-- Body taken verbatim from the official org/quartz/impl/jdbcjobstore/tables_mysql_innodb.sql shipped
-- inside quartz-2.5.2.jar, so no column definition, index or table-name casing was hand-copied. Four
-- edits only: that script's own file header and its eleven table-drop lines are gone (a Flyway
-- migration never drops), every ENGINE=InnoDB clause gained a utf8mb4 default charset and a COMMENT,
-- and the trailing commit statement is gone because Flyway manages the transaction.
--
-- Why Flyway owns this and Boot is pinned to `spring.quartz.jdbc.initialize-schema: never`: Boot's
-- always setting runs that same bundled script on every start, and the script begins by dropping the
-- tables — on a cluster that erases the schedule state every node shares each time one node restarts.
-- The baseline applies once.
--
-- SCHED_NAME is the first column of every primary key here and the only way a node recognises rows its
-- sibling wrote, so it must be identical on both instances:
-- spring.quartz.properties.org.quartz.scheduler.instanceName is HarnaxScheduler for both, while
-- instanceId AUTO keeps the nodes apart.

CREATE TABLE QRTZ_JOB_DETAILS(
SCHED_NAME VARCHAR(120) NOT NULL,
JOB_NAME VARCHAR(190) NOT NULL,
JOB_GROUP VARCHAR(190) NOT NULL,
DESCRIPTION VARCHAR(250) NULL,
JOB_CLASS_NAME VARCHAR(250) NOT NULL,
IS_DURABLE VARCHAR(1) NOT NULL,
IS_NONCONCURRENT VARCHAR(1) NOT NULL,
IS_UPDATE_DATA VARCHAR(1) NOT NULL,
REQUESTS_RECOVERY VARCHAR(1) NOT NULL,
JOB_DATA BLOB NULL,
PRIMARY KEY (SCHED_NAME,JOB_NAME,JOB_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz job definitions (cluster-shared store)';

CREATE TABLE QRTZ_TRIGGERS (
SCHED_NAME VARCHAR(120) NOT NULL,
TRIGGER_NAME VARCHAR(190) NOT NULL,
TRIGGER_GROUP VARCHAR(190) NOT NULL,
JOB_NAME VARCHAR(190) NOT NULL,
JOB_GROUP VARCHAR(190) NOT NULL,
DESCRIPTION VARCHAR(250) NULL,
NEXT_FIRE_TIME BIGINT(13) NULL,
PREV_FIRE_TIME BIGINT(13) NULL,
PRIORITY INTEGER NULL,
TRIGGER_STATE VARCHAR(16) NOT NULL,
TRIGGER_TYPE VARCHAR(8) NOT NULL,
START_TIME BIGINT(13) NOT NULL,
END_TIME BIGINT(13) NULL,
CALENDAR_NAME VARCHAR(190) NULL,
MISFIRE_INSTR SMALLINT(2) NULL,
JOB_DATA BLOB NULL,
PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
FOREIGN KEY (SCHED_NAME,JOB_NAME,JOB_GROUP)
REFERENCES QRTZ_JOB_DETAILS(SCHED_NAME,JOB_NAME,JOB_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz triggers (cluster-shared store)';

CREATE TABLE QRTZ_SIMPLE_TRIGGERS (
SCHED_NAME VARCHAR(120) NOT NULL,
TRIGGER_NAME VARCHAR(190) NOT NULL,
TRIGGER_GROUP VARCHAR(190) NOT NULL,
REPEAT_COUNT BIGINT(7) NOT NULL,
REPEAT_INTERVAL BIGINT(12) NOT NULL,
TIMES_TRIGGERED BIGINT(10) NOT NULL,
PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
REFERENCES QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz simple trigger extensions';

CREATE TABLE QRTZ_CRON_TRIGGERS (
SCHED_NAME VARCHAR(120) NOT NULL,
TRIGGER_NAME VARCHAR(190) NOT NULL,
TRIGGER_GROUP VARCHAR(190) NOT NULL,
CRON_EXPRESSION VARCHAR(120) NOT NULL,
TIME_ZONE_ID VARCHAR(80),
PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
REFERENCES QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz cron trigger extensions';

CREATE TABLE QRTZ_SIMPROP_TRIGGERS
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(190) NOT NULL,
    TRIGGER_GROUP VARCHAR(190) NOT NULL,
    STR_PROP_1 VARCHAR(512) NULL,
    STR_PROP_2 VARCHAR(512) NULL,
    STR_PROP_3 VARCHAR(512) NULL,
    INT_PROP_1 INT NULL,
    INT_PROP_2 INT NULL,
    LONG_PROP_1 BIGINT NULL,
    LONG_PROP_2 BIGINT NULL,
    DEC_PROP_1 NUMERIC(13,4) NULL,
    DEC_PROP_2 NUMERIC(13,4) NULL,
    BOOL_PROP_1 VARCHAR(1) NULL,
    BOOL_PROP_2 VARCHAR(1) NULL,
    PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
    REFERENCES QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz calendar-interval trigger extensions';

CREATE TABLE QRTZ_BLOB_TRIGGERS (
SCHED_NAME VARCHAR(120) NOT NULL,
TRIGGER_NAME VARCHAR(190) NOT NULL,
TRIGGER_GROUP VARCHAR(190) NOT NULL,
BLOB_DATA BLOB NULL,
PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
INDEX (SCHED_NAME,TRIGGER_NAME, TRIGGER_GROUP),
FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
REFERENCES QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz blob trigger extensions';

CREATE TABLE QRTZ_CALENDARS (
SCHED_NAME VARCHAR(120) NOT NULL,
CALENDAR_NAME VARCHAR(190) NOT NULL,
CALENDAR BLOB NOT NULL,
PRIMARY KEY (SCHED_NAME,CALENDAR_NAME))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz calendars';

CREATE TABLE QRTZ_PAUSED_TRIGGER_GRPS (
SCHED_NAME VARCHAR(120) NOT NULL,
TRIGGER_GROUP VARCHAR(190) NOT NULL,
PRIMARY KEY (SCHED_NAME,TRIGGER_GROUP))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz paused trigger groups';

CREATE TABLE QRTZ_FIRED_TRIGGERS (
SCHED_NAME VARCHAR(120) NOT NULL,
ENTRY_ID VARCHAR(95) NOT NULL,
TRIGGER_NAME VARCHAR(190) NOT NULL,
TRIGGER_GROUP VARCHAR(190) NOT NULL,
INSTANCE_NAME VARCHAR(190) NOT NULL,
FIRED_TIME BIGINT(13) NOT NULL,
SCHED_TIME BIGINT(13) NOT NULL,
PRIORITY INTEGER NOT NULL,
STATE VARCHAR(16) NOT NULL,
JOB_NAME VARCHAR(190) NULL,
JOB_GROUP VARCHAR(190) NULL,
IS_NONCONCURRENT VARCHAR(1) NULL,
REQUESTS_RECOVERY VARCHAR(1) NULL,
PRIMARY KEY (SCHED_NAME,ENTRY_ID))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz fired triggers; also the cluster liveness evidence';

CREATE TABLE QRTZ_SCHEDULER_STATE (
SCHED_NAME VARCHAR(120) NOT NULL,
INSTANCE_NAME VARCHAR(190) NOT NULL,
LAST_CHECKIN_TIME BIGINT(13) NOT NULL,
CHECKIN_INTERVAL BIGINT(13) NOT NULL,
PRIMARY KEY (SCHED_NAME,INSTANCE_NAME))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz scheduler state; one row per cluster node';

CREATE TABLE QRTZ_LOCKS (
SCHED_NAME VARCHAR(120) NOT NULL,
LOCK_NAME VARCHAR(40) NOT NULL,
PRIMARY KEY (SCHED_NAME,LOCK_NAME))
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Quartz row locks used for clustered trigger acquisition';

CREATE INDEX IDX_QRTZ_J_REQ_RECOVERY ON QRTZ_JOB_DETAILS(SCHED_NAME,REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_J_GRP ON QRTZ_JOB_DETAILS(SCHED_NAME,JOB_GROUP);

CREATE INDEX IDX_QRTZ_T_J ON QRTZ_TRIGGERS(SCHED_NAME,JOB_NAME,JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_JG ON QRTZ_TRIGGERS(SCHED_NAME,JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_C ON QRTZ_TRIGGERS(SCHED_NAME,CALENDAR_NAME);
CREATE INDEX IDX_QRTZ_T_G ON QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_T_STATE ON QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_STATE ON QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_G_STATE ON QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_GROUP,TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NEXT_FIRE_TIME ON QRTZ_TRIGGERS(SCHED_NAME,NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST ON QRTZ_TRIGGERS(SCHED_NAME,TRIGGER_STATE,NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_MISFIRE ON QRTZ_TRIGGERS(SCHED_NAME,MISFIRE_INSTR,NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE ON QRTZ_TRIGGERS(SCHED_NAME,MISFIRE_INSTR,NEXT_FIRE_TIME,TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE_GRP ON QRTZ_TRIGGERS(SCHED_NAME,MISFIRE_INSTR,NEXT_FIRE_TIME,TRIGGER_GROUP,TRIGGER_STATE);

CREATE INDEX IDX_QRTZ_FT_TRIG_INST_NAME ON QRTZ_FIRED_TRIGGERS(SCHED_NAME,INSTANCE_NAME);
CREATE INDEX IDX_QRTZ_FT_INST_JOB_REQ_RCVRY ON QRTZ_FIRED_TRIGGERS(SCHED_NAME,INSTANCE_NAME,REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_FT_J_G ON QRTZ_FIRED_TRIGGERS(SCHED_NAME,JOB_NAME,JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_JG ON QRTZ_FIRED_TRIGGERS(SCHED_NAME,JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_T_G ON QRTZ_FIRED_TRIGGERS(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_FT_TG ON QRTZ_FIRED_TRIGGERS(SCHED_NAME,TRIGGER_GROUP);



-- The scheduled-task domain tables: `agent_task`, `agent_task_log`, `agent_task_execution`. This file holds
-- their only definition in the repository — harnax-admin's schema baseline creates neither them nor anything
-- like them, so there is no second copy to keep diffable and nothing here to reconcile a column against.
-- Three points worth knowing before reading the DDL:
--   * `agent_task_log.session_id` is 128 characters, because the task session id carries the agentId
--     segment (contract C1; the grammar lives in harnax-common's `TaskSessionId`);
--   * `agent_task_execution` carries its two sweep indexes, `(status, create_time)` and `(create_time)`,
--     inline in the CREATE TABLE, so a reader can see what the five-minute sweeps scan without
--     reconstructing anyone's migration history;
--   * no statement that removes anything. An existing `harnax_admin` may still hold orphan copies of these
--     three tables; no service reads them and dropping them is operator work
--     (docs/deploy-harnax-scheduler.md), not this file's.
--
-- What that means for the data: these three tables start EMPTY and stay empty until someone creates tasks
-- in the webui — this baseline seeds no rows, so the task list's last-run columns read null and the
-- execution-log page has no rows on a fresh install, which is expected rather than an incident.
-- `agent_task_log` is built anyway, because `AgentTaskMapper.selectTaskList` LEFT JOINs it for
-- last_run_status / last_run_time — a missing table is a 500 on the list page, not an empty column.
--
-- Where this lands: this module's Flyway applies it into the database `spring.datasource.url` names,
-- `harnax_scheduler` — this service's own schema, the only migrator that writes tables into it.

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
    `session_id`      VARCHAR(128) COMMENT 'task-{taskId}-{agentId}-{uuid} (C1)',
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
