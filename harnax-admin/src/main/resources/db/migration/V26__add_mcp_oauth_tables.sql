-- V26: OAuth client registrations, per-user credentials, and call audit for MCP servers
--
-- Three tables because they have three different lifetimes and three different blast radii:
--
-- - `mcp_oauth_client` is one registration per (tenant, authorization server, client_id). Several MCP
--   servers behind the same AS share it, so it cannot hang off `mcp_server` and deleting one MCP
--   server must not delete it.
-- - `mcp_user_credential` is one row per (tenant, user, mcp_server): the actual grant. Tokens live
--   only here, encrypted, and never appear in any response DTO.
-- - `mcp_call_log` is append-only audit: outcome and latency per authorization issue / refresh, no
--   request bodies and no Authorization values.
--
-- Soft delete is deliberately absent from all three. `mcp_user_credential` rows are updated in place
-- (revoke clears the ciphertexts and sets status = REVOKED, so a plain unique key keeps working when
-- the user re-consents) and hard-deleted only when the MCP server itself is deleted; a soft-deleted
-- credential row would still occupy `(tenant_id, user_id, mcp_id)` and block the re-consent insert.
-- `mcp_oauth_client` is the one place where a re-registration after deletion is plausible, so it uses
-- the V15 / V23 generated-column trick: the unique key includes a column that turns NULL once
-- `active = 0`, and MySQL ignores NULLs in unique indexes.

-- One registration per (tenant, issuer, client_id). Reuse lookup by (tenant, issuer) returns the
-- lowest id so discovery never registers a second client for an AS the tenant already talks to.
-- The two URL identity columns (issuer, callback_url) are COLLATE utf8mb4_bin: MySQL's default
-- collation folds case, but RFC 8414 compares `iss` and RFC 6749 compares `redirect_uri` as exact
-- strings, so a case-insensitive match here would silently reuse another server's registration.
-- Human-facing text columns keep the table default so they stay searchable without thinking about case.
CREATE TABLE IF NOT EXISTS mcp_oauth_client (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id              BIGINT       NOT NULL DEFAULT 1 COMMENT 'Tenant ID',
    issuer                 VARCHAR(255) COLLATE utf8mb4_bin NOT NULL COMMENT 'Authorization server issuer, exact string from metadata',
    client_id              VARCHAR(255) NOT NULL COMMENT 'client_id from manual registration, DCR, or client ID metadata document',
    client_secret_enc      TEXT DEFAULT NULL COMMENT 'AES ciphertext; NULL for public clients (PKCE only)',
    registration_source    VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/DCR/ID_METADATA',
    authorization_endpoint VARCHAR(500) DEFAULT NULL COMMENT 'Discovery snapshot',
    token_endpoint         VARCHAR(500) DEFAULT NULL COMMENT 'Discovery snapshot',
    registration_endpoint  VARCHAR(500) DEFAULT NULL COMMENT 'Discovery snapshot; NULL means no DCR support',
    revocation_endpoint    VARCHAR(500) DEFAULT NULL COMMENT 'Discovery snapshot; NULL means revoke locally only (RFC 7009 not supported)',
    scopes_supported       TEXT DEFAULT NULL COMMENT 'Discovery snapshot, comma-separated',
    callback_url           VARCHAR(500) COLLATE utf8mb4_bin NOT NULL COMMENT 'Exact redirect_uri registered at the AS; no prefix matching',
    creator                VARCHAR(100) DEFAULT '',
    active                 TINYINT      NOT NULL DEFAULT 1 COMMENT '0:deleted, 1:active',
    create_time            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    active_client_id       VARCHAR(255) GENERATED ALWAYS AS (IF(active = 1, client_id, NULL)) VIRTUAL,
    UNIQUE KEY uk_mcp_oauth_client_tenant_issuer_client (tenant_id, issuer, active_client_id),
    KEY idx_mcp_oauth_client_tenant_issuer (tenant_id, issuer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OAuth client registration per tenant and authorization server';

-- The grant itself. user_id is sys_user.id, not a username: the delivery path resolves a session back
-- to its owner, and matching on a mutable display name would let a rename steal or orphan a grant.
CREATE TABLE IF NOT EXISTS mcp_user_credential (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL DEFAULT 1 COMMENT 'Tenant ID',
    user_id           BIGINT       NOT NULL COMMENT 'sys_user.id',
    mcp_id            BIGINT       NOT NULL COMMENT 'mcp_server.id',
    access_token_enc  TEXT DEFAULT NULL COMMENT 'AES ciphertext; cleared on revoke',
    refresh_token_enc TEXT DEFAULT NULL COMMENT 'AES ciphertext; never leaves the admin process',
    access_expires_at DATETIME DEFAULT NULL COMMENT 'Expiry of the stored access token; past due is treated as missing',
    scopes            VARCHAR(512) DEFAULT NULL COMMENT 'Scopes actually granted, which may be narrower than requested',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/NEEDS_CONSENT/REVOKED',
    last_error        VARCHAR(512) DEFAULT NULL COMMENT 'Redacted failure reason; must never contain a token fragment',
    last_refreshed_at DATETIME DEFAULT NULL,
    create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mcp_user_credential_tenant_user_mcp (tenant_id, user_id, mcp_id),
    KEY idx_mcp_user_credential_mcp (mcp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user OAuth grant for an MCP server';

-- Audit only. Deliberately no request body, no response body, no header: this table is readable by
-- admins who are not the token owner, so anything resembling a credential would turn the audit log
-- into the leak.
CREATE TABLE IF NOT EXISTS mcp_call_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL DEFAULT 1 COMMENT 'Tenant ID',
    user_id     BIGINT DEFAULT NULL COMMENT 'sys_user.id; NULL when the session owner could not be resolved',
    mcp_id      BIGINT NOT NULL COMMENT 'mcp_server.id',
    session_id  VARCHAR(255) DEFAULT NULL COMMENT 'Runtime session the call came from',
    tool_name   VARCHAR(255) DEFAULT NULL COMMENT 'Tool name for call-level audit; NULL for token issuance',
    action      VARCHAR(20)  NOT NULL DEFAULT 'ISSUE' COMMENT 'ISSUE/REFRESH/REVOKE/CALL',
    outcome     VARCHAR(20)  NOT NULL COMMENT 'OK/AUTH_FAILED/NEEDS_CONSENT/ERROR',
    latency_ms  BIGINT       DEFAULT 0,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_mcp_call_log_tenant_mcp_time (tenant_id, mcp_id, create_time),
    KEY idx_mcp_call_log_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP authorization and call audit';
