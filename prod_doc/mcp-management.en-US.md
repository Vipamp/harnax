# Harnax MCP Server Entity Management Flow (English)

> 中文版本见 [mcp-management.zh-CN.md](./mcp-management.zh-CN.md)
>
> For the overall tool system design, see [tool-capability.en-US.md](./tool-capability.en-US.md). This document covers the full chain of MCP server data model, Admin management, binding/delivery, and runtime assembly, with discovered issues and pending TODOs at the end.

## 1. Overview

MCP (Model Context Protocol) servers are one of the external tool sources for agents, alongside built-in tools (`BUILTIN`) and HTTP proxy tools (`HTTP`). MCP entity management follows the same layered pattern as the tool system:

- **harnax-entity**: `McpServer` / `AgentMcpBinding` entities and mappers;
- **harnax-admin**: MCP server CRUD, encrypted secret storage, masked echo, connectivity tests, binding management, and config delivery;
- **harnax-agent-service / harness-core**: runtime MCP client assembly per agent configuration.

## 2. Data Model

### 2.1 mcp_server (MCP server master table)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`

| Field | Description |
|-------|-------------|
| `name` / `description` | Server name (globally unique) and description |
| `type` | Transport type: `stdio` / `sse` / `streamablehttp` (default) |
| `command` | Execution command (stdio type only) |
| `url` | Server URL (sse / streamablehttp types) |
| `headers` | HTTP headers JSON, format `[{"key":"Authorization","value":"...","secret":true}]`; values of `secret=true` entries are stored **AES-256-GCM encrypted** |
| `envParams` | Env parameters JSON (process environment for stdio type), same format as `ToolEnvParamEntry`, secret entries encrypted |
| `status` | Enabled state (0 disabled / 1 enabled) |
| `isPublic` | Public state |
| `creator` / `tenantId` | Creator and tenant |
| `active` | Logical delete flag (0 deleted / 1 active) |

### 2.2 agent_mcp_binding (agent-MCP binding table)

Entity: `AgentMcpBinding.kt`

- `agentId` / `mcpId`: the binding relationship;
- `enableSkip`: whether to skip when the MCP is unavailable (`"true"` / `"false"` strings);
- `envBindings`: env variable binding JSON snapshot (envKey + `envVarId` or `customValue`). **Note this channel only feeds `ToolEnvContext` for ToolBox tools — it is NOT injected into the MCP client itself** (see section 6).

### 2.3 McpDetailDto (internal API delivery carrier)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/McpDetailDto.kt`

The full MCP configuration delivered by the Admin internal API to agent-service (including binding-level `enableSkip`), eliminating direct queries of the `mcp_server` table from agent-service. Note that `headers` / `envParams` are passed through **in encrypted form**.

## 3. Admin Management Flow

Implementation: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` + `service/impl/McpServerServiceImpl.kt`, prefix `/api/admin/mcp`.

### 3.1 Endpoint List

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/page` | GET | Paginated query (keyword / status / type filters) |
| `/{id}` | GET | Server details (secret fields masked) |
| `/` (root) | POST | Create an MCP server |
| `/update/{id}` | PUT | Update an MCP server |
| `/toggle/{id}` | PUT | Enable / disable |
| `/{id}` | DELETE | Logical delete (`active=0`) |
| `/{id}/connectivity-test` | POST | Connectivity test (internally calls listTools) |
| `/{id}/list_tools` | GET | Live-connect to the MCP server and fetch its tool list (names + parameter schemas) |

### 3.2 Create / Update Validation and Encryption

1. **Name uniqueness**: checked via `selectByName`; duplicates throw `BizException`;
2. **Type-field linkage validation** (`validateTypeAndFields`): `stdio` requires `command`; `sse` / `streamablehttp` require `url`; other types are rejected;
3. **Secret encryption**: `headers` via `SecretFieldEncryptor.serializeWithEncryption`, `envParams` via `serializeToolEnvParams`, encrypted then serialized to the DB (only `secret=true` entries are encrypted);
4. On update, `headers` / `envParams` are re-encrypted and overwritten only when present in the request.

### 3.3 Masked Echo

`McpServerResponse.fromEntity` decrypts secret entries then masks them: length > 7 shows "first 3 chars + `****` + last 4 chars", otherwise `******`; decryption failures also show `******`. The frontend never sees plaintext secrets.

### 3.4 Encryption Infrastructure

- `SecretFieldEncryptor` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/`): implements the `McpConfigDecryptor` interface (defined in `harnax-common`), based on `AesUtil` (AES-256-GCM);
- `McpConfigDecryptor` (`harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/`): cross-module decryption SPI with two methods, `decryptToMap` (headers) and `decryptToolEnvParamsToMap` (envParams).

## 4. Agent Binding and Config Delivery

1. **Binding save**: when an agent configuration is saved, `AgentServiceImpl.saveMcpBindings` deletes then re-inserts `agent_mcp_binding` rows; `envBindings` is written as a snapshot via `serializeEnvBindings` — global env variable references (`envVarId`) are resolved to `envVarName` plus the decrypted `envValue`, custom values are stored as `customValue`;
2. **Config delivery**: when agent-service requests an agent configuration, `InternalApiController.buildAgentSpecResponse` delivers two payloads:
   - `mcpDetails`: full `McpDetailDto` list (`headers` / `envParams` remain encrypted);
   - legacy `mcpList` JSON: `env_bindings` already resolved to plaintext via `resolveEnvBindingsJson` (`envVarId` resolves to the **latest** decrypted value of the global variable, falling back to the snapshot).

## 5. Runtime Assembly Flow (agent-service)

```
AgentSpecResolver.buildAgentSpec()
    ├─ mcpDetails → McpSpec(mcpId, skipIfMissing = enableSkip == "true")
    └─ mcpList env_bindings → merged into ToolEnvContext (read by ToolBox tools)
            ▼
HarnessAgentLauncher.createAgentBase()
    Iterates agentSpec.mcpServices:
    ├─ McpConfigAdaptorImpl.getConfig(mcpId)
    │     Primary: mcpDetails pre-delivered by admin in AgentSpecContextHolder (DTO → entity conversion)
    │     Fallback: McpServerMapper.selectById direct DB query
    ├─ Config found → McpHelper.createMcpClient(
    │        mcpConfig, isAsync,
    │        mcpConfigDecryptor?.decryptToMap,            // headers decryption
    │        mcpConfigDecryptor?.decryptToolEnvParamsToMap // envParams decryption
    │     )
    └─ Config missing → skipIfMissing=false throws AGENT_MCP_NOT_FOUND; true logs a warning and skips
            ▼
McpHelper.buildMcpConfig() dispatches by type:
    ├─ stdio          → StdioMcpConfig(command, env = envResolver(envParams))
    ├─ sse            → SseHttpMcpConfig(url, headers = configResolver(headers))
    └─ streamablehttp → StreamableHttpMcpConfig(url, headers = configResolver(headers))
            ▼
McpClientBuilder builds the client (buildSync / buildAsync) → agentBuilder.addMcp(...)
```

Other runtime entry points:

- `McpHelper.listTools`: used by the Admin connectivity test / list_tools endpoints; `initialize()` blocks with a 10-second timeout, failures throw `MCP_CONNECTION_FAILED`;
- When a resolver is `null`, it falls back to `{ emptyMap() }` — the root of pending TODO-1.

## 6. Two Channels for Env Variables and Secrets (Easily Confused)

| Channel | Configured at | Applies to | Decrypted when |
|---------|---------------|-----------|----------------|
| `mcp_server.headers` / `envParams` | The MCP server's own config (Admin MCP edit page) | **The MCP client itself** (auth headers / stdio process environment) | At agent-service runtime via `McpConfigDecryptor` (currently broken — see TODO-1) |
| `agent_mcp_binding.envBindings` | The env-variable form when binding an MCP to an agent | **ToolEnvContext** (read by ToolBox tool methods); NOT injected into the MCP client | At Admin delivery time (`InternalApiController`), already plaintext |

## 7. Discovered Issues and TODOs (Pending)

> Found during the 2026-09 code review; none fixed yet, ordered by priority.

### TODO-1 (High risk): McpConfigDecryptor is always null in the agent-service runtime — encrypted headers/envParams are silently dropped

- **Symptom**: auth headers (e.g. `Authorization`) of SSE / streamablehttp MCP servers and env parameters of stdio MCP servers are all lost during agent-service runtime assembly. MCP servers requiring credentials connect unauthenticated and fail or misbehave, with no warning logs at all.
- **Root cause chain**:
  1. The only implementation of `McpConfigDecryptor`, `SecretFieldEncryptor`, lives in the harnax-admin module;
  2. agent-service's pom does not depend on harnax-admin, so the bean does not exist in its container;
  3. `HarnessAutoConfiguration.harnessAgentLauncher` injects via `mcpConfigDecryptorProvider.ifAvailable` — when absent it stays `null`, with no fallback implementation (unlike `ToolCallLogAdaptor`, which has a no-op fallback);
  4. `HarnessAgentLauncher` passes `null` to `McpHelper.createMcpClient`; `buildMcpConfig`'s resolver falls back to `{ emptyMap() }`, blanking headers / envParams;
  5. Even a local decryptor implementation in agent-service would not help — the `mcpDetails.headers/envParams` delivered by Admin are encrypted ciphertext, and the AES key (`AesUtil`) only exists on the admin side.
- **Fix directions (either/or, to be evaluated)**:
  - Option A: sink the encryption/decryption capability (`AesUtil` + `SecretFieldEncryptor` or an equivalent) into `harnax-common`, sharing the same key configuration between agent-service and admin;
  - Option B: have the Admin internal API decrypt before delivering `mcpDetails`; plaintext travels only over the inter-service internal-auth channel (aligned with how `mcpList.env_bindings` is handled), removing the need for a decryptor in agent-service.
- **Files involved**: `HarnessAutoConfiguration.kt`, `HarnessAgentLauncher.kt`, `McpHelper.kt`, `InternalApiController.kt`, `SecretFieldEncryptor.kt`, agent-service `pom.xml`.

### TODO-2 (Medium): Create and update endpoints enforce inconsistent stdio-type validation

- **Symptom**: `McpServerController.createMcpServer` hard-codes a rejection of `type == "stdio"` (message: "stdio mode is not supported in current edition" — apparently a leftover from the multi-edition mechanism cleanup); but `updateMcpServer` goes through `validateTypeAndFields`, which allows stdio — an existing record can be switched to stdio via the update endpoint, bypassing the create restriction.
- **Suggested action**: confirm whether stdio is a supported target form in the current edition; if not, add the same interception on the update path; if yes, remove the leftover check in create.

### TODO-3 (Low): Unused mock dead code in McpServerController

- **Symptom**: `getMockTools()` and `createParameter()` — about 70 lines of mock tool data — have no callers.
- **Suggested action**: delete outright.

### TODO-4 (Low, UX): Incomplete env-variable configuration on the frontend MCP edit page

- **Symptom**: the `envs` field is only shown for the stdio type; network types (SSE / Streamable HTTP) lack env-variable configuration (source: a previously recorded issue; re-verify against current frontend code).
- **Suggested action**: design the credential configuration UX for network-type MCP servers together with the TODO-1 fix.

## 8. Key File Index

| Module | File |
|--------|------|
| Entities | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/McpServer.kt`, `AgentMcpBinding.kt`, `dto/McpDetailDto.kt` |
| Mapper | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/McpServerMapper.kt` (XML: `harnax-entity/src/main/resources/mapper/McpServerMapper.xml`) |
| Management APIs | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt` |
| Management service | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpServerServiceImpl.kt` |
| Encryptor | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/SecretFieldEncryptor.kt` |
| Decryption SPI | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/mcp/McpConfigDecryptor.kt` |
| Binding save / delivery | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`, `controller/InternalApiController.kt` |
| Spec resolution | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| Config adaptor | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/McpConfigAdaptorImpl.kt` |
| Client building | `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/mcp/McpHelper.kt`, `McpConfig.kt` |
| Runtime assembly | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`, `spring/HarnessAutoConfiguration.kt` |
