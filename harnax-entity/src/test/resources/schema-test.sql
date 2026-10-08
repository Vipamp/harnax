-- Mapper integration test baseline (harnax-entity, Testcontainers MySQLContainer + withInitScript)
--
-- The DDL below is the table-definition block of harnax-admin's schema at its last version - the baseline
-- (harnax-admin/src/main/resources/db/migration/V1__init_schema.sql) plus the forward increments stacked on
-- it (V2__agent_session_memory.sql, V3__tool_invocation_metrics.sql) - copied so this file and the
-- production schema cannot drift apart the way the hand-maintained version did. What matters is the shape
-- Flyway ends at, because that is what SchemaBaselineDriftIT replays and compares. Regenerate this file's
-- DDL block whenever the baseline or any increment changes.
--
-- Production's initial-data INSERTs are deliberately absent: the fixtures below allocate their own
-- users, tenants, agents and sessions, and seeded rows with fixed ids would collide with them.
CREATE TABLE IF NOT EXISTS `agent` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Agent ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL,
  `description` text,
  `system_prompt` text COMMENT 'System prompt (Markdown format)',
  `model_id` bigint DEFAULT NULL COMMENT 'Model ID',
  `owner` varchar(100) DEFAULT NULL,
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
  `skill_self_write` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Whether the agent may author skills itself (0: no, 1: yes)',
  `memory_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Whether the agent has long-term memory (0: no, 1: yes)',
  `session_memory_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Whether the agent also keeps a per-session memory layer that is later promoted into the long-term one (0: no, 1: yes)',
  `creator` varchar(100) DEFAULT NULL,
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_name` varchar(100) GENERATED ALWAYS AS (if((`active` = 1),`name`,NULL)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tenant_active_name` (`tenant_id`,`active_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `agent_cli_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL,
  `cli_id` bigint NOT NULL,
  `env_bindings` text COMMENT 'JSON array of env binding snapshots',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_cli_binding_agent_id_cli_id` (`agent_id`,`cli_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `agent_mcp_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL,
  `mcp_id` bigint NOT NULL,
  `env_bindings` text COMMENT 'JSON array of env binding snapshots',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp_binding_agent_id_mcp_id` (`agent_id`,`mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `agent_skill_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL,
  `skill_id` bigint NOT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill_binding_agent_id_skill_id` (`agent_id`,`skill_id`),
  KEY `idx_agent_skill_binding_skill_id` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `agent_tool` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Tool ID',
  `name` varchar(100) NOT NULL COMMENT 'Tool identifier name (snake_case, unique across the platform)',
  `display_name` varchar(200) DEFAULT NULL COMMENT 'Display name',
  `display_name_zh` varchar(200) DEFAULT NULL COMMENT 'Display name (Chinese, for i18n zh-CN locale)',
  `description` text COMMENT 'Tool description (sent to LLM)',
  `bean_name` varchar(200) DEFAULT NULL COMMENT 'Spring Bean name of the builtin tool',
  `method_name` varchar(100) DEFAULT NULL COMMENT 'Java method name (one record per @Tool method)',
  `required_env_param_keys` varchar(1000) DEFAULT NULL COMMENT 'Required environment parameter keys, JSON array',
  `read_only` tinyint(1) DEFAULT '0' COMMENT 'Is read-only tool (0: No, 1: Yes)',
  `need_confirm` tinyint(1) DEFAULT '0' COMMENT 'Requires human confirmation (0: No, 1: Yes)',
  `is_required` tinyint NOT NULL DEFAULT '0' COMMENT 'Is mandatory tool (0: optional, 1: required)',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `creator` varchar(100) DEFAULT NULL COMMENT 'Creator',
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tool_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent Tool definition table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `agent_tool_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_id` bigint NOT NULL,
  `tool_id` bigint NOT NULL,
  `need_confirm` tinyint DEFAULT '0',
  `env_bindings` text COMMENT 'JSON array of env binding snapshots',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_tool_binding_agent_id_tool_id` (`agent_id`,`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `agent_tool_env_param` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Env entry ID',
  `tool_id` bigint NOT NULL COMMENT 'FK to agent_tool.id',
  `env_param_name` varchar(200) NOT NULL COMMENT 'Environment parameter name',
  `description` varchar(500) DEFAULT NULL COMMENT 'Human-readable description shown in Admin UI',
  `required` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Is required (0: No, 1: Yes)',
  `secret` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Is sensitive (0: No, 1: Yes, encrypted storage)',
  `default_value` text COMMENT 'Default value (required when required=1)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_env_param_name` (`tool_id`,`env_param_name`),
  KEY `idx_tool_id` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent Tool environment variable definitions';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `api_key` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL COMMENT 'API Key name',
  `key_type` varchar(16) NOT NULL DEFAULT 'TEMPORARY' COMMENT 'Key type: PERMANENT, TEMPORARY or SYSTEM',
  `user_id` bigint DEFAULT NULL COMMENT 'Associated user ID (for PERMANENT keys)',
  `raw_key_encrypted` varchar(256) DEFAULT NULL COMMENT 'AES-encrypted raw key (for PERMANENT/SYSTEM keys only)',
  `service_name` varchar(64) DEFAULT NULL COMMENT 'Service name (for SYSTEM keys, e.g. channel-service)',
  `key_hash` varchar(64) NOT NULL COMMENT 'SHA-256 hash of the raw key',
  `key_prefix` varchar(32) NOT NULL COMMENT 'Key prefix for display (e.g. hnx_sk_live_xxxx)',
  `scopes` varchar(512) NOT NULL COMMENT 'Comma-separated scopes (e.g. api:chat,api:session)',
  `tenant_id` bigint DEFAULT NULL COMMENT 'Tenant ID',
  `rate_limit` int DEFAULT '60' COMMENT 'Rate limit per minute',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Whether enabled (0:disabled, 1:enabled)',
  `expires_at` datetime DEFAULT NULL COMMENT 'Expiration time',
  `creator` varchar(64) DEFAULT NULL COMMENT 'Creator',
  `active` int NOT NULL DEFAULT '1' COMMENT 'Active status (0:deleted, 1:active)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `key_hash` (`key_hash`),
  UNIQUE KEY `uk_user_permanent` (`user_id`,`key_type`),
  UNIQUE KEY `uk_service_system` (`service_name`,`key_type`),
  KEY `idx_name` (`name`),
  KEY `idx_key_hash` (`key_hash`),
  KEY `idx_enabled` (`enabled`),
  KEY `idx_key_type` (`key_type`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_service_name` (`service_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API Key table (supports PERMANENT / TEMPORARY / SYSTEM types)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `channel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL COMMENT 'Channel name',
  `type` varchar(20) NOT NULL COMMENT 'Channel type: wecom/wechat/feishu/dingtalk/http',
  `agent_id` bigint NOT NULL COMMENT 'Associated Agent ID',
  `callback_key` varchar(100) NOT NULL COMMENT 'Callback key (for generating callback URL)',
  `session_id` varchar(64) NOT NULL COMMENT 'Immutable session ID (UUID), generated on creation',
  `communication_mode` varchar(20) NOT NULL DEFAULT 'webhook' COMMENT 'Communication mode: webhook/websocket/long_polling',
  `permission_mode` varchar(20) NOT NULL DEFAULT 'DEFAULT' COMMENT 'Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Auto-start with service (0: No, 1: Yes)',
  `config_json` text COMMENT 'Channel-specific configuration JSON',
  `description` text COMMENT 'Description',
  `creator` varchar(100) NOT NULL DEFAULT 'system' COMMENT 'Creator',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Enabled (0: Disabled, 1: Enabled)',
  `active` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Logical delete (0: Deleted, 1: Active)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  `enable_think` tinyint NOT NULL DEFAULT '0' COMMENT 'Enable thinking mode (0:no, 1:yes)',
  `enable_search` tinyint NOT NULL DEFAULT '0' COMMENT 'Enable web search (0:no, 1:yes)',
  `enable_plan` tinyint NOT NULL DEFAULT '0' COMMENT 'Enable plan mode (0:no, 1:yes)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_callback_key` (`callback_key`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_type_enabled_status_active` (`type`,`enabled`,`status`,`active`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Channel configuration table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `cli` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL COMMENT 'CLI name, e.g. kubectl',
  `description` varchar(512) DEFAULT '' COMMENT 'CLI description',
  `version` varchar(64) DEFAULT '' COMMENT 'CLI version, e.g. 1.30.0',
  `check_command` varchar(512) DEFAULT '' COMMENT 'Command to verify installation',
  `skill_id` bigint DEFAULT NULL COMMENT 'FK to skill.id: the SKILL.md shipped inside the package',
  `env_params` text COMMENT 'Environment variable declarations (JSON)',
  `status` tinyint DEFAULT '1' COMMENT '0:disabled, 1:enabled',
  `active` tinyint DEFAULT '1' COMMENT '0:deleted, 1:active',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `package_digest` char(64) NOT NULL DEFAULT '' COMMENT 'sha256 of the whole package zip: package identity, MinIO object key, re-registration test',
  `payload_digest` char(64) NOT NULL DEFAULT '' COMMENT 'Canonical sha256 of payload/ plus deps: the sandbox image fingerprint. Deliberately not package_digest — editing only SKILL.md must not rebuild live containers',
  `package_object` varchar(256) NOT NULL DEFAULT '' COMMENT 'MinIO object key of the package',
  `deps_apt` varchar(512) DEFAULT NULL COMMENT 'apt packages installed alongside the payload (JSON array)',
  `runtime_env` varchar(1024) DEFAULT NULL COMMENT 'Env slots the platform injects at container creation, JSON object of name to literal value or platform slot (e.g. HARNAX_URL -> platform.adminUrl)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cli_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `env_variable` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `env_key` varchar(200) NOT NULL COMMENT 'Environment variable key',
  `env_value` text NOT NULL COMMENT 'Environment variable value',
  `description` varchar(500) DEFAULT NULL COMMENT 'Description',
  `sensitive` tinyint(1) DEFAULT '0' COMMENT 'Sensitive flag (0: No, 1: Yes)',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Enabled status (0: Disabled, 1: Enabled)',
  `creator` varchar(100) DEFAULT NULL COMMENT 'Creator',
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  `active_env_key` varchar(200) GENERATED ALWAYS AS (if((`active` = 1),`env_key`,NULL)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_env_tenant_creator_active_key` (`tenant_id`,`creator`,`active_env_key`),
  KEY `idx_creator` (`creator`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Environment Variable';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `mcp_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `user_id` bigint DEFAULT NULL COMMENT 'sys_user.id; NULL when the session owner could not be resolved',
  `mcp_id` bigint NOT NULL COMMENT 'mcp_server.id',
  `session_id` varchar(255) DEFAULT NULL COMMENT 'Runtime session the call came from',
  `tool_name` varchar(255) DEFAULT NULL COMMENT 'Tool name for call-level audit; NULL for token issuance',
  `action` varchar(20) NOT NULL DEFAULT 'ISSUE' COMMENT 'ISSUE/REFRESH/REVOKE/CALL',
  `outcome` varchar(20) NOT NULL COMMENT 'OK/AUTH_FAILED/NEEDS_CONSENT/ERROR',
  `latency_ms` bigint DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mcp_call_log_tenant_mcp_time` (`tenant_id`,`mcp_id`,`create_time`),
  KEY `idx_mcp_call_log_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP authorization and call audit';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `mcp_oauth_client` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `issuer` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'Authorization server issuer, exact string from metadata',
  `client_id` varchar(255) NOT NULL COMMENT 'client_id from manual registration, DCR, or client ID metadata document',
  `client_secret_enc` text COMMENT 'AES ciphertext; NULL for public clients (PKCE only)',
  `registration_source` varchar(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/DCR/ID_METADATA',
  `authorization_endpoint` varchar(500) DEFAULT NULL COMMENT 'Discovery snapshot',
  `token_endpoint` varchar(500) DEFAULT NULL COMMENT 'Discovery snapshot',
  `registration_endpoint` varchar(500) DEFAULT NULL COMMENT 'Discovery snapshot; NULL means no DCR support',
  `revocation_endpoint` varchar(500) DEFAULT NULL COMMENT 'Discovery snapshot; NULL means revoke locally only (RFC 7009 not supported)',
  `scopes_supported` text COMMENT 'Discovery snapshot, comma-separated',
  `callback_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'Exact redirect_uri registered at the AS; no prefix matching',
  `creator` varchar(100) DEFAULT '',
  `active` tinyint NOT NULL DEFAULT '1' COMMENT '0:deleted, 1:active',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_client_id` varchar(255) GENERATED ALWAYS AS (if((`active` = 1),`client_id`,NULL)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_oauth_client_tenant_issuer_client` (`tenant_id`,`issuer`,`active_client_id`),
  KEY `idx_mcp_oauth_client_tenant_issuer` (`tenant_id`,`issuer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OAuth client registration per tenant and authorization server';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `mcp_server` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'MCP Server ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL COMMENT 'MCP Server name',
  `description` text COMMENT 'MCP Server description',
  `type` varchar(20) NOT NULL COMMENT 'MCP type (stdio/sse/streamablehttp)',
  `command` varchar(500) DEFAULT NULL COMMENT 'Command (for stdio type)',
  `url` varchar(500) DEFAULT NULL COMMENT 'URL (for sse/streamablehttp type)',
  `auth_type` varchar(20) NOT NULL DEFAULT 'NONE' COMMENT 'Upstream auth method: NONE/STATIC_HEADER/BASIC/OAUTH2',
  `oauth_config` text COMMENT 'Non-sensitive OAuth config JSON (no client credentials, no tokens)',
  `headers` text COMMENT 'HTTP headers JSON: [{"key":"Authorization","value":"Bearer xxx","secret":true}]',
  `env_params` text COMMENT 'Environment parameters JSON',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) DEFAULT NULL,
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_name` varchar(100) GENERATED ALWAYS AS (if((`active` = 1),`name`,NULL)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_server_tenant_active_name` (`tenant_id`,`active_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP Server table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `mcp_user_credential` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `user_id` bigint NOT NULL COMMENT 'sys_user.id',
  `mcp_id` bigint NOT NULL COMMENT 'mcp_server.id',
  `access_token_enc` text COMMENT 'AES ciphertext; cleared on revoke',
  `refresh_token_enc` text COMMENT 'AES ciphertext; never leaves the admin process',
  `access_expires_at` datetime DEFAULT NULL COMMENT 'Expiry of the stored access token; past due is treated as missing',
  `scopes` varchar(512) DEFAULT NULL COMMENT 'Scopes actually granted, which may be narrower than requested',
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/NEEDS_CONSENT/REVOKED',
  `last_error` varchar(512) DEFAULT NULL COMMENT 'Redacted failure reason; must never contain a token fragment',
  `last_refreshed_at` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_user_credential_tenant_user_mcp` (`tenant_id`,`user_id`,`mcp_id`),
  KEY `idx_mcp_user_credential_mcp` (`mcp_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Per-user OAuth grant for an MCP server';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `model` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Model ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL COMMENT 'Model name',
  `model_name` varchar(100) NOT NULL COMMENT 'Model identifier',
  `provider_id` bigint NOT NULL COMMENT 'Model Provider ID',
  `description` text COMMENT 'Model description',
  `model_type` varchar(20) NOT NULL COMMENT 'Model type (chat/embedding)',
  `support_internet` tinyint(1) DEFAULT '0' COMMENT 'Support internet search (0: No, 1: Yes)',
  `support_reasoning` tinyint(1) DEFAULT '0' COMMENT 'Support reasoning (0: No, 1: Yes)',
  `thinking_mode` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Thinking mode (0:not supported, 1:optional, 2:required)',
  `support_tool` tinyint(1) DEFAULT '0' COMMENT 'Support tools (0: No, 1: Yes)',
  `support_mcp` tinyint(1) DEFAULT '0' COMMENT 'Support MCP (0: No, 1: Yes)',
  `support_vision` tinyint(1) DEFAULT '0' COMMENT 'Support vision (0: No, 1: Yes)',
  `context_window` int DEFAULT NULL COMMENT 'Model context window in tokens',
  `price` decimal(10,4) DEFAULT '0.0000' COMMENT 'Price (CNY per million tokens)',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint(1) DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) DEFAULT NULL COMMENT 'Creator',
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  KEY `idx_provider_id` (`provider_id`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Model table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `model_provider` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Provider ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `type` varchar(50) NOT NULL COMMENT 'Provider type (dashscope/openai/ollama)',
  `name` varchar(100) NOT NULL COMMENT 'Display name',
  `description` varchar(500) DEFAULT NULL COMMENT 'Provider description',
  `api_key` varchar(500) DEFAULT NULL COMMENT 'API key',
  `base_url` varchar(500) DEFAULT NULL COMMENT 'API base URL',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint(1) DEFAULT '1' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) NOT NULL COMMENT 'Creator',
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Model Provider table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `mp_chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `session_id` bigint NOT NULL COMMENT 'Session ID (FK to mp_session)',
  `role` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT 'Message role (user/assistant/system)',
  `content` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'Plain text content',
  `segments_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'Message segments (JSON array)',
  `token_usage_json` text COLLATE utf8mb4_unicode_ci COMMENT 'Token usage info (JSON object)',
  `image_urls_json` text COLLATE utf8mb4_unicode_ci COMMENT 'Image URLs (JSON array)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  PRIMARY KEY (`id`),
  KEY `idx_mp_chat_message_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mobile chat messages';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `mp_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` bigint NOT NULL COMMENT 'User ID (FK to sys_user)',
  `session_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'Session name',
  `router_session_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'Corresponding router session ID',
  `agent_id` bigint NOT NULL DEFAULT '0' COMMENT 'Associated Agent ID',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT 'Status (0:archived, 1:active)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`),
  KEY `idx_mp_session_user_id` (`user_id`),
  KEY `idx_mp_session_status` (`status`),
  KEY `idx_mp_session_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Mobile chat sessions';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `plan_note` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Plan ID',
  `session_id` varchar(128) NOT NULL COMMENT 'Session ID',
  `plan_id` varchar(128) NOT NULL COMMENT 'Plan identifier',
  `name` varchar(256) NOT NULL COMMENT 'Plan name',
  `description` text COMMENT 'Plan description',
  `expected_outcome` text COMMENT 'Expected outcome',
  `subtasks` text COMMENT 'Subtasks list (JSON format)',
  `created_at` varchar(64) DEFAULT NULL COMMENT 'Creation timestamp',
  `finished_at` varchar(64) DEFAULT NULL COMMENT 'Completion timestamp',
  `cost_timeseconds` bigint DEFAULT '0' COMMENT 'Execution time (seconds)',
  `status` varchar(32) DEFAULT 'TODO' COMMENT 'Status (TODO, IN_PROGRESS, DONE, ABANDONED)',
  PRIMARY KEY (`id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_plan_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Plan Note table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `process_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Log ID',
  `agent_id` bigint DEFAULT NULL COMMENT 'Agent ID',
  `agent_name` varchar(255) DEFAULT NULL COMMENT 'Agent name',
  `session_id` varchar(255) DEFAULT NULL COMMENT 'Session ID',
  `message` text COMMENT 'Log message',
  `log_type` varchar(20) DEFAULT 'INFO' COMMENT 'Log type (INFO/WARN/ERROR)',
  `stack_trace` text COMMENT 'Exception stack trace',
  `ts` datetime DEFAULT NULL COMMENT 'Timestamp',
  `tenant_id` bigint DEFAULT NULL COMMENT 'Tenant ID',
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_log_type` (`log_type`),
  KEY `idx_ts` (`ts`),
  KEY `idx_tenant_ts` (`tenant_id`,`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Process Log table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Session ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `title` varchar(100) NOT NULL,
  `session_description` text,
  `session_id` varchar(100) NOT NULL COMMENT 'Unique session identifier',
  `agent_id` bigint DEFAULT NULL COMMENT 'Agent ID',
  `name` varchar(100) DEFAULT NULL,
  `description` text,
  `system_prompt` text COMMENT 'System prompt (Markdown format)',
  `model_id` bigint DEFAULT NULL COMMENT 'Model ID',
  `enable_think` tinyint(1) DEFAULT '0' COMMENT 'Enable deep thinking (0: No, 1: Yes)',
  `enable_search` tinyint(1) DEFAULT '0' COMMENT 'Enable internet search (0: No, 1: Yes)',
  `enable_plan` tinyint(1) DEFAULT '0' COMMENT 'Enable planning (0: No, 1: Yes)',
  `permission_mode` varchar(20) NOT NULL DEFAULT 'DEFAULT' COMMENT 'Permission mode (DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK)',
  `owner` varchar(100) DEFAULT NULL,
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint(1) DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) DEFAULT NULL,
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `team_id` bigint DEFAULT NULL COMMENT 'Team ID when this session runs in team mode, NULL for an ordinary agent session',
  PRIMARY KEY (`id`),
  KEY `idx_creator` (`creator`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_team_id` (`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Session table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `skill` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Skill ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL,
  `repository_id` bigint NOT NULL COMMENT 'Repository ID',
  `description` text,
  `skillmd` mediumtext COMMENT 'skill.md content',
  `resources` mediumtext COMMENT 'Bundled resource files as JSON (path -> content)',
  `version` varchar(100) DEFAULT NULL COMMENT 'Skill version',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) DEFAULT NULL,
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_name` varchar(100) GENERATED ALWAYS AS (if((`active` = 1),`name`,NULL)) VIRTUAL,
  `origin` varchar(16) NOT NULL DEFAULT 'human' COMMENT 'Provenance: human / agent_promoted',
  `origin_ref` varchar(64) DEFAULT NULL COMMENT 'Session the agent proposed this skill in, NULL for human skills',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_repo_active_name` (`repository_id`,`active_name`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skill table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `skill_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Draft ID',
  `tenant_id` bigint NOT NULL COMMENT 'Tenant resolved server-side from the session, never taken from the request body',
  `name` varchar(100) NOT NULL COMMENT 'Proposed skill name; same-name proposals may coexist, dedup happens at promotion',
  `description` text COMMENT 'Proposed description',
  `skillmd` mediumtext NOT NULL COMMENT 'Proposed SKILL.md body',
  `resources` mediumtext COMMENT 'path -> content JSON, the same shape as skill.resources',
  `script_previews` mediumtext COMMENT 'relPath / headPreview / totalLines / sha256 per script, from SkillCandidate.scriptFiles',
  `scan_verdict` varchar(16) DEFAULT NULL COMMENT 'Upstream SkillSecurityScanner verdict: SAFE / CAUTION / DANGEROUS',
  `scan_findings` mediumtext COMMENT 'Upstream scan findings as JSON; the promotion rescan goes to skill_review_log',
  `source_session_id` varchar(64) NOT NULL COMMENT 'Session the agent proposed the skill in',
  `agent_id` bigint DEFAULT NULL COMMENT 'Agent that proposed it, resolved from the session',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED / EXPIRED',
  `reviewed_by` varchar(100) DEFAULT NULL COMMENT 'Reviewer username, once decided',
  `reviewed_at` datetime DEFAULT NULL COMMENT 'Review time, once decided',
  `reject_reason` varchar(512) DEFAULT NULL COMMENT 'Why a reviewer rejected it',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill_draft_tenant_status` (`tenant_id`,`status`),
  KEY `idx_skill_draft_session` (`source_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent-proposed skills awaiting human review';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `skill_repository` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Repository ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL,
  `url` varchar(500) DEFAULT NULL,
  `description` text,
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) DEFAULT NULL,
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `branch` varchar(100) DEFAULT 'main',
  `source_type` varchar(20) NOT NULL DEFAULT 'GIT' COMMENT 'Source type: GIT / NPM / ZIP',
  `source_config` text COMMENT 'Source configuration JSON',
  `version` varchar(100) DEFAULT NULL COMMENT 'Version identifier',
  `active_name` varchar(100) GENERATED ALWAYS AS (if((`active` = 1),`name`,NULL)) VIRTUAL,
  `builtin_guard` tinyint GENERATED ALWAYS AS (if(((`active` = 1) and (`name` = _utf8mb4'builtin-cli-skills')),1,NULL)) VIRTUAL,
  `last_sync_time` datetime DEFAULT NULL COMMENT 'When the last sync finished, NULL until the first run',
  `last_sync_status` varchar(16) DEFAULT NULL COMMENT 'SUCCESS / PARTIAL / FAILED / EMPTY, NULL until the first run',
  `last_sync_detail` mediumtext COMMENT 'Last sync report as JSON: saved/installed/updated/failed/flagged/error',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_repository_tenant_active_name` (`tenant_id`,`active_name`),
  UNIQUE KEY `uk_skill_repository_builtin_guard` (`builtin_guard`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_skill_repository_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skill Repository table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `skill_review_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Log ID',
  `tenant_id` bigint NOT NULL COMMENT 'Tenant ID',
  `subject` varchar(16) NOT NULL COMMENT 'What the row is about: SKILL / DRAFT',
  `subject_id` bigint NOT NULL COMMENT 'Row id of the subject',
  `actor` varchar(64) NOT NULL COMMENT 'Real operator: a sys_user username, or the sentinel agent / system',
  `action` varchar(32) NOT NULL COMMENT 'PROPOSE / SCAN / APPROVE / REJECT / ENABLE / DISABLE / DELETE / VISIBILITY_CHANGE',
  `detail` mediumtext COMMENT 'Scan findings, reject reason, policy before and after, as JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill_review_log_subject` (`subject`,`subject_id`),
  KEY `idx_skill_review_log_tenant_time` (`tenant_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Audit trail for skill-domain state changes';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `skill_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Event ID',
  `tenant_id` bigint NOT NULL COMMENT 'Tenant ID',
  `skill_id` bigint NOT NULL COMMENT 'Skill ID; never keyed by name, which is only unique inside one repository',
  `user_id` bigint DEFAULT NULL COMMENT 'Owning user id, NULL until the runtime carries one; never a sentinel',
  `event` varchar(16) NOT NULL COMMENT 'VIEW=loaded into the context, USE=its instructions were executed',
  `session_id` varchar(64) NOT NULL COMMENT 'Session that produced the event',
  `occurred_at` datetime NOT NULL COMMENT 'Event time',
  PRIMARY KEY (`id`),
  KEY `idx_skill_usage_skill_time` (`skill_id`,`occurred_at`),
  KEY `idx_skill_usage_tenant_skill` (`tenant_id`,`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skill load and use stream';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `skill_visibility_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Policy ID',
  `skill_id` bigint NOT NULL COMMENT 'Skill the policy restricts, one row per skill',
  `tenant_id` bigint NOT NULL COMMENT 'Tenant owning that skill',
  `mode` varchar(16) NOT NULL COMMENT 'ALL / CANARY / ALLOW_LIST / ENV',
  `canary_pct` int DEFAULT NULL COMMENT 'Rollout percentage 0-100, when mode = CANARY',
  `user_ids` text COMMENT 'User id list as JSON, when mode = ALLOW_LIST',
  `environments` varchar(255) DEFAULT NULL COMMENT 'Environment labels, comma separated, when mode = ENV',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_visibility_policy_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Runtime visibility policy of one skill';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `sys_token_blacklist` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Blacklist ID',
  `token` varchar(512) NOT NULL COMMENT 'JWT Token',
  `token_hash` varchar(64) NOT NULL COMMENT 'Token hash (SHA256)',
  `username` varchar(50) DEFAULT NULL,
  `user_id` bigint DEFAULT NULL COMMENT 'User ID',
  `reason` varchar(50) DEFAULT 'logout' COMMENT 'Blacklist reason (logout/revoke/ban/expired)',
  `expire_time` datetime NOT NULL COMMENT 'Token expiration time',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_ip` varchar(50) DEFAULT NULL COMMENT 'Client IP address',
  PRIMARY KEY (`id`),
  KEY `idx_expire_time` (`expire_time`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_username` (`username`),
  KEY `idx_token_lookup` (`token_hash`,`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Token blacklist table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'User ID',
  `tenant_id` bigint DEFAULT NULL COMMENT 'Tenant ID (primary tenant)',
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL,
  `nickname` varchar(50) NOT NULL,
  `email` varchar(100) NOT NULL,
  `phone` varchar(20) NOT NULL,
  `gender` tinyint DEFAULT '2' COMMENT 'Gender (0: Male, 1: Female, 2: Unknown)',
  `avatar` varchar(255) DEFAULT '' COMMENT 'Avatar URL',
  `status` tinyint DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_admin` tinyint DEFAULT '0' COMMENT 'Is admin (0: No, 1: Yes)',
  `active` tinyint DEFAULT '1' COMMENT 'Active status (0: Inactive, 1: Active)',
  `last_login_time` datetime DEFAULT NULL COMMENT 'Last login time',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_username` varchar(50) GENERATED ALWAYS AS (if((`active` = 1),`username`,NULL)) VIRTUAL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_username` (`active_username`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='User table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `team` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Team ID',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL COMMENT 'Team name',
  `description` text COMMENT 'Team description',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `is_public` tinyint DEFAULT '0' COMMENT 'Public visibility (0: Private, 1: Public)',
  `creator` varchar(100) DEFAULT NULL COMMENT 'Creator',
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `system_prompt` text NOT NULL COMMENT 'System prompt of the lead, team orchestration rules included',
  `model_id` bigint NOT NULL COMMENT 'FK to model.id, the model the lead runs on',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Multi-agent team table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `team_artifact` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Artifact ID',
  `file_id` varchar(64) NOT NULL COMMENT 'Opaque artifact reference (UUID), stable for the user and the lead',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT 'Owning tenant',
  `session_id` varchar(100) NOT NULL COMMENT 'Root team session this artifact belongs to',
  `team_id` bigint NOT NULL COMMENT 'FK to team.id',
  `member_agent_id` bigint NOT NULL COMMENT 'FK to agent.id — the member that produced it',
  `child_session_id` varchar(100) NOT NULL COMMENT 'Member child session that produced it (its own state and sandbox scope)',
  `file_name` varchar(255) NOT NULL COMMENT 'Original file name; may repeat across artifacts',
  `mime_type` varchar(100) NOT NULL DEFAULT 'application/octet-stream' COMMENT 'MIME type',
  `size_bytes` bigint NOT NULL DEFAULT '0' COMMENT 'Size in bytes',
  `object_key` varchar(500) NOT NULL COMMENT 'Internal MinIO object key, never accepted from the model',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_id` (`file_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Team artifact handoff metadata table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `team_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Member binding ID',
  `team_id` bigint NOT NULL COMMENT 'FK to team.id',
  `member_agent_id` bigint NOT NULL COMMENT 'FK to agent.id — the member agent',
  `delegation_description` varchar(500) NOT NULL DEFAULT '' COMMENT 'What this member is responsible for in this team; defaults to the agent description, never written back to it',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_member` (`team_id`,`member_agent_id`),
  KEY `idx_member_agent_id` (`member_agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Team member binding table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `team_skill_binding` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Binding ID',
  `team_id` bigint NOT NULL COMMENT 'FK to team.id',
  `skill_id` bigint NOT NULL COMMENT 'FK to skill.id',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_skill_binding_team_id_skill_id` (`team_id`,`skill_id`),
  KEY `idx_team_skill_binding_skill_id` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Skills the lead of a team is given';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Tenant ID',
  `name` varchar(100) NOT NULL COMMENT 'Tenant name',
  `status` tinyint(1) DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `creator` varchar(100) NOT NULL COMMENT 'Creator',
  `active` tinyint(1) DEFAULT '1' COMMENT 'Active status (0: Deleted, 1: Active)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Tenant table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `token_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Stats ID',
  `agent_id` bigint DEFAULT NULL COMMENT 'Agent ID',
  `session_id` varchar(255) DEFAULT NULL COMMENT 'Session ID',
  `chat_model_id` bigint DEFAULT NULL COMMENT 'Chat Model ID',
  `input_token` bigint DEFAULT '0' COMMENT 'Input token count',
  `output_token` bigint DEFAULT '0' COMMENT 'Output token count',
  `total_token` bigint DEFAULT '0' COMMENT 'Total token count',
  `ts` datetime DEFAULT NULL,
  `fee` decimal(10,0) DEFAULT NULL,
  `tenant_id` bigint DEFAULT NULL COMMENT 'Tenant ID',
  PRIMARY KEY (`id`),
  KEY `idx_agent_id` (`agent_id`),
  KEY `idx_session_id` (`session_id`),
  KEY `idx_chat_model_id` (`chat_model_id`),
  KEY `idx_ts` (`ts`),
  KEY `idx_tenant_ts` (`tenant_id`,`ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Token Statistics table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `tool_invocation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Invocation event ID',
  `tenant_id` bigint DEFAULT NULL COMMENT 'Owning tenant; NULL when the delivered spec named none, and no tenant-scoped read returns such a row',
  `agent_id` bigint DEFAULT NULL COMMENT 'Owning agent; NULL for a team lead, which has no agent row',
  `session_id` varchar(255) DEFAULT NULL COMMENT 'Session that produced the call',
  `user_id` bigint DEFAULT NULL COMMENT 'End user behind the call, NULL for channel sessions and service keys',
  `kind` varchar(16) NOT NULL COMMENT 'Origin (builtin: delivered tool, mcp: MCP server tool, cli: delivered CLI package run through the shell, shell: bare shell command, framework: harness built-in)',
  `tool_name` varchar(255) NOT NULL COMMENT 'Tool name as the model sees it; the matched command name when kind = cli',
  `mcp_id` bigint DEFAULT NULL COMMENT 'MCP server row, set only when kind = mcp',
  `cli_id` bigint DEFAULT NULL COMMENT 'CLI package row, set only when kind = cli',
  `outcome` varchar(16) NOT NULL COMMENT 'Terminal state (SUCCESS, ERROR, DENIED, INTERRUPTED)',
  `error_message` varchar(512) DEFAULT NULL COMMENT 'Failure reason, truncated',
  `args_json` text COMMENT 'Tool input as JSON, truncated; NULL when payload capture is off',
  `result_excerpt` text COMMENT 'Leading part of the tool result, truncated; NULL when payload capture is off',
  `duration_ms` bigint NOT NULL COMMENT 'End time minus start time',
  `start_time` datetime(3) DEFAULT NULL COMMENT 'Call start; milliseconds because a second-resolution column collapses two calls inside one second onto one instant',
  `end_time` datetime(3) DEFAULT NULL COMMENT 'Call end',
  `ts` datetime(3) NOT NULL COMMENT 'Recorded time, equal to end_time; aggregation and indexes key on it',
  PRIMARY KEY (`id`),
  KEY `idx_tool_invocation_log_tenant_ts` (`tenant_id`,`ts`),
  KEY `idx_tool_invocation_log_tenant_kind_ts` (`tenant_id`,`kind`,`ts`),
  KEY `idx_tool_invocation_log_mcp_ts` (`mcp_id`,`ts`),
  KEY `idx_tool_invocation_log_cli_ts` (`cli_id`,`ts`),
  KEY `idx_tool_invocation_log_session` (`session_id`),
  KEY `idx_tool_invocation_log_tool_name` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='One row per tool invocation, kept for a bounded window';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `tool_invocation_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Aggregate row ID',
  `stat_date` date NOT NULL COMMENT 'Day of the calls, taken from tool_invocation_log.ts',
  `tenant_id` bigint NOT NULL COMMENT 'Owning tenant; detail rows without one are not aggregated at all',
  `kind` varchar(16) NOT NULL COMMENT 'Origin bucket, same vocabulary as the detail table',
  `subject_id` bigint NOT NULL DEFAULT '0' COMMENT 'mcp_id when kind = mcp, cli_id when kind = cli, 0 otherwise; 0 rather than NULL because a unique index does not treat NULLs as equal, and NULL would make the upsert insert a second row for the same day',
  `tool_name` varchar(255) NOT NULL DEFAULT '' COMMENT 'Tool name as the model sees it; every kind carries it, so two tools of one MCP server are two rows on a day',
  `calls` int NOT NULL COMMENT 'Total invocations',
  `successes` int NOT NULL COMMENT 'Invocations ending SUCCESS',
  `errors` int NOT NULL COMMENT 'Invocations ending ERROR',
  `denials` int NOT NULL COMMENT 'Invocations ending DENIED',
  `interruptions` int NOT NULL COMMENT 'Invocations ending INTERRUPTED',
  `sum_duration_ms` bigint NOT NULL COMMENT 'Duration total; the mean is this divided by calls',
  `max_duration_ms` bigint NOT NULL COMMENT 'Longest single call of the day',
  `le_100ms` int NOT NULL DEFAULT '0' COMMENT 'Calls of at most 100 ms',
  `le_500ms` int NOT NULL DEFAULT '0' COMMENT 'Calls over 100 ms and at most 500 ms',
  `le_2s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 500 ms and at most 2 s',
  `le_10s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 2 s and at most 10 s',
  `le_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 10 s and at most 30 s',
  `gt_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 30 s',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_invocation_stats_day` (`stat_date`,`tenant_id`,`kind`,`subject_id`,`tool_name`),
  KEY `idx_tool_invocation_stats_tenant_date` (`tenant_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Daily rollup of tool_invocation_log, retained permanently';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `user_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Relationship ID',
  `user_id` bigint NOT NULL COMMENT 'User ID',
  `tenant_id` bigint NOT NULL COMMENT 'Tenant ID',
  `role` varchar(50) NOT NULL DEFAULT 'member' COMMENT 'Role (admin/member)',
  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT 'Status (0: Disabled, 1: Enabled)',
  `joined_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Join time',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_id` (`tenant_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='User-Tenant relationship table';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;



-- ============================================
-- Fixtures: 3 to 5 rows per table, covering active, logically deleted (active = 0)
-- and disabled (status = 0) so the filters have something to discriminate on.
-- ============================================

INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `phone`, `gender`, `avatar`, `status`, `is_admin`, `active`) VALUES
('testuser1', 'password123', '测试用户1', 'test1@example.com', '13800138001', 1, 'https://example.com/avatar1.jpg', 1, 0, 1),
('testuser2', 'password123', '测试用户2', 'test2@example.com', '13800138002', 2, 'https://example.com/avatar2.jpg', 1, 0, 1),
('testuser3', 'password123', '测试用户3', 'test3@example.com', '13800138003', 0, 'https://example.com/avatar3.jpg', 0, 0, 1),
('admin', 'admin123', '管理员', 'admin@example.com', '13800138000', 1, 'https://example.com/admin.jpg', 1, 1, 1),
('deleted_user', 'password123', '已删除用户', 'deleted@example.com', '13800138099', 2, NULL, 1, 0, 0);

INSERT INTO `model_provider` (`tenant_id`, `type`, `name`, `api_key`, `base_url`, `status`, `is_public`, `creator`, `active`) VALUES
(1, 'dashscope', '阿里云百炼', 'sk-test-key-12345', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 1, 1, 'admin', 1),
(1, 'openai', 'OpenAI', 'sk-openai-key-67890', 'https://api.openai.com/v1', 1, 1, 'admin', 1),
(1, 'ollama', 'Ollama', '', 'http://localhost:11434', 1, 1, 'admin', 1),
(1, 'deleted_type', '已删除服务商', 'sk-deleted', 'https://api.deleted.com', 1, 1, 'admin', 0),
-- 租户 1 的私有服务商：其他租户无论按 id 还是按列表都读不到
(1, 'tenant1_private', '租户一私有家', 'sk-t1-private', 'https://t1.private.example.com', 1, 0, 'testuser1', 1),
-- 租户 2 的服务商：只有租户 2 可写，公开后对其他租户可见
(2, 'tenant2_provider', '租户二服务商', 'sk-t2-key', 'https://t2.example.com', 1, 1, 'testuser2', 1);

INSERT INTO `model` (`tenant_id`, `name`, `model_name`, `provider_id`, `description`, `model_type`, `support_internet`, `support_reasoning`, `support_tool`, `support_mcp`, `support_vision`, `price`, `status`, `is_public`, `creator`, `active`) VALUES
(1, 'GPT-4', 'gpt-4', 1, 'GPT-4 模型', 'chat', 1, 1, 1, 1, 0, 0.0300, 1, 1, 'admin', 1),
(1, 'GPT-3.5 Turbo', 'gpt-3.5-turbo', 1, 'GPT-3.5 Turbo 模型', 'chat', 0, 0, 1, 0, 0, 0.0020, 1, 1, 'admin', 1),
(1, 'Claude 3', 'claude-3', 2, 'Claude 3 模型', 'chat', 0, 1, 1, 1, 0, 0.0250, 1, 1, 'admin', 1),
(1, 'Qwen-Turbo', 'qwen-turbo', 3, '通义千问 Turbo', 'chat', 0, 0, 1, 0, 0, 0.0010, 1, 1, 'admin', 1),
(1, 'Deleted Model', 'deleted-model', 1, '已删除模型', 'chat', 0, 0, 0, 0, 0, 0.0100, 1, 1, 'admin', 0),
-- 租户 1 的私有模型：对其他租户既不在列表里也不可按 id 读到
(1, 'T1 Private GPT', 't1-private-gpt', 5, '租户一私有模型', 'chat', 0, 0, 1, 0, 0, 0.0500, 1, 0, 'testuser1', 1),
-- 租户 2 的模型：挂在租户 2 的服务商上
(2, 'T2 Model', 't2-model', 6, '租户二模型', 'chat', 0, 0, 1, 0, 0, 0.0400, 1, 1, 'testuser2', 1);

INSERT INTO `mcp_server` (`name`, `description`, `type`, `command`, `url`, `status`, `is_public`, `creator`, `active`) VALUES
('Weather MCP', '天气查询服务', 'streamablehttp', NULL, 'http://localhost:8081/weather', 1, 1, 'admin', 1),
('Calculator MCP', '计算器服务', 'stdio', 'python calculator.py', NULL, 1, 1, 'admin', 1),
('File MCP', '文件操作服务', 'sse', NULL, 'http://localhost:8083/file', 1, 1, 'admin', 1),
('Deleted MCP', '已删除服务', 'streamablehttp', NULL, 'http://localhost:8084/deleted', 1, 1, 'admin', 0);

INSERT INTO `skill_repository` (`tenant_id`, `name`, `url`, `branch`, `source_type`, `source_config`, `description`, `status`, `is_public`, `creator`, `active`) VALUES
(1, 'Default Repository', 'https://github.com/agnetix/skills', 'main', 'GIT', '{"url":"https://github.com/agnetix/skills","branch":"main"}', '默认技能仓库', 1, 1, 'admin', 1),
(1, 'Advanced Skills', 'https://github.com/agnetix/advanced-skills', 'master', 'GIT', '{"url":"https://github.com/agnetix/advanced-skills","branch":"master"}', '高级技能仓库', 1, 1, 'admin', 1),
(1, 'Deleted Repository', 'https://github.com/agnetix/deleted', 'main', 'GIT', NULL, '已删除仓库', 1, 1, 'admin', 0),
(2, 'Tenant2 Repository', 'https://github.com/tenant2/skills', 'main', 'GIT', '{"url":"https://github.com/tenant2/skills","branch":"main"}', '租户2仓库', 1, 1, 'user2', 1),
(2, 'Default Repository', 'https://github.com/tenant2/default', 'main', 'GIT', NULL, '租户2同名仓库', 1, 0, 'user2', 1);

INSERT INTO `skill` (`tenant_id`, `name`, `repository_id`, `description`, `skillmd`, `resources`, `version`, `status`, `is_public`, `creator`, `active`) VALUES
(1, 'web-search', 1, '网络搜索技能', '# Web Search\n搜索网络信息', '{}', '1.0.0', 1, 1, 'admin', 1),
(1, 'code-review', 1, '代码审查技能', '# Code Review\n审查代码质量', '{}', '1.0.0', 1, 1, 'admin', 1),
(1, 'data-analysis', 2, '数据分析技能', '# Data Analysis\n分析数据', '{}', '1.0.0', 1, 1, 'admin', 1),
(1, 'deleted-skill', 1, '已删除技能', '# Deleted', '{}', '1.0.0', 1, 1, 'admin', 0),
(2, 'tenant2-skill', 4, '租户 2 技能', '# Tenant2 Skill', '{}', '1.0.0', 1, 1, 'user2', 1);

INSERT INTO `agent` (`tenant_id`, `name`, `description`, `system_prompt`, `model_id`, `owner`, `status`, `is_public`, `creator`, `active`) VALUES
(1, 'Test Agent 1', '测试智能体1', '你是一个助手', 1, 'testuser1', 1, 1, 'testuser1', 1),
(1, 'Test Agent 2', '测试智能体2', '你是一个编程助手', 2, 'testuser1', 1, 0, 'testuser1', 1),
(1, 'Test Agent 3', '测试智能体3', '你是一个翻译助手', 3, 'testuser2', 0, 1, 'testuser2', 1),
(1, 'Deleted Agent', '已删除智能体', '已删除', 1, 'testuser1', 1, 1, 'testuser1', 0),
(2, 'Tenant2 Agent', '租户 2 智能体', '你是租户 2 助手', 1, 'user2', 1, 1, 'user2', 1);

INSERT INTO `channel` (`name`, `type`, `agent_id`, `callback_key`, `session_id`, `communication_mode`, `enabled`, `config_json`, `description`, `status`, `active`) VALUES
('Test WeCom Channel', 'wecom', 1, 'test-wecom', 'sess-wecom-001', 'webhook', 1, '{"webhookUrl":"http://localhost:8080/webhook/wecom","token":"test-token","encodingAesKey":"aes-key-123"}', '企业微信测试通道', 1, 1),
('Test HTTP Channel', 'http', 2, 'test-http', 'sess-http-001', 'webhook', 1, '{"webhookUrl":"http://localhost:8080/webhook/http","token":"http-token"}', 'HTTP测试通道', 1, 1),
('Test Feishu Channel', 'feishu', 1, 'test-feishu', 'sess-feishu-001', 'websocket', 1, '{"appId":"app-id-123","appSecret":"app-secret-123"}', '飞书测试通道', 1, 1),
('Deleted Channel', 'wecom', 1, 'test-deleted', 'sess-deleted-001', 'webhook', 1, '{"webhookUrl":"http://localhost:8080/webhook/deleted","token":"deleted-token"}', '已删除通道', 1, 0);

INSERT INTO `session` (`tenant_id`, `session_id`, `agent_id`, `title`, `session_description`, `name`, `model_id`, `permission_mode`, `owner`, `status`, `is_public`, `creator`, `active`) VALUES
(1, 'session-001', 1, '测试会话1', '第一个测试会话', 'session-001', 1, 'DEFAULT', 'admin', 1, 1, 'admin', 1),
(1, 'session-002', 1, '测试会话2', '第二个测试会话', 'session-002', 1, 'DEFAULT', 'admin', 1, 1, 'admin', 1),
(1, 'session-003', 2, '测试会话3', '第三个测试会话', 'session-003', 2, 'DEFAULT', 'admin', 0, 1, 'admin', 1),
(1, 'session-004', 3, '测试会话4', '第四个测试会话', 'session-004', 3, 'DEFAULT', 'admin', 1, 1, 'admin', 1),
(1, 'session-deleted', 1, '已删除会话', '已删除', 'session-deleted', 1, 'DEFAULT', 'admin', 1, 1, 'admin', 0);

INSERT INTO `plan_note` (`session_id`, `plan_id`, `name`, `description`, `expected_outcome`, `subtasks`, `created_at`, `finished_at`, `cost_timeseconds`, `status`) VALUES
('session-001', 'plan-001', '数据分析计划', '分析用户数据', '生成分析报告', '[{"name":"数据收集","status":"DONE"},{"name":"数据分析","status":"IN_PROGRESS"}]', '2026-04-25 10:00:00', NULL, 300, 'IN_PROGRESS'),
('session-001', 'plan-002', '数据清洗计划', '清洗原始数据', '输出清洗后的数据', '[{"name":"数据导入","status":"DONE"},{"name":"数据清洗","status":"DONE"}]', '2026-04-25 09:00:00', '2026-04-25 09:30:00', 1800, 'DONE'),
('session-002', 'plan-003', '报表生成计划', '生成月度报表', 'PDF格式报表', '[]', '2026-04-25 11:00:00', NULL, 0, 'TODO');

INSERT INTO `process_log` (`agent_id`, `agent_name`, `session_id`, `message`, `log_type`, `stack_trace`, `ts`, `tenant_id`) VALUES
(1, 'Test Agent 1', 'session-001', '开始处理请求', 'INFO', NULL, '2026-04-25 10:00:00', 1),
(1, 'Test Agent 1', 'session-001', '处理完成', 'INFO', NULL, '2026-04-25 10:00:05', 1),
(2, 'Test Agent 2', 'session-002', '发生错误：超时', 'ERROR', 'java.util.concurrent.TimeoutException', '2026-04-25 11:00:00', 1);

INSERT INTO `token_stats` (`session_id`, `agent_id`, `chat_model_id`, `input_token`, `output_token`, `total_token`, `fee`, `ts`, `tenant_id`) VALUES
('session-001', 1, 1, 100, 50, 150, 1, '2025-01-01 10:00:00', 1),
('session-001', 1, 1, 200, 100, 300, 2, '2025-01-02 10:00:00', 1),
('session-002', 1, 1, 150, 75, 225, 1, '2025-01-03 10:00:00', 1),
('session-003', 2, 2, 300, 150, 450, 3, '2025-01-04 10:00:00', 1),
('session-004', 3, 3, 250, 120, 370, 1, '2025-01-05 10:00:00', 1);

INSERT INTO `sys_token_blacklist` (`token`, `token_hash`, `username`, `user_id`, `reason`, `expire_time`, `create_ip`) VALUES
('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test1', 'hash001', 'testuser1', 1, 'logout', '2026-04-26 10:00:00', '192.168.1.100'),
('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test2', 'hash002', 'testuser2', 2, 'logout', '2026-04-26 11:00:00', '192.168.1.101'),
('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.admin', 'hash003', 'admin', 4, 'force_logout', '2026-04-26 12:00:00', '192.168.1.1');

INSERT INTO `agent_tool` (`id`, `name`, `display_name`, `description`, `bean_name`, `method_name`, `need_confirm`, `status`, `creator`, `active`) VALUES
(1, 'getDate', '获取日期', '获取当前日期', 'time-tool-box', 'getDate', 0, 1, 'SYSTEM', 1),
(2, 'getDatetime', '获取时间', '获取当前时间', 'time-tool-box', 'getDatetime', 0, 1, 'SYSTEM', 1),
(3, 'weather-tool', '天气查询', '查询城市天气信息', 'weather-tool-box', 'getWeather', 1, 1, 'SYSTEM', 1),
(5, 'disabled-tool', '已禁用工具', '测试禁用状态', 'disabled-tool-box', 'doSomething', 0, 0, 'SYSTEM', 1),
(6, 'deleted-tool', '已删除工具', '历史软删残留：证明名字仍占位、且 selectByName 不加 active 过滤', 'deleted-tool-box', 'doSomething', 0, 1, 'SYSTEM', 0);

