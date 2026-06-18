CREATE TABLE IF NOT EXISTS api_key (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE COMMENT 'API Key name',
    key_hash        VARCHAR(64)  NOT NULL UNIQUE COMMENT 'SHA-256 hash of the raw key',
    key_prefix      VARCHAR(32)  NOT NULL COMMENT 'Key prefix for display (e.g. hnx_sk_live_xxxx)',
    scopes          VARCHAR(512) NOT NULL COMMENT 'Comma-separated scopes (e.g. api:chat,api:session)',
    tenant_id       BIGINT       NULL COMMENT 'Tenant ID',
    rate_limit      INT          NULL DEFAULT 60 COMMENT 'Rate limit per minute',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'Whether enabled (0:disabled, 1:enabled)',
    expires_at      DATETIME     NULL COMMENT 'Expiration time',
    creator         VARCHAR(64)  NULL COMMENT 'Creator',
    active          INT          NOT NULL DEFAULT 1 COMMENT 'Active status (0:deleted, 1:active)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_key_hash (key_hash),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='External API Key table';
