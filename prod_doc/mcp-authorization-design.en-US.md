# Harnax MCP Outbound Authorization Design (English)

> Chinese version: [mcp-authorization-design.zh-CN.md](./mcp-authorization-design.zh-CN.md).
> This document covers **the per-user OAuth authorization chain**: how a code becomes a token, how tokens are encrypted at rest, how they are injected per user at runtime, and which session types carry no user identity. The `mcp_server` CRUD, field masking, configuration delivery and the rest of runtime assembly are outside its scope; `mcp-management.en-US.md` covers that half. Each document stands on its own and neither needs the other to be read, and where both state the same fact they state it the same way.
> Inbound authentication (who may call harnax) is out of scope, although the issuance endpoint reuses the existing "resolve a session back to its owner" capability.

## 1. Scope and Settled Positions

1. On this chain harnax is only an OAuth 2.1 **client** and a **credential store**: no IdP, no MCP server side, no proxying of the upstream tool surface.
2. A refresh token exists only inside the `harnax-admin` database, encrypted, never a field of any response DTO, and never part of the model context. Before each outbound HTTP request the runtime asks admin for a short-lived access token.
3. How the upstream authenticates is an explicit column, `mcp_server.auth_type`: `NONE` / `STATIC_HEADER` / `BASIC` / `OAUTH2`. The runtime honours three of them (`NONE`, `STATIC_HEADER`, `OAUTH2`); `BASIC` is refused on the write side by `McpAuthTypes.SUPPORTED` — storing a value the runtime does not understand tells the administrator it is configured while the request stays bare.
4. **stdio MCP servers are not supported**: `HARNAX_MCP_STDIO_ENABLED` defaults to false, admin and the runtime each carry a switch of that name, and a process only starts when both are on. stdio also has no HTTP request to attach a token to, so `validateAuthType` refuses stdio + `OAUTH2` outright. This chain applies to `sse` and `streamablehttp`.
5. **Channel sessions (`chn-`) have no user identity, so OAuth MCP servers are not loaded for them**: `McpSessionOwnerResolver.resolve` returns null for the `chn-` prefix, and assembly leaves that server out of the toolkit.
6. **Scheduled-task sessions (`task-`) run as the person who created the task**: ownership is read back from the scheduler's task row (`creator` plus `tenant_id`), and the grant spent is that person's own.
7. The injection point is agentscope's `McpClientBuilder.httpRequestCustomizer`, a per-request callback, so token rotation never needs a rebuilt MCP client.
8. The issuance endpoint accepts only a `sessionId`, never any user field. admin resolves the owner itself, so agent-service cannot name whose credential it wants.

## 2. The Authorization Data Model

Migrations live in `harnax-admin/src/main/resources/db/migration/`. This domain uses `V25__add_mcp_oauth_columns.sql` (two columns on `mcp_server`) and `V26__add_mcp_oauth_tables.sql` (three tables); for anything beyond those two, the script with the highest number in that directory decides the final shape. There is a second DDL baseline for these four tables in the repository, in `harnax-entity/src/test/resources/schema-test.sql` (one `CREATE TABLE` each for `mcp_server`, `mcp_oauth_client`, `mcp_user_credential` and `mcp_call_log`): nothing synchronises the two, so the column definitions there have to be aligned by hand. The column sets below are admin's creation scripts as written.

### 2.1 The two authorization columns on `mcp_server`

| Column | DDL | Meaning |
|--------|-----|---------|
| `auth_type` | `VARCHAR(20) NOT NULL DEFAULT 'NONE'` | Upstream auth method, one of the four `McpAuthTypes` values; blank or absent is treated as `NONE` |
| `oauth_config` | `TEXT DEFAULT NULL` | JSON holding **only non-sensitive** config, shaped by the `McpOAuthConfig` DTO: `authorizationServer`, `scopes`, `audience`, `resourceIndicator` |

- `oauth_config` is the same kind of column as `headers`: plain JSON, unencrypted, echoed to the front end in clear text. Nothing that looks like a secret may therefore appear in it — `client_id`/`client_secret` belong to `mcp_oauth_client`, tokens belong to `mcp_user_credential`. The allow-list is enforced by the type itself: extra keys do not fit into `McpOAuthConfig`, so they cannot be written into the column.
- `oauth_config` may only be written while `auth_type=OAUTH2` (`writeOAuthConfig` raises for a non-OAuth server that carries a config); when a row's `auth_type` is anything else the column is cleared to NULL, so the UI never keeps showing a scope list that row does not use; a non-blank `authorizationServer` must be `http(s)` with a host, because discovery really requests that address.
- The authorization path that reads this column (`McpOAuthUserServiceImpl.readConfig`) raises "re-save this server's OAuth settings" on a parse failure instead of treating it as unconfigured — silently reading bad JSON as "nothing configured" shows the user "authorization server unknown" while the person who configured it has no idea why.
- `auth_type` is in no index and `V25` backfills nothing: `NONE` and `STATIC_HEADER` take the identical code path, and `headers` also holds plain routing headers, so a derived label would be indistinguishable from a decision. Administrators set the label deliberately where it matters.

### 2.2 `mcp_oauth_client`: the client identity at the AS, shared per (tenant, issuer)

Several MCP servers behind one authorization server share one registration, which is why it does not hang off `mcp_server` and why deleting an MCP server does not delete it.

| Column | DDL | Meaning |
|--------|-----|---------|
| `id` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | Row identity |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 1` | Owning tenant |
| `issuer` | `VARCHAR(255) COLLATE utf8mb4_bin NOT NULL` | The AS `issuer`, compared byte-exactly with a token's `iss` |
| `client_id` | `VARCHAR(255) NOT NULL` | Registered `client_id`; the empty string is an explicit state "endpoints known, client not registered" |
| `client_secret_enc` | `TEXT DEFAULT NULL` | AES ciphertext; NULL means a public client (PKCE only) |
| `registration_source` | `VARCHAR(20) NOT NULL DEFAULT 'MANUAL'` | `MANUAL` / `DCR` / `ID_METADATA`; the only value code writes is `MANUAL` |
| `authorization_endpoint` / `token_endpoint` / `registration_endpoint` / `revocation_endpoint` | `VARCHAR(500)` | Discovery snapshot; a NULL `registration_endpoint` means no DCR support, a NULL `revocation_endpoint` means revoke locally only |
| `scopes_supported` | `TEXT` | Discovery snapshot, comma-separated |
| `callback_url` | `VARCHAR(500) COLLATE utf8mb4_bin NOT NULL` | The exact `redirect_uri` registered at the AS; no prefix matching |
| `creator` | `VARCHAR(100) DEFAULT ''` | Creator |
| `active` | `TINYINT NOT NULL DEFAULT 1` | Soft-delete flag |
| `create_time` / `update_time` | `DATETIME` | Timestamps, `update_time` maintained by the database |
| `active_client_id` | `VARCHAR(255) GENERATED ALWAYS AS (IF(active = 1, client_id, NULL)) VIRTUAL` | Generated column backing the unique key |
| Keys | `UNIQUE KEY uk_mcp_oauth_client_tenant_issuer_client (tenant_id, issuer, active_client_id)`, `KEY idx_mcp_oauth_client_tenant_issuer (tenant_id, issuer)` | A retired registration does not hold its name, so it can be re-registered |

- `issuer` and `callback_url` use the binary collation while the rest of the table keeps the default. MySQL's default collation folds case, but RFC 8414 compares `iss` and RFC 6749 compares `redirect_uri` as exact strings, so a case-insensitive match would silently reuse another authorization server's registration. An administrator who mistypes the case of an issuer gets "not registered" rather than an automatic hit.
- The reuse query `selectByTenantAndIssuer(tenantId, issuer)` carries `ORDER BY id LIMIT 1`: the unique key allows several `client_id` values under one issuer, so there has to be a definite answer, otherwise the same tenant would keep registering a second client at one AS.
- `updateById`'s SET list applies no null-checks: when "the AS withdrew DCR support" has to be written as a NULL `registration_endpoint`, a conditional update would keep a dead endpoint in the database and keep calling it. The row-identity columns (`tenant_id`, `issuer`) are absent from the SET list.

### 2.3 `mcp_user_credential`: the grant, per user and per MCP server

| Column | DDL | Meaning |
|--------|-----|---------|
| `id` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | Row identity |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 1` | Matches `mcp_server.tenant_id`; grants are archived under the server row's tenant |
| `user_id` | `BIGINT NOT NULL` | `sys_user.id`, never a username |
| `mcp_id` | `BIGINT NOT NULL` | `mcp_server.id` |
| `access_token_enc` | `TEXT DEFAULT NULL` | AES ciphertext; cleared to NULL on revoke |
| `refresh_token_enc` | `TEXT DEFAULT NULL` | AES ciphertext; never leaves the admin process |
| `access_expires_at` | `DATETIME DEFAULT NULL` | Expiry of the stored access token; past due is treated as missing |
| `scopes` | `VARCHAR(512) DEFAULT NULL` | Scopes actually granted, possibly narrower than requested |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` | `ACTIVE` / `NEEDS_CONSENT` / `REVOKED` |
| `last_error` | `VARCHAR(512) DEFAULT NULL` | Redacted failure reason, truncated to 500 characters before the write |
| `last_refreshed_at` | `DATETIME DEFAULT NULL` | Last successful refresh |
| `create_time` / `update_time` | `DATETIME` | Timestamps |
| Keys | `UNIQUE KEY uk_mcp_user_credential_tenant_user_mcp (tenant_id, user_id, mcp_id)`, `KEY idx_mcp_user_credential_mcp (mcp_id)` | Unique on the triple |

- **This table has no `active` column and is never soft-deleted.** Revoking updates the row in place — both ciphertexts plus `access_expires_at` and `scopes` become NULL, `last_error` is cleared, `status = REVOKED` (`clearLocally`) — and a re-authorization overwrites the same row. A soft-deleted credential would still occupy `(tenant_id, user_id, mcp_id)` and block the re-consent insert. Hard deletion happens in four situations: the MCP server is deleted, `auth_type` leaves `OAUTH2`, or `url` changes so the RFC 8707 resource moves — those three all call `deleteByMcpId` — and the fourth is deleting the user account, which calls `deleteByUserId` and clears every grant that person holds on any server (the call site is the delete path in `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SysUserServiceImpl.kt`): the `sys_user` row itself only goes soft-deleted, so nothing else ever reads those grants again and their owner has no page left to revoke them from.
- `updateById`'s SET list is **unconditional throughout**: a conditional update can never write NULL back, so a revoke would leave a token in the database that "still works while the administrator believes it revoked".
- `selectByUserAndMcp(tenantId, userId, mcpId)` puts the tenant in the query rather than filtering afterwards.
- The Mapper has exactly `selectByUserAndMcp` / `insert` / `updateById` / `deleteByMcpId` / `deleteByUserId`, five methods, with no list method: rows here are located by "who, for which server", there is nothing to page through, and another entry point would only be another way around the tenant condition. Both deletes clear a whole batch by an external key (one server, one person) and carry no tenant predicate — `mcp_id` and `user_id` each already bound the scope.

### 2.4 `mcp_call_log`: audit of authorization decisions

| Column | DDL | Meaning |
|--------|-----|---------|
| `id` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | Row identity |
| `tenant_id` | `BIGINT NOT NULL DEFAULT 1` | Taken from the server row |
| `user_id` | `BIGINT DEFAULT NULL` | A call whose session owner cannot be resolved still has to leave a trace |
| `mcp_id` | `BIGINT NOT NULL` | The server |
| `session_id` | `VARCHAR(255) DEFAULT NULL` | Which runtime session the call came from; NULL for a decision taken from the admin page |
| `tool_name` | `VARCHAR(255) DEFAULT NULL` | For call-level audit; NULL for token issuance and refresh |
| `action` | `VARCHAR(20) NOT NULL DEFAULT 'ISSUE'` | `ISSUE` / `REFRESH` / `REVOKE` / `CALL` |
| `outcome` | `VARCHAR(20) NOT NULL` | `OK` / `AUTH_FAILED` / `NEEDS_CONSENT` / `ERROR` |
| `latency_ms` | `BIGINT DEFAULT 0` | Duration of this decision |
| `create_time` | `DATETIME DEFAULT CURRENT_TIMESTAMP` | Write time; this table has no `update_time` |
| Keys | `KEY idx_mcp_call_log_tenant_mcp_time (tenant_id, mcp_id, create_time)`, `KEY idx_mcp_call_log_session (session_id)` | Read by server and time, by session |

- Only outcome and duration are recorded: **no request body, no response body, no Authorization value**. This table is readable by administrators who are not the token's owner, so anything resembling a credential would turn the audit log into the leak.
- `action` and `outcome` are separate because with only `outcome` "issuance failed" and "call failed" look identical in the audit, while the first calls for looking at the authorization chain and the second at the tool itself. There is a single writer of this table: `McpOAuthUserServiceImpl.audit()`, and the combinations it writes are `ISSUE` × {`OK`, `NEEDS_CONSENT`}, `REFRESH` × {`OK`, `ERROR`} and `REVOKE` × `OK`; `CALL` and `AUTH_FAILED` are values defined in the enum with no writing call site in production code.
- A failed audit write never fails the call: `audit()` wraps the insert and only warns that the row could not be written.
- The Mapper has `insert` alone. An audit table an application can rewrite is not an audit table.

### 2.5 Encryption of credentials

- The algorithm is AES-256-GCM: `AesUtil.encrypt` generates a fresh 12-byte IV and returns Base64(`IV + ciphertext`); `decrypt` splits it back. The key comes from `harnax.aes.secret-key`, i.e. the environment variable `HARNAX_AES_SECRET_KEY`.
- When the configured key is not 32 bytes, `normalizedKey()` zero-pads or truncates it and **warns at startup about exactly that**: a short key is weaker than it looks, and correcting it makes everything already written undecryptable. A key still equal to the public placeholder in `application.yml` warns too — with a placeholder, every ciphertext is readable to anyone holding the source tree.
- **This boundary decides the design**: `harnax.aes.secret-key` is configured only in `harnax-admin`; agent, router, channel and scheduler yml files do not carry it, and `AesUtil` exists only in admin's source tree. So configuration delivery decrypts every `secret = true` entry on the way out (`InternalApiController.plainConfigJson`), and the runtime's `PlaintextMcpConfigDecryptor` only parses JSON. The credential store therefore has to be in admin, and the runtime may only ever hold an already-minted, short-lived access token.
- **Per-entry decryption**: `SecretFieldEncryptor.decryptToMap` runs `runCatching` around each `secret = true` entry separately. One entry that will not open skips that entry alone with a named warning, and a summary line adds "N of M entries cannot be decrypted, K are being delivered". Wrapping the whole batch in one try would let a single undecryptable row drop every header of the service, which reads on screen as "no headers configured". `decryptToolEnvParamsToMap` applies the same rule to `ToolEnvParamEntry`.
- **Mask write-back protection** (one three-state semantics shared by every secret column, alongside the public/private flag `is_public`): the detail API masks secrets, so an untouched field comes back from the edit form as a mask. `serializeWithEncryption` and `resolveSecret` decide as follows — field absent means keep the stored ciphertext; a value shaped like a mask (four consecutive `*`) means keep the stored ciphertext, and for a single column with nothing to keep it raises and asks for re-entry; an empty string (the single-column path `resolveSecret` only) means clear it explicitly, which is the only way to say "this client has no secret"; anything else is a fresh value and gets encrypted. A `secret = false` entry arriving with a mask is **refused** rather than stored, because a non-secret entry has no ciphertext to carry over and the result would be a request header that looks like a key and authenticates as nothing.
- Two places in this domain use that machinery: `mcp_oauth_client.client_secret_enc` goes through `resolveSecret` (a single column), `mcp_server.headers` / `env_params` through `serializeWithEncryption` / `serializeToolEnvParams` (JSON entries).
- Tokens and secrets are decrypted only when needed: `openToken` returns null for a ciphertext it cannot open, and callers treat that as "this credential is absent", warning about the row kind and the exception name only, never about the material.

## 3. The Authorization Chain: First Consent

The precondition is that discovery and client registration have run for this server: `oauth_config.authorizationServer` names an issuer, `mcp_oauth_client` has the row reused per (tenant, issuer), and its `client_id` is not empty. An empty `client_id` is the state discovery stores on purpose — "endpoints known, client not registered" — and `requireClientRegistration` refuses it **when the authorization is requested**, so no user ever reaches the AS with `client_id=` and gets told off there instead. A missing `authorization_endpoint` or `callback_url` raises on the spot and names the endpoint to re-run.

### 3.1 Starting: `GET /api/admin/mcp/{id}/oauth/authorize-url`

```
1. currentUserId(): no user identity, no answer — authorization is per person and there is nobody to attribute the grant to
2. requireOAuthServer(id): read the row through getVisibleMcpServer (tenant plus visibility guard), then require authType=OAUTH2
3. an optional scope parameter overrides the list configured on the server; after de-duplication, a joined
   list wider than 512 is refused (truncating would record a grant nobody asked for)
4. compare against mcp_oauth_client.scopes_supported: an unknown scope only warns
5. resource = the server row's url (RFC 8707), present unless oauth_config.resourceIndicator says otherwise
6. budget check: stateStore.countFor(userId) >= 5 refuses, and it runs before any verifier or state is minted
7. generate code_verifier (32 random bytes, base64url unpadded) and state (24 random bytes)
8. store PendingAuthorization(tenantId, userId, mcpId, issuer, codeVerifier, redirectUri, resource,
   requestedScopes, TTL) in memory; put returning false means the global 500 is full — "try again in a moment"
9. build the authorization_endpoint URL: response_type=code, client_id, redirect_uri, state,
   code_challenge=BASE64URL(SHA256(verifier)), code_challenge_method=S256, plus optional scope / resource / audience
```

- `redirect_uri` is the `callback_url` stored on the registration, and that is a **front-end route**, not an endpoint of this service.
- PKCE is unconditionally `S256`: the AS's `code_challenge_methods_supported` is not snapshotted. OAuth 2.1 and MCP both make PKCE mandatory, so an AS without `S256` errors on its own side and admin does not downgrade to `plain`.
- `resource` is the binding that stops a token minted for one MCP server being replayed at another; it is switched off only for AS deployments that reject the parameter, via `resourceIndicator=false`.

### 3.2 Exchange: `POST /api/admin/mcp/oauth/exchange`

The AS redirects the browser to a front-end route; the landing page clears its query string first and then calls this endpoint with the JWT the browser already has. There is **no** `{id}` in the path: the target server can only be named by the pending request, and accepting an id would let a captured `state` be pointed at any server the caller picks.

The decision order is fixed (each step is an explicit answer, HTTP stays 200 with `authorized=false` plus what to do next):

```
1. stateStore.consume(state) — the state is spent in every branch
2. pending != null and pending.userId != caller → warn (mcpId and the two userIds only) and refuse
3. the body carries error → the AS's own error and error_description, verbatim, capped at 200 characters
4. pending == null → "this authorization request is unknown or has expired"
5. no code → "the authorization server returned no code"
6. only now redeem(code)
```

- Step 2 sits **before anything the request body says**. Otherwise a caller who learned someone else's `state` could cancel that person's in-flight authorization by posting an `error` of their own, and the warning that detects consent phishing would say nothing. This is what makes the chain resistant to consent phishing: `state` is not a source of identity, it only answers "whose name should this consent be recorded under".
- Step 3 answers even when the pending entry has aged out: nothing is stored and nothing is left to burn, and upstream's own reason is worth more to the page than "unknown state".
- No field of the answer can carry token material.

### 3.3 Checks Before the Row Is Written

What `redeem` does, in order:

- The server row is re-read through `requireOAuthServer(pending.mcpId)` (tenant and visibility guards included), and: its `tenantId` must still equal `pending.tenantId` (a server moved out of the originating tenant does not get the grant); **this next check has a precondition** — only when `pending.resource` is non-null must the row's `url` still equal it (the code was minted for the address consent was given against, and storing the result would park a key that opens nothing); when the pending entry carries no resource at all (`resourceIndicator` off, or no resource was built), the whole comparison is skipped.
- The registration is looked up by `pending.issuer`, not by whatever `oauth_config` says now — the issuer is the identity the pending state was built on. A missing row, a blank `client_id`, or an empty `token_endpoint` each raise their own message.
- The form is `grant_type=authorization_code` + `code` + `redirect_uri` + `client_id` + `code_verifier`, plus the client secret when there is one (an undecryptable one reports "re-save the OAuth client" rather than approaching the endpoint without credentials and coming back as an `invalid_client` that reads like a broken registration), plus `resource` when the pending state carries one. Credentials go in the body rather than as basic auth because every implementation accepts the body form and it needs no second code path.
- The outbound call goes through `RemoteJsonFetcher.postForm`, the same guardrail discovery uses; upstream's `error` / `error_description` is truncated and told to the page verbatim.
- The returned token is validated (`validateAgainstIssuer`): a response-level `iss` and the JWT claim `iss` must both equal the discovered issuer **byte for byte** (`assertIssuer`), and `aud` must contain this `resource`. The JWT is **decoded, not verified** — nothing here consumes the token's contents, it is only forwarded to the MCP server, which is the party that validates it. An opaque token cannot be inspected at all, and a JWT carrying no `aud` is equally uninspectable: both are **logged rather than counted as a pass**, which leaves the resource binding resting on the assumption that the AS honoured the `resource` parameter.
- Scopes come from the response, falling back to the requested list when the response omits `scope` (RFC 6749 §5.1 makes it optional); joined, a list wider than 512 is **not recorded at all** and the reason goes into `last_error` — half a list is more misleading than an empty one.
- The write is wholesale: `access_token_enc`, `refresh_token_enc`, `access_expires_at`, `scopes`, `status=ACTIVE`, `last_refreshed_at` and `last_error` are all set in one pass. A refresh token left over from the previous grant is **not kept**: it would be a way to mint tokens whose scopes nobody just agreed to.
- With no `expires_in`, `access_expires_at` is NULL, which reads as "reusable until something refuses it".
- A concurrent insert hitting `uk_mcp_user_credential_tenant_user_mcp` (`DuplicateKeyException`) falls back to `updateById` on the row that won: an authorization that genuinely happened must not be reported as a database failure.
- The `scopes` in the answer are **what the stored column holds**, not the raw `scope` field of the token response and not a list too wide to record: `status` reads the same column, and two different answers would show the landing page zero scopes while the detail page shows two seconds later.
- When the granted list is narrower than requested, the difference is warned about.

### 3.4 Status: `GET /api/admin/mcp/{id}/oauth/status`

`authorized` is true when `status == ACTIVE` and `access_expires_at` is null or later than now; this decision **does not trigger a refresh**, so a grant whose access token aged out but which could be refreshed shows as "not authorized" on the page while the runtime asking for a token gets a fresh one. The remaining fields are `status`, `scopes` (split on commas), `accessExpiresAt`, `lastRefreshedAt`, `lastError`. With no row for this user the answer is `authorized=false` and nothing else.

### 3.5 Revoke: `POST /api/admin/mcp/{id}/oauth/revoke`

**The local copy is always cleared.** Upstream is contacted only when the server's registration recorded a `revocation_endpoint`, with one RFC 7009 request. `token_type_hint` prefers `refresh_token`, falling back to `access_token`: an access token dies of age within minutes while a refresh token keeps minting new ones, and a compliant AS revoking the refresh token kills the grant as a whole. A ciphertext that will not open (the AES key moved) is handled as "no token to present", and that **does not skip clearing the local row** — this call is the user's only way out, and "will not open" is neither "there is no token" nor "the upstream refused".

The answer states which situation applies, without one standing in for another (`revoked` is always true, `upstreamRevoked` describes the other half):

| Situation | What the answer says |
|-----------|----------------------|
| No row for this user | Nothing to revoke locally; no audit row |
| The registration cannot be resolved (issuer unconfigured, row deleted) | Local copy cleared; the local row by itself does not say which AS this grant belongs to, so nothing could be presented upstream, plus the path to run discovery again |
| A registration exists but `revocation_endpoint` is empty | Local copy cleared; the AS publishes no revocation endpoint (RFC 7009), so its own copy stays valid until it expires |
| Ciphertexts present but undecryptable | Local copy cleared; the stored token could not be decrypted because the key changed, so nothing could be presented |
| Both ciphertexts already NULL | Local copy cleared; no token left to present |
| Upstream accepted / refused | Accepted: both sides cleared. Refused: "local cleared, upstream still usable" |

On refusal there is no retry and no rewrite to `NEEDS_CONSENT` — that would require a real call to observe. A decision with a row writes `REVOKE` × `OK` regardless of the upstream outcome (no row writes nothing).

## 4. Token Issuance and Refresh

### 4.1 The Internal Issuance Endpoint

`POST /api/admin/internal/mcp/access-token`, whose body is only `{sessionId, mcpId}`:

- It sits behind `InternalApiAuthFilter`, like every route under that prefix, which is what keeps it away from a browser.
- A blank `sessionId` answers 400.
- The server row is read by id through `mcpServerMapper.selectById` and **not** through `McpServerService`: that guard compares the row against the *request's* tenant header, and an internal call from agent-service has none. What scopes this answer is the server's own tenant, which is also the key the grant was stored under.
- The answer is `McpAccessTokenResponse(accessToken, tokenType, expiresAtEpochSecond)`, defined in `harnax-entity`'s dto package because it is a contract between two services. Expiry is an epoch second rather than a date-time: each service compares it against its own local clock and the two need not share a time zone.
- No `X-User-Id` or any other client-reported field is accepted. On the runtime side `userId` is used only as a presence check and never travels over the wire, so a wrong or missing value cannot make admin issue someone else's token.

### 4.2 Session Ownership: Whose Identity Is Available

`McpSessionOwnerResolver.resolve(sessionId)` dispatches on the prefix and returns `McpSessionOwner(userId, tenantId)` or null:

| Prefix | Where ownership comes from | Result |
|--------|----------------------------|--------|
| `web-` / `mp-` | The `session` row (`selectBySessionIdAndStatus(..., 1)`, active sessions only): its `creator` plus `tenant_id` | Has an identity |
| `task-` | The first segment after `task-` is the taskId; `SchedulerClient.taskOwner` reads the task row's `creator` and `tenant_id` back over HTTP | Uses **the task creator's** grant |
| `chn-` | Nothing | **No user identity**, OAuth MCP servers are not loaded (one debug line) |
| Any other prefix | Nothing | null, plus a warning about the unknown prefix |

How `creator` becomes a user:

- `web-` stores a username, `mp-` stores the numeric user id, so both are tried: `selectByUsername` first, and `selectById` only when that missed and the string is a plain number — a login literally named "12345" still wins as itself.
- A blank `creator`, or one that resolves to no user, gives null.
- The resolved user must sit in the session's own tenant: the unique key on `sys_user` covers the `active_username` generated column filtered to live accounts, but the same username in two tenants can still exist, and resolving to it would spend that person's grant. A tenant mismatch gives null with a warning. A user row with no tenant value is taken as it stands.
- `status != 1` gives null: a session opened before the account was switched off must not keep spending its owner's grants.

The task path is a cold path by design: it runs when an agent builds an OAuth MCP client, never on the agent-spec lookup every message goes through. A refused call, no answer at all, a missing task, and an exception inside the client are each null **and each warns separately**, because nothing downstream sees a failure — resolution answers nobody, the run continues without that tool, and "the scheduler has been unreachable since 03:00" must not read as "this task has no OAuth MCP servers".

### 4.3 Deciding Usability, and Refresh

The full decision in `accessToken(sessionId, mcpId)`:

```
authType != OAUTH2                 → error: this server has no per-user token to issue
owner == null                      → 401 NEEDS_CONSENT "this session has no user identity to authorize as"
owner.tenantId != server.tenantId  → 403, with no credential lookup at all
no credential row                  → 401 NEEDS_CONSENT
status == REVOKED                  → 401, and the status stays REVOKED (overwriting it with NEEDS_CONSENT
                                     would erase the one fact this row still records: that this person revoked it)
usableToken() hits                 → audit ISSUE × OK, hand it out
otherwise                          → refreshGrant()
```

- `usableToken`: `status == ACTIVE`, a ciphertext exists, and `access_expires_at` is not before `now + 60s` (`REFRESH_SKEW_SECONDS`). Refresh before expiry rather than after: a tool call that dies because a token aged out mid-run reads like a broken MCP server, and the user has no way to tell that from an outage. A row with no expiry keeps its token until something refuses it. An undecryptable ciphertext is the same as missing here and left to the refresh path to explain.
- `refreshGrant` serialises concurrent refreshes of one grant with 64 stripes (`credential.id % 64` picks one) and **re-reads the row under the lock** before deciding again: whoever held it a moment ago may have refreshed this very grant, and doing it twice burns the rotation that call just stored, which then looks like a user who needs to consent again.
- The refresh request: `grant_type=refresh_token` + `refresh_token` + `client_id` (+ `client_secret`, + `resource`). RFC 8707 §2.3 requires a refreshed token to be re-bound to the resource, otherwise the AS may hand back one whose audience is whatever it defaults to, which the MCP server then refuses.
- After a successful refresh: `access_token` is always written, `refresh_token` is replaced **only when the answer carries one** (RFC 6749 §6 lets an AS issue none, and dropping the stored one would end a grant that is still good); a missing `expires_in` keeps the previous expiry while it is still ahead, and with nothing to keep the column is NULL; a granted list wider than 512 keeps the previous value and warns; `status` returns to `ACTIVE`, `last_error` is cleared, `last_refreshed_at` is set; audit `REFRESH` × `OK`.
- `validateAgainstIssuer` runs here too, but **failure on the refresh path is a 503, not "needs consent"**: a token with the wrong issuer or audience says nothing about this person's consent.

### 4.4 Failure Semantics

The transport always answers HTTP 200 with the code carried in `ResultVo`; `BizException.code` decides the meaning:

| Situation | Code | What changes in the database | Audit |
|-----------|------|------------------------------|-------|
| No credential row / session without identity / `invalid_grant` / "4xx with no `error` in the body" / no refresh token / undecryptable refresh ciphertext | 401 | `status = NEEDS_CONSENT`, reason into `last_error` (capped at 500) | `ISSUE` × `NEEDS_CONSENT` |
| Session and server in different tenants | 403 | Nothing is looked up, nothing is touched | none |
| A revoked row | 401 | `status` stays `REVOKED` | `ISSUE` × `NEEDS_CONSENT` |
| AS unreachable / a refusal that is not `invalid_grant` / a 200 that is not a token answer / no `access_token` / issuer or audience mismatch / undecryptable `client_secret` | 503 | **Status unchanged**, only `last_error` | `REFRESH` × `ERROR` |
| During refresh: no token endpoint / no client registration row / blank `client_id` | 400 | A bare `BizException` (its constructor code is 400): `status` untouched, `last_error` **not written** | none |

Those three sit apart from the 503 group on purpose: what they say is that this server's configuration is missing a piece an administrator has to supply (run discovery again, re-save the client), not that this grant hit a retryable fault. Writing `last_error` or an audit row for them would record a management-plane gap on a person's credential row.

Telling `needsConsent` apart from `transientFailure` is the core of this design: `invalid_grant` means the authorization is gone and only the user can bring it back, while a 5xx or an unreachable server means nothing about the grant changed and retrying is enough. Marking the second kind as consent-needed pushes users through an authorization page because of a network blip, and clearing a working grant to do it is worse than the outage.

`markStatus` failing warns and moves on: a row that cannot be written must not hide the answer the caller needs, and the next attempt reads whatever status is still stored and reaches the same conclusion.

## 5. Runtime Injection

### 5.1 Delivery

`McpDetailDto` carries `authType`, taken straight from the server row by `InternalApiController`; `headers` / `envParams` have already been decrypted on the way out of admin (`plainConfigJson` / `plainToolEnvJson`). Four kinds of row are withheld before delivery: unresolvable ones, ones whose tenant differs from the agent's, ones with `status == 0`, plus every stdio row while the stdio switch is off. `McpConfigAdaptorImpl` copies `authType` onto the runtime entity and, when it arrives blank or absent, keeps the entity default `NONE` and warns — an OAuth server that arrives without this looks like an unauthenticated one and fails at the first tool call with an error pointing at the server.

### 5.2 Assembly and Binding the Token Source

The MCP loop in `HarnessAgentLauncher` passes each bound server through, in order: no config → skip; `status == 0` → skip; stdio while `harness.mcp-stdio-enabled` is false → skip (the second line of defence, reached only when the two services are configured differently); then, for `authType == OAUTH2`, it obtains a token source:

- `mcpTokenSourceFactory.forUser(authSessionId, userIdentifier.userId)`; a missing factory or a null result **skips that server** with a warning. No identity means no grant to spend, so the toolkit is short one server rather than connected unauthenticated.
- `authSessionId` is the session id being assembled, and in a team run it is the root session id — authorization follows the owner of the conversation, not the sub-agent.
- With a source in hand, one `accessToken(mcpId)` call is warmed here, on the thread already building the agent. The customizer reads the same source from whichever pool the MCP client sends on, and the asynchronous handshake runs on the common fork-join pool; a cold mint there parks one of its few threads for a whole admin round trip. **A warm-up failure is neither fatal nor reported**: the user may authorize mid-session, and the per-request callback is what surfaces it later.
- `McpHelper.createMcpClient` throws `MCP_CLIENT_CREATE_FAILED` when `authType == OAUTH2` and `tokenSource == null`: building the client anyway would connect without a token, fail on the first tool call, and point the error at the MCP server instead of at the missing grant.

**The user identity comes from the closure, not from a transport context.** `McpAccessTokenSourceFactory.forUser(sessionId, userId)` binds the identity **at build time**, and the source it returns can only ever ask for that session's token; an MCP client is created with the agent instance and then reused for every call that instance serves, so reading "the current user" from a thread local at request time would hand user A's token to user B the moment a cached agent is shared. `AdminMcpAccessTokenSourceFactory` states the same rule from the other side: admin is asked with the session id and nothing else, and the `userId` it sees is a local presence check.

### 5.3 The Injection Point and Its Cache

`McpClientBuilder.httpRequestCustomizer` takes an `McpSyncHttpClientRequestCustomizer` invoked on every outbound request, the `initialize` handshake included:

```kotlin
request.setHeader("Authorization", "Bearer ${source.accessToken(mcpId)}")
```

A callback rather than `headers(...)`, because the token expires and rotates: a header frozen at build time is baked into the connection, so a long conversation would die at the access token's expiry — with a five-minute token, minutes into a single answer. `applyUserToken` also widens this client's `initializationTimeout` to 30 seconds (`OAUTH_INITIALIZATION_TIMEOUT`): the first handshake goes to admin and through it to the AS, two network hops, and 10 seconds would make a cold first call look broken.

`AdminMcpAccessTokenSourceFactory` cache and cooldown:

| Mechanism | Value | Purpose |
|-----------|-------|---------|
| Key | `(sessionId, mcpId)` | One MCP server reached from two sessions presents two different tokens |
| Hit rule | `now + 30 < expiresAtEpochSecond`, or `now + 300` when none was stated | Renew 30 seconds early; a token with no expiry gets a five-minute lease |
| Cooldown | 15 seconds of silence after a refusal | A missing grant is fixed by a person clicking authorize, not by retrying; otherwise every tool call is another admin round trip, another refresh attempt against the AS, and another identical audit row |
| Concurrency | 64 stripes plus a re-read under the lock | Minting the same token twice would make the second attempt use a refresh token that has already rotated |
| Capacity | 2048 entries per map, then drop expired ones and trim to 1024 | Emptying the map is not an option: every session that lost a live token would mint a new one from inside the MCP request customizer, so one full cache becomes a stampede of blocking round trips |

Both cache and cooldown entries render themselves as their expiry instant only, never as the token.

### 5.4 Mutual Exclusion with a Static Header

For an `OAUTH2` server, `networkHeaders` removes any `Authorization` entry saved in `headers` before assembly and warns that the static header is ignored in favour of the per-user token. Both would write the same header and which one wins would be decided by the transport's own ordering, with nothing on screen to explain it. The per-user token is the one carrying an identity, so it is the one kept.

The admin side has a matching position: `McpServerServiceImpl.listTools` (which the connectivity test also runs) **refuses** to connect to an `OAUTH2` server — that check needs someone's token and an admin-side check has no person to spend: the grant belongs to whoever owns the session, not to whoever clicked "test". Listing tools with the requester's own grant would also make the tool set depend on who asked, which is not what a connectivity check claims to show. The answer points at the OAuth panel on the detail page.

## 6. Ending an Authorization

### 6.1 The Three Server-Side Clears

Every whole-server clear of `mcp_user_credential` goes through `deleteByMcpId`, and always after the `updateById` matched: an unmatched `updateById` means the row's auth_type has been changed by someone else in the meantime and still says OAUTH2 in the database, and deleting its grants would destroy credentials for a server that is still an OAuth one.

| Occasion | Condition | Effect |
|----------|-----------|--------|
| Server deleted | `deleteMcpServer` | All of its credential rows go in the same transaction as its `agent_mcp_binding` rows; `mcp_oauth_client` is reused per (tenant, issuer) and other servers may still use it, so it stays |
| Server URL changed | `wasOAuth && oauthResourceMoved` (the request carried a different `url`) | **This is the branch the update path tests first**: it is the `if`, and the auth-type row below hangs off it as the `else if`, so hitting this one never reaches that line. Every grant was issued for the previous resource and the token check compares `aud` with the configured url, so all of them would now fail upstream while `status` keeps reading "authorized". Clearing forces a re-authorization instead of a permanent, unexplained breakage |
| Auth type moved away from OAuth | `else if (wasOAuth && authType != OAUTH2)` | Tested only when the row above did not fire: all credential rows cleared, with the count warned. Reading and revoking a grant both go through `requireOAuthServer` (anything but `OAUTH2` is refused), so leaving the rows behind strands ciphertext their owners can neither see nor revoke |

"Did this request switch OAuth off" uses `wasOAuth`, read **before any field was overwritten**; a server that never was OAuth (including rows sitting on the default `NONE`) never queries this table at all. None of the three presents a revocation upstream: those copies expire on their own, and to revoke upstream the person has to click revoke, per grant, before the change.

### 6.2 The Person's Side

- **Account disabled**: `McpSessionOwnerResolver` returns null for an owner whose `status != 1`, so issuance reports "this session has no user identity to authorize as". The `mcp_user_credential` rows are **kept** — deleting a person's credentials by accident is worse than making them unusable for a while, and re-enabling restores them.
- **Account deleted**: a different path — the delete path in `SysUserServiceImpl` calls `deleteByUserId`, which clears every grant that person holds on any server. The `sys_user` row likewise only goes soft-deleted, but the owner of these ciphertexts cannot sign in and has no page from which to click revoke, so keeping them is blast radius nobody controls.
- **Tenant change / tenant mismatch**: blocked at resolution only, with nothing deleted.
- **A public MCP server (`is_public = 1`)** shares its **service configuration** inside the tenant, never **anybody else's credentials**. `visibleToCurrentUser` decides who can see the server, while `accessToken` always looks up the current session owner's `(tenant_id, user_id, mcp_id)` row; no path spends another person's grant.
- **Management-side visibility**: `getMcpServer` compares `tenant_id`, `getVisibleMcpServer` additionally compares `is_public` against the creator. Every `{id}`-taking endpoint in this chain goes through the latter — these endpoints build redirect URLs from a row and write grants against it, so not finding a row one may not read is the correct answer.

## 7. Security Invariants

1. **Tokens are never carried through the MCP protocol itself** (confused deputy): they travel only in the HTTP `Authorization` header, and the `resource` parameter binds the audience to this MCP server (RFC 8707).
2. A refresh token exists only encrypted inside admin; `McpDetailDto`, `McpServerResponse`, `McpOAuthStatusResponse` and `AgentSpecInfoResponse` have no field that could hold one. `McpAccessTokenResponse` is the only DTO carrying an access token, and it answers the internal API alone.
3. Tokens are compared, never consumed: `iss` must equal the discovered issuer exactly, `aud` must contain the target resource; both comparisons need the JWT claims, which is why the implementation decodes without verifying, and an opaque token is logged rather than counted as a pass.
4. Scopes are minimal; the difference between requested and granted scopes is logged.
5. **No silent escalation**: `NEEDS_CONSENT` records the status and the reason and asks the person to re-consent; no path initiates an authorization on its own.
6. A public client (`client_secret_enc` IS NULL) is protected by PKCE `S256`, and the rotated refresh token overwrites the ciphertext; the consequence of a rotation burned by a concurrent call is avoided by the 64 stripes plus the re-read under the lock.
7. `state` and `code_verifier` are single-use, live for five minutes, and are bound to the owner: `consume` removes the entry **before** the exchange is attempted, so a failed exchange cannot be retried with the same `state` and a concurrent second request with it loses; an owner mismatch is refused and warned, and the state is spent in every branch.
8. Logs and `last_error` carry no token material: `openToken` records only the kind of row and the exception name, `ApiErrors.message` keeps database errors from reaching the browser in their own words, and an upstream `error_description` is capped at 200 characters and rendered as text by the front end.
9. The in-memory pending budget is an explicit refusal rather than a silent drop: 500 entries is a budget shared by everybody, with 5 per user in front of it, otherwise one authenticated user with a script can lock everyone else out of authorization until entries age out.
10. Audit cannot be switched off (a failed write warns once) and holds nothing resembling a credential.
11. Pending state lives in **one JVM's memory**, so the exchange has to land on the process that served this `authorize-url`. Once admin runs several replicas without sticky routing, it can land on a replica that never saw the request and the user reads "unknown or expired". Multi-replica therefore needs sticky routing or a shared store behind `McpOAuthStateStore`; the 5-per-user cap is likewise counted per replica.
12. Only `/api/admin/internal/**` is `permitAll` among business paths, and what actually gates it is `InternalApiAuthFilter`'s shared secret. The branch in `JwtAuthenticationFilter` that treats "the shared secret presented as a JWT on `/api/admin/**`" as an internal service rejects `application.yml`'s placeholder default — that string is public in this repository — and counts it as unconfigured; the consequence is that the sandboxed `harnax-cli` needs a real value in `ADMIN_INTERNAL_API_SECRET` or its calls are all 401, which admin says once at startup with a WARN.

## 8. Endpoint Inventory

### 8.1 Management side (browser, JWT)

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/admin/mcp/{id}/oauth/discover` | Resolve the AS, read its metadata, store it as this tenant's registration |
| POST | `/api/admin/mcp/{id}/oauth/client` | Store `client_id` and an optional `client_secret` (the secret goes through the mask three-state single-column path) |
| GET | `/api/admin/mcp/{id}/oauth/authorize-url` | Build the redirect URL for **the current user**, with an optional `scope` override |
| POST | `/api/admin/mcp/oauth/exchange` | Redeem the `code` / `state` / `error` the landing page received; no `{id}` in the path, the pending request names the server |
| GET | `/api/admin/mcp/{id}/oauth/status` | Whether the current user is authorized, with scopes, expiry and `last_error` |
| POST | `/api/admin/mcp/{id}/oauth/revoke` | Revoke the current user's authorization |

All six carry a JWT and there is no unauthenticated OAuth entry point: `SecurityConfig` grants no exception for this chain. The five that take `{id}` go through `getVisibleMcpServer` (tenant plus visibility) and then require `auth_type = OAUTH2`; `exchange` identifies its server through the pending request. `authorize-url`, `exchange`, `status` and `revoke` all require a user identity on the request, and answer with an error rather than falling back to a default tenant. Discovery and client registration echo only the `clientSecretPresent` boolean, never the secret.

Three front-end pieces: the landing page `harnax-webui/src/pages/mcp/oauth-callback.tsx` (its `useEffect` reads the query first and `history.replace` removes it; `code` and `state` live only in a local object inside that effect and are sent immediately — never into `useState`, never into `localStorage`), the route `/mcp/oauth/callback` with `layout: false` placed before the wildcard path, and the not-logged-in redirect built by `loginRedirect` in `harnax-webui/src/app.tsx`, which for the OAuth landing page (exactly that `oauthCallbackPath`) **drops the search string** and points `redirect` back at `/context/mcp`; only other paths carry `location.search` along — which is how `code` and `state` stay out of the login page's address bar and out of the return parameter, and since the login already interrupted this consent, the person restarts it from the MCP list. The per-user block `harnax-webui/src/pages/mcp/components/OAuthPanel.tsx` reads `status` on mount and shows a three-state badge plus "authorize" (same-page redirect) plus "revoke" behind a `Modal.confirm`, along with scopes, expiry, `last_error` and the reminder that `callbackUrl` must match the registered value character for character; when `status` cannot be read, no badge is drawn at all — "not read yet" must not assert "not authorized" on the user's behalf.

### 8.2 Internal side (agent-service, shared secret)

| Method | Path | Input | Output |
|--------|------|-------|--------|
| POST | `/api/admin/internal/mcp/access-token` | `sessionId`, `mcpId`; **no user field**, admin resolves the identity from the session | `accessToken`, `tokenType`, `expiresAtEpochSecond`; no usable grant 401, cross-tenant 403, unreachable upstream 503 — all through `ResultVo.code`, HTTP stays 200 |

`GET /api/admin/internal/agent-spec/{sessionId}` carries no user identity: personal information stays out of every downstream response, and the owner is resolved only at the moment a token is issued.

## 9. Boundaries and What This Does Not Do

| Item | Status | Basis |
|------|--------|-------|
| No auth / static API key / custom headers | Present, and the `Authorization` header is dropped when such a server is also marked OAuth | `networkHeaders` |
| OAuth 2.1 authorization code + PKCE (per user) | The body of this document | `McpOAuthUserServiceImpl` |
| RFC 9728 protected-resource discovery + AS metadata | Present, with the issuer accepted only on an exact match | `McpOAuthServiceImpl` |
| `BASIC` | Column exists, refused on the write side | `McpAuthTypes.SUPPORTED`, `resolveAuthType` |
| OAuth2 `client_credentials` (service-to-service) | Not done | Needs a second way to archive credentials; it is not the same shape as a per-user chain |
| Dynamic client registration (RFC 7591), though `registration_endpoint` is stored | Not done | Lower priority in the specification than pre-registration; only `MANUAL` has a writer for `registration_source` |
| Client ID Metadata Documents (CIMD) | Not done | Same reason |
| OIDC `id_token` pass-through / JWT assertion (RFC 8693) | Not done | Requires a self-owned IdP and AS cooperation; there is no scenario |
| mTLS (per-tenant client certificates) | Not done | Certificate issuance and rotation operations are missing, not the injection point |
| Cloud signing schemes (SigV4 / GCP SA / Azure MI) | Not done | Each one needs an SDK |
| External credential vault / third-party MCP gateway holding tokens | Not done | A decision-level change; what is built here is the minimum usable version of it |
| Exposing harnax as an MCP server | Not done | There is no MCP server transport anywhere in the repository |
| stdio MCP servers | Off by default | `McpStdioPolicy`, `harness.mcp-stdio-enabled` |
| Reporting an upstream 401 back to admin ("this server refused the token") | No such path | A credential that expired and cannot be renewed is told by `status` and the audit rows, and the user re-authorizes from the detail page |
| An authorization badge on the list page | Absent | The detail page's `OAuthPanel` is the only entry point |

## 10. Key File Index

| Topic | Files |
|-------|-------|
| Authorization data model | `harnax-admin/src/main/resources/db/migration/V25__add_mcp_oauth_columns.sql`, `harnax-admin/src/main/resources/db/migration/V26__add_mcp_oauth_tables.sql`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpAuthTypes.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpOauthClient.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpUserCredential.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpCallLog.kt` |
| Mappers and SQL | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpOauthClientMapper.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpUserCredentialMapper.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpCallLogMapper.kt`, `harnax-entity/src/main/resources/mapper/McpOauthClientMapper.xml`, `harnax-entity/src/main/resources/mapper/McpUserCredentialMapper.xml`, `harnax-entity/src/main/resources/mapper/McpCallLogMapper.xml` |
| Authorization code + PKCE + issuance and refresh | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpOAuthUserService.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStore.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpOAuthController.kt` |
| Discovery and client registration | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/RemoteJsonFetcher.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt` |
| Session ownership | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/SchedulerClient.kt`, `harnax-common/src/main/kotlin/com/agnetix/harnax/common/dto/AgentTaskOwner.kt` |
| Encryption and masking | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/AesUtil.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt`, `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` |
| Management rules (auth type, stdio, visibility, clearing) | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/McpStdioPolicy.kt` |
| Delivery and internal API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpAccessTokenResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt` |
| Runtime injection | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`, `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpAccessTokenSource.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/McpAccessTokenSourceFactory.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactory.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/mcp/PlaintextMcpConfigDecryptor.kt` |
| Identity chain | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/AuthContext.kt`, `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ApiKeyInfo.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt` |
| User-facing front end | `harnax-webui/src/pages/mcp/oauth-callback.tsx`, `harnax-webui/config/routes.ts`, `harnax-webui/src/app.tsx`, `harnax-webui/src/pages/mcp/components/OAuthPanel.tsx`, `harnax-webui/src/services/ant-design-pro/mcp.ts`, `harnax-webui/src/locales/en-US/pages.ts` |
| Tests | `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImplTest.kt`, `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolverTest.kt`, `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/util/McpOAuthStateStoreTest.kt`, `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/McpOAuthControllerTest.kt`, `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/AdminMcpAccessTokenSourceFactoryTest.kt`, `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/McpUserCredentialMapperTest.kt`, `harnax-entity/src/test/resources/schema-test.sql` |
| Client construction capability | agentscope's `io.agentscope.core.tool.mcp.McpClientBuilder` (`httpRequestCustomizer`, `initializationTimeout`, `timeout`) and `io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer` |
