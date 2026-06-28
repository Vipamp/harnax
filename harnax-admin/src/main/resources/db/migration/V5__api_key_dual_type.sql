-- V5: API Key dual type support
-- Add key_type, user_id, raw_key_encrypted, service_name fields to api_key table

-- key_type: PERMANENT / TEMPORARY / SYSTEM
ALTER TABLE `api_key`
    ADD COLUMN `key_type` VARCHAR(16) NOT NULL DEFAULT 'TEMPORARY' COMMENT 'Key type: PERMANENT, TEMPORARY or SYSTEM'
    AFTER `name`;

-- user_id: associated user ID (for PERMANENT keys)
ALTER TABLE `api_key`
    ADD COLUMN `user_id` BIGINT NULL COMMENT 'Associated user ID (for PERMANENT keys)'
    AFTER `key_type`;

-- raw_key_encrypted: AES-encrypted raw key (for PERMANENT/SYSTEM keys only)
ALTER TABLE `api_key`
    ADD COLUMN `raw_key_encrypted` VARCHAR(256) NULL COMMENT 'AES-encrypted raw key (for PERMANENT/SYSTEM keys only)'
    AFTER `user_id`;

-- service_name: service identifier (for SYSTEM keys, e.g. channel-service)
ALTER TABLE `api_key`
    ADD COLUMN `service_name` VARCHAR(64) NULL COMMENT 'Service name (for SYSTEM keys, e.g. channel-service)'
    AFTER `raw_key_encrypted`;

-- Indexes
ALTER TABLE `api_key` ADD INDEX `idx_key_type` (`key_type`);
ALTER TABLE `api_key` ADD INDEX `idx_user_id` (`user_id`);
ALTER TABLE `api_key` ADD INDEX `idx_service_name` (`service_name`);

-- Unique constraint: each user can only have one PERMANENT key
ALTER TABLE `api_key` ADD UNIQUE INDEX `uk_user_permanent` (`user_id`, `key_type`);

-- Unique constraint: each service can only have one SYSTEM key
ALTER TABLE `api_key` ADD UNIQUE INDEX `uk_service_system` (`service_name`, `key_type`);

-- Drop the UNIQUE constraint on name (permanent keys use a fixed naming pattern)
-- Note: the original table has a UNIQUE index on `name`
ALTER TABLE `api_key` DROP INDEX `name`;
ALTER TABLE `api_key` ADD INDEX `idx_name` (`name`);

-- Migrate existing data: mark all existing keys as TEMPORARY
UPDATE `api_key` SET `key_type` = 'TEMPORARY' WHERE `key_type` IS NULL OR `key_type` = '';

-- Optional: disable expired historical login keys
UPDATE `api_key` SET `active` = 0 WHERE `expires_at` < NOW() AND `active` = 1;
