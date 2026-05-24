-- Session Router database tables
-- Stores agent-service instance information and session mappings

-- Agent service instance registry
CREATE TABLE IF NOT EXISTS agent_instance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id VARCHAR(64) NOT NULL UNIQUE COMMENT 'Unique instance identifier',
    host VARCHAR(128) NOT NULL COMMENT 'Instance host address',
    port INT NOT NULL COMMENT 'Instance port',
    status VARCHAR(16) NOT NULL DEFAULT 'UP' COMMENT 'Instance status: UP, DOWN',
    last_heartbeat DATETIME NOT NULL COMMENT 'Last heartbeat timestamp',
    active INT NOT NULL DEFAULT 1 COMMENT 'Active flag: 0=deleted, 1=active',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    INDEX idx_instance_id (instance_id),
    INDEX idx_status (status),
    INDEX idx_last_heartbeat (last_heartbeat)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent service instance registry';

-- Session to instance mapping
CREATE TABLE IF NOT EXISTS session_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL UNIQUE COMMENT 'Session identifier',
    instance_id VARCHAR(64) NOT NULL COMMENT 'Bound agent-service instance ID',
    agent_id BIGINT COMMENT 'Associated agent ID',
    last_active_time DATETIME NOT NULL COMMENT 'Last activity timestamp',
    active INT NOT NULL DEFAULT 1 COMMENT 'Active flag: 0=deleted, 1=active',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    INDEX idx_session_id (session_id),
    INDEX idx_instance_id (instance_id),
    INDEX idx_last_active_time (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Session to instance mapping';