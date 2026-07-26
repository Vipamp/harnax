-- V8: Add CLI plugin management tables
-- Supports system-integrated and custom CLI plugins for sandbox injection.

CREATE TABLE IF NOT EXISTS cli_plugin (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       BIGINT       DEFAULT 1,
    name            VARCHAR(100) NOT NULL COMMENT 'Plugin identifier (e.g. harnax-cli)',
    display_name    VARCHAR(200) DEFAULT NULL COMMENT 'Display name (EN)',
    display_name_zh VARCHAR(200) DEFAULT NULL COMMENT 'Display name (ZH)',
    description     TEXT         DEFAULT NULL COMMENT 'Plugin description',
    version         VARCHAR(50)  DEFAULT NULL COMMENT 'CLI binary version',
    type            VARCHAR(20)  DEFAULT 'SYSTEM' COMMENT 'SYSTEM / CUSTOM',
    binary_path     VARCHAR(500) DEFAULT NULL COMMENT 'Container binary path',
    init_script     VARCHAR(500) DEFAULT NULL COMMENT 'Container init script path',
    skill_doc_path  VARCHAR(500) DEFAULT NULL COMMENT 'Container SKILL.md path',
    health_check    VARCHAR(500) DEFAULT NULL COMMENT 'Health check command',
    status          TINYINT      DEFAULT 1 COMMENT '0:disabled 1:enabled',
    creator         VARCHAR(50)  DEFAULT 'SYSTEM',
    active          TINYINT      DEFAULT 1,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cli_plugin_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_cli_plugin_binding (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    plugin_id   BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_cli_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
