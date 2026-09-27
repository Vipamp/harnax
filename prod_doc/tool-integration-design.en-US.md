# Harnax Tool Integration Design (English)

This is the design document of the tool domain: the integration model, the data invariants, the layer responsibilities, the end-to-end chain, the key decisions with their constraints, and how this domain lines up with MCP and Skill. Operational content - annotation attributes, the built-in tool inventory, the management API, the development steps - lives in the sibling document "Harnax Tool Capability and Developer Guide". Each document stands on its own; neither refers to the other's section numbers.

## 1. Design Goals

| Goal | Mechanism |
| --- | --- |
| One source of truth for tool definitions: the code | `@Tool` / `@ToolMeta` are the only place a tool is declared; `agent_tool` is written by `BuiltinToolAutoRegistrar`, and both the management API and the page are read-only |
| A predictable, auditable tool set at runtime | Delivery set = binding rows ∪ required tools, then filtered by `registeredToolNames()`; there is no fallback tool set and no path where the page configures something the runtime quietly adds |
| Authorization granularity down to the single tool | One `@Tool` method per row, so confirmation, environment parameters and per-agent granting all operate on methods |
| Separation of configuration values from configuration definitions | "Which parameters are needed" sits in `agent_tool_env_param` (synced from code); "what the values are" sits in `agent_tool_binding.env_bindings` (written by the operator) |
| Dangerous capability is declarable and closed by default | `readOnly` / `needConfirm` / `dangerousInput` feed one permission engine, and the dangerous-input ASK is bypass-immune |
| Tool execution leaves a trace without changing execution | `ToolBox.execute` records arguments, result and duration; a failed record or an absent logger changes nothing about the returned value |

## 2. Classification Model

### 2.1 One implementation only

Tools are code-built-in only: a `@Tool` method on a `ToolBox` subclass, shipped on the process classpath. `agent_tool` has no type column, `AgentToolService` has no write method, and `AgentToolController` serves GET only. Integrating an external system means writing a ToolBox, not configuring a row.

### 2.2 Two levels: tool group and tool

- Tool group = one `ToolBox` bean, identified by the Spring bean name (`bean_name`); its role is shared dependencies and the unit of instantiation.
- Tool = one `@Tool` method inside the group, identified by `@Tool.name` (`agent_tool.name`); its role is the unit of granting, confirmation and environment parameters.

Both identifiers exist, but only the method-level name carries identity: `bean_name` and `method_name` serve `createToolBoxInstance` plus reflective invocation, so moving a method into another ToolBox updates those two attributes on the same row while the row, its `id` and every agent binding stay put.

### 2.3 Splitting by whether a tool can be turned off

`@ToolMeta.isRequired` maps to `agent_tool.is_required`. Optional tools follow "binding row → delivery"; required tools follow "appended unconditionally at delivery", which is why no configuration screen can switch them off and why they never appear in `selectAvailableTools`.

### 2.4 Tool groups outside the registration model

`TeamLeadToolBox` / `TeamMemberToolBox` are constructed directly by `HarnessAgentLauncher` when a team role is assembled. They are not Spring beans, so they enter neither `ToolRegistry` nor `agent_tool` nor any binding. The visibility of such a group is a property of the assembled role, and its names join the framework ALLOW set through constants such as `TOOL_NAMES`.

## 3. Data Model

### 3.1 Tables and their writers

| Table | Row meaning | Written by | Read by | Lifecycle |
| --- | --- | --- | --- | --- |
| `agent_tool` | one `@Tool` method | `BuiltinToolAutoRegistrar` exclusively | management page, agent panel, `ToolConfigAdaptorImpl`, delivery queries | inserted and updated, never deleted; `status` / `active` stay 1 |
| `agent_tool_env_param` | one env parameter definition of a tool | same | management page, save-time required check | follows the declaration, including removal; `tool_id` points at the current row |
| `agent_tool_binding` | one agent's grant of one tool plus its values | `AgentServiceImpl.saveToolBindings` | delivery queries, assembly | rewritten as a whole per save; `(agent_id, tool_id)` unique |
| `tool_call_log` | one tool call | `ToolCallLogAdaptorImpl` | nothing (`ToolCallLogMapper` has `insert` only) | kept; never pruned with the session or agent |

### 3.2 Key constraints

- `agent_tool.name` carries `UNIQUE uk_agent_tool_name`: name uniqueness is guaranteed by the database, so two rows of one name cannot coexist.
- `agent_tool` has no `tenant_id` column, and none of `type` / `is_public` / `http_url` / `http_method` / `http_headers` / `input_schema` / `output_schema` / `env_params` / `timeout_seconds`: a tool is a platform resource, its only configuration shape is "a method declared in code", and timeout is not part of the data model.
- `agent_tool_binding` has no "skip when missing" column: an unresolvable tool is always logged at WARN and skipped.
- `(tool_id, env_param_name)` is unique in `agent_tool_env_param`, and a `secret = 1` `default_value` is decrypted by `SecretFieldEncryptor` and then masked before it reaches a response.
- Both `tool_call_log.agent_id` and `tool_call_log.tenant_id` are nullable, where NULL means "attribution unknown" and is never back-filled by guessing.

### 3.3 Structural baseline

Columns and indexes follow the DDL under `harnax-admin/src/main/resources/db/migration/`: the `agent_tool` and `tool_call_log` create statements live in `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`, `agent_tool_binding` and its sibling binding tables in `harnax-admin/src/main/resources/db/migration/V7__normalize_agent_bindings.sql`, the `uk_agent_tool_name` key and the registry's lack of a tenant column and a timeout column in `harnax-admin/src/main/resources/db/migration/V40__tool_registry_platform_scoped.sql`, and `tenant_id` plus `idx_tenant_ts` on `tool_call_log` in `harnax-admin/src/main/resources/db/migration/V50__scope_stats_and_logs_to_a_tenant.sql`.

## 4. Layer Responsibilities

| Layer | Modules and key types | Owns | Does not own |
| --- | --- | --- | --- |
| Contract | `harnax-agent/harnax-tools-sdk`: `ToolBox`, `ToolMeta`, `ToolEnvParamDef`, `ToolEnvContext`, `SessionMetaContext` / `UserIdentifier`, `ToolRegistry`, `ToolCallLogAdaptor` / `ToolConfigAdaptor`, `ToolSpec` | The tool shape, the injection contract, the SPI | Never queries the database, never decides what is bound |
| Implementation | `harnax-tools-external/harnax-tools-buildin`: `TimeToolBox`, `EmailToolBox` | Declares and implements tools | Unaware of agents, reads no tables |
| Metadata | `harnax-admin`: `BuiltinToolAutoRegistrar`, `AgentToolController` / `AgentToolServiceImpl`, `AgentServiceImpl.saveToolBindings`, `InternalApiController.buildAgentSpecResponse` | Syncing declarations, read-only exposure, binding saves, spec delivery | Never executes a tool |
| Runtime | `harnax-agent/harnax-harness-core`: `HarnessAgentLauncher`, `HarnessAgentBuilder`, `DangerousInputCheckingTool`, `TeamToolBoxes`, `HarnessAgentWrapper`; `harnax-agent-service`: `AgentSpecResolver`, the two adaptor implementations | Instantiation, grant sweeping, permission rules, context injection, turn timeout, logging | Never defines a tool, never edits metadata |
| Persistence | `harnax-entity`: `AgentTool` / `AgentToolBinding` / `AgentToolEnvParam` / `ToolCallLogEntity` and their mappers | Reads, writes and the SQL predicates | Contains no business judgement |

Dependency direction: the implementation and runtime layers depend only on the contract layer; admin and agent-service each put the implementation layer on their classpath and scan `com.agnetix.harnax.tools`. One set of declarations is therefore resolved into a registry twice - what the table contains is decided on the admin side, what the runtime can instantiate on the agent side.

## 5. End-to-End Chain

1. **Release**: the ToolBox classes land on the classpath of both `harnax-admin` and `harnax-agent-service`.
2. **Discovery**: at process startup `ToolRegistry.init()` (`@PostConstruct`) builds the bean map from `getBeansOfType(ToolBox::class.java)` and reads `@Tool` plus `@ToolMeta` per method into `ToolMetaDescriptor`; a bean without any `@Tool` method is left out of the metadata.
3. **Sync**: `ApplicationReadyEvent` triggers `syncBuiltinTools()`. `requireUniqueNames` refuses a duplicate name first; `agent_tool` is then upserted by name; `agent_tool_env_param` follows using the row ids just resolved, deleting definitions absent from the declaration set; finally the declared names are stored in `declaredNames`.
4. **Configuration**: the operator selects tools in the agent form, toggles confirmation, fills env values; `saveToolBindings` validates bindability and required parameters, then rewrites the agent's binding set.
5. **Delivery**: when a session asks for a spec, `buildAgentSpecResponse` reads the binding rows and `selectRequiredTools()`, de-duplicates, filters by `declaredNames`, and emits `toolDetails` (including `bindingNeedConfirm`) plus `toolList` with resolved `env_bindings`; an env-variable reference is resolved to the agent tenant's current decrypted value, falling back to the stored snapshot. A team lead goes through `specForTeam`, with both the tool list and the required ids empty.
6. **Resolution**: `AgentSpecResolver` folds `toolDetails` into `ToolSpec(toolId, needConfirm)` entries and merges the tool and MCP env bindings into one flat map registered as a `ToolEnvContext` in `contextForTools` (registered even when empty).
7. **Assembly**: `HarnessAgentLauncher.createAgentBase` instantiates each ToolBox once per `beanName`, calls `init`, registers the whole group with `addTool`, then sweeps with `removeTool` down to the granted names; the union of tool-level and binding-level confirmation becomes ASK rules, and `dangerousInput` methods become wrapped tools.
8. **Execution**: the model issues a tool call → the permission engine judges mode and rules (the decorator's `checkPermissions` sits on that path) → the ToolBox method is invoked reflectively → `execute` produces a `ToolCallInfo` → `ToolCallLogAdaptorImpl` inserts it into `tool_call_log` → the value returns to the framework.
9. **Wrap-up**: the whole turn is bounded by `turnTimeoutSeconds` (team turns use the team budget through `turnBudget`); an individual tool has no timeout of its own.

At every stage of this chain, an unresolvable tool behaves the same way: log a warning, skip it, keep building the agent.

## 6. Key Design Decisions

### 6.1 One row per method

`agent_tool`'s granularity is the `@Tool` method, not the ToolBox class. Because `Toolkit.registerTool(toolBox)` registers the whole group, the assembly side must sweep: every name in `getToolMeta(bean).methods` that was not granted gets a `removeTool`. That makes "select one method out of a group" a real authorization statement; the constraint is that the sweep and the group-level registration stay coupled, and a missing sweep would over-grant.

### 6.2 The name is the identity

Registration identity is `@Tool.name`, enforced by `uk_agent_tool_name`. `selectByName` carries no `active` predicate, so a row sitting at `active = 0` still holds the name and is updated back to 1 rather than being duplicated into a key collision. `bean_name` and `method_name` are therefore attributes, and relocating a method in code costs an agent nothing.

Corollary: renaming is a new tool. Changing `@Tool.name` in code inserts a new row and the earlier row stays (nothing is deleted); the row matching the current declaration is the one that gets delivered.

### 6.3 Duplicate names are refused at startup

Two `@Tool` methods claiming one name has no correct resolution - the winner would be a function of bean order. `requireUniqueNames` throws `IllegalStateException` before any write, listing each `bean::method`, and the process fails to start. The database unique key guards runtime data; this gate guards the classpath.

### 6.4 Additive sync, filtered delivery

The sync inserts and updates but never deletes: a tool an agent is bound to must not vanish because a Java method moved or a ToolBox class left the build. Rows whose declaration is off the classpath are held back at delivery by `registeredToolNames()` - the row remains visible to the operator but stops travelling. An empty `declaredNames` means the sync did not run, so it disables the filter rather than treating "unknown" as "no tools" and emptying every agent.

### 6.5 One lifecycle entry point

`BuiltinToolAutoRegistrar` is the only writer of tool rows. Three consequences follow: `status` / `active` stay 1 (a human disable has nowhere to be recorded), the API and page are read-only, and `creator` is constant `SYSTEM`. Any additional write path would break the premise that the code is the single fact source.

### 6.6 Required tools carry no binding row

"Cannot be turned off" is implemented as an unconditional append at delivery. The side effect is that such a tool has no binding row and therefore nowhere to take values, so the sync warns when `isRequired` meets a `required = true` env parameter - a reminder, not a refusal; the runtime symptom is `require()` throwing "not configured".

### 6.7 Binding-level confirmation can only tighten

The runtime judgement is `agent_tool.need_confirm == 1 || ToolSpec.needConfirm`. A tool the code declares as confirm-worthy cannot be relaxed by a binding, and a binding can add confirmation where the code did not. `AgentServiceImpl.saveToolBindings` documents the same OR direction on the write side.

### 6.8 Per-session ToolBox instances

`createToolBoxInstance` calls `newInstance()` for every session, and assembly immediately runs `init(adaptor, SessionMetaContext(...), userIdentifier)`. The reason: a ToolBox must know the calling session in order to log the call, and a singleton shared across sessions would cross `agentId` / `sessionId`. The contract that follows is that a ToolBox keeps a no-argument constructor and stores no cross-session state in fields. The fallback to the singleton on instantiation failure logs ERROR - and in that case the log attribution may land on another session's context.

### 6.9 Env parameters: reference and snapshot coexist, scope is shared

A binding element either references `env_variable` (`envVarId` plus an `envVarName` snapshot) or carries `customValue`; delivery prefers the tenant's current decrypted value and falls back to the snapshot. That keeps "change one variable, every reference follows" and "a deleted variable does not silently erase the configuration" both true, and the constraint is that a reference may drift from the stored snapshot, so the displayed snapshot is not necessarily the value in use.

`ToolEnvContext.bindings` is the merged result of all tool and MCP bindings of that agent, answered by key with no source attribution, tools merged before MCP. Reusing one key across tools is therefore a supported pattern, and a duplicate key resolves to the later value.

### 6.10 A declared default never fills a value at runtime

`agent_tool_env_param.default_value` serves the configuration page; runtime values come solely from the binding, and the save-time required check uses `defaultValueCounts = false` for built-in tools. "A default is written on the page" is therefore never mistaken for "the parameter is satisfied".

### 6.11 Timeout belongs to the assembly side

There is no tool-level timeout: no column in `agent_tool`, no attribute in `@ToolMeta`. Timeout is applied to the whole turn in `HarnessAgentWrapper`, from `harness.turn-timeout-seconds` (team turns: `harness.team.turn-timeout-seconds`, selected by `turnBudget(teamRole)`). The rationale: within one turn tool calls interleave, so per-tool timing would need an execution layer that belongs to the framework, and a column that nobody reads only offers a number. The containment lever for a slow or harmful tool is confirmation and dangerous-input interception, not timeout.

### 6.12 Dangerous input uses a decorator

`dangerousInput` introduces no custom middleware. The registered tool is wrapped by `DangerousInputCheckingTool` (`wrapWithDangerousInputCheck`), which inspects the arguments inside `checkPermissions` and returns an ASK whose reason starts with `safety:` - bypass-immune under BYPASS per the PermissionEngine contract. The decorator copies the name, description and schema and delegates `callAsync`, so it is indistinguishable to the model.

Deduplication: when a tool already carries the needConfirm ASK rule, assembly skips the wrapping, because ASK fires earlier in the decision chain than `checkPermissions` and the scan could not change the outcome.

### 6.13 A missing tool is always skipped visibly

Both delivery and assembly log a warning and continue for an unresolvable tool; there is no switch choosing the degree of silence - `agent_tool_binding` has no skip column and the SDK has no `skipIfMissing` field. MCP follows the same rule: that table has no such column either, a missing config logs a warning, and an unreachable server is dropped by the per-server `try/catch` in `HarnessAgentLauncher` (its client closed), so the agent is built from the remaining capabilities and one aggregate line reports "N of M bound MCP servers". A tool cannot fail to connect, so only the first of those two behaviours exists in this domain.

### 6.14 Logs are kept

`ToolCallLogMapper` has `insert` and nothing else - no select, no delete, and no cleanup task: `tool_call_log` outlives its session and agent. The table is the raw record used when troubleshooting, not a statistics source, and the product offers no tool-call page. What lands in it is decided by the arguments a developer passes to `execute` explicitly, and `result` is stored verbatim, so a tool should avoid echoing secrets.

## 7. Consistency with MCP / Skill

The same agent's capability assembly rests on the `agent_*_binding` tables, which share one pattern:

- **Binding tables**: `agent_tool_binding`, `agent_mcp_binding` and `agent_skill_binding` all use a unique `(agent_id, <target>_id)` key plus `create_time` / `update_time`, and the tool and MCP tables each carry an `env_bindings` JSON column; the binding-level `need_confirm` column exists only on the tool side (`agent_mcp_binding` has no such column). `agent_cli_binding` is a fourth table of the same shape, with `env_bindings` and without `need_confirm`.
- **Same value shape**: tools and MCP both use "reference plus snapshot", with `customValue` and `envVarId` mutually exclusive; skill bindings have no env parameter column, because a skill body is not an external endpoint needing credentials.
- **One delivery point**: all three are assembled into detail lists by `InternalApiController.buildAgentSpecResponse`, with resolved env values placed on the same spec, and `AgentSpecResolver` merges tool and MCP env into one `ToolEnvContext`.
- **Same treatment of unresolvable rows**: MCP's "other tenant", "disabled" and "stdio not permitted" cases and the tool's "absent from the declared set" case all stay in the table, stay off the wire, and log a warning.

The core difference from both is the fact source and the owner: MCP servers and skills are tenant-scoped data rows an operator creates, toggles and deletes, while tools are a projection of platform-level code that an operator can only reference. Hence `agent_tool` has no `tenant_id` and no write API, whereas `mcp_server` and `skill` keep their tenant columns and full CRUD pages. The dangerous-path and dangerous-command constants (`ToolDangerousPathConstants`) and the permission engine are shared between the MCP and tool paths, so both sides speak one ASK/DENY semantics.

## 8. Known Boundaries

- Tools carry no tenant: a row is visible to every tenant. Isolation happens in the binding layer, where the `agent` owns a tenant and its `agent_tool_binding` rows follow it.
- Tool rows never disappear, so the table grows with the accumulation of declarations; the tool page lists everything at `active = 1`, which can include names that delivery holds back.
- No configuration slot exists for per-tool timeout, retry or concurrency limits; `@Tool.concurrencySafe` is a code attribute, neither stored nor adjustable per agent.
- Session-level instantiation depends on a no-argument constructor, and the singleton-fallback branch can misattribute a call log across sessions.
- `ToolEnvContext` is a per-agent flat map: a tool can read same-named keys bound by other targets. That is a sharing mechanism, not per-tool isolation.
- Required tools and required env parameters are mutually exclusive; the sync warns and starts anyway.
- A team lead assembles no business or required tools, and `specForTeam` passes an empty tool set deliberately; the lead's only visible capabilities are the team tool group.
- `ToolSpec.toolName` is never read on the assembly path; the tool name always comes from the stored row and `@Tool.name`.
- Call logs are write-only, and any argument a tool passes explicitly to `execute` - including a secret - is stored verbatim.
- `/builtin` omits the `status` predicate while `/available` adds `is_required = 0`: the two listings answer different questions, display versus selection.

## 9. Key File Index

| Topic | Path |
| --- | --- |
| SDK contract | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMeta.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolEnvContext.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolMetaDescriptor.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolSpec.kt` |
| Registry and SPI | `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolCallLogAdaptor.kt`, `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolConfigAdaptor.kt` |
| Built-in tools and module wiring | `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBox.kt`, `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt`, `harnax-tools-external/harnax-tools-buildin/pom.xml`, `harnax-agent/harnax-tools-sdk/pom.xml`, `harnax-admin/pom.xml`, `harnax-agent/harnax-agent-service/pom.xml` |
| Metadata sync and read-only API | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/BuiltinToolAutoRegistrar.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentToolController.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/AgentToolService.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentToolServiceImpl.kt` |
| Binding save and delivery | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentToolResponse.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolEnvParamEntry.kt` |
| Runtime assembly | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/permission/DangerousInputCheckingTool.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt`, `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` |
| Spec resolution and SPI implementations | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolConfigAdaptorImpl.kt`, `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImpl.kt` |
| Entities and SQL | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentTool.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolBinding.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/AgentToolEnvParam.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/AgentToolMapper.kt`, `harnax-entity/src/main/resources/mapper/AgentToolMapper.xml`, `harnax-entity/src/main/resources/mapper/ToolCallLogMapper.xml` |
| DDL | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`, `harnax-admin/src/main/resources/db/migration/V5__add_tool_is_required.sql`, `harnax-admin/src/main/resources/db/migration/V18__add_tool_binding_unique_key.sql`, `harnax-admin/src/main/resources/db/migration/V40__tool_registry_platform_scoped.sql`, `harnax-admin/src/main/resources/db/migration/V50__scope_stats_and_logs_to_a_tenant.sql` |
| Frontend | `harnax-webui/src/pages/tool/index.tsx`, `harnax-webui/src/pages/tool/components/ToolEnvEntriesEditor.tsx`, `harnax-webui/src/pages/agent/components/ToolConfigPanel.tsx`, `harnax-webui/src/services/ant-design-pro/tool.ts` |
| Framework annotations and engine (external dependency agentscope 2.0.2) | `io.agentscope.core.tool.Tool`, `io.agentscope.core.tool.ToolParam`, `io.agentscope.core.tool.ToolSchemaGenerator`, `io.agentscope.core.tool.ToolMethodInvoker`, `io.agentscope.core.tool.ToolDangerousPathConstants`, `io.agentscope.core.permission.PermissionMode` |
