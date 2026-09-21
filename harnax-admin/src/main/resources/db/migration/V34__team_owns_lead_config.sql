-- V34: the team hosts its own lead configuration (design D1/D2/D6/D8)
--
-- `team` gains `system_prompt` and `model_id` and loses `lead_agent_id` and `instructions`. A lead is no
-- longer an existing agent a team points at; it is configuration the team carries, which is what the
-- first step of the team wizard edits. `team_skill_binding` is the one capability table a team owns:
-- Skill is the only thing a lead may be given, since it executes nothing itself. `instructions` merges
-- into `system_prompt` — two prompt fields would only raise which one wins.
--
-- Existing teams are dropped rather than migrated, on the user's call that team data may be discarded.
-- No team row translates: a lead agent's model and prompt deliberately do not become team
-- configuration. Team *sessions* keep their history — only their `team_id` is cleared, so each reads on
-- as the ordinary agent session it was created alongside, snapshots and all.
--
-- Data goes first and columns change after. Adding NOT NULL columns to a table that still holds rows
-- would fill them with implicit defaults, which reads like a migration preserving data it is about to
-- delete.

DELETE FROM team_artifact;
DELETE FROM team_member;
DELETE FROM team;

UPDATE session SET team_id = NULL WHERE team_id IS NOT NULL;

-- idx_team_lead_agent_id goes with its column.
ALTER TABLE `team`
    DROP COLUMN `lead_agent_id`,
    DROP COLUMN `instructions`,
    ADD COLUMN `system_prompt` text NOT NULL COMMENT 'System prompt of the lead, team orchestration rules included',
    ADD COLUMN `model_id` bigint NOT NULL COMMENT 'FK to model.id, the model the lead runs on';

-- Same shape as agent_skill_binding after V33, minus `env_bindings`: per-skill environment values have
-- no consumer on either side (see the note on AgentSkillBinding).
CREATE TABLE IF NOT EXISTS `team_skill_binding`
(
    `id`          bigint   NOT NULL AUTO_INCREMENT COMMENT 'Binding ID',
    `team_id`     bigint   NOT NULL COMMENT 'FK to team.id',
    `skill_id`    bigint   NOT NULL COMMENT 'FK to skill.id',
    `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_team_skill_binding_team_id_skill_id` (`team_id`, `skill_id`),
    KEY `idx_team_skill_binding_skill_id` (`skill_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Skills the lead of a team is given';
