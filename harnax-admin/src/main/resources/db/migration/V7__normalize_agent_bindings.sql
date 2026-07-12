-- V7: Normalize agent bindings into separate tables
-- Creates structured binding tables for agent-tool/mcp/skill relationships.
-- Data migration from JSON columns is handled at application level.

-- ============================================================
-- 1. Create binding tables
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_tool_binding (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT NOT NULL,
    tool_id      BIGINT NOT NULL,
    enable_skip  VARCHAR(5)  DEFAULT 'false',
    need_confirm TINYINT     DEFAULT 0,
    env_bindings TEXT        DEFAULT NULL COMMENT 'JSON array of env binding snapshots',
    create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_tool_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_mcp_binding (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT NOT NULL,
    mcp_id       BIGINT NOT NULL,
    enable_skip  VARCHAR(5)  DEFAULT 'false',
    env_bindings TEXT        DEFAULT NULL COMMENT 'JSON array of env binding snapshots',
    create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_mcp_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_skill_binding (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT NOT NULL,
    skill_id     BIGINT NOT NULL,
    env_bindings TEXT        DEFAULT NULL COMMENT 'JSON array of env binding snapshots',
    create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_skill_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
