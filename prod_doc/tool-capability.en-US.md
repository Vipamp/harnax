# Harnax Tool Capability Overview (English)

> 中文版本见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md)
>
> This document is based on a survey of the current codebase, covering the tool SDK (`harnax-tools-sdk`), built-in tools (`harnax-tools-buildin`), and the full runtime tool-assembly chain in the agent.
>
> For the classification model, layer responsibilities, key design decisions with their trade-offs, and the evolution timeline, see [tool-integration-design.en-US.md](./tool-integration-design.en-US.md).

## 1. Overview

The Harnax tool system provides invocable external capabilities for agents. The design follows a layered architecture: "the SDK defines the contract, built-in modules provide implementations, Admin manages metadata, and the agent runtime assembles tools dynamically":

- **harnax-tools-sdk**: the tool SDK layer. Defines the tool abstraction (`ToolBox`), annotations (`@ToolMeta`, `ToolEnvParamDef`), the registry (`ToolRegistry`), and adaptor interfaces. No business dependencies.
- **harnax-tools-buildin**: the built-in tool module (under `harnax-tools-external`), providing ready-to-use tools (time, email, etc.) built on the SDK.
- **harnax-harness-core**: the agent runtime, responsible for dynamically assembling tools per configuration when building an agent, injecting environment parameters, and configuring permission rules.
- **harnax-admin**: the metadata management service, syncing built-in tools to the database at startup and exposing tool-management APIs.

The underlying framework is agentscope 2.0.2 (`io.agentscope`). Tools are ultimately registered into the HarnessAgent toolkit as `AgentTool` instances.

### Module dependencies

```
harnax-tools-sdk  ←── harnax-tools-buildin (built-in tool implementations)
        ↑                      ↑
        │                      │
harnax-harness-core     harnax-admin (startup sync + management APIs)
        ↑
harnax-agent-service (runtime assembly, adaptor implementations)
```

## 2. Tool Classification

### 2.1 Two categories: built-in and custom

| Category | `agent_tool.type` | Visibility | Status |
|----------|-------------------|---------------|--------|
| **Built-in tools** | `BUILTIN` | Available to every user on the platform (shipped with the code, auto-synced into the DB at startup) | Live |
| **Custom tools** | `CUSTOM` / `HTTP` | Only usable by their creator (filtered by `creator` + `is_public`) | **Not exposed yet**: the whole assembly / encryption / DTO chain is kept in the code, but the Admin UI does not show them; records can only be produced by writing the table directly or calling the service |

> Both categories go through the same runtime path (`HarnessAgentLauncher.createAgentBase()`); they only differ in the `type` branch: `BUILTIN`/`CUSTOM` create a ToolBox reflectively by `beanName`, `HTTP` instantiates `HttpProxyToolBox`.

### 2.2 Built-in tools split further: required and optional

| Sub-category | `agent_tool.is_required` | Who selects it | Runtime source |
|--------------|--------------------------|----------------|----------------|
| **Required tools** | `1` | Nobody has to — and cannot | Appended automatically when Admin delivers the AgentSpec (see 6.1); **no `agent_tool_binding` row** |
| **Optional built-in tools** | `0` | The user, in the agent configuration wizard | `agent_tool_binding` rows |
| Custom tools | `0` | Same as above (not exposed yet) | Same as above |

How this maps onto the UI:

- **Tool management page** (`/api/admin/tools/builtin`): shows all built-in tools, required and optional alike, distinguished by a "Required / Optional" tag; custom tools are not shown.
- **Agent configuration wizard** (`/api/admin/tools/available`): lists only selectable tools, i.e. enabled tools with `is_required = 0`; required tools never appear as candidates.
- Required tools cannot be bound either: `agent_tool.is_required` is synced from `@ToolMeta(isRequired)` and has no UI switch.

> For the full chain of MCP server entity management, encrypted storage, and runtime assembly, see [mcp-management.en-US.md](./mcp-management.en-US.md).
>
> Skills are another capability source alongside tools (SKILL.md plus bundled resources, not stored in `agent_tool`); for repository management, sync-into-DB and runtime assembly, see [skill-management.en-US.md](./skill-management.en-US.md).

## 3. SDK Core Concepts (harnax-tools-sdk)

### 3.1 The ToolBox Abstract Base Class

Path: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`

- Every built-in/custom tool must extend `ToolBox` and implement `name()` (the logical tool-group name).
- At runtime, `init(toolCallLogAdaptor, sessionMetaContext, userIdentifier)` injects the session context. **A fresh instance is created per session** to avoid context bleed between concurrent sessions.
- The `execute { ... }` / `execute(vararg args) { ... }` template methods wrap the tool method body, automatically handling timing, success/failure log emission (via `ToolCallLogAdaptor`), and rethrowing exceptions.
- `userIdentifier()` exposes the current invoking user.

### 3.2 Annotation System

Tool methods combine three annotations:

| Annotation | Source | Responsibility |
|------------|--------|----------------|
| `@Tool` | agentscope | Tool name (`name`), description (`description`, sent to the LLM), `readOnly` |
| `@ToolParam` | agentscope | Declares LLM-visible parameters (`name` + `description`); **parameters without it are excluded from the JSON schema** and treated as framework-injected |
| `@ToolMeta` | harnax SDK | Method-level metadata, synced to the database at startup (see 3.3) |

> ⚠️ Common pitfalls:
> - `@ToolMeta` applies to methods only; each `@Tool` method is a standalone tool instance.
> - Every parameter the LLM must supply requires `@ToolParam(name, description)`; framework-injected parameters (e.g. `ToolEnvContext`) must **not** have `@ToolParam`.
> - Prefer snake_case parameter names; mark optional parameters with `required = false`.

### 3.3 @ToolMeta Attributes

Path: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`

| Attribute | Default | Description |
|-----------|---------|-------------|
| `displayName` | `""` | English display name in the Admin UI; when blank, the sync falls back to the tool name (`@Tool.name`); also serves as the i18n fallback text |
| `displayNameZh` | `""` | Chinese display name in the Admin UI (used in the i18n zh-CN locale); the frontend falls back to the English name when blank |
| `envParamDefs` | `[]` | Array of environment parameter definitions (`ToolEnvParamDef`), synced to the `agent_tool_env_param` table at startup and used as the rendering basis for the env-parameter form when binding tools in the Admin UI; each entry has `key` (parameter name), `description` (UI hint), `required` (mandatory or not), `secret` (secret or not, masked in the UI), and `defaultValue` (default, non-secret only) — see section 3.4 |
| `timeoutSeconds` | `0` | Execution timeout in seconds; `0` means the system default (written as 30 seconds during DB sync) |
| `isPublic` | `true` | Whether publicly available (visible to all users) |
| `needConfirm` | `false` | **Whether user confirmation is required before execution.** When `true`, an ASK permission rule is generated at runtime: every invocation pauses and waits for user confirmation; it does not inspect input content and is independent of the arguments; it can be skipped in `BYPASS` permission mode. For a builtin tool this value comes from the annotation and is not editable in the UI; the only no-code way to tighten it is the binding row `agent_tool_binding.needConfirm` for one agent. The runtime ORs the two, so a binding can only add confirmation, never cancel a confirmation the tool itself declares (`agent_tool.needConfirm` itself is editable only for CUSTOM / HTTP tools) — see section 6.4 |
| `dangerousInput` | `false` | **Whether to scan string inputs for dangerous patterns** (dangerous commands like `rm -rf`, sensitive paths like `.env`/`.ssh`). Confirmation is triggered only when an input hits a dangerous pattern, and that confirmation cannot be skipped even in `BYPASS` mode (bypass-immune); safe inputs pass through directly; when combined with `needConfirm`, the input scanning is automatically skipped (the confirmation rule fires first) — see sections 6.3 / 6.4 |
| `isRequired` | `false` | **Whether this is a mandatory tool**: when `true` it applies to every agent — Admin appends it to the delivered AgentSpec, so no binding row and no user selection is needed; it never appears in the agent wizard candidate list but is still listed on the tool management page (tagged "Required"); the value comes from the annotation only and has no UI switch. See 2.2 / 6.1 |

Quick selection guide for `needConfirm` vs `dangerousInput`:

| Requirement | Which one |
|-------------|-----------|
| The tool has side effects and **every invocation** needs human confirmation | `needConfirm = true` |
| The tool is neutral; only intercept dangerous commands / paths in the inputs | `dangerousInput = true` |
| Both | Just mark `needConfirm = true` (already covers all invocations; the scan is auto-skipped when combined) |

### 3.4 Environment Parameter System

- **`ToolEnvParamDef`**: nested annotation definition declaring a single env parameter's `key`, `description`, `required`, `secret` (masked in the UI), and `defaultValue`.
- **`ToolEnvContext`**: the runtime env-binding container (`bindings: Map<String, String>`). Registered into agentscope's `ToolExecutionContext` at agent build time; any tool method declaring a parameter of this type receives it automatically and reads values via `get(key)` / `require(key)`.

### 3.5 ToolRegistry

Path: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`

A Spring `@Component`. At `@PostConstruct`:

1. Scans all `ToolBox` beans in the container and registers them by bean name;
2. Reflectively reads each bean's `@Tool` + `@ToolMeta` annotations, extracting a `ToolMetaDescriptor` (containing a `ToolMethodDescriptor` per method);
3. ToolBoxes without `@Tool` methods are skipped.

Key methods:

- `createToolBoxInstance(beanName)`: creates a **fresh per-session instance** via the no-arg constructor (falls back to the singleton template on failure);
- `getAllToolMeta()`: consumed by the Admin startup sync.

### 3.6 HttpProxyToolBox (HTTP Proxy Tool)

Path: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/HttpProxyToolBox.kt`

Implements agentscope's `AgentTool` interface directly (does not extend `ToolBox`), instantiated from the database configuration:

- Constructor: `toolName`, `toolDescription`, `httpUrl`, `httpMethod` (default POST), `httpHeaders` (stored encrypted, decrypted at runtime via `McpConfigDecryptor`), `inputSchemaJson` (the LLM parameter schema), `timeoutSeconds`;
- On invocation, model inputs are serialized as a JSON body; 2xx returns the response body, non-2xx or exceptions return error text (never throws and aborts the session).

### 3.7 Adaptor Interfaces (SPI)

| Interface | Responsibility | Implementation |
|-----------|----------------|----------------|
| `ToolCallLogAdaptor` | Emits tool call logs (`ToolCallInfo`: agentId, sessionId, toolName, args, result, success, duration) | `ToolCallLogAdaptorImpl` (agent-service, persisted to `tool_call_log`) |
| `ToolConfigAdaptor` | Loads tool configuration by `toolId` (`AgentTool` entity) | `ToolConfigAdaptorImpl` (agent-service, prefers the admin pre-resolved context, falls back to a DB query) |

### 3.8 Other Data Classes

- `ToolSpec`: a tool reference inside the agent configuration (`toolId`, `toolName`, `needConfirm` override).
- `SessionMetaContext` / `UserIdentifier`: session and user contexts, implementing the `ToolCallContext` marker interface.

## 4. Current Built-in Tools (harnax-tools-buildin)

Path: `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`

### 4.1 TimeToolBox (bean `time-tool-box`)

| `@Tool` name | `methodName` | Description | readOnly | needConfirm | isRequired | Timeout | Env params |
|--------------|--------------|-------------|----------|-------------|------------|---------|------------|
| `getDate` | `getDate` | Get the current date (`yyyy-MM-dd`) | yes | no | no | default 30s | none |
| `getDatetime` | `getDatetime` | Get the current datetime (`yyyy-MM-dd HH:mm:ss`) | yes | no | no | default 30s | none |

### 4.2 EmailToolBox (bean `email-tool-box`)

| `@Tool` name | `methodName` | Description | readOnly | needConfirm | isRequired | Timeout | Env params |
|--------------|--------------|-------------|----------|-------------|------------|---------|------------|
| `sendEmail` | `sendEmail` | Send an email via SMTP, supports plain text / HTML body | no | **yes** | no | default 30s | 5 (see below) |

- Uses Jakarta Mail directly, no Spring dependency.
- LLM parameters: `to`, `subject`, `body`, `is_html` (optional).
- Env parameters: `SMTP_HOST` (required), `SMTP_PORT` (optional, default 587), `SMTP_USER` (required), `SMTP_PASSWORD` (required, secret), `SMTP_FROM` (required).
- Port policy: 465 uses implicit SSL, 25 is unencrypted, others (including 587) enforce STARTTLS.

> All three methods take the annotation default `isPublic = true`, so the sync writes `agent_tool.is_public = 1` and every user can see them. **No builtin tool in the codebase is currently `isRequired = true`** — the required-tool branch (auto-appended at delivery, no binding row, startup conflict warning) is implemented and covered by tests, but no shipped tool uses it yet.

## 5. Builtin Tool Registration (the only lifecycle entry point)

Implementation:

- Scan: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt` (`@PostConstruct`)
- Persist: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` (`ApplicationReadyEvent`)

**The rule**: adding, updating and deleting builtin tools (`type = 'BUILTIN'`) happens **only here**. The `@Tool` / `@ToolMeta` annotations are the single source of truth, and neither the pages nor the APIs may write builtin rows.

### 5.1 Scan

At startup `ToolRegistry` collects `getBeansOfType(ToolBox::class.java)` and reads `@Tool` + `@ToolMeta` per bean, per method, producing a `ToolMetaDescriptor` (`beanName` plus each method's `toolName`, `methodName`, `displayName` / `displayNameZh`, `description`, `readOnly`, `needConfirm`, `isRequired`, `isPublic`, `timeoutSeconds`, `envParamDescriptors`). A bean without any `@Tool` method yields no metadata and is never persisted.

### 5.2 Sync (a full convergence pass on every admin start)

1. **Insert**: a method present in code but not in the database → one new `agent_tool` row (`type='BUILTIN'`, `status=1`, `active=1`, `creator='SYSTEM'`, `tenant_id=1`).
2. **Update**: anything that changed in code — the tool name, description, display names, `needConfirm`, `isRequired`, timeout, env parameter definitions — is written back field by field, `name` included. **`status` converges to 1 as well**; a builtin tool has no "disabled by an operator" state.
3. **Delete**: identity is `beanName + methodName + toolName`. Any `type='BUILTIN'` row in the database (including historical `active=0` rows) that is not in the method set declared by the code is **hard-deleted**, together with its `agent_tool_env_param` definitions and its `agent_tool_binding` rows. This covers two kinds of residue: a removed ToolBox class, and a `@Tool` method whose Java name changed or disappeared.
4. **Env parameter definitions**: `@ToolMeta.envParamDefs` sync into `agent_tool_env_param` with an "update in place + insert new + delete stale" strategy, preserving record IDs.
5. **Three brakes on deletion** — deleting is the one thing this sync must not get wrong:
   - when `ToolRegistry` finds no `@Tool` method at all (e.g. the tools module was not component-scanned), the whole sync is skipped and nothing is deleted;
   - when any tool group failed to sync (`failCount > 0`), nothing is deleted in this run — "extra rows in the database" are not trustworthy while the declared set is incomplete;
   - when the stale set is at least as large as the declared set, the run is treated as a broken scan rather than a code-side removal: the delete is skipped and an ERROR log lists the would-be victims. Fix the code and re-release.
6. **Config-conflict warning**: a method combining `isRequired = true` with required `envParamDefs` is logged at startup — a required tool has no binding row and therefore no env values, so that combination is guaranteed to fail at runtime (see 6.1).

> What a rename costs depends on which name changed:
> - `@Tool(name = ...)` only: **converges in place**. The duplicate key is `(tenant_id, bean_name, method_name, active)` and the changed column is `name`, so the row keeps its id and the `agent_tool_binding` rows hanging off it (user-entered env values, the confirmation switch) survive — nothing to re-select.
> - The Java method name or the bean name: the identity key changes, which is a delete plus a re-insert. The id moves and the old bindings are cascade-deleted, so the tool must be re-selected in the agent configuration.

> Builtin rows are always written with `tenant_id = 1`, and `MybatisTenantInterceptor`'s filtering is currently disabled: builtin tools are one platform-wide set of rows, not a copy per tenant.

> Consequently, the required/optional split, whether a tool exists at all, and every field value are code-owned: changing them means a code change plus a restart of admin, not an operator action.

### 5.3 External write paths: all closed

| Path | For builtin tools |
|------|-------------------|
| Frontends (webui tool page, miniprogram tool list) | Read-only — no add / edit / delete / disable; the update / toggle / delete request wrappers in `services/ant-design-pro/tool.ts` are gone |
| `AgentToolService.createAgentTool` | Rejected at the service layer when `type = 'BUILTIN'`; the Controller has no POST endpoint anyway |
| `/update/{id}`, `/toggle/{id}`, `DELETE /{id}` | `AgentToolServiceImpl.requireManageableTool` throws `BizException` as soon as the target row is `BUILTIN`, message "Builtin tools are owned by the code sync (BuiltinToolAutoRegistrar): ... is not allowed"; the Controller turns it into a non-200 `ResultVo.error` |

`/update/{id}` checks **both sides**: the stored row must not be `BUILTIN`, and the request must not try to set `type = 'BUILTIN'` — the second one blocks re-typing a custom tool into a builtin, which would hand a user-owned row to the code sync.

Those three write endpoints remain only for `CUSTOM` / `HTTP` (custom) tools.

### 5.4 Idempotency and troubleshooting anchors

- **When it runs**: `BuiltinToolAutoRegistrar` listens on `ApplicationReadyEvent`, not `@PostConstruct`. `ToolRegistry` scans at `@PostConstruct`, but persisting has to wait for Flyway and the datasource, and the ready event is what guarantees `agent_tool` already exists.
- **Idempotent**: the whole pass is replayed on every start. Thanks to the unique key plus `ON DUPLICATE KEY UPDATE`, a restart inserts nothing new and changes no value except `update_time`; env parameter definitions converge in place and keep their record IDs.
- **Fault isolation**: each bean has its own `try/catch`, so a failing tool group affects only that group (the rest is still written) — the price is that this run performs no deletions.
- **Log anchors** (grep the admin startup log):

| Log line | Meaning |
|----------|---------|
| `Syncing N builtin tool groups to database` | N ToolBox classes found, sync starting |
| `Required tool '...' declares required env params ...` | WARN: `isRequired` contradicts required env params — a required tool has no binding, so those values can never resolve (see 6.1) |
| `Synced tool group: xxx [N methods]` | That group written successfully |
| `Synced env params for tool 'bean::name': N total, M stale removed` | Env parameter definitions converged; `M > 0` means a parameter definition was removed in code (cleaned from `agent_tool_env_param`) |
| `Failed to sync tool group: xxx` | That group threw; this run will not delete |
| `Sync complete: X succeeded, Y failed` | Overview; when `Y > 0` no deletion happened |
| `Skipping prune: ...` | ERROR: one of the three brakes stopped the delete; surplus rows are still in the table |
| `Removed N builtin tool record(s) no longer declared by the code: [...]` | Deletion happened; the list is `beanName::methodName(id=…)` |
| `No @Tool annotated methods found, skipping sync` | Nothing was scanned; the whole sync was skipped |

- **Unit coverage**: the convergence rules are pinned by `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrarTest.kt` (8 cases) and `AgentToolServiceImplTest` (5 write-rejection cases); case IDs live in `docs/unit-test-cases.md` §5.1 / §5.2.

## 6. Runtime Tool Assembly in the Agent

Core implementation: `createAgentBase()` in `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`.

### 6.1 Assembly Flow

```
InternalApiController.buildAgentSpecResponse (Admin delivery stage)
        ├─ toolDetails = tools from agent_tool_binding
        │                + enabled builtin tools with is_required=1 (appended, deduped by id, no binding rows)
        └─ toolList (legacy JSON) carries only the env-parameter snapshot of bound tools
        ▼
AgentSpecResolver (admin response → AgentSpec)
        │  toolSpecs + contextForTools (ToolEnvContext)
        ▼
HarnessAgentLauncher.createAgentBase()
        │
        ├─ Iterate agentSpec.toolSpecs
        │    ├─ ToolConfigAdaptor.getToolConfig(toolId) loads the config
        │    ├─ status=0 (disabled) → skip, and leave it out of the granted set
        │    ├─ BUILTIN/CUSTOM → ToolRegistry.createToolBoxInstance(beanName)
        │    │      → init(log adaptor, SessionMetaContext, UserIdentifier)
        │    │      → agentBuilder.addTool(toolBox) (registers ALL @Tool methods of the ToolBox)
        │    ├─ HTTP → new HttpProxyToolBox(...) (decrypts headers)
        │    │      → agentBuilder.registerAgentTool(...)
        │    ├─ Missing config / ToolBox → log a warning and skip (no switch, never blocks the build)
        │    └─ needConfirm (entity value OR binding value) → collected into needConfirmedTools
        │
        ├─ Final sweep: take every @Tool method of each added ToolBox from its ToolMetaDescriptor and
        │    removeTool the ones this agent was not granted (unselected siblings, disabled methods)
        │
        ├─ Empty toolSpecs → no tool is assembled (the old "register every ToolBox" fallback is gone)
        │
        ├─ contextForTools → ToolExecutionContext (injects ToolEnvContext)
        │
        ├─ Permission rules (PermissionContextState)
        │    ├─ Framework tool ALLOW whitelist: plan_enter / plan_write / plan_exit /
        │    │   todo_write / agent_spawn / agent_send / agent_list / task_output / task_list
        │    └─ needConfirmedTools → ASK rules
        │
        └─ Dangerous-input wrapping: methods annotated @ToolMeta(dangerousInput=true)
             → agentBuilder.wrapWithDangerousInputCheck(toolName)
             (skipped for tools that already have a needConfirm ASK rule, to avoid duplication)
```

Key points:

- **Required tools are injected at delivery**: tools with `is_required = 1` are appended to `toolDetails` by `InternalApiController` (deduped by id against the bindings); the agent wizard cannot select or deselect them. Taking one away means a code change — drop `isRequired` or delete the `@Tool` method — after which the sync pass converges the database (see 5.2).
- **`status` travels with the delivery**: `ToolDetailDto.status` → `ToolConfigAdaptorImpl` restores the entity → runtime skips `status == 0`. Drop any link in that chain and "disable a tool" silently stops working. Note that sync forces builtin `status` to 1, so this branch only ever applies to custom / HTTP tools.
- **Required tools have no binding row**, hence no `agent_tool_binding.envBindings` snapshot and no per-agent environment parameters. Do not mark a tool that needs env parameters as `isRequired` — `require()` will always fail with "not configured". Admin logs a warning for that combination at sync time.
- **Dedup by beanName, grant by method**: multiple `agent_tool` records may share one ToolBox and `addTool` runs only once; because `addTool` registers every method of that ToolBox, the final sweep subtracts the granted method set from `ToolRegistry.getToolMeta(beanName)` and removes the remainder — otherwise selecting one tool in a ToolBox would expose the whole box.
- **Config source**: `ToolConfigAdaptorImpl` prefers the admin pre-resolved `toolDetails` (delivered with the AgentSpec) and falls back to a direct DB query on a miss.
- **Env parameter injection**: `AgentSpecResolver` merges env bindings from tool/MCP bindings into a flat map, wraps it as a `ToolEnvContext` attached to `agentSpec.contextForTools`, and registers it into the `ToolExecutionContext` at build time so tool methods receive it automatically. The container is registered even when empty, so a tool's `envContext` parameter always injects and reports a readable "not configured" error.

### 6.2 Permission Modes

Sessions support 5 tool-execution permission modes (switched via the `PERMISSION <mode>` command):

| Mode | Behavior |
|------|----------|
| `DEFAULT` | Default; tools matching rules require confirmation |
| `BYPASS` | Auto-execute (but `safety` ASK decisions cannot be skipped) |
| `ACCEPT_EDITS` | Auto-approve file edits |
| `EXPLORE` | Read-only exploration |
| `DONT_ASK` | Auto-reject dangerous tools |

### 6.3 Dangerous Input Interception (bypass-immune)

Implementation: `harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt`

Tools annotated with `@ToolMeta(dangerousInput = true)` are wrapped by `DangerousInputCheckingTool`, overriding `checkPermissions()`:

1. **Dangerous command scan**: substring matching against string inputs of length ≥ 3 (`rm -rf`, `sudo rm`, `chmod 777`, `kill -9`, etc.);
2. **Dangerous path scan**: reuses `ToolBase.isDangerousPath`, checking sensitive files (`.env`, `.bashrc`, `.ssh/config`) and directories (`.git`, `.ssh`), resolving symlinks to prevent bypasses.

A hit returns an ASK decision with a `safety` reason — per the PermissionEngine contract, such decisions cannot be skipped even in `BYPASS` mode.

### 6.4 How to Mark a Tool as Dangerous (Developer's Perspective)

There are two levels of marking, which can be combined:

**Option 1: `needConfirm = true` — mandatory user confirmation before execution**

```kotlin
@Tool(name = "sendEmail", description = "Send an email via SMTP")
@ToolMeta(needConfirm = true)   // requires user confirmation before every execution
fun sendEmail(...)
```

- Inspects no input content; every invocation generates an ASK permission rule and asks the user for confirmation;
- Can be skipped in `BYPASS` mode (a regular ASK);
- Adjustable without code changes: for a builtin tool the entity-level `agent_tool.needConfirm` is read-only in the UI (see 5.3), so the available knob is the binding-level `agent_tool_binding.needConfirm`, which adds confirmation for one agent (the runtime ORs the two, so a binding can never cancel the tool's own confirmation). For CUSTOM / HTTP tools the entity-level value remains editable through the tool management API.

**Option 2: `dangerousInput = true` — dangerous input scanning (bypass-immune)**

For tools whose inputs may contain dangerous commands / sensitive paths (e.g. shell execution, file writes):

```kotlin
@Tool(name = "execute_command", description = "Run a shell command")
@ToolMeta(dangerousInput = true)   // input pattern scanning — cannot be skipped even in BYPASS mode
fun executeCommand(
    @ToolParam(name = "command", description = "The shell command to run")
    command: String?,
): String = execute("command" to command) { ... }
```

**Selection guide**:

| Scenario | Recommended marking |
|----------|---------------------|
| The tool has side effects (sending email, placing orders, writing to external systems) and always needs human confirmation | `needConfirm = true` |
| The tool itself is neutral, but inputs may smuggle in dangerous commands / paths | `dangerousInput = true` |
| Side effects + dangerous inputs | Just mark `needConfirm = true` (already covers every invocation) |

> Combining note: when both are marked, the runtime skips the `dangerousInput` wrapping — the `needConfirm` ASK rule fires first in the permission engine, making input scanning redundant.

## 7. Data Model

### 7.1 agent_tool (tool master table)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`

| Field | Description |
|-------|-------------|
| `name` / `displayName` / `displayNameZh` | Tool identifier and English/Chinese display names |
| `description` | Tool description (sent to the LLM) |
| `type` | `BUILTIN` / `CUSTOM` / `HTTP` |
| `beanName` / `methodName` | Locate the ToolBox bean and method for BUILTIN/CUSTOM |
| `httpUrl` / `httpMethod` / `httpHeaders` | Request config for the HTTP type (headers stored encrypted) |
| `inputSchema` / `outputSchema` | JSON schemas for the HTTP type |
| `envParams` / `requiredEnvParamKeys` | Env parameter config and required key list (JSON) |
| `readOnly` / `needConfirm` / `isRequired` | Read-only, confirmation-required, and mandatory flags (0/1) |
| `timeoutSeconds` | Timeout (default 30s) |
| `status` / `isPublic` / `active` | Enabled state, public state, logical delete |

> Constraints and deletion semantics:
> - Unique key `uk_tenant_bean_method (tenant_id, bean_name, method_name, active)` (since V4). `name` is **not part of it**, which is exactly what lets the sync rename a row in place (see 5.2); the price is that two methods declaring the same `@Tool(name)` are not caught by the database.
> - `deleteById` is a soft delete (`active = 0`) and only ever applies to CUSTOM / HTTP tools. Removing a builtin row goes through `deleteBuiltinByIds`, a hard delete whose SQL carries a `type = 'BUILTIN'` guard so it cannot touch user-created rows, and which only the registration mechanism calls.

### 7.2 agent_tool_binding (agent-tool binding table)

Entity: `AgentToolBinding.kt`

- `agentId` / `toolId`: the binding relationship; `(agent_id, tool_id)` is unique (see the V18 note below) and saving dedupes by toolId;
- `needConfirm`: binding-level confirmation, **OR-ed** with `agent_tool.needConfirm` — it can add confirmation for one agent, never cancel the tool's own confirmation;
- `envBindings`: env variable binding JSON snapshot (per-agent tool env configuration).

> There used to be an `enable_skip` column ("skip when the tool is unavailable"). Its only semantic was error-vs-warn logging; it granted no runtime tolerance. It was dropped by `V17__drop_tool_binding_enable_skip.sql`. The MCP binding table keeps its own `agent_mcp_binding.enable_skip`.
>
> `V18__add_tool_binding_unique_key.sql` first removes historical duplicate rows (same agent bound to the same tool twice, keeping the newest), then adds a unique key on `(agent_id, tool_id)` and drops `idx_agent_tool_binding_agent_id`, which that key already covers as a left prefix.
>
> Required tools (`is_required = 1`) are **never written to this table** — see 2.2 / 6.1.

### 7.3 agent_tool_env_param (tool env parameter definition table)

Entity: `AgentToolEnvParam.kt`

`toolId`, `envParamName`, `description`, `required`, `secret`, `defaultValue` — auto-synced from `@ToolMeta.envParamDefs`, used by the Admin UI to render configuration forms.

### 7.4 tool_call_log (tool call log table)

`ToolCallLogAdaptorImpl` persists each `ToolBox.execute` invocation: agentId, sessionId, toolName (`toolGroup::methodName` format), args (JSON), result, success, startTime / endTime / duration. Log failures never affect the main flow.

## 8. Management APIs (harnax-admin)

Implementation: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt`, prefix `/api/admin/tools`.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/page` | GET | Paginated query (keyword / status / type filters) |
| `/{id}` | GET | Tool details |
| `/update/{id}` | PUT | Update a tool (**`CUSTOM` / `HTTP` only**; rejected when the target is builtin) |
| `/toggle/{id}` | PUT | Enable / disable status 0/1 (**`CUSTOM` / `HTTP` only**; builtin tools are always enabled) |
| `/{id}` | DELETE | Logical delete (**`CUSTOM` / `HTTP` only**; builtin rows are deleted by the registration pass) |
| `/available` | GET | Wizard candidate list: `status=1 AND active=1 AND is_required = 0`, i.e. selectable optional tools (optionally filtered by type) |
| `/builtin` | GET | Tool management page list: every builtin tool, **required and optional alike** (`type='BUILTIN' AND active=1`) |
| `/{id}/required-env-params` | GET | Required env parameter keys of a tool |

> Notes:
> - Builtin tools have no write path whatsoever (see 5.3): the service layer intercepts the write endpoints by `type`, and the frontend tool page is display-only.
> - `AgentToolService.createAgentTool` is implemented (supports creating HTTP and other custom tools with secret-field encryption), but the Controller exposes no POST creation endpoint and the service rejects `type = 'BUILTIN'`; custom tools (`CUSTOM` / `HTTP`) are not exposed at all — the frontend tool page lists builtin tools only, while the supporting code is kept.
> - `/page` applies the `is_public = 1 OR creator = current user` visibility filter; `/builtin` and `/available` do not, since builtin tools are platform-wide.

Frontend management page: `harnax-webui/src/pages/tool/` (read-only list).

## 9. Developing a New Tool: Step-by-Step Guide

This section walks through the full flow — "write code → support env variables → register in the database → bind to an agent → verify it works" — using a fictional `WeatherToolBox` (weather query tool) as the example.

### Step 1: Choose the Hosting Module

Either of the two options works:

**Option A (recommended): add it to the existing `harnax-tools-buildin` module**

Simply create a new class under `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`. Both `harnax-admin` and `harnax-agent-service` already depend on this module, so **no pom changes are needed**.

**Option B: create a standalone module** (suitable for large toolboxes intended for independent release)

1. Create a Maven submodule under `harnax-tools-external/` (e.g. `harnax-tools-weather`) depending on `harnax-tools-sdk`;
2. Register the submodule in the `<modules>` section of `harnax-tools-external/pom.xml`;
3. Add the dependency in **both** places — neither can be omitted:
   - `harnax-admin/pom.xml`: Admin scans the new ToolBox at startup and syncs its metadata into the database;
   - `harnax-agent/harnax-agent-service/pom.xml`: actual tool execution happens in the agent-service runtime.

### Step 2: Write the ToolBox Class

```kotlin
@Component("weather-tool-box")
class WeatherToolBox : ToolBox() {

    @Tool(name = "getWeather", description = "Query the real-time weather of a given city")
    @ToolMeta(
        displayName = "Get Weather",
        displayNameZh = "查询天气",
        envParamDefs = [
            ToolEnvParamDef(key = "WEATHER_API_KEY", description = "Weather service API key", required = true, secret = true),
            ToolEnvParamDef(key = "WEATHER_BASE_URL", description = "Weather service base URL", required = false, defaultValue = "https://api.example.com"),
        ],
        needConfirm = false,
        timeoutSeconds = 15,
    )
    fun getWeather(
        @ToolParam(name = "city", description = "City name, e.g. Hangzhou")
        city: String?,
        envContext: ToolEnvContext,
    ): String = execute("city" to city) {
        // The LLM may pass null — validate explicitly
        require(!city.isNullOrBlank()) { "Parameter 'city' is required" }
        val apiKey = envContext.require("WEATHER_API_KEY")
        val baseUrl = envContext.get("WEATHER_BASE_URL") ?: "https://api.example.com"
        // TODO: call the weather service HTTP API and return the result text
        "weather of $city: ..."
    }

    override fun name(): String = NAME

    companion object {
        const val NAME = "weather-tool-box"
    }
}
```

Authoring checklist:

| Item | Notes |
|------|-------|
| `@Component` bean name | Prefer the `xxx-tool-box` format, matching the return value of `name()` |
| `@Tool` | `description` is the model's only clue about the tool's purpose — state the capability and when to use it clearly |
| `@ToolParam` | Every parameter the LLM must supply **requires** this annotation (with `name` and `description`); otherwise it is excluded from the JSON schema and the model cannot pass a value |
| `ToolEnvContext` parameter | Framework-injected — do **not** annotate it with `@ToolParam` |
| `execute {}` | The method body must be wrapped in it, listing key arguments as `"key" to value` pairs for call logging |
| Null defense | The LLM may pass `null`; validate inside the method body |
| `secret = true` | Always mark secret env parameters; the Admin UI masks them |
| `dangerousInput = true` | Enable when inputs may contain dangerous commands / sensitive paths (bypass-immune interception) |
| `isRequired = true` | Baseline tools every agent needs: appended at delivery, kept out of the wizard candidate list (still visible on the management page, and unable to carry env parameters) |

### Step 3: Supporting Environment Variables

**Declaration**: declare each parameter in `@ToolMeta.envParamDefs` (see the Step 2 code), each with `key`, `description`, `required`, `secret`, and `defaultValue`.

**Assigning values**: when the tool is bound to an agent, the Admin UI renders an env-parameter form based on the declarations; the operator picks one of two options:

1. **Reference a global env variable**: first create the variable in Admin's "Environment Variables" management (`/api/admin/env-variables`, values stored encrypted), then associate it at binding time (`envVarId` is stored). At runtime Admin resolves it to the **latest** decrypted value — updating the global variable propagates to all referencing bindings;
2. **Custom value**: enter a literal directly (`customValue` is stored), saved as a snapshot.

**Full data flow**:

```
@ToolMeta.envParamDefs (declared in code)
    → admin startup: BuiltinToolAutoRegistrar syncs them into the agent_tool_env_param table (basis for UI form rendering)
    → Admin UI: when binding the tool to an agent, fill in each envKey via the form (global variable reference or custom value)
    → agent_tool_binding.envBindings (JSON snapshot: envKey + envVarId / customValue)
    → agent startup: InternalApiController resolves envVarId to the latest decrypted value (falls back to the snapshot)
    → AgentSpecResolver merges all bindings into a flat map, wrapped as a ToolEnvContext
    → HarnessAgentLauncher registers it into the ToolExecutionContext
    → the tool method's envContext parameter is auto-injected; read values via envContext.require("KEY")
```

When a required parameter is not configured, `require()` throws `Environment parameter 'XXX' is required but not configured`, and the error message is returned to the model.

### Step 4: Write Unit Tests

Refer to `TimeToolBoxTest` and `EmailToolBoxTest` under `harnax-tools-buildin/src/test/kotlin/com/agnetix/harnax/tools/buildin/`. Minimum coverage:

- Return value for valid inputs;
- Validation exceptions for missing / invalid parameters;
- Exceptions when a required env parameter is missing (inject an empty `ToolEnvContext`).

### Step 5: Register the Tool in the Database (Fully Automatic, No Manual Inserts)

1. Build: run `mvn clean install` for the hosting module;
2. **Restart harnax-admin**: `BuiltinToolAutoRegistrar` scans the `ToolRegistry` and runs the full convergence pass described in 5.2 — new methods inserted, changed methods overwritten, methods no longer present in code deleted along with their bindings; env parameter definitions sync into `agent_tool_env_param`;
3. Verify registration: the new tool appears on the Admin UI "Tool Management" page, or confirm the record in the `agent_tool` table;
4. **Restart harnax-agent-service**: actual execution lives in agent-service; without a restart the `ToolRegistry` lacks the new ToolBox, and the runtime logs a warning and skips the tool (this behaviour has no switch).

> Sync policy reminders: builtin tools are code-owned — adding, changing and deleting them all converge on an admin restart. The tool management page is read-only; there is no edit / delete / disable control. Renaming `@Tool(name = ...)` updates that row in place (`id` and bindings untouched); only renaming the Java method or the bean is a delete plus a re-insert, which drops the agent bindings on the old id (including user-entered env values) so they have to be selected again. When a prune is held back by one of the brakes (a tool group failed to sync, or the stale count reached the declared count), the startup log prints an ERROR listing the records it refused to delete, and the database keeps those temporarily surplus rows.

### Step 6: Bind the Tool to an Agent and Configure Env Variables

1. In the Admin UI, open the agent configuration (create or edit) and select the new tool in the tool-selection step (the candidate list holds only `is_required = 0` tools; a tool marked `isRequired = true` is not in the list and needs no selection — it is appended at delivery);
2. Fill in the required env parameters via the form (global variable reference or custom value);
3. Optionally set `needConfirm` (confirmation before execution) — the switch can only tighten the rule: turning it on makes every invocation in this agent ask for confirmation, and a tool that already requires confirmation cannot be switched off here;
4. Save — the binding is written to `agent_tool_binding`.

### Step 7: Verify the Tool Works

1. Start a conversation with the agent and steer the model to call the new tool (e.g. "What's the weather in Hangzhou?");
2. Watch the tool-call card on the frontend conversation page (SSE tool event stream);
3. Check the `tool_call_log` table / service logs for a `weather-tool-box::getWeather` call record (including args, result, and duration);
4. For `needConfirm=true` tools, verify the ASK confirmation interaction;
5. Deliberately leave a required env parameter unconfigured and verify the tool returns a "parameter not configured" error to the model instead of failing silently.

### Common Pitfalls Quick Reference

| Symptom | Cause and fix |
|---------|---------------|
| The tool "doesn't exist" from the model's side; parameters can't be passed | Parameters lack `@ToolParam` — re-check the annotations |
| The new tool doesn't appear on the tool management page | admin not restarted (never synced), the ToolBox exposes no `@Tool` method so `ToolRegistry` has no metadata for it, or its `type` is not `BUILTIN` (custom tools are not shown in the UI) |
| The tool is missing from the agent wizard | it has `is_required = 1` (required tools are never candidates — they are appended at delivery), or it is a custom / HTTP tool with `status = 0` |
| `Tool ... not found, skipping` in the runtime logs | the `agent_tool` record is missing (admin not restarted after sync), `beanName` is empty, or agent-service was not restarted so the `ToolRegistry` lacks the ToolBox — the tool is skipped outright; there is no fallback registration any more |
| Env parameter resolves to nothing | No value assigned at binding time; confirm the envKey in `agent_tool_binding.envBindings` matches the code declaration |
| A required tool fails at runtime with "environment parameter not configured" | A tool with `is_required = 1` has no binding row, so it gets no envBindings snapshot at all. Do not declare required env params on a required tool; if the value really must be configurable, keep the tool optional so users can bind it, or have the code fall back via `ToolEnvContext.get(key)` instead of calling `require(key)` |
| "I want to disable / rename / delete a builtin tool" | There is no such entry point: builtin tools are owned by the code sync (see 5.3) — the write endpoints reject `BUILTIN` and the page has no switch. Remove or rename them by editing the annotations and releasing; a manual row edit is converged back to the code state on the next admin restart |
| Agents report "tool not found" after a Java method or bean rename | The identity key is `beanName + methodName + toolName`, so changing either of those two is a delete plus a re-insert: the id moved and its `agent_tool_binding` rows were cascade-deleted — re-select the tool in the agent configuration and refill its env parameters. A pure `@Tool(name = ...)` rename does not have this problem; that row is updated in place |
| A tool the code removed is still in the table | A prune brake fired: some tool group failed to sync, or the stale count reached the declared count. Look for the `Skipping prune` ERROR in the admin startup log, confirm the code is right, then re-release |
| Context bleed between sessions | Don't cache singleton state; the runtime already creates a fresh ToolBox instance per session — avoid relying on mutable member variables in methods |


## 10. Key File Index

| Module | File |
|--------|------|
| SDK base class | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt` |
| Metadata annotations | `ToolMeta.kt`, `ToolEnvParamDef.kt`, `ToolMetaDescriptor.kt` (same directory) |
| Env context | `ToolEnvContext.kt`, `ToolCallContext.kt` (same directory) |
| Registry | `registry/ToolRegistry.kt` (same directory) |
| HTTP proxy tool | `HttpProxyToolBox.kt` (same directory) |
| Adaptor interfaces | `adaptor/ToolCallLogAdaptor.kt`, `adaptor/ToolConfigAdaptor.kt` (same directory) |
| Built-in tools | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt`, `EmailToolBox.kt` |
| Runtime assembly | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` |
| Dangerous-input wrapping | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt` |
| Spec resolution | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` |
| Adaptor implementations | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`, `ToolCallLogAdaptorImpl.kt` |
| Startup sync | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` |
| AgentSpec delivery (where required tools are appended) | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| Management APIs | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt` |
| Entities | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`, `AgentToolBinding.kt`, `AgentToolEnvParam.kt` |
