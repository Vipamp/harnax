-- Add enabled column to env_variable table
ALTER TABLE `env_variable` ADD COLUMN `enabled` tinyint(1) DEFAULT '1' COMMENT 'Enabled status (0: Disabled, 1: Enabled)' AFTER `sensitive`;
