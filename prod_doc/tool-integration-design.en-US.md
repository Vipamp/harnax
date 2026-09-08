# Harnax Tool Integration Design (English)

> Chinese version: [tool-integration-design.zh-CN.md](./tool-integration-design.zh-CN.md)
>
> This document answers "why the tool system looks the way it does": the classification model, the data model, layer responsibilities, key design decisions and the evolution timeline.
> For "how to use it" — annotation syntax, registration convergence rules, API list, new-tool walkthrough, troubleshooting — see [tool-capability.en-US.md](./tool-capability.en-US.md).
> MCP servers: [mcp-management.en-US.md](./mcp-management.en-US.md). Skills: [skill-management.en-US.md](./skill-management.en-US.md).
>
> The original proposal `harnax-admin/TOOL_INTEGRATION_DESIGN.md` is kept as an archive. Wherever it disagrees with this document (`enable_skip`, a tool-create endpoint, one record per ToolBox, the runtime default tool set) this document wins.

## 1. Design goals

| Goal | How it is realised |
|------|--------------------|
| Tools extend without touching a hardcoded set | `ToolRegistry` scans `ToolBox` beans; `BuiltinToolAutoRegistrar` converges them into the DB at startup |
| Each agent picks its tools instead of sharing all of them | `agent_tool_binding` + `toolDetails` delivery + per-binding runtime assembly |
| Same shape as MCP / Skill management | The same five stages: entity table, Admin management, binding table, spec delivery, runtime adaptor |
| Builtin tools cannot be broken by operators | Lifecycle owned by the code sync; pages and APIs reject any write to `type='BUILTIN'` |
| Per-tool policy (confirmation, env params, mandatory) | Granularity pushed down to the `@Tool` method: one method = one `agent_tool` row |

Out of scope: MCP tools (own table `mcp_server`, see mcp-management), skills (`skill`, never in `agent_tool`), CLI plugins (`cli` + `cli_skill_binding`).

## 2. Classification model

### 2.1 By implementation (`agent_tool.type`)

| Type | Source of truth | Runtime carrier | Who owns the lifecycle |
|------|-----------------|-----------------|------------------------|
| `BUILTIN` | Code annotations `@Tool` + `@ToolMeta` | `ToolRegistry.createToolBoxInstance(beanName)`, a per-session instance | **The code sync exclusively** (no external write path) |
| `CUSTOM` | DB row + a ToolBox bean in user code | Same as above | Admin write endpoints (not enabled yet) |
| `HTTP` | Pure DB row (URL / method / headers / inputSchema) | `HttpProxyToolBox` | Admin write endpoints (not enabled yet) |

`BUILTIN` and `CUSTOM` take the same runtime branch (both resolve a ToolBox by `beanName`); they differ only in who may write the row.

### 2.2 By whether it can be turned off (`agent_tool.is_required`)

| Sub-class | Who selects it | Binding row | Env params |
|-----------|----------------|-------------|------------|
| Required tool (`is_required=1`) | Nobody — appended at delivery | None | Unreachable (see 6.4) |
| Optional builtin tool | User ticks it in the agent wizard | `agent_tool_binding` | Per agent configuration |

The two dimensions are **orthogonal**: `is_required` only means anything for `BUILTIN`, is synced from `@ToolMeta(isRequired)`, and has no UI switch.

## 3. Data model

```
agent_tool (master table, one row = one @Tool method)
  ├── tenant_id + bean_name + method_name + active   ← unique key uk_tenant_bean_method
  ├── agent_tool_env_param   (the "definition" of a tool env param, 1:N)
  └── agent_tool_binding     (agent↔tool binding + that agent's value snapshot, 1:N)
agent.tool_list              ← legacy column, no longer written by business code
tool_call_log                (tool call log)
```

Field-by-field semantics live in [tool-capability.en-US.md](./tool-capability.en-US.md) §7. Three points matter here:

- **One `agent_tool` row = one method**, not "one ToolBox". `getDate` and `getDatetime` of the same bean are two rows and can be granted and confirmed separately.
- **Definition and value are separated**: `agent_tool_env_param` holds what the code declares (the UI renders its form from that), `agent_tool_binding.envBindings` holds what a given agent filled. The first is overwritten by the sync, the second is user data.
- **`agent.tool_list` is a leftover**: after V7 normalised bindings, business writes go to `agent_tool_binding`. The delivery field `AgentSpecInfoResponse.toolList` still exists, but its content is **rebuilt from the binding table at delivery time** (`id` / `need_confirm` / `env_bindings`) and only serves as a compatibility input for `AgentSpecResolver`'s env-param parsing.

## 4. Layer responsibilities

| Module | Responsibility | Key classes |
|--------|----------------|-------------|
| `harnax-tools-sdk` | Tool abstraction, annotations, registry, HTTP proxy, SPI interfaces | `ToolBox`, `@ToolMeta`, `ToolRegistry`, `HttpProxyToolBox`, `ToolConfigAdaptor` |
| `harnax-tools-buildin` | Builtin tool implementations (time, email) | `TimeToolBox`, `EmailToolBox` |
| `harnax-entity` | `agent_tool` / `agent_tool_binding` / `agent_tool_env_param` / `tool_call_log` entities and mappers | `AgentTool`, `AgentToolMapper` |
| `harnax-admin` | Startup sync, management API, agent spec delivery | `BuiltinToolAutoRegistrar`, `AgentToolController`, `InternalApiController` |
| `harnax-harness-core` | Runtime tool assembly, permission rules, dangerous-input wrapping | `HarnessAgentLauncher`, `DangerousInputCheckingTool` |
| `harnax-agent-service` | Fetch config, resolve tool config by toolId, persist call logs | `AgentSpecResolver`, `ToolConfigAdaptorImpl`, `ToolCallLogAdaptorImpl` |

Dependency direction: `sdk` depends on no business code; `harness-core` only knows `sdk` interfaces, with implementations injected by `agent-service`; both `admin` and `agent-service` depend on `sdk`, which is what lets the registering side and the executing side see the same set of ToolBox classes.

## 5. End-to-end chain

```
Code annotations @Tool / @ToolMeta
   ▼  admin startup (ApplicationReadyEvent)
ToolRegistry scan → BuiltinToolAutoRegistrar convergence → agent_tool / agent_tool_env_param
   ▼  a session / task / channel triggers a config pull
InternalApiController.buildAgentSpecResponse
   = bound tools ∪ required tools (de-duplicated by id) → toolDetails + toolList (legacy JSON with values)
   ▼
AgentSpecResolver → AgentSpec.toolSpecs + ToolEnvContext
   ▼
HarnessAgentLauncher.createAgentBase()
   BUILTIN/CUSTOM → ToolBox instance; HTTP → HttpProxyToolBox
   → addTool → strip ungranted methods → permission rules (ALLOW / ASK) → dangerous-input wrapping
```

Two hard rules at assembly time: **there is no fallback registration at all** (empty `toolSpecs` means zero tools), and **a missing config only logs a warning and skips** (it never blocks building the agent).

## 6. Key design decisions

### 6.1 One row per method (V4)

- **Decision**: `agent_tool` moved from "one row per ToolBox" to "one row per `@Tool` method"; the unique key moved from `uk_tenant_name` to `uk_tenant_bean_method`.
- **Why**: a ToolBox usually mixes reads and writes. Granting by bean means ticking `getDate` also releases `sendEmail`; `needConfirm`, env params and timeout could only be shared wholesale.
- **Cost**: `addTool(toolBox)` is agentscope's bean-level registration and registers every method. Assembly must therefore end with a **subtraction**: take the method set from `ToolRegistry`, remove the methods granted this time, `removeTool` the difference. That strip-down is the only defence against whole-box leakage and must not be dropped when editing the assembly code.

### 6.2 Builtin tool lifecycle belongs to the code sync

- **Decision**: `BuiltinToolAutoRegistrar` is the only path that inserts / updates / deletes `type='BUILTIN'` rows. Every other write path is closed: the service layer rejects by `type`, the Controller exposes no create endpoint, the frontend is read-only and its write request wrappers were deleted.
- **Why**: when the DB row and the code annotation disagree, runtime always follows the code (`beanName` / `methodName` must resolve to a real method). Letting operators edit builtin tools only produces rows that either cannot run or carry fields contradicting the code.
- **Cost**: any change to a builtin tool — including the required / optional split — means editing annotations, releasing, and restarting admin. There is no operator-side switch.

### 6.3 Identity key and rename semantics

- **Decision**: the sync compares on `beanName + methodName + toolName`; `upsertBuiltinTool` includes `name` in its `ON DUPLICATE KEY UPDATE` list.
- **Why**: the duplicate key is `(tenant_id, bean_name, method_name, active)`, so a `@Tool(name = ...)` rename hits the existing row. Refreshing `name` in place makes the rename converge without moving the row.
- **Consequence**: **only a renamed Java method or bean still deletes and recreates** (the `id` changes and bindings cascade away, so re-tick the tool in the agent config). A pure tool-name rename no longer loses bindings.

### 6.4 Required tools have no binding row

- **Decision**: `is_required=1` builtins are appended to `toolDetails` by Admin at delivery time and never written to `agent_tool_binding`.
- **Why**: the semantics are "every agent must have this". Modelling that as bindings means inserting one row per agent and back-filling every new agent when a tool is added — and since the wizard cannot untick them, those rows carry no information.
- **Cost**: **no binding row means no `envBindings` snapshot**, so a required tool cannot receive environment parameters. `isRequired` combined with required `envParamDefs` is therefore a contradictory configuration, and the startup sync logs a warning for it.

### 6.5 No runtime fallback tool set

- **Decision**: removed the "when the agent has no tools, register every ToolBox in `TOOL_SET`" branch.
- **Why**: the fallback made "not configured" and "configured empty" indistinguishable at runtime, and it defeated the method-granular granting of 6.1 — ungranted tools in the same box leaked back in through the fallback.
- **Cost**: agents that relied on the fallback must now tick tools explicitly.

### 6.6 `enable_skip` deleted

- **Decision**: `agent_tool_binding.enable_skip` and `ToolSpec.skipIfMissing` were removed together (V17); a missing tool now always means "warn and skip".
- **Why**: the switch only chose between an error and a warning; it granted no runtime tolerance. Surfaced in the UI as "缺失时跳过" it read like a resilience setting it never was.
- **Contrast**: `agent_mcp_binding.enable_skip` is **kept** — an unreachable MCP server really does block agent construction, so there "fail" versus "warn and skip" are two genuine behaviours.

### 6.7 Binding-level `needConfirm` may only tighten

- **Decision**: runtime evaluates `agent_tool.needConfirm || agent_tool_binding.needConfirm`.
- **Why**: the earlier rule ("if the entity does not require confirmation, force false") left operators no way to add confirmation for one risky agent. With an OR, the binding layer can append confirmation but can never cancel what the code declared — safety properties only move in one direction.

### 6.8 Per-session ToolBox instances

- **Decision**: `ToolRegistry.createToolBoxInstance(beanName)` builds a fresh instance via the no-arg constructor and calls `init(logAdaptor, SessionMetaContext, UserIdentifier)` instead of reusing the Spring singleton.
- **Why**: `ToolBox` carries session and user state; sharing a singleton across sessions causes cross-talk. The singleton template is only a fallback for reflection failure.

### 6.9 Env params: reference and snapshot coexist

- **Decision**: when filling values an agent may either **reference a global env variable** (stores `envVarId`, resolved to the latest decrypted value at delivery) or **store a custom value** (a `customValue` snapshot).
- **Why**: the first makes one edit effective for every referencing agent; the second lets an individual agent pin its own value. `secret=true` params are encrypted at rest and masked on echo.

## 7. Consistency with MCP / Skill

| Dimension | Tool | MCP | Skill |
|-----------|------|-----|-------|
| Master table | `agent_tool` | `mcp_server` | `skill` (+ `skill_repository`) |
| Binding table | `agent_tool_binding` | `agent_mcp_binding` | `agent_skill_binding` |
| Spec carrier | `ToolDetailDto` → `ToolSpec` | `McpDetailDto` → `McpSpec` | `SkillDetailDto` → `SkillSpec` |
| Runtime adaptor | `ToolConfigAdaptor` | `McpConfigAdaptor` | `SkillAdaptor` |
| Metadata source | Code annotations (builtin) / DB (custom) | DB | Remote repository sync |
| Writable by operators | No for builtin, yes for custom (not enabled) | Yes | Yes |
| Secret handling | Encrypted headers / env values | Encrypted env values | None |
| Behaviour when missing | Warn and skip (no switch) | Governed by `enable_skip` | Cached fallback |

## 8. Known boundaries

| Boundary | Detail |
|----------|--------|
| Builtin tools are written with `tenant_id = 1` only | The sync always uses tenant 1, and `MybatisTenantInterceptor`'s filtering is currently disabled, so builtin tools are a platform-wide shared resource rather than one copy per tenant |
| Custom tool chain exists but is unreachable | Assembly, encryption and DTOs are all in place; there is no create endpoint and the frontend hides them, so rows only appear if someone writes the DB directly |
| HTTP tools have no UI entry point | `HttpProxyToolBox` and its columns are complete, but the tool page offers no creation form |
| Pruning residue needs a human | When the circuit breaker trips (stale rows ≥ declared rows) or a tool group failed to sync, the delete is skipped with an ERROR log; review the code and re-release |
| `name` is not part of the unique key | `uk_tenant_bean_method` excludes `name`, so two methods declaring the same `@Tool(name)` are not caught by the database; the sync looks rows up by `name` to attach env params, so a duplicate can land those definitions on the wrong row |

## 9. Evolution timeline

| Version | Change | Motivation |
|---------|--------|------------|
| V1 | Create `agent_tool`; add `agent.tool_list` JSON | Detach tools from the hardcoded `TOOL_SET` |
| V2 | Create `agent_tool_env` | Tools need external configuration (SMTP, API keys) |
| V3 | Add `display_name_zh` and param `description` | Chinese / English display |
| V4 | Rename to `agent_tool_env_param`, add `method_name`, unique key → `uk_tenant_bean_method` | Granularity down to the method (6.1) |
| V5 | Add `is_required` | Some tools must be present on every agent |
| V7 | Create `agent_tool_binding` and friends | Replace JSON columns; per-binding params and confirmation |
| — | The registration mechanism takes over builtin insert / update / delete | Eliminate DB-vs-code drift (6.2) |
| — | API and frontend write paths for builtins closed | Single source of truth (6.2) |
| V17 | Drop `agent_tool_binding.enable_skip` | The switch had no semantics (6.6) |
| V18 | Unique key `(agent_id, tool_id)` on `agent_tool_binding` | Make "the binding row is the one fact for this agent-tool pair" actually true |
| — | Assembly loses the `TOOL_SET` fallback | Close the loop on method-granular granting (6.5) |
| — | Upsert refreshes `name` + two brakes on prune | Renames stop losing bindings; mass deletes are gated (6.3) |

## 10. Key file index

| Concern | File |
|---------|------|
| Annotations and descriptors | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`, `ToolMetaDescriptor.kt` |
| Scan and registry | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt` |
| Startup convergence | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt` |
| Write-path guard | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt` (`requireManageableTool`) |
| SQL and unique-key behaviour | `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml` (`upsertBuiltinTool` / `deleteBuiltinByIds`) |
| Spec delivery | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` (`buildAgentSpecResponse`) |
| Runtime assembly | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` |
| Migrations | `harnax-admin/src/main/resources/db/migration/V4__refactor_tool_granularity.sql`, `V17__drop_tool_binding_enable_skip.sql`, `V18__add_tool_binding_unique_key.sql` |
