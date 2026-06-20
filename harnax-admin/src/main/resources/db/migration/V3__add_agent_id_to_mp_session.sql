-- Add agent_id column to mp_session table
ALTER TABLE `mp_session` ADD COLUMN `agent_id` BIGINT NOT NULL DEFAULT 0
    AFTER `router_session_id`;
ALTER TABLE `mp_session` ADD INDEX `idx_mp_session_agent_id` (`agent_id`);
