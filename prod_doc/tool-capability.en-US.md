# Harnax Tool Capability Overview (English)

> 中文版本见 [tool-capability.zh-CN.md](./tool-capability.zh-CN.md)
>
> This document is based on a survey of the current codebase, covering the tool SDK (`harnax-tools-sdk`), built-in tools (`harnax-tools-buildin`), and the full runtime tool-assembly chain in the agent.

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

## 2. Tool Types

| Type | Description | Runtime carrier |
|------|-------------|-----------------|
| `BUILTIN` | Built-in tools provided by `ToolBox` beans in `harnax-tools-buildin`, auto-synced to the DB at startup | `ToolBox` reflective registration |
| `CUSTOM` | Custom ToolBox tools, assembled via the same path as BUILTIN (looked up by `beanName`) | `ToolBox` reflective registration |
| `HTTP` | HTTP proxy tools configured in Admin (URL / method / headers / JSON schema); requests are forwarded at runtime | `HttpProxyToolBox` |
| MCP | External MCP services (separate from this tool system, assembled via `McpConfigAdaptor`) | MCP client |

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
| `needConfirm` | `false` | **Whether user confirmation is required before execution.** When `true`, an ASK permission rule is generated at runtime: every invocation pauses and waits for user confirmation; it does not inspect input content and is independent of the arguments; it can be skipped in `BYPASS` permission mode; it can also be overridden without code changes via `agent_tool.needConfirm` in the Admin UI or the binding-level `agent_tool_binding.needConfirm` — see section 6.4 |
| `dangerousInput` | `false` | **Whether to scan string inputs for dangerous patterns** (dangerous commands like `rm -rf`, sensitive paths like `.env`/`.ssh`). Confirmation is triggered only when an input hits a dangerous pattern, and that confirmation cannot be skipped even in `BYPASS` mode (bypass-immune); safe inputs pass through directly; when combined with `needConfirm`, the input scanning is automatically skipped (the confirmation rule fires first) — see sections 6.3 / 6.4 |
| `isRequired` | `false` | **Whether this is a mandatory tool**: when `true`, it is auto-injected into every agent (no selection needed in configuration), hidden from the tool management page and the agent configuration wizard, and cannot be deselected; suitable for baseline tools every agent needs |

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

- `ToolSpec`: a tool reference inside the agent configuration (`toolId`, `skipIfMissing`, `needConfirm` override).
- `SessionMetaContext` / `UserIdentifier`: session and user contexts, implementing the `ToolCallContext` marker interface.

## 4. Current Built-in Tools (harnax-tools-buildin)

Path: `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`

### 4.1 TimeToolBox (`time-tool-box`)

| Tool name | Description | readOnly | needConfirm |
|-----------|-------------|----------|-------------|
| `getDate` | Get the current date (`yyyy-MM-dd`) | yes | no |
| `getDatetime` | Get the current datetime (`yyyy-MM-dd HH:mm:ss`) | yes | no |

### 4.2 EmailToolBox (`email-tool-box`)

| Tool name | Description | needConfirm |
|-----------|-------------|-------------|
| `sendEmail` | Send an email via SMTP, supports plain text / HTML body | yes |

- Uses Jakarta Mail directly, no Spring dependency.
- LLM parameters: `to`, `subject`, `body`, `is_html` (optional).
- Env parameters: `SMTP_HOST` (required), `SMTP_PORT` (optional, default 587), `SMTP_USER` (required), `SMTP_PASSWORD` (required, secret), `SMTP_FROM` (required).
- Port policy: 465 uses implicit SSL, 25 is unencrypted, others (including 587) enforce STARTTLS.

## 5. Metadata Sync (Admin Startup)

Implementation: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt`

When the Admin service starts (`ApplicationReadyEvent`), `syncBuiltinTools()` runs:

1. **Upsert**: each `@Tool` method becomes one `agent_tool` record (`type=BUILTIN`, `beanName`, `methodName`, `readOnly`, `needConfirm`, `isRequired`, `requiredEnvParamKeys`, `timeoutSeconds`, etc.). New tools are inserted with `status=1, active=1`; existing tools get only metadata fields updated — **`status` is never overwritten** (preserving manual disable decisions).
2. **Env param sync**: `@ToolMeta.envParamDefs` are synced to the `agent_tool_env_param` table using an "update in place + insert new + delete stale" strategy, preserving record IDs.
3. Removed ToolBox classes are not auto-deleted from the database; they must be removed manually in the UI.

## 6. Runtime Tool Assembly in the Agent

Core implementation: `createAgentBase()` in `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`.

### 6.1 Assembly Flow

```
AgentSpecResolver (admin response → AgentSpec)
        │  toolSpecs + contextForTools (ToolEnvContext)
        ▼
HarnessAgentLauncher.createAgentBase()
        │
        ├─ Iterate agentSpec.toolSpecs
        │    ├─ ToolConfigAdaptor.getToolConfig(toolId) loads the config
        │    ├─ status=0 (disabled) → skip, and removeTool the same-named tool from the toolkit
        │    ├─ BUILTIN/CUSTOM → ToolRegistry.createToolBoxInstance(beanName)
        │    │      → init(log adaptor, SessionMetaContext, UserIdentifier)
        │    │      → agentBuilder.addTool(toolBox) (registers ALL @Tool methods of the ToolBox)
        │    │      → removeTool prunes disabled methods from the disable list
        │    ├─ HTTP → new HttpProxyToolBox(...) (decrypts headers)
        │    │      → agentBuilder.registerAgentTool(...)
        │    └─ needConfirm (DB record or toolSpec override) → collected into needConfirmedTools
        │
        ├─ Fallback path: with no toolSpecs configured, registers every ToolBox in the ToolRegistry
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

- **Dedup by beanName**: multiple `agent_tool` records may share one ToolBox; `addTool` runs only once, and disabled methods are pruned precisely via `removeTool`.
- **Config source**: `ToolConfigAdaptorImpl` prefers the admin pre-resolved `toolDetails` (delivered with the AgentSpec) and falls back to a direct DB query on a miss.
- **Env parameter injection**: `AgentSpecResolver` merges env bindings from tool/MCP bindings into a flat map, wraps it as a `ToolEnvContext` attached to `agentSpec.contextForTools`, and registers it into the `ToolExecutionContext` at build time so tool methods receive it automatically.

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
- Adjustable without code changes: `agent_tool.needConfirm` can be modified in the Admin UI, and the binding-level `agent_tool_binding.needConfirm` can override it per agent.

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

### 7.2 agent_tool_binding (agent-tool binding table)

Entity: `AgentToolBinding.kt`

- `agentId` / `toolId`: the binding relationship;
- `enableSkip`: whether to skip when the tool is unavailable;
- `needConfirm`: binding-level confirmation override (applied on top of the tool default);
- `envBindings`: env variable binding JSON snapshot (per-agent tool env configuration).

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
| `/update/{id}` | PUT | Update a tool |
| `/toggle/{id}` | PUT | Enable / disable (status 0/1) |
| `/{id}` | DELETE | Logical delete |
| `/available` | GET | Available tools for agent configuration (optionally filtered by type) |
| `/builtin` | GET | Enabled builtin tools (optional ones with `is_required = 0`) |
| `/{id}/required-env-params` | GET | Required env parameter keys of a tool |

> Note: `AgentToolService.createAgentTool` is implemented (supports creating HTTP and other custom tools with secret-field encryption), but the Controller does not currently expose a POST creation endpoint.

Frontend management page: `harnax-webui/src/pages/tool/`.

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
| `isRequired = true` | Baseline tools every agent needs — auto-injected and hidden from the UI |

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
2. **Restart harnax-admin**: at startup, `BuiltinToolAutoRegistrar` scans the `ToolRegistry`, automatically upserting each `@Tool` method as one `agent_tool` record (`type=BUILTIN`) and syncing env parameter definitions into `agent_tool_env_param`;
3. Verify registration: the new tool appears on the Admin UI "Tool Management" page, or confirm the record in the `agent_tool` table;
4. **Restart harnax-agent-service**: actual execution lives in agent-service; without a restart the `ToolRegistry` lacks the new ToolBox and the runtime silently skips the tool (`skipIfMissing`).

> Sync policy reminders: the `status` (enabled/disabled) of existing records is never overwritten; records whose ToolBox classes were removed from code are not auto-cleaned — delete them manually in the UI.

### Step 6: Bind the Tool to an Agent and Configure Env Variables

1. In the Admin UI, open the agent configuration (create or edit) and select the new tool in the tool-selection step (`isRequired=true` tools need no selection — they are auto-injected);
2. Fill in the required env parameters via the form (global variable reference or custom value);
3. Optionally set `needConfirm` (confirmation before execution) and `enableSkip` (skip when the tool is unavailable);
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
| The new tool doesn't appear in the tool list page | admin not restarted (not synced), or the tool has `status=0` / `isRequired=1` (mandatory tools are hidden by design) |
| The runtime silently skips the tool | agent-service not restarted — the `ToolRegistry` lacks the ToolBox |
| Env parameter resolves to nothing | No value assigned at binding time; confirm the envKey in `agent_tool_binding.envBindings` matches the code declaration |
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
| Management APIs | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt` |
| Entities | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`, `AgentToolBinding.kt`, `AgentToolEnvParam.kt` |
