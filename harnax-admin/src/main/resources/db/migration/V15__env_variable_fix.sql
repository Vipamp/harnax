-- Fix enabled column to have NOT NULL constraint
ALTER TABLE `env_variable` MODIFY COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Enabled status (0: Disabled, 1: Enabled)';

-- Add index for faster lookups
ALTER TABLE `env_variable` ADD INDEX `idx_tenant_key` (`tenant_id`, `env_key`);
ALTER TABLE `env_variable` ADD INDEX `idx_enabled` (`enabled`);
