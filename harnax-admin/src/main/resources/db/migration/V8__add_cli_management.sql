-- V8: CLI management
-- Adds CLI tool registry, agent-cli bindings, and cli-skill bindings.
-- CLIs are installed into agent sandbox images via Dockerfile fragments;
-- skills bound to a CLI are merged into the agent's skill set at runtime.

CREATE TABLE IF NOT EXISTS cli (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL DEFAULT 1,
    name           VARCHAR(128) NOT NULL COMMENT 'CLI name, e.g. kubectl',
    description    VARCHAR(512) DEFAULT '' COMMENT 'CLI description',
    version        VARCHAR(64)  DEFAULT '' COMMENT 'CLI version, e.g. 1.30.0',
    install_script TEXT COMMENT 'Dockerfile RUN fragment that installs this CLI',
    check_command  VARCHAR(512) DEFAULT '' COMMENT 'Command to verify installation',
    env_params     TEXT COMMENT 'Environment variable declarations (JSON)',
    status         TINYINT      DEFAULT 1 COMMENT '0:disabled, 1:enabled',
    is_public      TINYINT      DEFAULT 0 COMMENT '0:no, 1:yes',
    creator        VARCHAR(64)  DEFAULT '',
    active         TINYINT      DEFAULT 1 COMMENT '0:deleted, 1:active',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cli_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_cli_binding (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT NOT NULL,
    cli_id       BIGINT NOT NULL,
    env_bindings TEXT DEFAULT NULL COMMENT 'JSON array of env binding snapshots',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_cli_binding_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cli_skill_binding (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    cli_id      BIGINT NOT NULL,
    skill_id    BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cli_skill_binding_cli_id (cli_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
