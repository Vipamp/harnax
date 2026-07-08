-- Add permission_mode column to session and channel tables
-- PermissionMode enum values: DEFAULT, ACCEPT_EDITS, EXPLORE, BYPASS, DONT_ASK

ALTER TABLE `session`
    ADD COLUMN `permission_mode` VARCHAR(20) NOT NULL DEFAULT 'DEFAULT' COMMENT 'Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)'
    AFTER `enable_plan`;

ALTER TABLE `channel`
    ADD COLUMN `permission_mode` VARCHAR(20) NOT NULL DEFAULT 'DEFAULT' COMMENT 'Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)'
    AFTER `communication_mode`;
