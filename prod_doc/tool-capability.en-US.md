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

### 2.1 One category only: built-in tools

Every tool is a built-in tool: it ships with the code, is synced into the database at admin startup from the `@Tool` / `@ToolMeta` annotations, and is visible to every user on the platform. There are no user-authored tools and neither the `type` column nor the "public or not" (`is_public`) concept exists anymore — every row of `agent_tool` is written by `BuiltinToolAutoRegistrar`, and no API or page can create, modify, enable, disable or delete a tool.

> All tools take the same runtime path (`HarnessAgentLauncher.createAgentBase()`): a ToolBox is created reflectively by `beanName`, then authorized per method.

### 2.2 Built-in tools split further: required and optional

| Sub-category | `agent_tool.is_required` | Who selects it | Runtime source |
|--------------|--------------------------|----------------|----------------|
| **Required tools** | `1` | Nobody has to — and cannot | Appended automatically when Admin delivers the AgentSpec (see 6.1); **no `agent_tool_binding` row** |
| **Optional built-in tools** | `0` | The user, in the agent configuration wizard | `agent_tool_binding` rows |

How this maps onto the UI:

- **Tool management page** (`/api/admin/tools/builtin`): shows every tool, required and optional alike, distinguished by a "Required / Optional" tag.
- **Agent configuration wizard** (`/api/admin/tools/available`): lists only selectable tools, i.e. enabled tools with `is_required = 0`; required tools never appear as candidates.
- Required tools cannot be bound either: `agent_tool.is_required` is synced from `@ToolMeta(isRequired)` and has no UI switch.

> For the full chain of MCP server entity management, encrypted storage, and runtime assembly, see [mcp-management.en-US.md](./mcp-management.en-US.md).
>
> Skills are another capability source alongside tools (SKILL.md plus bundled resources, not stored in `agent_tool`); for repository management, sync-into-DB and runtime assembly, see [skill-management.en-US.md](./skill-management.en-US.md).

## 3. SDK Core Concepts (harnax-tools-sdk)

### 3.1 The ToolBox Abstract Base Class

Path: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`

- Every tool must extend `ToolBox` and implement `name()` (the logical tool-group name).
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
| `envParamDefs` | `[]` | Array of environment parameter definitions (`ToolEnvParamDef`), synced to the `agent_tool_env_param` table at startup and used as the rendering basis for the env-parameter form when binding tools in the Admin UI; each entry has `key` (parameter name), `description` (UI hint), `required` (mandatory or not), `secret` (secret or not, masked in the UI), and `defaultValue` (default, non-secret only — the sync stores the annotation value verbatim and never goes through the encryptor, so putting a default on a `secret = true` parameter writes a plaintext credential into the table) — see section 3.4 |
| `needConfirm` | `false` | **Whether user confirmation is required before execution.** When `true`, an ASK permission rule is generated at runtime: every invocation pauses and waits for user confirmation; it does not inspect input content and is independent of the arguments; it can be skipped in `BYPASS` permission mode. This value comes from the annotation and is not editable in the UI; the only no-code way to tighten it is the binding row `agent_tool_binding.needConfirm` for one agent. The runtime ORs the two, so a binding can only add confirmation, never cancel a confirmation the tool itself declares (`agent_tool.needConfirm` has no write endpoint at all) — see section 6.4 |
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

### 3.6 Adaptor Interfaces (SPI)

| Interface | Responsibility | Implementation |
|-----------|----------------|----------------|
| `ToolCallLogAdaptor` | Emits tool call logs (`ToolCallInfo`: agentId, sessionId, toolName, args, result, success, duration) | `ToolCallLogAdaptorImpl` (agent-service, persisted to `tool_call_log`) |
| `ToolConfigAdaptor` | Loads tool configuration by `toolId` (`AgentTool` entity) | `ToolConfigAdaptorImpl` (agent-service, prefers the admin pre-resolved context, falls back to a DB query) |

### 3.7 Other Data Classes

- `ToolSpec`: a tool reference inside the agent configuration (`toolId`, `toolName`, `needConfirm` override).
- `SessionMetaContext` / `UserIdentifier`: session and user contexts, implementing the `ToolCallContext` marker interface.

## 4. Current Built-in Tools (harnax-tools-buildin)

Path: `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/`

### 4.1 TimeToolBox (bean `time-tool-box`)

| `@Tool` name | `methodName` | Description | readOnly | needConfirm | isRequired | Env params |
|--------------|--------------|-------------|----------|-------------|------------|------------|
| `getDate` | `getDate` | Get the current date (`yyyy-MM-dd`) | yes | no | no | none |
| `getDatetime` | `getDatetime` | Get the current datetime (`yyyy-MM-dd HH:mm:ss`) | yes | no | no | none |

### 4.2 EmailToolBox (bean `email-tool-box`)

| `@Tool` name | `methodName` | Description | readOnly | needConfirm | isRequired | Env params |
|--------------|--------------|-------------|----------|-------------|------------|------------|
| `sendEmail` | `sendEmail` | Send an email via SMTP, supports plain text / HTML body | no | **yes** | no | 5 (see below) |

- Uses Jakarta Mail directly, no Spring dependency.
- LLM parameters: `to`, `subject`, `body`, `is_html` (optional).
- Env parameters: `SMTP_HOST` (required), `SMTP_PORT` (optional, default 587), `SMTP_USER` (required), `SMTP_PASSWORD` (required, secret), `SMTP_FROM` (required).
- Port policy: 465 uses implicit SSL, 25 is unencrypted, others (including 587) enforce STARTTLS.

> **No builtin tool in the codebase is currently `isRequired = true`** — the required-tool branch (auto-appended at delivery, no binding row, startup conflict warning) is implemented and covered by tests, but no shipped tool uses it yet.

## 5. Builtin Tool Registration (the only lifecycle entry point)

Implementation:

- Scan: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt` (`@PostConstruct`)
- Persist: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` (`ApplicationReadyEvent`)

**The rule**: registering, updating and deleting tools happens **only here**. The `@Tool` / `@ToolMeta` annotations are the single source of truth, every row of `agent_tool` is written by this mechanism, and neither the pages nor the APIs expose any write path.

### 5.1 Scan

At startup `ToolRegistry` collects `getBeansOfType(ToolBox::class.java)` and reads `@Tool` + `@ToolMeta` per bean, per method, producing a `ToolMetaDescriptor` (`beanName` plus each method's `toolName`, `methodName`, `displayName` / `displayNameZh`, `description`, `readOnly`, `needConfirm`, `isRequired`, `envParamDescriptors`). A bean without any `@Tool` method yields no metadata and is never persisted.

Tool-level timeout is not annotation-driven: `@ToolMeta` has no `timeoutSeconds` (removed in V40 together with `agent_tool.timeout_seconds`); the whole-turn budget is set on the assembly side by `HarnessConfig.turnTimeoutSeconds` — see 6.5.

### 5.2 Sync (a full convergence pass on every admin start)

1. **Insert**: a name present in the declarations but absent from the database → one new `agent_tool` row (`status=1`, `active=1`, `creator='SYSTEM'`).
2. **Update**: when a row with the same name already exists, every column is compared; an UPDATE is issued only when something differs, and the difference is written to the startup log (which tool, which columns, from what to what). `name` never takes part in the update: it is the lookup key. `status` and `active` converge to 1 as well; a tool has no "disabled by an operator" state.
3. **No deletion**: a row that exists in the database but was not declared on this start is **kept as is**; neither the row nor its `agent_tool_binding` rows are touched. Because its name is not in this start's declared name set, it is **never delivered** (see 6.1) — it stays visible to operators only.
4. **Env parameter definitions**: `@ToolMeta.envParamDefs` sync into `agent_tool_env_param` with an "update in place + insert new + delete stale" strategy, preserving record IDs. What gets deleted is a parameter definition, not a tool, which counts as updating the tool's definition.
5. **Duplicate names fail hard**: when two `@Tool` methods declare the same `@Tool.name`, the whole sync throws `IllegalStateException` before any write, listing every conflicting `bean::method`. The name is the identity, so letting one of them win would make the bean order decide which method the tool actually executes.
6. **Config-conflict warning**: a method combining `isRequired = true` with required `envParamDefs` is logged at startup — a required tool has no binding row and therefore no env values, so that combination is guaranteed to fail at runtime (see 6.1).

> A rename costs one of two things:
> - Changing only `@Tool(name = ...)`: **this swaps the tool**. The identity is `name`, so the new name inserts a new row while the row under the old name is kept (but no longer delivered), and the `agent_tool_binding` rows on the old row are not migrated — an agent already using the tool silently loses it and needs the new tool re-selected in its configuration.
> - Changing the Java method name (`methodName`) or the bean name: **still the same tool**. Both are merely parameters for reflective instantiation and take no part in identity, so the row refreshes in place (the difference shows up in the startup log) and its `id` and bindings are all preserved.

> Tools are a **platform-level asset**: the `agent_tool` table has no `tenant_id` (dropped in V40) — one shared set of rows for the whole platform, not a copy per tenant.

> Consequently, the required/optional split, whether a tool exists at all, and every field value are code-owned: changing them means a code change plus a re-release, not an operator action.

### 5.3 External write paths: they do not exist

| Path | Current state |
|------|---------------|
| `AgentToolController` | GET only: `/page`, `/{id}`, `/available`, `/builtin`, `/{id}/required-env-params`. `PUT /update/{id}`, `PUT /toggle/{id}` and `DELETE /{id}` are gone, together with the `AgentToolCreateRequest` / `AgentToolUpdateRequest` DTOs |
| `AgentToolService` | Declares queries and `convertToResponse` only — no create / update / toggle / delete method |
| Frontends (webui tool page, miniprogram tool list) | Read-only — no add / edit / delete / disable; `services/ant-design-pro/tool.ts` keeps only `getAvailableTools` / `getBuiltinTools` |
| `harnax-cli` | Only the five query commands `tool list / get / available / builtin / env-params`; no `update / delete / toggle` |

### 5.4 Idempotency and troubleshooting anchors

- **When it runs**: `BuiltinToolAutoRegistrar` listens on `ApplicationReadyEvent`, not `@PostConstruct`. `ToolRegistry` scans at `@PostConstruct`, but persisting has to wait for Flyway and the datasource, and the ready event is what guarantees `agent_tool` already exists.
- **Idempotent**: the whole pass is replayed on every start. The identity is `name` (unique key `uk_agent_tool_name`); an existing row is updated only when a difference is found, so a restart inserts nothing new and changes no value except `update_time`; env parameter definitions converge in place and keep their record IDs.
- **Fault isolation**: each bean has its own `try/catch`, so a failing tool group affects only that group (the rest is still written); the tool names of the failed group still count as declared, so one failed write never turns into every agent missing that tool.
- **Log anchors** (grep the admin startup log):

| Log line | Meaning |
|----------|---------|
| `Syncing N builtin tool group(s), M declared tool(s)` | N ToolBoxes and M declarations scanned, sync starting |
| `Required tool '...' declares required env params ...` | WARN: `isRequired` contradicts required env params — a required tool has no binding, so those values can never resolve (see 6.1) |
| `Registered new tool '<name>' (<bean>::<method>)` | A newly declared tool was inserted |
| `Updated tool '<name>' (id=N): <columns>` | An existing row differed and was refreshed; the column names after the colon are the ones that changed |
| `Synced tool group: xxx [N methods]` | That group written successfully |
| `Synced env params for tool 'bean::name': N total, M stale removed` | Env parameter definitions converged; `M > 0` means a parameter definition was removed in code (cleaned from `agent_tool_env_param`) |
| `Failed to sync tool group: xxx` | That group threw; the other groups are unaffected |
| `Sync complete: X succeeded, Y failed; N tool name(s) declared` | Overview; `N` is the total number of names declared on this start |
| `Duplicate @Tool name(s) on the classpath: ...` | Exception: duplicate names, startup fails; the parentheses list every conflicting `bean::method` |
| `No @Tool annotated methods found, skipping sync` | Nothing was scanned; the whole sync was skipped |

- **Unit coverage**: the convergence rules are pinned by `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrarTest.kt` (9 cases: insert, no diff means no write, refresh on diff, a disabled row converging back to enabled, undeclared rows left untouched, duplicate name rejected, empty registry skipped, single-group failure isolated, env parameter convergence), case IDs in `docs/unit-test-cases.md` §5.2; the mapper-level write and unique-key behaviour lives in `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/AgentToolMapperTest.kt`. The tool service keeps only queries, so the former "write entry rejects BUILTIN" guard cases were deleted together with the write endpoints, and `docs/unit-test-cases.md` §5.1 now registers the query and response-assembly cases.

## 6. Runtime Tool Assembly in the Agent

Core implementation: `createAgentBase()` in `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`.

### 6.1 Assembly Flow

```
InternalApiController.buildAgentSpecResponse (Admin delivery stage)
        ├─ toolDetails = tools from agent_tool_binding
        │                + enabled builtin tools with is_required=1 (appended, deduped by id, no binding rows)
        │                − rows whose name is not in this startup's declared name set (see the key points below)
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
        │    ├─ ToolRegistry.createToolBoxInstance(beanName)
        │    │      → init(log adaptor, SessionMetaContext, UserIdentifier)
        │    │      → agentBuilder.addTool(toolBox) (registers ALL @Tool methods of the ToolBox)
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

- **Undeclared rows are not delivered**: the sync deletes no `agent_tool` row (see 5.2), so the database may keep tools that "no longer exist in code" — their methods are gone, so assembling them at runtime is bound to fail. `buildAgentSpecResponse` filters against `BuiltinToolAutoRegistrar.registeredToolNames()` (the name set declared on this start) and logs every blocked id at WARN; an empty set means the sync never ran, in which case nothing is filtered rather than blocking every tool.
- **Required tools are injected at delivery**: tools with `is_required = 1` are appended to `toolDetails` by `InternalApiController` (deduped by id against the bindings); the agent wizard cannot select or deselect them. Taking one away means a code change — drop `isRequired` or delete the `@Tool` method — and after the re-release the old row is kept but no longer delivered (see 5.2).
- **`status` travels with the delivery**: `ToolDetailDto.status` → `ToolConfigAdaptorImpl` restores the entity → runtime skips `status == 0`. Drop any link in that chain and "disable a tool" silently stops working. Since sync forces `status` to 1 and no write endpoint can set it to 0, this skip is purely defensive today.
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
- Adjustable without a code change in exactly one direction: `agent_tool.needConfirm` has no write endpoint (see 5.3), so the available knob is the binding-level `agent_tool_binding.needConfirm`, which adds confirmation for one agent (the runtime ORs the two, so a binding can never cancel the tool's own confirmation).

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

### 6.5 Turn timeout (assembly-side, not a tool attribute)

Timeout is not a property of a tool: `@ToolMeta` has no `timeoutSeconds`, and neither does `agent_tool` (both removed in V40 — the old value made it into the entity but had no reader, so it was decoration from the start).

What actually applies is the **whole-turn budget**, decided by `HarnessConfig.turnTimeoutSeconds`:

| Item | Location | Notes |
|---|---|---|
| Config | `harness.turn-timeout-seconds` / `HARNAX_TURN_TIMEOUT_SECONDS` | Defaults to 300 seconds; bound by `HarnessProperties` into `HarnessConfig` |
| Batch path | `HarnessAgentWrapper.call` | Applies to the whole `harnessAgent.call` |
| Streaming path | `HarnessAgentWrapper.callStreamInternal` | The same value; deliberately placed before the output-file probe so that probe does not consume budget. A timeout lands in `onErrorResume` like any other stream error and becomes an `ErrorChatEvent` |

> A single tool call has no timeout of its own: a batch of tool calls shares this one turn budget. In a team the lead may wait a long time between delegations, so raise `turn-timeout-seconds` when needed (the team's own `memberTurnTimeoutSeconds` / `confirmTimeoutSeconds` are a separate budget — see `multi-agent-team-design`).

## 7. Data Model

### 7.1 agent_tool (tool master table)

Entity: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`

| Field | Description |
|-------|-------------|
| `name` / `displayName` / `displayNameZh` | Tool identifier and English/Chinese display names |
| `description` | Tool description (sent to the LLM) |
| `beanName` / `methodName` | Parameters for reflective ToolBox bean and method instantiation; **not part of the identity** |
| `requiredEnvParamKeys` | Required env parameter key list (JSON); the definitions themselves live in `agent_tool_env_param` (see 7.3). The column only serves management-side display and save-time validation; the runtime never reads it |
| `readOnly` / `needConfirm` / `isRequired` | Read-only, confirmation-required, and mandatory flags (0/1) |
| `status` / `active` | Enabled state and logical-delete flag; `status` is forced to 1 by the startup sync and `active` is always 1 — the sync deletes no row (see 5.2) |

> Constraints and deletion semantics:
> - Unique key `uk_agent_tool_name (name)` (since V40). `name` is the identity: when two `@Tool` methods in code declare the same name, the registration pass fails outright instead of leaving it to the database to catch (see 5.2).
> - This table has **no DELETE statement**: the startup sync only inserts and updates, and `agent_tool_binding` / `agent_tool_env_param` are kept alongside the tool. Env parameter definitions still converge with the annotations (that counts as updating the tool definition).
> - There is no `tenant_id` on the table: tools are a platform-level asset, one shared set of rows.

### 7.2 agent_tool_binding (agent-tool binding table)

Entity: `AgentToolBinding.kt`

- `agentId` / `toolId`: the binding relationship; `(agent_id, tool_id)` is unique (see the V18 note below) and saving dedupes by toolId;
- `needConfirm`: binding-level confirmation, **OR-ed** with `agent_tool.needConfirm` — it can add confirmation for one agent, never cancel the tool's own confirmation;
- `envBindings`: env variable binding JSON snapshot (per-agent tool env configuration).

> There used to be an `enable_skip` column ("skip when the tool is unavailable"). Its only semantic was error-vs-warn logging; it granted no runtime tolerance. It was dropped by `V17__drop_tool_binding_enable_skip.sql`. The same-named column on the MCP binding table was dropped too, by `V20__drop_mcp_binding_enable_skip.sql`: it only covered "no `mcp_server` record found" while an unreachable server still threw, so keeping it around only misled people (see section 7 of `mcp-management`).
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
| `/page` | GET | Paginated query; parameters are only `pageNum` / `pageSize` / `keyword` / `status` |
| `/{id}` | GET | Tool details |
| `/available` | GET | Wizard candidate list: `status=1 AND active=1 AND is_required = 0`, i.e. the selectable optional tools; takes no filter parameter |
| `/builtin` | GET | Tool management page list: every tool, **required and optional alike** (`active=1`) |
| `/{id}/required-env-params` | GET | Required env parameter keys of a tool |

> Notes:
> - This is a purely read-only API: `PUT /update/{id}`, `PUT /toggle/{id}` and `DELETE /{id}` are deleted, and there is no POST creation endpoint either (see 5.3).
> - `AgentToolResponse` no longer carries `type` / `httpUrl` / `httpMethod` / `httpHeaders` / `inputSchema` / `outputSchema`; env parameters come from `envParams` (read from `agent_tool_env_param`) and `requiredEnvParamKeys`.
> - Tools are one platform-wide set of rows, so `/page` applies no creator or visibility filter.

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

1. **Reference a global env variable**: first create the variable in Admin's "Environment Variables" management (`/api/admin/env-variables`, values stored encrypted), then associate it at binding time (`envVarId` is stored). At runtime Admin resolves it to the **latest** decrypted value — updating the global variable propagates to all referencing bindings. **The snapshot does not store the value**, only the pointer: what the client submits back is a display value (`******` for a sensitive one), so snapshotting it would keep a string of stars as the secret, while resolving it server-side first would write a plaintext secret into the `env_bindings` column (the AES key lives in admin only — see `mcp-management` §7.16). A pointer has to be checked before it can be stored: `assertEnvVarRefsBindable` verifies that every `envVarId` in the batch **resolves, belongs to the current tenant, and is not disabled**, one guard shared by the tool / MCP / CLI binding paths (CLI counts, because `mergeCliEnvBindings` delivers that column too). The two halves of "disabled" are consistent: `getDecryptedValue` now answers null for `enabled = 0`, so **disabling a variable retracts it from every referencing binding**, while a binding that points at a disabled variable cannot be saved at all — otherwise the form would show it filled and the runtime would receive nothing. The other direction of the same rule: **a referenced variable cannot be deleted** — `deleteEnvVariable` checks the `envVarId` pointers in all three binding tables first and answers "bound by N agent(s): …; rebind them first";
2. **Custom value**: enter a literal directly (`customValue` is stored), saved as a snapshot.

A `secret = true` parameter is **never prefilled with its default**: the read API masks a secret default too, so prefilling would drop `abc****wxyz` into the form field and from there into the row. Override it by typing a real value or by referencing a global variable.

**Full data flow**:

```
@ToolMeta.envParamDefs (declared in code)
    → admin startup: BuiltinToolAutoRegistrar syncs them into the agent_tool_env_param table (basis for UI form rendering)
    → Admin UI: when binding the tool to an agent, fill in each envKey via the form (global variable reference or custom value)
    → agent_tool_binding.envBindings (JSON snapshot: envKey + envVarId / customValue; a reference stores the pointer, never the value)
    → agent startup: InternalApiController resolves envVarId to the latest decrypted value (falls back to the snapshot value — rows written now carry no snapshot to fall back to, so a miss is left at a warn)
    → AgentSpecResolver merges all bindings into a flat map, wrapped as a ToolEnvContext
    → HarnessAgentLauncher registers it into the ToolExecutionContext
    → the tool method's envContext parameter is auto-injected; read values via envContext.require("KEY")
```

Leaving a required parameter empty is **rejected when you save**: `assertRequiredEnvParamsFilled` goes through the rows with `agent_tool_env_param.required = 1` and asks each one "will anything resolve at runtime?" — an `envVarId` reference counts as answered, a hand-typed value must be non-blank and must not contain `****`, and the parameter's own `default_value` does **not** count (`ToolConfigAdaptorImpl` loads the definitions into the runtime `AgentTool`, but nothing in the codebase reads them, so a default never reaches `ToolEnvContext`). Both frontends have their own submit-time check that names the missing parameters, but this server-side one is what actually holds.

The runtime line `Environment parameter 'XXX' is required but not configured` (thrown by `require()`, returned to the model as the tool result) therefore has only two remaining sources: dirty rows stored before this guard existed, and `is_required = 1` tools, which have no binding row at all (see 6.1).

### Step 4: Write Unit Tests

Refer to `TimeToolBoxTest` and `EmailToolBoxTest` under `harnax-tools-buildin/src/test/kotlin/com/agnetix/harnax/tools/buildin/`. Minimum coverage:

- Return value for valid inputs;
- Validation exceptions for missing / invalid parameters;
- Exceptions when a required env parameter is missing (inject an empty `ToolEnvContext`).

### Step 5: Register the Tool in the Database (Fully Automatic, No Manual Inserts)

1. Build: run `mvn clean install` for the hosting module;
2. **Restart harnax-admin**: `BuiltinToolAutoRegistrar` scans the `ToolRegistry` and runs the full convergence pass described in 5.2 — new names inserted, changed rows refreshed in place only where a column differs, names no longer declared kept as rows but no longer delivered; env parameter definitions sync into `agent_tool_env_param`;
3. Verify registration: the new tool appears on the Admin UI "Tool Management" page, or confirm the record in the `agent_tool` table;
4. **Restart harnax-agent-service**: actual execution lives in agent-service; without a restart the `ToolRegistry` lacks the new ToolBox, and the runtime logs a warning and skips the tool (this behaviour has no switch).

> Sync policy reminders: builtin tools are code-owned — adding, changing and removing them all take effect on an admin restart. The tool management page is read-only; there is no edit / delete / disable control. Renaming `@Tool(name = ...)` swaps the tool: a new row under the new name, the old row kept but no longer delivered, and its bindings left behind, so the tool has to be re-selected in the agent configuration; renaming the Java method or the bean keeps the same tool and refreshes the row in place (`id` and bindings untouched). A tool removed from the code keeps its row and its bindings but stops being delivered (see 5.2).

### Step 6: Bind the Tool to an Agent and Configure Env Variables

1. In the Admin UI, open the agent configuration (create or edit) and select the new tool in the tool-selection step (the candidate list holds only `is_required = 0` tools; a tool marked `isRequired = true` is not in the list and needs no selection — it is appended at delivery);
2. Fill in the required env parameters via the form (global variable reference or custom value) — an empty one cannot be saved: the server names the parameters left without a value, and an `envVarId` belonging to another tenant or already deleted is rejected in the same pass;
3. Optionally set `needConfirm` (confirmation before execution) — the switch can only tighten the rule: turning it on makes every invocation in this agent ask for confirmation, and a tool that already requires confirmation cannot be switched off here;
4. Save — the binding is written to `agent_tool_binding`.

### Step 7: Verify the Tool Works

1. Start a conversation with the agent and steer the model to call the new tool (e.g. "What's the weather in Hangzhou?");
2. Watch the tool-call card on the frontend conversation page (SSE tool event stream);
3. Check the `tool_call_log` table / service logs for a `weather-tool-box::getWeather` call record (including args, result, and duration);
4. For `needConfirm=true` tools, verify the ASK confirmation interaction;
5. Deliberately leave a required env parameter empty and verify the save is rejected and names the parameter (the runtime "parameter not configured" error is no longer reachable that way — it now only comes from dirty rows stored before the guard and from `is_required = 1` tools).

### Common Pitfalls Quick Reference

| Symptom | Cause and fix |
|---------|---------------|
| The tool "doesn't exist" from the model's side; parameters can't be passed | Parameters lack `@ToolParam` — re-check the annotations |
| The new tool doesn't appear on the tool management page | admin not restarted (never synced), or the ToolBox exposes no `@Tool` method so `ToolRegistry` has no metadata for it |
| The tool is missing from the agent wizard | it has `is_required = 1` (required tools are never candidates — they are appended at delivery) |
| `Tool ... not found, skipping` in the runtime logs | the `agent_tool` record is missing (admin not restarted after sync), `beanName` is empty, or agent-service was not restarted so the `ToolRegistry` lacks the ToolBox — the tool is skipped outright; there is no fallback registration any more |
| Env parameter resolves to nothing | A reference entry stores no value in the snapshot, only `envVarId`: as long as the variable still exists it is always resolved to its latest value, so an empty result usually means the envKey disagrees with the code declaration, or `agent_tool_binding.envBindings` has no entry for that key at all (the save-time required/reference checks now block that case first). One silent case remains in rows stored before the change: they may carry a mask string as the value while the variable is already gone, which is what surfaces as stars |
| The form looked filled, yet the tool gets a string of stars | Those are mask characters, not a value. Two historical sources: a `secret = true` parameter used to prefill its masked default into the binding field, and a reference snapshot used to store `displayValue` (`******` for a sensitive one). Both are fixed now (secrets stay blank, references store no value). The rule is "anything containing `****` does not count as filled", so legacy dirty rows need to be filled in once more |
| A required tool fails at runtime with "environment parameter not configured" | A tool with `is_required = 1` has no binding row, so it gets no envBindings snapshot at all. Do not declare required env params on a required tool; if the value really must be configurable, keep the tool optional so users can bind it, or have the code fall back via `ToolEnvContext.get(key)` instead of calling `require(key)` |
| "I want to disable / rename / delete a tool" | There is no such entry point: tools are owned exclusively by the code sync (see 5.3) — the write endpoints do not exist and the page has no switch. To disable or delete one, drop that `@Tool` method from the code and re-release: the row is kept but no longer delivered (see 5.2). A manual row edit is converged back to the code state on the next admin restart |
| Agents report "tool not found" after a `@Tool(name = ...)` rename | The identity is `name`, so a rename swaps the tool: the new name inserts a new row, the old row is kept but no longer delivered, and the `agent_tool_binding` rows hanging off the old row do not migrate — go to the agent configuration and re-select the new tool |
| Agents report "tool not found" after a Java method or bean rename | Those two take no part in identity, so this should not happen: the row refreshes in place and keeps its `id` and bindings. If it does happen, check the startup log for an `Updated tool ... (id=N)` line for that tool first |
| A tool the code removed is still in the table | Expected behaviour: the sync deletes no row (see 5.2), so the old row is kept as is and is simply no longer delivered. Truly clearing it is a manual operation (confirm nothing binds it, then delete the row) |
| Context bleed between sessions | Don't cache singleton state; the runtime already creates a fresh ToolBox instance per session — avoid relying on mutable member variables in methods |


## 10. Key File Index

| Module | File |
|--------|------|
| SDK base class | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt` |
| Metadata annotations | `ToolMeta.kt`, `ToolEnvParamDef.kt`, `ToolMetaDescriptor.kt` (same directory) |
| Env context | `ToolEnvContext.kt`, `ToolCallContext.kt` (same directory) |
| Registry | `registry/ToolRegistry.kt` (same directory) |
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
| Turn timeout | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt`, `HarnessAgentWrapper.kt` |
| Migrations | `harnax-admin/src/main/resources/db/migration/V4__refactor_tool_granularity.sql`, `V17__drop_tool_binding_enable_skip.sql`, `V18__add_tool_binding_unique_key.sql`, `V29__drop_custom_and_http_tool.sql`, `V40__tool_registry_platform_scoped.sql` (platform-level + identity = `name` + tool-level timeout removed) |
