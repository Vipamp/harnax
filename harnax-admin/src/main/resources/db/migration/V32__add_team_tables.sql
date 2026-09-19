-- V32: multi-agent teams — an independent team configuration that references existing agents.
--
-- A team is not a second kind of agent. `team` holds only the grouping (lead reference, team
-- instructions, ownership), and `team_member` holds only references plus the per-team responsibility
-- note. Model, prompt, Tool, MCP, Skill and CLI bindings keep living on `agent`, so the same agent can
-- lead one team, serve as a member of another, and still be used standalone (design D1/D2).
--
-- No unique key on `team.name`: every other config table here (agent, cli) enforces name uniqueness in
-- the service instead, because rows are logically deleted (`active = 0`) and a database constraint would
-- then refuse to reuse the name of a team the user deleted.
--
-- `team_id` on `session` is the team entry point. It stays NULL for ordinary agent sessions, so an agent
-- that happens to lead a team does not start one just because it was messaged (design 3.2).
ALTER TABLE `session`
    ADD COLUMN `team_id` bigint DEFAULT NULL COMMENT 'Team ID when this session runs in team mode, NULL for an ordinary agent session',
    ADD KEY `idx_team_id` (`team_id`);

CREATE TABLE IF NOT EXISTS `team`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT 'Team ID',
    `tenant_id`     bigint       NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
    `name`          varchar(100) NOT NULL COMMENT 'Team name',
    `description`   text COMMENT 'Team description',
    `lead_agent_id` bigint       NOT NULL COMMENT 'FK to agent.id — the agent that orchestrates this team',
    `instructions`  text COMMENT 'Team instructions appended to the lead role prompt',
    `status`        tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
    `is_public`     tinyint               DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
    `creator`       varchar(100)          DEFAULT NULL COMMENT 'Creator',
    `active`        tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
    `create_time`   datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time`   datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY             `idx_tenant_id` (`tenant_id`),
    KEY             `idx_lead_agent_id` (`lead_agent_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Multi-agent team table';

CREATE TABLE IF NOT EXISTS `team_member`
(
    `id`                     bigint       NOT NULL AUTO_INCREMENT COMMENT 'Member binding ID',
    `team_id`                bigint       NOT NULL COMMENT 'FK to team.id',
    `member_agent_id`        bigint       NOT NULL COMMENT 'FK to agent.id — the member agent',
    `delegation_description` varchar(500) NOT NULL DEFAULT '' COMMENT 'What this member is responsible for in this team; defaults to the agent description, never written back to it',
    `create_time`            datetime              DEFAULT CURRENT_TIMESTAMP,
    `update_time`            datetime              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_team_member` (`team_id`, `member_agent_id`),
    KEY                      `idx_member_agent_id` (`member_agent_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Team member binding table';

-- Members run in their own sandbox and hand files over through MinIO instead of sharing a workspace
-- (design D5/D6). A file id is a reference, not a credential: the download path resolves the tenant,
-- the root session and the producer run from this row before returning a single byte, which is why the
-- ownership columns are stored next to the object key rather than derived from it.
--
-- Every publish inserts a new row with a new file id, so a later member can never overwrite an earlier
-- artifact and the lead chooses which version to pass on.
CREATE TABLE IF NOT EXISTS `team_artifact`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT 'Artifact ID',
    `file_id`          varchar(64)  NOT NULL COMMENT 'Opaque artifact reference (UUID), stable for the user and the lead',
    `tenant_id`        bigint       NOT NULL DEFAULT '1' COMMENT 'Owning tenant',
    `session_id`       varchar(100) NOT NULL COMMENT 'Root team session this artifact belongs to',
    `team_id`          bigint       NOT NULL COMMENT 'FK to team.id',
    `member_agent_id`  bigint       NOT NULL COMMENT 'FK to agent.id — the member that produced it',
    `child_session_id` varchar(100) NOT NULL COMMENT 'Member child session that produced it (its own state and sandbox scope)',
    `file_name`        varchar(255) NOT NULL COMMENT 'Original file name; may repeat across artifacts',
    `mime_type`        varchar(100) NOT NULL DEFAULT 'application/octet-stream' COMMENT 'MIME type',
    `size_bytes`       bigint       NOT NULL DEFAULT '0' COMMENT 'Size in bytes',
    `object_key`       varchar(500) NOT NULL COMMENT 'Internal MinIO object key, never accepted from the model',
    `create_time`      datetime              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_id` (`file_id`),
    KEY                `idx_session_id` (`session_id`),
    KEY                `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Team artifact handoff metadata table';
