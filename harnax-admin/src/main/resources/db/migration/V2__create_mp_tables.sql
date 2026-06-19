-- ============================================================
-- Mobile session table
-- ============================================================
CREATE TABLE IF NOT EXISTS `mp_session` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT 'ID',
    `user_id`           BIGINT          NOT NULL                 COMMENT 'User ID (FK to sys_user)',
    `session_name`      VARCHAR(255)    NOT NULL DEFAULT ''      COMMENT 'Session name',
    `router_session_id` VARCHAR(128)    NOT NULL DEFAULT ''      COMMENT 'Corresponding router session ID',
    `status`            TINYINT         NOT NULL DEFAULT 1       COMMENT 'Status (0:archived, 1:active)',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    PRIMARY KEY (`id`),
    INDEX `idx_mp_session_user_id` (`user_id`),
    INDEX `idx_mp_session_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mobile chat sessions';

-- ============================================================
-- Mobile chat message table
-- ============================================================
CREATE TABLE IF NOT EXISTS `mp_chat_message` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT  COMMENT 'ID',
    `session_id`       BIGINT       NOT NULL                 COMMENT 'Session ID (FK to mp_session)',
    `role`             VARCHAR(32)  NOT NULL DEFAULT 'user'  COMMENT 'Message role (user/assistant/system)',
    `content`          MEDIUMTEXT                            COMMENT 'Plain text content',
    `segments_json`    MEDIUMTEXT                            COMMENT 'Message segments (JSON array)',
    `token_usage_json` TEXT                                  COMMENT 'Token usage info (JSON object)',
    `image_urls_json`  TEXT                                  COMMENT 'Image URLs (JSON array)',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    PRIMARY KEY (`id`),
    INDEX `idx_mp_chat_message_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mobile chat messages';
