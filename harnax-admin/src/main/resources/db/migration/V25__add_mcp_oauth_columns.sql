-- V25: make the upstream auth method an explicit column on mcp_server
--
-- Until now the only way to authenticate against an upstream MCP server was to put a literal
-- `Authorization` (or similar) entry into `headers`. That is fine as a mechanism but it carries no
-- intent: nothing in the row says "this value is a static credential" versus "this server expects a
-- per-user OAuth token", and the runtime needs that distinction before it can start injecting tokens
-- per (mcp, user) instead of one shared header for everybody.
--
-- auth_type: NONE (default, exactly today's behaviour) / STATIC_HEADER (today's `headers`, just
-- labelled) / BASIC / OAUTH2. Only OAUTH2 has a flow; the other three all keep using `headers`.
--
-- No backfill. NONE and STATIC_HEADER take the identical code path, so a row that happens to have a
-- `headers` value is not evidence of either intent -- `headers` also holds plain routing headers like
-- `X-Request-Source`, and a wrong guess here would be a label nobody could tell apart from a
-- decision. Existing rows stay NONE and an admin sets the label deliberately when it matters.
--
-- oauth_config holds non-sensitive discovery output and admin choices only (authorization_server,
-- scopes, audience, resource_indicator - the shape is admin/dto/McpOAuthConfig.kt). Client credentials
-- do NOT belong here: they are shared per (tenant, authorization server) and live in
-- `mcp_oauth_client` (V26). `SecretFieldEncryptor` does not touch this column, so the write path
-- rejects any configuration on a non-OAuth server instead of encrypting a secret into a column that
-- is read in plaintext by the UI.

ALTER TABLE mcp_server
    ADD COLUMN auth_type    VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'Upstream auth method: NONE/STATIC_HEADER/BASIC/OAUTH2' AFTER url,
    ADD COLUMN oauth_config TEXT DEFAULT NULL COMMENT 'Non-sensitive OAuth config JSON (no client credentials, no tokens)' AFTER auth_type;
