# Harnax MCP Management Design

> This document is the complete design of the MCP management domain: the data model, the Admin management plane (CRUD, secrets, authorization-server discovery, per-user authorization, token minting), agent binding and configuration delivery, runtime assembly, and the boundaries this domain accepts.
>
> Chinese version: [mcp-management.zh-CN.md](./mcp-management.zh-CN.md).

## 1. Overview and layering

An MCP (Model Context Protocol) server is one of the external tool sources for an Agent, alongside builtin tools. The two share only the layering approach, not the lifecycle: MCP servers are maintained entirely by the management plane (pages and APIs). Create, read, update and delete is its whole lifecycle; there is no startup-time auto-reconciliation of any kind.

| Module | Responsibility in this domain |
|------|------------------|
| `harnax-entity` | Entities, mappers and XML for `McpServer` / `AgentMcpBinding` / the three OAuth tables, plus the cross-service wire types `McpDetailDto` and `McpAccessTokenResponse` |
| `harnax-admin` | MCP server CRUD, secret encryption and masking, auth-type validation, authorization-server discovery and client registration, per-user authorization and token minting, connectivity probes, binding resolution and configuration delivery |
| `harnax-common` | Definition of the cross-module decryption SPI `McpConfigDecryptor` |
| `harnax-agent/harnax-harness-core` | Runtime assembly: builds one MCP client per configured server, second line of defence for stdio, releases clients together with the agent |
| `harnax-agent/harnax-agent-utils` | Client construction `McpHelper` and the three transport configs in `McpConfig`, callback interface `McpAccessTokenSource` |
| `harnax-agent/harnax-agent-service` | Configuration adaptor (reads MCP config out of the spec delivered by admin) and token-source implementation (exchanges a token with admin and caches it) |
| `harnax-webui` | MCP list / detail / forms, the OAuth panel and the consent landing page |

Two chains form the backbone of this domain:

- **Per-user authorization**: authorization-server discovery and client registration are performed once by an administrator (one row per (tenant, authorization server)); consent is performed once by each user for each of their OAuth-type servers. Access and refresh tokens exist only as AES ciphertext in `mcp_user_credential` on the admin side.
- **Runtime-side injection**: the runtime side (agent-service / harness-core) holds no AES key, no refresh token, and knows only that "this server uses OAuth"; every outbound request exchanges a token with admin per (session, server). The token identity is decided when the agent instance is built and bound to the token source; from then on every call served by that instance uses that one identity and no other.

## 2. Data model

Table shapes come from admin's schema baseline `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`: that one script holds every table and column definition, with no later version to stack on top of it, and the column names and defaults below are exactly what it writes.

### 2.1 mcp_server (MCP server table)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`

| Column | Shape and meaning |
|----|-----------|
| `id` | Auto-increment primary key |
| `tenant_id` | Owning tenant, default 1; written from the request context on creation (`TenantResolver`) |
| `name` | Server name, `varchar(100)`, unique within the tenant (see `active_name`) |
| `description` | Description, `text` |
| `type` | Transport type: `stdio` / `sse` / `streamablehttp`; the column is `varchar(20) NOT NULL` **with no DEFAULT**, so `streamablehttp` exists only as the entity property default |
| `command` | Command line, `varchar(500)`, used only by the stdio type |
| `url` | Server address, `varchar(500)`, used by `sse` / `streamablehttp` |
| `auth_type` | Upstream authentication: `NONE` / `STATIC_HEADER` / `BASIC` / `OAUTH2`, `NOT NULL DEFAULT 'NONE'` |
| `oauth_config` | Non-sensitive OAuth configuration JSON, `text`, nullable; shape defined by `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt` |
| `status` | Enabled state: 0 disabled / 1 enabled |
| `is_public` | Visibility: 0 private / 1 public, column default 1 |
| `creator` | Creator (username) |
| `active` | Logical delete: 0 deleted / 1 live |
| `headers` | JSON array of HTTP header entries; the value of an entry with `secret=true` is AES-256-GCM ciphertext |
| `env_params` | JSON array of environment parameter entries, the stdio process environment; secret entries are ciphertext as well |
| `active_name` | `VARCHAR(100) GENERATED ALWAYS AS (IF(active = 1, name, NULL)) VIRTUAL` |
| `create_time` / `update_time` | Timestamps; `update_time` carries `ON UPDATE CURRENT_TIMESTAMP`, and the update path refreshes it explicitly on top of that |

Constraints and conventions:

- `PRIMARY KEY (id)`; `UNIQUE KEY uk_mcp_server_tenant_active_name (tenant_id, active_name)`. Uniqueness holds within one tenant and covers live rows only — the generated column becomes NULL when `active = 0`, and MySQL's unique index ignores NULL, so a name can be reused after that server is deleted.
- TEXT columns such as `headers` / `oauth_config` / `env_params` carry no single-column index.
- `auth_type` is deliberately never backfilled: `NONE` and `STATIC_HEADER` take the same code path (both read `headers`), and the only difference is a readable label for the administrator. A row holding `headers` values is therefore not evidence of either intent (`headers` also holds routing-style headers), so which label applies is an explicit decision by the administrator.
- Every property on the `McpServer` entity is nullable with a default (`type` defaults to `streamablehttp`, `authType` to `NONE`, `status` / `isPublic` / `active` to 1). They are not all mirrored by the columns: `type` is `NOT NULL` with no DEFAULT, so that value has to come from a write path that goes through the entity, while `authType`'s `NONE` means the same thing as the column's own DEFAULT (`VARCHAR(20) NOT NULL DEFAULT 'NONE'`).

### 2.2 agent_mcp_binding (agent-to-MCP binding)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentMcpBinding.kt`

| Column | Meaning |
|----|------|
| `id` | Auto-increment primary key |
| `agent_id` | Points at `agent.id` |
| `mcp_id` | Points at `mcp_server.id` |
| `env_bindings` | JSON snapshot of environment variable bindings (`envKey` plus `envVarId` or `customValue`) |
| `create_time` / `update_time` | Timestamps |

Constraint: `UNIQUE KEY uk_agent_mcp_binding_agent_id_mcp_id (agent_id, mcp_id)`, whose leftmost prefix is `agent_id`. The application layer dedupes by `mcpId` in `saveMcpBindings` before writing, and one save rewrites the entire set.

There is no "skip a missing configuration" switch on this table: when a configuration cannot be resolved the runtime warns and skips, the same as on the tool side. `env_bindings` is the only per-binding configuration item in this domain (see "Two channels for environment parameters and secrets").

### 2.3 mcp_oauth_client (client registration per tenant × authorization server)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt`

One row is "this tenant's client identity at this authorization server"; several MCP servers behind the same authorization server share it.

| Column | Meaning |
|----|------|
| `id` / `tenant_id` | Primary key and tenant |
| `issuer` | Authorization server identifier, `varchar(255) COLLATE utf8mb4_bin` |
| `client_id` | `varchar(255) NOT NULL`; a row created by discovery stores an empty string, meaning "endpoints known, client not registered" |
| `client_secret_enc` | Client secret ciphertext, `text`; NULL for a public client (PKCE only) |
| `registration_source` | `MANUAL` / `DCR` / `ID_METADATA`; the only value the current write paths produce is `MANUAL` |
| `authorization_endpoint` / `token_endpoint` / `registration_endpoint` / `revocation_endpoint` | Discovery snapshot, `varchar(500)`; `registration_endpoint` NULL means DCR is not supported, `revocation_endpoint` NULL means revocation is local-only |
| `scopes_supported` | Discovery snapshot, comma-separated |
| `callback_url` | The exact `redirect_uri` registered at the authorization server, `varchar(500) COLLATE utf8mb4_bin` |
| `active` + `active_client_id` | `active_client_id VARCHAR(255) GENERATED ALWAYS AS (IF(active = 1, client_id, NULL)) VIRTUAL` |
| `creator` / `create_time` / `update_time` | Regular columns |

Constraints: `UNIQUE KEY uk_mcp_oauth_client_tenant_issuer_client (tenant_id, issuer, active_client_id)`, `KEY idx_mcp_oauth_client_tenant_issuer (tenant_id, issuer)`. The two URL identity columns use `utf8mb4_bin` because `iss` and `redirect_uri` are compared byte for byte by specification; a case-insensitive comparison would let one server's registration be reused for another's.

`selectByTenantAndIssuer` ends with `ORDER BY id LIMIT 1` so that concurrent discoveries land on the same row instead of registering two clients for one authorization server. This table is not cleaned up when an MCP server is deleted (see "Disabling and deleting are two different things").

### 2.4 mcp_user_credential (authorization outcome per user × MCP server)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`

| Column | Meaning |
|----|------|
| `id` / `tenant_id` | Primary key and tenant |
| `user_id` | `sys_user.id`, not a username: a rename neither orphans the grant nor hands it to whoever takes over the name |
| `mcp_id` | `mcp_server.id` |
| `access_token_enc` / `refresh_token_enc` | AES ciphertext of the two tokens; revocation writes NULL back to both columns, and the refresh ciphertext never leaves the admin process |
| `access_expires_at` | Access token expiry; past it, there is no usable token |
| `scopes` | The scopes actually granted, which may be narrower than those requested |
| `status` | `ACTIVE` / `NEEDS_CONSENT` / `REVOKED` |
| `last_error` | Failure reason with sensitive fragments removed; never a token fragment |
| `last_refreshed_at` | Time of the most recent successful refresh |
| `create_time` / `update_time` | Timestamps |

Constraints: `UNIQUE KEY uk_mcp_user_credential_tenant_user_mcp (tenant_id, user_id, mcp_id)`, `KEY idx_mcp_user_credential_mcp (mcp_id)`.

This table has no logical-delete column: a grant row is updated in place (revocation clears the ciphertext and sets `REVOKED`; consenting again reuses the same row). Beyond that in-place rewrite there are exactly two bulk physical deletes, each keyed by an external id: deleting the MCP server clears every grant for it (`deleteByMcpId`), and deleting the user account clears every grant that person holds on any server (`deleteByUserId`, called from the delete path in `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImpl.kt` — that `sys_user` row only goes soft-deleted, while the owner of these ciphertexts cannot sign in and has no page from which to revoke them). A soft-deleted row would keep holding `(tenant_id, user_id, mcp_id)` and block the insert of a fresh consent.

### 2.5 mcp_call_log (append-only audit)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt`

`action` takes `ISSUE` / `REFRESH` / `REVOKE` / `CALL`, `outcome` takes `OK` / `AUTH_FAILED` / `NEEDS_CONSENT` / `ERROR`. A row carries tenant, user (NULL when the session owner cannot be resolved), MCP server, session, tool name (NULL for token issuance) and duration.

This table deliberately records no request body, response body or `Authorization` header: whoever reads it is not necessarily the owner of the token. `McpCallLogMapper` exposes `insert` only — the append-only rule is visible in there being no other method to call.

The writer is a private `audit(...)` in `McpOAuthUserServiceImpl`; every authorization decision appends one row: an issuance that produced a usable token (`ISSUE` / `OK`), a decision that consent is required (`ISSUE` / `NEEDS_CONSENT`), a successful refresh (`REFRESH` / `OK`), a refresh that hit a retryable failure (`REFRESH` / `ERROR`), and a user revocation (`REVOKE` / `OK`). A decision made from the management page (revocation) records `session_id` as NULL; one made by a session carries that session. Failing to write the audit logs one warn and never fails the issuance or revocation itself.

### 2.6 Cross-service wire formats

- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`: the complete MCP configuration admin delivers through its internal API. Fields are `id` / `name` / `description` / `type` / `command` / `url` / `authType` / `headers` / `envParams` / `status`. `headers` and `envParams` are **decrypted plaintext JSON objects** (`{"KEY":"value"}` shape), decrypted on the way out. `authType` must travel with it, otherwise the runtime cannot tell an OAuth server from a static-header server; `oauthConfig` is not delivered — its `authorizationServer` and `scopes` serve only the management plane, and the runtime needs to know only that it takes the OAuth branch.
- `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpAccessTokenResponse.kt`: `accessToken` / `tokenType` / `expiresAtEpochSecond`. It lives in `harnax-entity` rather than in admin's dto package because it is a wire format between two services. This is the only response body that carries a plaintext token out of admin, and it answers only the internal API. `expiresAtEpochSecond` is a Unix second value rather than a date-time because the two sides judge expiry by their own clocks and are not guaranteed to share a time zone; NULL means the authorization server did not state a lifetime.

## 3. The Admin management plane

Entry points: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` and `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt`. Both share the `/api/admin/mcp` prefix, the same `ResultVo` envelope and the same `ApiErrors` error conventions, and every endpoint requires a JWT.

### 3.1 MCP server CRUD endpoints

| Endpoint | Method | Purpose |
|------|------|------|
| `/api/admin/mcp/page` | GET | Paged query, filters on `keyword` / `status` / `type`, `pageSize` clamped to 1..1000 |
| `/api/admin/mcp/{id}` | GET | Detail, secret fields returned masked |
| `/api/admin/mcp` | POST | Create |
| `/api/admin/mcp/update/{id}` | PUT | Update; an omitted field keeps its stored value |
| `/api/admin/mcp/toggle/{id}` | PUT | Enable / disable |
| `/api/admin/mcp/{id}/related-agents` | GET | Agents referencing this server |
| `/api/admin/mcp/{id}` | DELETE | Logical delete, cascading cleanup of bindings and user grants |
| `/api/admin/mcp/{id}/connectivity-test` | POST | Connectivity test |
| `/api/admin/mcp/{id}/list_tools` | GET | Live connection returning the tool list |

### 3.2 OAuth endpoints

| Endpoint | Method | Purpose |
|------|------|------|
| `/api/admin/mcp/{id}/oauth/discover` | POST | Discover the authorization server and store its endpoints |
| `/api/admin/mcp/{id}/oauth/client` | POST | Register or amend the client credentials for that authorization server |
| `/api/admin/mcp/{id}/oauth/authorize-url` | GET | Build the consent redirect for the current user, with an optional `scope` override |
| `/api/admin/mcp/oauth/exchange` | POST | Code exchange: takes the `code` / `state` / `error` posted by the landing page, credits the caller |
| `/api/admin/mcp/{id}/oauth/status` | GET | The current user's authorization state for this server |
| `/api/admin/mcp/{id}/oauth/revoke` | POST | Revoke the current user's authorization |
| `/api/admin/internal/mcp/access-token` | POST | Runtime mint: inputs are `sessionId` + `mcpId` only |

The internal mint endpoint sits on `InternalApiController`, behind `InternalApiAuthFilter`, so a browser cannot reach it. Its input has **no user field**: the session is the only identity the runtime holds, and admin resolves the owner itself, which means a caller cannot name whose authorization it wants to spend.

### 3.3 Tenant and visibility guards on single-row access

`McpServerServiceImpl` offers two read entry points:

- `getMcpServer(id)`: loads the row, then compares against the current tenant (`TenantResolver.resolve(jwtUtil)`); a row outside the current tenant is answered as non-existent. The list query filters by tenant, so if the single-row read skipped the check, that filter would be decoration — guessing an auto-increment id would be enough to open, edit and delete another tenant's row. There is no MyBatis tenant interceptor in this repository and `selectById`'s SQL carries no tenant predicate, so this layer has to live in the service.
- `getVisibleMcpServer(id)`: the tenant guard plus `is_public = 1 OR creator = <current username>`, matching the list's visibility rule. Every write and probe path (update, toggle, delete, `list_tools` / connectivity test, and all OAuth entry points) goes through it, and a refusal always answers "MCP server not found".

Read paths that only resolve names (agent form rendering the servers on its bindings, session capability listing) stay on `getMcpServer`: what they show is "what this agent already has configured", and hiding another tenant's private server there makes one valid binding silently disappear from a form rather than refusing an operation.

The list query `selectMcpServerList` takes both the current username and the tenant id: `is_public = 1 OR creator = #{creator}`, with `tenant_id` applied on top. "Public" therefore means **shared within the tenant**.

### 3.4 Creation and update

Creation (`createMcpServer`) proceeds in order:

1. Name duplicate check inside the current tenant (`selectByName(name, tenantId)`), throwing `BizException` on a hit; the database backs this with `uk_mcp_server_tenant_active_name`, and on a concurrent index collision `ApiErrors` maps the index name to readable text.
2. `type` rules: the stdio admission gate (see "Configuration switches and deployment parameters") and `validateTypeAndFields` (stdio requires `command`, `sse` / `streamablehttp` require `url`, any other value is refused).
3. Authentication: `resolveAuthType` (default `NONE`, only `McpAuthTypes.SUPPORTED` accepted), `validateAuthType` (`OAUTH2` may not be combined with stdio), `writeOAuthConfig` (see "Authentication modes and the OAuth configuration column").
4. Secret serialization: `headers` through `SecretFieldEncryptor.serializeWithEncryption`; `envParams` is written only when `type` is stdio, otherwise NULL — the transports that spawn a process are the only ones with something to put into a process environment, and storing it on a network row parks values that nothing reads.
5. `tenantId` from the current tenant, `creator` from the current username, `status` / `isPublic` defaulting to 1 when the request omits them.

Update (`updateMcpServer`), point by point:

- It starts at `requireVisibleServer(id)` and records whether the row was `OAUTH2` **before** any field is overwritten — at the end of the method it has to decide "did this request turn OAuth off", and by then `auth_type` has already been rewritten.
- `name` / `description` / `type` / `command` / `url` / `authType` / `isPublic` / `status` / `oauthConfig` / `headers` / `envParams` are all nullable, and **an omitted value keeps what is stored**; an empty `name` is refused.
- Changing `type` clears what the previous transport left behind: switching to stdio writes `url` as an empty string and sets `headers` to NULL; switching to a network transport writes `command` as an empty string and sets `envParams` to NULL. `updateById` writes the whole row, so these values do reach the database. Keeping them would make the detail page display a field that this transport never reads.
- When `oauthConfig` is replaced: if the request carries no `authorizationServer` while the row has one (written back by discovery), the stored one is kept — editing the scopes once should not void the entire discovery result. An explicitly supplied unusable value is still refused.
- As soon as `authType` is anything other than `OAUTH2`, `oauthConfig` is cleared to NULL, so a server that does not do OAuth does not keep a configuration the UI would go on rendering.
- Validation runs on the **merged row**, before `updateById`: switching `type` to stdio hits the same `validateAuthType` and the same stdio admission gate, and values such as `BASIC` are kept out the same way.
- `update_time` is refreshed explicitly (`updateById` writes this column from the entity, so leaving the loaded value would freeze it and with it the list's ordering by update time).
- After a successful write it clears user grants where applicable — see "Disabling and deleting are two different things".

### 3.5 Secret storage and mask write-back

Encryption infrastructure:

- `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`: built on `AesUtil` (AES-256-GCM) and the implementation of the cross-module decryption SPI defined in `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt`.
- Entry shapes: `headers` is `McpConfigEntry` (`key` / `value` / `secret`), `envParams` is `ToolEnvParamEntry` (`envParamName` / `description` / `required` / `secret` / `defaultValue`). The latter reuses the tool parameter entry shape and the value lives in `defaultValue`, so encryption, masked display and write-back recognition for a secret entry all act on that field (`SecretFieldEncryptor.storedEnvSecrets` indexes by `envParamName` to recover the ciphertext). In both shapes only entries with `secret = true` are encrypted.

Display (`McpServerResponse.fromEntity`): a secret entry is decrypted and then masked — "first 3 characters + `****` + last 4 characters" when longer than 7, `******` otherwise and whenever decryption fails. The frontend never touches a plaintext secret.

Masking is a two-directional convention. Since the frontend only ever sees a mask, an untouched field comes back through the form as that same mask, so the write side must recognise it: `serializeWithEncryption` / `serializeToolEnvParams` test `value.contains("****")` (`******` itself contains `****`, so one test covers both forms) and on a hit recover the ciphertext for that `key` / `envParamName` from **the JSON currently in this row's column**, storing it unchanged. When nothing is found (the entry was renamed, or the column never had it) a `BizException` asks for a re-entry; a mask is never encrypted as if it were a plaintext.

A single-column secret (`mcp_oauth_client.client_secret_enc`) is masked the same way, through the extracted helper `SecretFieldEncryptor.resolveSecret(provided, storedEncrypted)`, whose three states each mean something: `null` keeps the stored value; a mask reuses the stored ciphertext (and asks for a re-entry when there is none); a blank string clears it deliberately — the normal shape for a public client using PKCE.

### 3.6 Authentication modes and the OAuth configuration column

`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt` is the source of the constants and both processes read it: admin decides what may be stored and what is delivered, the runtime branches on it to choose between static headers and a per-user token.

- `NONE`: no upstream credential; behaviour is identical to "static headers are empty".
- `STATIC_HEADER`: the `headers` column, a static credential shared by the whole tenant, with one readable label added.
- `BASIC`: a column and a constant exist, the runtime has no matching branch, so the management side refuses it and the error text says so ("declared by the schema but not wired into the runtime yet").
- `OAUTH2`: per-user authorization; the runtime mints a token per (server, person).

Refusing an unknown value instead of treating it as `NONE` follows from **stored means delivered**: an unrecognised branch arriving at the runtime is harder to diagnose than `NONE`.

`oauthConfig` is serialized by `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt`, fields: `authorizationServer` (empty means derive it from the MCP server's own RFC 9728 metadata), `scopes` (default empty list), `audience` (kept for authorization servers that require it), `resourceIndicator` (default true, decides whether RFC 8707 `resource` is sent, which binds the token to this MCP server). It is a typed DTO rather than free JSON precisely so that a client secret or a token has nowhere to be written here — this column is returned to the frontend form as plaintext. `SecretFieldEncryptor` does not touch it, so the write path reports "OAuth configuration on a non-OAuth server" as an error instead of dropping it quietly; a supplied `authorizationServer` must be a requestable http(s) address (parsed with `URI.create(trim())`, scheme http or https, non-empty host), because the discovery flow sends requests to it.

`OAUTH2` and `type = stdio` are mutually exclusive: an OAuth token rides on an HTTP request header, stdio has no request to attach it to, and getting this wrong would silently connect unauthenticated.

### 3.7 Authorization-server discovery and client registration

Implementation: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`, with all outbound requests funnelled through `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`. This layer performs a configuration action "once per (tenant, authorization server)" and exchanges no token at all.

1. **Admission**: both endpoints first go through `McpServerService.getVisibleMcpServer(id)` (tenant and visibility guards live there), then require `authType == OAUTH2` and a non-empty http(s) `url`. Choosing OAuth as the auth type only makes these two endpoints available; the server itself still connects with its `headers`.
2. **Issuer resolution order** (error only when all of them fail): (i) `oauthConfig.authorizationServer`, typed by the administrator and recorded as `CONFIG`; (ii) protected-resource metadata derived from the MCP server address, candidates being path-inserted `origin/.well-known/oauth-protected-resource/<path>`, path-suffixed `origin/<path>/.well-known/oauth-protected-resource`, and the host root; (iii) one direct GET of the MCP address, reading the `resource_metadata` pointer out of the `WWW-Authenticate` response header (quoted and unquoted forms both parse, multiple headers are stitched back). The first entry of `authorization_servers` is taken, and more than one is logged.
3. **Strict issuer validation**: `trim()` first, then checks stricter than for a general URL — no userinfo, no query, no fragment (those three parts never reach a metadata address, so storing one yields a value nobody can confirm). A trailing slash is removed by `normalizeIssuer`, because registration is matched byte for byte and a slash manufactures a second authorization server. Hand-typed and documented values pass the same checks, and both are length-checked against the target columns (`issuer` 255, other URLs 500).
4. **AS metadata candidates**: `.well-known/oauth-authorization-server` and `.well-known/openid-configuration`, each tried in inserted and suffixed form (the OIDC-style one last, since it is the address authorization servers and OIDC libraries actually serve). A hit requires both `authorization_endpoint` and `token_endpoint`, and those two values themselves must pass the http(s) and column-width checks — they are the address to redirect to and the address to POST a `code` and a `client_secret` to. Optional endpoints such as `registration_endpoint` / `revocation_endpoint` are dropped with a warn when unusable; a missing optional capability is not a failure.
5. **Issuer consistency**: a documented `issuer` that is not exactly equal to the issuer being queried is refused (RFC 8414 requires equality; otherwise every token exchange fails issuer validation). When the document declares no issuer the decision follows the source: a hand-typed one is accepted (a person vouches for it, and it is the only way out), one advertised by the document is refused.
6. **Failures must speak**: every candidate's reason is concatenated into one `BizException` message with a length cap, and addresses in the message pass through `redactUrl` to strip userinfo first. No path degrades to "keep going with static headers" — a half-discovered server that reads as "configured" is how a runtime ends up sending unauthenticated requests to an external system on someone's behalf.
7. **One row per (tenant, issuer)**: when `selectByTenantAndIssuer` hits, only the endpoints are refreshed, and `client_id` / `client_secret_enc` / `callback_url` are carried over from the loaded row (`updateById`'s SET list is unconditional, so not carrying them would deregister the client on the next discovery). Otherwise a row is inserted with an empty `client_id`. An optional endpoint discovered as null is stored as null — that is exactly the fact "this authorization server took DCR away" that has to be recorded.
8. **Writing the issuer back touches one column**: the issuer does not come from `oauthConfig`, so on success the result is written into `mcp_server.oauth_config` via `McpServerMapper.updateOAuthConfig(id, json)` (plus `update_time`). The whole row is not written — a full-row overwrite would replace someone else's in-progress edit with values read minutes earlier.
9. **`callback_url` default**: a new row stores `${app.frontend-base-url}/mcp/oauth/callback`, which is a **frontend route** (the consent landing page); when `app.frontend-base-url` is unset it falls back to `app.base-url`. The value this column holds is what the authorization server compares byte for byte, so changing the environment variable does not move an existing row — a registered one has to be saved again. `McpOAuthClientRequest.callbackUrl` overrides it explicitly, and the override passes the same http(s) and column-width checks first.
10. **Registering a client**: `client_id` is validated as non-empty before any write (no half-row is left behind); `clientSecret` goes through the three states of `resolveSecret`; `registration_source` is written as `MANUAL`. When the stored row has no endpoints yet, discovery runs once to fill them — endpoints are the addresses actually being redirected to and may not be empty; this path revalidates the issuer stored on the row, because it is about to attach a `client_secret` to that address.
11. **An unreadable `oauth_config` aborts**: an empty column is "not configured yet", a non-empty one that will not parse is drift, and discovery rewrites this column. Continuing with defaults would overwrite an administrator's scopes with an empty list and report "discovery succeeded".
12. **Display carries no secret**: `McpOAuthDiscoveryResponse` returns a `clientSecretPresent` boolean, plus `issuerSource` (which makes clear whether the issuer was discovered or typed) and `unknownScopes` (the part of the requested scopes the authorization server does not recognise — a typo would otherwise surface only after a user has logged in). One DTO serves both endpoints.
13. **Outbound guardrails (`RemoteJsonFetcher`)**: every request whose target came from administrator input or from an upstream document leaves through here — http(s) only with a mandatory host; cloud metadata targets refused before a connection is even attempted (link-local, any local address, multicast, hostnames such as `metadata.google.internal`); loopback and private ranges allowed on purpose (self-hosted authorization servers live in them); `Redirect.NEVER`; 5s connect / 10s request timeouts; a 64KB response body cap plus a read deadline; a body read failure is still wrapped as `RemoteFetchException` (otherwise one dropped connection would crash the whole discovery instead of trying the next candidate); only a JSON object counts as metadata. `postForm` routes the form POSTs of token exchange and revocation through the same guardrails rather than a second client. These are a floor, not the whole policy: an address judged here is resolved again when the connection is made, and DNS rebinding is out of scope.

### 3.8 Per-user authorization: start, code exchange, status, revocation

Implementation: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`, pending state in `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt`, outbound calls through `RemoteJsonFetcher.postForm`.

1. **Admission**: `authorize-url` / `status` / `revoke` all pass the visibility and tenant guard, require `authType == OAUTH2`, and then require an actual current user — authorization is per person, so without a userId it is an error, with no fallback to "tenant 1". If configuration parsing yields no issuer, the registration row is absent, or that row's `client_id` is still the empty-string placeholder discovery writes, both the redirect build and the token exchange refuse: walking up to an authorization server with `client_id=` lets someone else's error page speak for us.
2. **`state` pins every premise of this consent**: one `PendingAuthorization` stores (tenant, user, mcpId, issuer, `code_verifier`, `redirect_uri`, resource, requested scopes), with a 5-minute TTL, a global cap of 500 entries and a per-user cap of 5 (`countFor(userId)` is checked before any verifier or state is generated, so a refused attempt leaves no material behind), and `consume` invalidates on read (taken out before the expiry test, so an empty string and a non-existent entry give the same answer). Pending state lives in memory rather than in a table because a code is itself single-use and minute-scoped. Two consequences: an admin restart interrupts a consent in progress, and multiple replicas require sticky routing, otherwise the exchange lands on a replica that never saw the request and answers only "unknown or expired request". Both caps refuse explicitly with their own reason instead of dropping quietly — 500 is a budget shared by everyone, and without the per-user cap a single logged-in user could fill it.
3. **The exchange's identity comes from the caller's JWT**: `POST /oauth/exchange` carries a JWT and credits the caller. The decision order is fixed: `consume(state)` first (the `state` is single-use whatever the outcome), then ownership — when the userId recorded in the `state` differs from the caller it is **refused and reported**, because that is the shape of consent phishing (a caller holding someone else's `state` could otherwise cancel that in-progress authorization with their own `error` report); a `state` that does not belong to the caller is therefore not an identity source but an event worth recording. When the authorization server returned an `error`, the reason it gave is answered to the page even if the `state` has expired — at that point nothing was stored and nothing needed burning, and the upstream reason is worth more than "unknown request". An empty `code` is refused in the same explicit way.
4. **Re-checks before the token request** (gathered in one private helper): the server row is still visible to this user and still in the tenant it was started in; `url` still equals the resource pinned in the `state` — this step has a precondition, it runs only when `pending.resource` is non-null, so with `resourceIndicator` off or no resource built at start the whole comparison is skipped (consent was given for that address, so a code obtained after an address change is a credential that will never be usable); the registration row is found by the issuer used at start (not by the current `oauth_config`); `client_id` is non-empty; the token endpoint is known. The token request is a form POST with `grant_type=authorization_code` + `code` + `redirect_uri` + `client_id` + `code_verifier`, and the client secret in the body when it can be read out of storage (RFC 6749 permits body or basic auth; body is chosen because every implementation accepts it and it needs no second code path); when the stored client secret cannot be decrypted it is an explicit error asking for a re-save rather than an anonymous attempt at the endpoint returning `invalid_client`. The response must contain an `access_token`, and it is validated by `validateAgainstIssuer` for `iss` and for the scopes actually granted. When the granted scope string overflows the `scopes` column (512) the **whole string is not recorded** and `last_error` explains what was dropped — a truncated list reads as "these are the granted scopes" while its last item is a fragment that was never granted.
5. **Authorization request parameters**: `response_type=code`, `client_id`, `redirect_uri` (the one on the registration row), `state`, `code_challenge` with `code_challenge_method=S256`, `scope` when non-empty (space-joined), RFC 8707 `resource` (the server's `url`, governed by `resourceIndicator`) and `audience` where applicable. Requested scopes wider than the column cap are refused before any material is generated; an entry not present in `scopes_supported` produces a warn. The response returns `authorizeUrl` / `issuer` / `scopes` / `expiresIn` (the pending entry's TTL in seconds).
6. **Landing page**: the authorization server brings the browser back to the frontend route `/mcp/oauth/callback` (`harnax-webui/src/pages/mcp/oauth-callback.tsx`, `layout: false`, placed before the wildcard 404). The page reads the query, calls the JWT-carrying exchange endpoint, and answers with one line of text. A redirect for a missing login drops the search string on the landing page, so that `code` and `state` never end up in a login page's address bar.
7. **Status**: reads the row for (tenant, user, server) and answers `authorized` when `status == ACTIVE` and `access_expires_at` has not passed (a NULL is treated as not expired), returning `status`, the expiry and `last_error`, and no token material at all.
8. **Revocation**: with no grant row the answer is "already revoked, no upstream action". With a row the local side is always cleared (`clearLocally`), and when the registration row has a `revocation_endpoint` an RFC 7009 POST is sent — preferring the refresh token (an access token dies within minutes; the refresh token is what keeps minting new ones, and a compliant server revoking the refresh token revokes the authorization). When the ciphertext cannot be decrypted it is treated as "no token to present" and the local copy is cleared anyway: this call is the user's only way out, and undecryptable ciphertext means the key changed, not that consent lapsed. The answer is picked by a `when` with six branches, each with its own sentence: the registration cannot be resolved, the registration carries no revocation endpoint, ciphertexts are present but undecryptable, both ciphertexts on the row are already NULL, the upstream accepted, the upstream refused (`revoked` is always true and `upstreamRevoked` describes the upstream half). Those six are exactly the rows the revoke section of the authorization design document lists.

### 3.9 Token minting (internal endpoint)

`POST /api/admin/internal/mcp/access-token`, input `McpAccessTokenRequest(sessionId, mcpId)`, output `McpAccessTokenResponse`. A blank `sessionId` is a 400 immediately.

The rules of `McpOAuthUserService.accessToken(sessionId, mcpId)`:

1. The server row is read straight from the mapper by id, not through `McpServerService`: that guard compares **the tenant in the request header**, and an internal call from agent-service carries no such header. What scopes this answer is the server row's own tenant, which is also the key the grant was stored under. `authType != OAUTH2` is an error (this server has no per-user token to issue).
2. `McpSessionOwnerResolver` resolves the session into `sys_user.id` + tenant. No resolution means no identity: a `NEEDS_CONSENT` audit row is written and the answer is 401.
3. A mismatch between the owner's tenant and the server's tenant is answered 403. Delivery only ever hands a server to an agent in the same tenant, so this is normally unreachable, and it stays: grants are keyed by the **server's** tenant, and without this check a session in tenant B could spend tenant A's authorization for some server.
4. No grant row is 401; `status == REVOKED` is 401 as well but **does not rewrite the status to `NEEDS_CONSENT`** — that would erase the only fact this row still records, namely that this person revoked it deliberately. In both cases the ciphertext is left intact: `revoke` still needs the stored content to notify the authorization server.
5. A stored access token that is still valid (and whose ciphertext reads) is handed out as is, with its lifetime converted to epoch seconds — comparisons inside admin are local semantics, while the caller is another process with no guaranteed shared time zone. An expiry inside the refresh lead time (`REFRESH_SKEW_SECONDS`) counts as needing refresh: a call that dies mid-run because the token aged out reads as "this MCP server is broken", and a user cannot tell that apart from a real fault. A row with no recorded lifetime keeps being used until somewhere refuses it.
6. Refresh happens inside a stripe lock taken on `credential.id`, and the row is **re-read** inside the lock: someone may have refreshed this very grant a moment ago, and a second refresh would burn the refresh token that rotation returned. The form is `grant_type=refresh_token` + `refresh_token` + `client_id` (plus a readable `client_secret`).
7. Two failure classes are kept apart. Ones where the authorization is gone and only the user can help (no refresh token, ciphertext unreadable, upstream refused) become `NEEDS_CONSENT` — status written, audit written, **401** with "please authorize again". Ones where nothing about the authorization changed this round and it is merely a retry problem (unreachable network, a client secret unreadable because the key rotated) become `transientFailure` — status untouched, only `last_error` written, audit written, **503** with the reason. The two codes must not be merged: merging them would send users to a consent page over a network hiccup.
8. Every decision appends one row to `mcp_call_log` (`ISSUE` / `REFRESH` plus outcome plus duration); no token content is stored.

### 3.10 Connectivity test and tool listing

`listTools(id)` is the only path in admin that really connects using what is in the database (`connectivityTest` goes through it too), so its gates are the strictest:

1. `requireVisibleServer(id)` — tenant and visibility;
2. `status == 0` refuses outright with "enable it first"; a row an operator switched off must not be reachable from here either;
3. the stdio gate: this path really does spawn the stored command inside the admin container, so the gate has to sit here as well, otherwise the switch would mean "not delivered" rather than "not running";
4. `authType == OAUTH2` refuses explicitly: this check needs somebody's token, and a management-side probe has no "user" to spend — the authorization belongs to the session owner, not to whoever clicked test. Listing tools with the clicker's own grant would also make the tool set vary by person, which is not what a connectivity test claims to show. End-to-end verification of an OAuth server goes through the authorization panel on the detail page.

After the gates, `McpHelper.listTools(mcpServer, decryptToMap, decryptToolEnvParamsToMap)` runs, with a 10-second cap each on `initialize()` and `tools/list`, and the client closed in a finally block (one click is one client, and for stdio also one process).

### 3.11 Disabling and deleting are two different things

| Action | Effect on data | Effect at runtime |
|------|---------|---------|
| Disable (`status = 0`) | Row, bindings and grants all remain; the detail page stays editable | The whole row is held back at delivery and refused once more on the runtime side, so there is **not one outbound request**; `list_tools` / connectivity test refuse as well |
| Delete (`active = 0`) | Row logically deleted; in the same transaction that server's `agent_mcp_binding` rows and `mcp_user_credential` rows are physically deleted | The bindings are gone, delivery resolves nothing, and it is handled as missing |

Disabling means "touch no ciphertext, touch no grant": an administrator switching a server off should not force its users through a fresh round of consent. Deleting means "there is no subject left to represent": with the row gone a grant has nothing to correspond to, and keeping ciphertext around is needless blast radius. Neither path **touches** `mcp_oauth_client` — that row is this tenant's client identity at that authorization server, and other MCP servers behind the same server are still using it.

Deletion and grant cleanup both run only after the write actually hit: if someone else deleted the row a moment ago and `updateById` / `deleteById` matched nothing, it must not then delete unrelated grant rows on the basis of "this request turned OAuth off".

Two places clear grants as a side effect, both on the update path, tested in this order:

- The server is still `OAUTH2` but the request carried a different `url`: this is the first `if` on the update path (`oauthResourceMoved`), so a hit here never reaches the next bullet. Grants were issued against a resource (RFC 8707), so after an address change every stored token is refused upstream while the `status` column still reads "authorized". Clearing them sends users back to consent, which is better than a permanent failure with no stated cause.
- This request moved `authType` away from `OAUTH2`: judged only when the row above did not fire (it is the `else if`). `oauth_config` is cleared to NULL and every user grant row for this server is deleted (status and revocation both require `authType == OAUTH2`, so a server that has left OAuth is refused by them, and the ciphertext left behind is invisible and unrevocable to its holder).

The upstream grant is not presented for cancellation: each token expires by its own lifetime, the same rule as when a server is deleted.

## 4. Binding and configuration delivery

### 4.1 Saving a binding

`saveMcpBindings` in `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt` rewrites the whole set: `deleteByAgentId` first, then a batch insert of the form's `mcpList` deduped by `mcpId`; an empty list means the bindings are cleared. For a binding to be stored it passes three checks:

| Check | Rule | Refusal text |
|------|------|---------|
| Server row resolvable | `McpServerMapper.selectByIds` (which already excludes `active = 0`) filtered by the current request tenant; unresolvable ids are collected and refused in one go | `MCP server is missing, deleted, or outside your tenant: …` |
| Server row enabled | Rows with `status != 1` are collected and refused in one go | `MCP server is disabled, enable it before binding: …` |
| Declared required params filled | Every entry in this server's `env_params` with `required = true` must have a bound value | `MCP server '…' requires env params that are left without a value: …` |

Refusing at save rather than accepting quietly is forced by delivery's own silent behaviour: an unresolvable binding leaves one log line, and the agent loses a tool with nobody noticing. The check needs the row's `env_params` to know which parameters are required, which is why `resolveBindableMcpServers` returns rows rather than ids. A disabled server therefore cannot be bound at all — delivery would hold it back too, so binding it only stores an object the agent can never reach.

The environment-parameter reference check is shared with the tool and CLI binding paths through `assertEnvBindingsBindable`, which runs only on the save path and refuses two shapes:

- One `envKey` bound to more than one source (a pointer mixed with a literal, or two different variables). Delivery emits one `{envKey, envValue}` pair per binding row and the runtime folds those pairs into a name-keyed map, so which credential reaches the tool would be decided by row order. Key uniqueness is scoped to (tenant, creator), so two users each holding an `OPENAI_KEY` is the normal case and a shared agent hits exactly this. A repeated key with the same source is kept: a server whose parameter list declares one name twice yields exactly those rows and both resolve to the same thing.
- A reference with no value. The pointer is validated with `EnvVariableService.getRowWithinTenant(id)`, compared against the **tenant** scope rather than the console's creator scope, otherwise a collaborator editing a shared agent would be refused pointers that do arrive at runtime.

Reading the value really happens at delivery, and that gate is `getDecryptedValue(id, agentTenantId)` in `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt`, which answers null in four situations: no row, tenant mismatch, `enabled != 1`, and a decryption exception (the last one with a warn). So "a variable was disabled" takes effect there; the save side refuses the first three states first, so that "filled in" on the form and "has a value" at runtime are the same fact.

The `env_bindings` snapshot stores a pointer only for a reference: `envVarId` plus the `envVarName` resolved server-side, and no value at all. What comes back in that field of the request body is the read API's display value (a mask string for a sensitive variable), and snapshotting it would turn the mask into the fallback a tool receives once the variable is gone; resolving the value server-side and storing it would write a plaintext secret into this column. A typed-in literal is stored as `customValue`, unchanged.

Whether a parameter's own default answers a required entry depends on whether that default actually reaches the runtime. Builtin tools pass `defaultValueCounts = false`; MCP and CLI pass `true` — see "Two channels for environment parameters and secrets".

### 4.2 Delivery resolution and three hold-backs

The MCP section of the agent spec built by `InternalApiController`:

1. Binding rows come from `AgentMcpBindingMapper.selectByAgentId(agentId)`, deduped by `mcpId`;
2. Server rows are fetched in bulk with `McpServerMapper.selectByIds` and then filtered by **the agent's own tenant**: `selectByIds` has no tenant predicate and an internal call carries no trustworthy tenant header, so the agent's tenant is the only comparable baseline;
3. Three hold-backs: unresolvable (deleted or cross-tenant), `stdio` while `harnax.mcp.stdio-enabled = false`, and `status = 0`. A held-back server disappears from **both halves, `mcpDetails` and `mcpList`** — this is what makes "a disabled row really goes nowhere" true: `mcpList`'s `env_bindings` are resolved into `ToolEnvContext`, so leaving it in place would still deliver the disabled server's resolved values to every tool, and a shared key would quietly override the value bound by an enabled tool;
4. Each hold-back logs its own line (missing at warn, stdio at warn, disabled at info), with the three kinds of mcpId kept distinct;
5. `mcpDetails` is built from the rows that passed, with `headers` / `envParams` **decrypted into plaintext JSON objects** by `plainConfigJson` / `plainToolEnvJson` before delivery; when the parse yields nothing while the stored column is not an empty array, an extra warn is logged so that "could not be parsed" is never displayed as "not configured".

`mcpList` has the shape `[{id, env_bindings:[{envKey, envValue}]}]`, rebuilt from the bindings that passed; it carries the resolved environment-parameter values, not server configuration.

### 4.3 Where plaintext delivery ends

The AES key lives only on the admin side (`SecretFieldEncryptor`); agent-service does not hold it, so decryption has to happen at the exit. The receiving end, `PlaintextMcpConfigDecryptor` (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt`), only parses and never touches a key: normally what arrives is a plaintext JSON object, and it reads it as a `Map<String,String>` for the client. It also accepts the stored array shape (`[{"key","value","secret"}]` and `[{"envParamName","defaultValue","secret"}]`) instead of rejecting it — that is the shape of the row in the database, so receiving it means some delivery path skipped the decryption step, and the entries flagged `secret` are then handed over as ciphertext that nothing can turn back into plaintext; for that it logs a warn naming the key, since otherwise "not a single header" and "no header configured" look identical on the page. A payload that throws during parsing likewise only produces a warn and an empty map, and that warn carries just the payload length and its first character, never the content itself. The delivered content is the runtime's single source of configuration (`AgentSpecContextHolder`), with no database fallback: the row in the database is ciphertext, this service has no key, and querying it directly would feed undecryptable ciphertext to a client.

## 5. Runtime assembly

### 5.1 Flow

```
AgentSpecResolver.buildAgentSpec()
    ├─ mcpDetails → AgentSpec.mcpServices (one McpSpec(mcpId, isAsync = true) per server)
    └─ mcpList's env_bindings → merged into ToolEnvContext (read by ToolBox tools)
            ▼
HarnessAgentLauncher.createAgentBase()
    iterate agentSpec.mcpServices (a team lead receives an empty list: MCP is a member concern):
    ├─ McpConfigAdaptorImpl.getConfig(mcpId)
    │     single source = this delivery's mcpDetails; DTO → entity conversion (incl. authType and status)
    │     nothing found → warn, skip this server
    ├─ status == 0 → info, skip (no client built, nothing reported missing)
    ├─ type == stdio and harness.mcp-stdio-enabled == false → warn, skip
    ├─ authType == OAUTH2 → mcpTokenSourceFactory.forUser(authSessionId, userId)
    │     null (session has no user identity / no token-source implementation) → warn, skip this server
    │     with a source, warm up one token first, on the thread that is building the agent
    ├─ McpHelper.createMcpClient(mcpConfig, isAsync, decryptToMap, decryptToolEnvParamsToMap, tokenSource)
    │     └─ client or registration failure → warn, close the half-built client, keep assembling the rest
    └─ agentBuilder.addMcp(client), client also collected into HarnessAgentWrapper.mcpClients
            ▼
when fewer servers were loaded than bound, one aggregate warn: from outside all anyone sees is "this agent has no MCP tools"
```

`AgentSpec.mcpServices` holds one id and `isAsync` per server. The latter chooses how the client is built (asynchronous construction calls `buildAsync()` and then waits with a deadline); its only assignment today is the declared default `true`, and no delivery path changes it. Carrying only the id is deliberate: transport, address, headers and authentication mode for this server all come from this delivery's `mcpDetails`, so the runtime has no second place that could hold a record differing from what admin decided.

The warm-up step exists because the per-request callback reads the token source on the thread where the MCP client sends, while the async handshake runs on the common fork-join pool; a cold first mint would occupy one of its few threads for an entire admin round trip. A warm-up failure is neither fatal nor reported — a user may authorize mid-session, and the path that faces the user is the per-request callback.

`McpConfigAdaptorImpl` (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`) logs one warn when the delivered `authType` is empty and lets the entity keep its default `NONE`: that connects an OAuth server out to the world as a static-header server, and the error then points at that server rather than at the deliverer missing a field.

### 5.2 Transport construction and request headers

`McpHelper.buildMcpConfig` (`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`) dispatches on `type`:

| type | Configuration | Value source |
|------|------|---------|
| `stdio` | `StdioMcpConfig(name, command, args, env)` | `env = envResolver(envParams)` |
| `sse` | `SseHttpMcpConfig(name, url, headers, queryParam)` | `headers = networkHeaders(headers)` |
| `streamablehttp` | `StreamableHttpMcpConfig(name, url, headers, queryParam)` | same as above |

An unrecognised `type` returns null and `createMcpClient` throws `MCP_CLIENT_CREATE_FAILED`. A null resolver falls back to `{ emptyMap() }`; a missing `envResolver` reuses `configResolver`.

For an `OAUTH2` server, `networkHeaders` drops a static `Authorization` of the same name from `headers` (case-insensitive) and logs one warn: both write the same header, and which one wins would be decided by transport-layer ordering rather than by anything the user can see; the one carrying an identity is the per-user token, so that one stays.

Timeouts: 10 seconds each for `initialize()` and `tools/list`; 60 seconds for client construction (stdio may first have to pull an external command); for an `OAUTH2` server the handshake budget (`initializationTimeout`) is widened to 30 seconds, because the first answer to a token callback adds a hop to admin and, through it, a hop to the authorization server. Asynchronous construction goes through `buildAsync().block(60s)`, and a null result throws `MCP_CLIENT_CREATE_FAILED` rather than a bare NPE.

### 5.3 Injecting the OAuth token

`McpHelper.createMcpClient` throws `MCP_CLIENT_CREATE_FAILED` straight away when `authType == OAUTH2` and `tokenSource == null` — building the client anyway would connect unauthenticated, fail on the first tool call, and point the error at that server rather than at the missing authorization.

With a token source the header is injected through `httpRequestCustomizer` rather than `headers(...)`: tokens expire and rotate, and a header frozen at connection time would make a long conversation die the moment its access token does (mid-answer, in a five-minute-token world). The callback is asked once per request (handshake included) and the implementation caches, so the common path is one map lookup; it runs on the thread where the client sends.

`McpAccessTokenSource` is the callback interface in `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpAccessTokenSource.kt`, and it throws `McpAuthRequiredException` when no token can be obtained: this call fails with the reason in the log, instead of an unauthenticated request going out.

### 5.4 Identity bound at construction, and the token cache

`McpAccessTokenSourceFactory.forUser(sessionId, userId)` (interface in `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpAccessTokenSourceFactory.kt`) binds the identity to the returned source. A factory rather than one global source because MCP clients are created with the agent instance and then reused by every call that instance serves, while the bearer token they present belongs to a specific person — reading "the current user" after construction would hand A's token to B the moment a cached agent is shared. A null `userId` (service key, channel session) returns null, which is the correct answer rather than an error.

The implementation is `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`:

| Mechanism | Rule |
|------|------|
| Cache key | the pair (sessionId, mcpId): the same server reached from two sessions is two tokens |
| Early renewal | treated as unusable 30 seconds before expiry, so a long call does not cross the expiry point |
| No lifetime given | a 300-second lease anyway; ask again when it runs out even if the authorization server said nothing |
| single-flight | 64 fixed stripe locks, re-read inside the lock; two threads minting the same token would make admin refresh twice, and a rotated refresh token makes the second attempt fail |
| Refusal cooldown | 15 seconds; during it `McpAuthRequiredException` is thrown directly. A missing authorization is fixed by a person clicking authorize, not by retries. The cached entry for that key is dropped at the same time, otherwise a token admin has already marked as needing consent would keep being served |
| Capacity | 2048 entries each for tokens and refusals; at the cap, expired entries are cleared first and then evicted one by one down to 1024. The tables are never cleared wholesale, because every session that lost its token would then re-mint inside the MCP request thread, turning one full cache into a batch of blocking round trips |

Only `sessionId` crosses the wire to admin: admin resolves the owner itself, so the `userId` here is only a test of "is there an identity at all" and is never transmitted — a wrong or empty value cannot make admin issue someone else's token.

### 5.5 Session identity and when OAuth-class MCP is loaded

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` maps a session prefix onto `sys_user.id` + tenant:

| Prefix | Resolution | Result |
|------|---------|------|
| `web-` / `mp-` | `SessionMapper.selectBySessionIdAndStatus(sessionId, 1)` for `creator` + `tenant_id`, then into `sys_user` | identity present |
| `task-` | the first numeric segment after `task-` as the taskId, then `SchedulerClient.taskOwner(taskId)` for creator and tenant | identity present (the task's creator) |
| `chn-` | a channel session has no user identity; returns null straight away (debug log) | no identity |
| anything else | unknown prefix, warn then null | no identity |

A null answer is not an error to escalate: a channel session is a DingTalk / Feishu conversation whose `creator` holds a channel-side sender id; presenting one person's authorization to a channel message is someone else's identity reaching into an external system. A scheduled task `task-` uses its creator's authorization — for that read the creator and tenant come from the scheduler's HTTP interface (the task domain's data lives on the scheduler side), and by design it is a cold path: it runs only when an OAuth MCP client is being built, never on the agent-spec lookup every message takes. Every consequence of a failed read is "this run does not have this identity", each with a warn, and no exception escapes into the execution flow.

Resolving `creator` to a user: web sessions store a username and mini-program sessions a numeric id, so both are tried (`selectByUsername` first, and `selectById` only when that missed and the value is all digits — a login name that really is "12345" still wins as itself). The resolved row passes two more checks: a row hit by `username` that belongs to another tenant is refused (same name across tenants is a real possibility, and landing on that row would spend someone else's authorization), and `status != 1` (account disabled) gives no identity either — the session may have opened before the switch was thrown, and it may not keep spending that authorization.

Three assembly-side consequences connect: `McpConfigAdaptorImpl` is indifferent to identity and only yields configuration; `HarnessAgentLauncher` skips an `authType == OAUTH2` server when no token source is available; `HarnessAutoConfiguration` injects the token-source factory through `ObjectProvider` as optional, so with no implementation an OAuth server cannot connect rather than connecting with an empty identity. In the team case members ask for tokens with the **root session id** (`authSessionId = rootSessionId`): the authorization belongs to whoever opened this session, and a child session is an internal key admin has never heard of.

### 5.6 Client lifecycle

`HarnessAgentLauncher` collects the clients built during this assembly into `HarnessAgentWrapper.mcpClients`; when the wrapper is discarded, `release()` is called (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`), triggered by cache eviction, explicit invalidation such as `/refresh`, a capability toggle, and service shutdown. Three steps in order: claim it with `released.compareAndSet(false, true)` (a shutdown sweep and the cache's own removal listener both reach for the same agent, so the second call must be a no-op), then `teamOrchestrator?.releaseAll()` (members exist only inside the orchestrator, so nothing else would ever close them), then each `client.close()` in turn, and finally `harnessAgent.close()`. Every close carries its own try/catch that logs a warn and continues, so one server that will not close does not leave the remaining clients open.

`HarnessAgent.close()` stops at the state layer — it unbinds the state saver and clears the state cache but never touches the toolkit's MCP clients, and a stdio client is an operating-system process; an agent is rebuilt on a 30-minute timer, so without this step every rebuild would leave a process behind.

`release()` is not called while a request is in flight: that would pull the tools out from under a step that is executing, and the caller owns that judgement.

A single failing server is not rethrown during assembly: one unreachable server should not cost the agent all of its tools, which is the same shape as admin dropping unresolvable bindings from the spec. `addMcp` has its own waiting timeout and its registration thread may still be running, so the failure path closes whatever client was already built.

## 6. Two channels for environment parameters and secrets

| Channel | Configuration site | Acts on | When it is decrypted |
|------|---------|---------|---------|
| `mcp_server.headers` / `env_params` | the MCP server's own configuration (Admin MCP edit page) | **the MCP client itself**: request headers / stdio process environment | at the admin exit (decrypted before delivery) |
| `agent_mcp_binding.env_bindings` | the environment-variable form when an agent binds the MCP server | **`ToolEnvContext`** (read by ToolBox tools) | at the admin exit (resolved to plaintext values inside `mcpList`) |

The second channel is never injected into the MCP client itself. What it resolves goes into this agent's environment context, which is exactly why a held-back server has to disappear from `mcpList` entirely — otherwise its values would still reach every tool.

`ToolEnvContext` is one flat name-indexed map shared by the whole agent, and both binding channels merge into it: `AgentSpecResolver` folds `toolList` in first and `mcpList` after, so a key appearing on both sides is decided by that order rather than by which configuration is closer to intent. This is also why `assertEnvBindingsBindable` refuses "one key bound to two sources" at save time — that check sees only one binding form at a time, while what decides the outcome is the merge order of this map.

There is one table holding variable values: `env_variable` (entity `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/EnvVariable.kt`). Key uniqueness is scoped to (tenant, creator) — `uk_env_tenant_creator_active_key (tenant_id, creator, active_env_key)`, where the generated column becomes NULL when `active = 0`, so a deleted key can be used again. At delivery `resolveEnvBindingsJson` follows each pointer with `getDecryptedValue(envVarId, the agent's tenant)` and, when it resolves to nothing, **delivers nothing for that key** with one warn: a reference row carries no value of its own (`serializeEnvBindings` writes only `envKey` / `envVarId` / `envVarName` for a reference), so the tool sees "this parameter is not configured" — a state one can act on, and more accurate than delivering an empty string, since many tools decide whether they are configured by looking for the name.

An MCP server's own `env_params` uses the same entry shape as builtin tools, `ToolEnvParamEntry` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt`: `envParamName` / `description` / `required` / `secret` / `defaultValue`), with the value stored in `defaultValue`; entries with `secret = true` are encrypted into this column by `SecretFieldEncryptor` and decrypted by `decryptToolEnvParamsToMap` into `envParamName → plaintext value`. Builtin tool parameter definitions live in their own table, `agent_tool_env_param`, one column per field, whereas MCP and CLI keep this JSON on their own row — they have no code-side parameter declaration to read.

This column feeds only the stdio branch: `McpHelper` turns it into the spawned process's environment. A network-transport MCP goes out with its own `headers` and does not read `env_params`.

MCP delivery does not "top up declared defaults into the environment context": what `mcpList` delivers is exactly the key/value pairs resolved from the binding rows, so a server that declares a `required` parameter the agent did not fill leaves the tool without it (the save-time checks refuse that shape one step earlier). CLI delivery does exactly that: `mergeCliEnvBindings` takes the binding values first and tops each missing key up with the default the package declares; without the top-up an installed CLI lands in a sandbox with no credentials at all — the payload arrives, `check_command` passes, and every invocation then fails on "not logged in", a silent failure no gate reports. The same difference decides `defaultValueCounts`. Builtin tools pass `false`: their delivery carries only binding values, so a tool's own declared default cannot stand in for a required parameter. MCP and CLI pass `true`: the required check reads the entries declared on that very server / package, and for MCP those entries do reach the runtime as the spawned process's environment while for CLI they are merged into the sandbox environment — in both cases a declared default really does answer a required parameter.

## 7. Configuration switches and deployment parameters

| Configuration | Default | Effect |
|------|------|------|
| `harnax.mcp.stdio-enabled` (admin, `McpStdioPolicy`) | false | stdio admission gate: when off, creating stdio and switching into stdio are refused, existing stdio rows are not delivered, and `list_tools` / connectivity test refuse as well |
| `harness.mcp-stdio-enabled` (agent, `HarnessAutoConfiguration`) | false | second line of defence on the runtime side: when off, assembly skips stdio servers even if a row reaches it. A process starts only when both sides are on |
| `app.base-url` | Two layers: `harnax-admin/src/main/resources/application.yml` reads `${APP_BASE_URL:http://localhost:8080}`, while `harnax-deploy/docker-compose.yml` passes the container `${APP_BASE_URL:-http://localhost:28080}` (that is what a container without the variable gets), and `harnax-deploy/.env.example` ships it as `http://localhost` | admin's own address, used for the callback URL when `app.frontend-base-url` is empty; inside a container the value read is the compose layer, not the 8080 in the yml |
| `app.frontend-base-url` | empty | the browser-side address: the OAuth `redirect_uri` is built from it plus `/mcp/oauth/callback`. In production nginx proxies both under one domain; in development the SPA is on `:8000` while admin is on `:8080`, and then it must be configured explicitly |

The two stdio switches are driven by one environment variable, `HARNAX_MCP_STDIO_ENABLED`: `harnax-admin/src/main/resources/application.yml` and `harnax-agent/harnax-agent-service/src/main/resources/application.yml` each bind it to their own key, `harnax-deploy/docker-compose.yml` passes that single value to both the admin container and the agent-service container, and `harnax-deploy/.env.example` carries the one commented line that explains the trade-off. The console's type dropdown offers only `sse` and `streamablehttp` (`harnax-webui/src/pages/mcp/components/CreateForm.tsx`); the form's `stdio` branches are left in place so that a deployment turning the switch on does not have to come back and rebuild the frontend logic.

The reason for keeping stdio off is written into `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt`: a stdio record is not a connection but a process, which agent-service starts, and that container runs as root with the host Docker socket mounted (`harnax-deploy/docker-compose.yml`) — being able to save such a row is being able to execute a command there. stdio is unsupported until the execution side is isolated. Storage stays open, because making existing rows un-editable would leave them impossible to maintain and impossible to disable, a worse state than the one being guarded against; what the gate covers is every path that would actually start one. An existing stdio row can still be renamed, described and disabled, it is simply never delivered, and `refusalReason()` says exactly that instead of returning a generic failure.

## 8. Explicit non-goals and boundaries

- **stdio-type MCP is unavailable on the current deployment**: both switches default to off, and opening them on a deployment whose runtime is isolated is an operator decision.
- **`BASIC` authentication is not usable**: the column and the constant exist, the runtime has no matching branch, and the management side refuses it.
- **No global or per-service OAuth token**: an OAuth grant has exactly one form, "some user for some server"; there is no path to a token shared at tenant level.
- **Sessions without a user identity do not load OAuth-class MCP**: a channel `chn-` session and a service key carrying no user cannot mint a token; assembly skips that server with one warn rather than degrading to an unauthenticated connection.
- **No dynamic client registration (DCR)**: `registration_endpoint` is discovered and recorded, but nothing calls it; client credentials are registered by an administrator at `/oauth/client`.
- **`CALL`-level audit records no tool-call content**: what `mcp_call_log` carries is the issuance and refresh decisions on the authorization side, without tokens or request bodies.
- **Binding-level environment parameters are not applied to the MCP client**: `env_bindings` serves `ToolEnvContext` only.
- **Admin does not authorize on someone's behalf**: the management plane's connectivity test and `list_tools` refuse OAuth servers, because that check has no user to spend.
- **No token delivery or persistence**: `oauthConfig` is not in `McpDetailDto`, an access token is not in any management-plane response body, and refresh-token ciphertext never leaves the admin process.
- **`mcp_oauth_client` is not cleaned up when a server is deleted**: one row is a tenant's client identity at one authorization server and may be shared by other MCP servers.
- **Pending consent state is not shared across processes**: it lives in memory, so an admin restart interrupts a consent in progress and multiple replicas require sticky routing.
- **No redirect following**: outbound HTTP for discovery and token exchange is always `Redirect.NEVER`, and DNS-rebinding protection is not claimed either.
- **MCP binding delivery does not top up the server's own declared defaults**: what `mcpList` delivers is exactly the values resolved from binding rows, and the declared `env_params` feed only the stdio branch's process environment (CLI delivery does merge a package's declared defaults key by key — a difference between the two domains). Environment-parameter key uniqueness lives in one table, `env_variable`.

## 9. Key file index

| Category | Path |
|------|------|
| Entities | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentMcpBinding.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/EnvVariable.kt` |
| Wire-format DTOs | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpAccessTokenResponse.kt` |
| Mappers | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpServerMapper.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentMcpBindingMapper.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpOauthClientMapper.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpUserCredentialMapper.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpCallLogMapper.kt`, with same-named XML under `harnax-entity/src/main/resources/mapper/` |
| Migrations | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` (admin's schema baseline: the columns, keys and defaults of the six tables this domain uses — `mcp_server`, `agent_mcp_binding`, `mcp_oauth_client`, `mcp_user_credential`, `mcp_call_log`, `env_variable` — are all written in this one script) |
| Management API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| Management services | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpServerService.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpOAuthService.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpOAuthUserService.kt` |
| Policy and identity | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` |
| Request and response DTOs | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerCreateRequest.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerUpdateRequest.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpServerResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpConfigEntry.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthClientRequest.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthDiscoveryResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthAuthorizeResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeRequest.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthExchangeResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthStatusResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthRevokeResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpToolResponse.kt` |
| Encryption and outbound | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/ApiErrors.kt`, `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` |
| Binding and delivery | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt` |
| Runtime configuration read | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpConfigAdaptor.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` |
| Token sources | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpAccessTokenSourceFactory.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`, `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpAccessTokenSource.kt` |
| Client construction | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`, `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpConfig.kt`, `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpErrorCode.kt` |
| Assembly | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` |
| Configuration and deployment | `harnax-admin/src/main/resources/application.yml`, `harnax-agent/harnax-agent-service/src/main/resources/application.yml`, `harnax-deploy/docker-compose.yml`, `harnax-deploy/.env.example` |
| Frontend | `harnax-webui/src/typings.d.ts`, `harnax-webui/src/services/ant-design-pro/mcp.ts`, `harnax-webui/src/pages/mcp/index.tsx`, `harnax-webui/src/pages/mcp/detail.tsx`, `harnax-webui/src/pages/mcp/oauth-callback.tsx`, `harnax-webui/src/pages/mcp/components/CreateForm.tsx`, `harnax-webui/src/pages/mcp/components/UpdateForm.tsx`, `harnax-webui/src/pages/mcp/components/OAuthFields.tsx`, `harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`, `harnax-webui/config/routes.ts`, `harnax-webui/src/app.tsx` |
