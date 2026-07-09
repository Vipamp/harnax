-- Add enabled column with NOT NULL constraint (V14 used plain ALTER which may fail on fresh deployments)
ALTER TABLE `env_variable` ADD COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Enabled status (0: Disabled, 1: Enabled)';

-- Add index for enabled filtering
ALTER TABLE `env_variable` ADD INDEX `idx_enabled` (`enabled`);
